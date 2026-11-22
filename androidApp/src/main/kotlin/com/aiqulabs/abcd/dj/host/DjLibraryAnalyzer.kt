package com.aiqulabs.abcd.dj.host

import android.content.Context
import androidx.core.content.ContextCompat
import com.aiqulabs.abcd.dj.analysis.MediaCodecPcmDecoder
import com.aiqulabs.abcd.dj.analysis.NativeAudioAnalyzer
import com.aiqulabs.abcd.dj.core.prep.TrackPreparation
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.ui.library.LibraryAnalysisState
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import timber.log.Timber

/**
 * "Analyse my library" (brief §5.1, second point): analyses liked, library and downloaded songs ahead of
 * time so later mixes start fast. Runs inside [DjAnalysisService], a foreground service with a progress
 * notification, so it keeps going when the app is in the background.
 *
 * One run handles at most [MAX_PER_RUN] songs, songs Metrolist already holds first (no download needed);
 * the finished card says how many are left, and tapping the button again continues.
 */
object DjLibraryAnalyzer {
    internal const val MAX_PER_RUN = 200
    private const val MAX_SONG_SECONDS = 10 * 60
    private const val TAG = "DjLibraryAnalyzer"
    private const val PREPARATION_POLL_MS = 1_000L

    private val mutableState = MutableStateFlow<LibraryAnalysisState>(LibraryAnalysisState.Idle)

    @Volatile
    private var job: Job? = null

    val state: StateFlow<LibraryAnalysisState> = mutableState.asStateFlow()

    internal val isRunning: Boolean get() = job?.isActive == true

    /** Starts the background service that runs the batch. Ignored while a batch is already running. */
    fun request(context: Context) {
        if (mutableState.value.isBusy) return
        mutableState.value = LibraryAnalysisState.Collecting
        try {
            ContextCompat.startForegroundService(context.applicationContext, DjAnalysisService.startIntent(context))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not start the analysis service")
            mutableState.value = LibraryAnalysisState.Idle
        }
    }

    /** Stops a running batch. Songs analysed so far are kept. */
    fun stop() {
        job?.cancel()
    }

    internal fun attach(runningJob: Job) {
        job = runningJob
    }

    /**
     * The batch itself. Called by the service. [onProgress] gets (finished, total, current title) so the
     * service can keep its notification up to date.
     */
    internal suspend fun run(
        context: Context,
        onProgress: suspend (done: Int, total: Int, title: String?) -> Unit,
    ) {
        mutableState.value = LibraryAnalysisState.Collecting
        val repository = DjData.analysisRepository(context)
        val analyzer = NativeAudioAnalyzer()
        val audio = DjAudioProvider.get(context)
        val (songs, remainingBeyondCap) = withContext(Dispatchers.IO) { collect(repository.analysedIds(analyzer.analysisVersion)) }
        if (songs.isEmpty()) {
            mutableState.value = LibraryAnalysisState.NothingToDo
            return
        }
        val byId = songs.associateBy { it.id }
        val preparer =
            DjTrackPreparer(
                repository = repository,
                audio = audio,
                decoder = MediaCodecPcmDecoder(context),
                analyzer = analyzer,
                songFor = byId::get,
                keepAudio = false,
            )
        var analysed = 0
        var failed = 0
        var processed = 0
        try {
            for (song in songs) {
                mutableState.value = LibraryAnalysisState.Running(processed, songs.size, song.title)
                onProgress(processed, songs.size, song.title)
                if (!audio.hasSpaceFor(1)) {
                    Timber.tag(TAG).w("Stopping: the phone is out of space")
                    break
                }
                // A mix being prepared has priority: both share one analysis slot (AnalysisGate), so a running
                // batch would otherwise halve preparation speed. Wait between songs until the mix is ready.
                while (DjController.isPreparing) delay(PREPARATION_POLL_MS)
                when (preparer.prepare(song.toDjTrack())) {
                    is TrackPreparation.Ready -> analysed++
                    is TrackPreparation.Failed -> failed++
                }
                processed++
            }
            mutableState.value =
                LibraryAnalysisState.Finished(analysed, failed, remainingBeyondCap + songs.size - processed, stopped = false)
        } catch (e: CancellationException) {
            mutableState.value =
                LibraryAnalysisState.Finished(analysed, failed, remainingBeyondCap + songs.size - processed, stopped = true)
            throw e
        } finally {
            Timber.tag(TAG).i("Library batch: %d analysed, %d failed, %d of %d processed", analysed, failed, processed, songs.size)
        }
    }

    /** Songs without a current analysis: Metrolist's downloads and cache first, then liked, then the rest of the library. */
    private suspend fun collect(analysedIds: Set<String>): Pair<List<SongItem>, Int> {
        val database: MusicDatabase = KoinPlatform.getKoin().get()
        val audioCache: AudioCacheManager = KoinPlatform.getKoin().get()
        val dao = database.dao()
        val onPhone = audioCache.cachedSongIds()
        val ordered = LinkedHashMap<String, SongItem>()

        fun add(songs: List<Song>) {
            for (entity in songs) {
                if (entity.id in analysedIds || entity.id in ordered) continue
                ordered[entity.id] = entity.toSongItem()
            }
        }
        add(dao.downloadedSongsByCreateDateAsc().first())
        add(dao.likedSongsByRowIdAsc().first())
        add(dao.songsByRowIdAsc().first())
        val usable =
            BlockedContent.filterPlayableSongs(ordered.values.filter(::isAnalysable)).sortedBy { if (it.id in onPhone) 0 else 1 }
        return usable.take(MAX_PER_RUN) to (usable.size - MAX_PER_RUN).coerceAtLeast(0)
    }

    private fun isAnalysable(song: SongItem): Boolean =
        !song.isUnavailable && !song.isLocal && !song.isUploaded && !song.isPodcast && (song.duration ?: 0) <= MAX_SONG_SECONDS
}
