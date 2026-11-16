package com.aiqulabs.abcd.dj.host

import android.content.Context
import com.aiqulabs.abcd.dj.analysis.MediaCodecPcmDecoder
import com.aiqulabs.abcd.dj.analysis.NativeAudioAnalyzer
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.data.DjAnalysisRepository
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.ui.lab.LabBusy
import com.aiqulabs.abcd.dj.ui.lab.LabDetail
import com.aiqulabs.abcd.dj.ui.lab.LabLocalSong
import com.aiqulabs.abcd.dj.ui.lab.LabMessage
import com.aiqulabs.abcd.dj.ui.lab.LabState
import com.metrolist.android.playback.AudioCacheManager
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import timber.log.Timber

/**
 * Drives the Lab (stage 5): lists songs whose audio is on the phone, analyses one on request, saves the
 * result in the DJ database and shows it.
 *
 * Audio comes only from files Metrolist already holds (downloaded or cached songs). Fetching songs that
 * are not on the phone is part of pre-analysis (stage 6). Metrolist's own database is only read.
 */
object DjLabController {
    private const val TAG = "DjLabController"
    private const val MAX_LOCAL_SONGS = 400

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow(LabState())
    private var job: Job? = null
    private var attachedTo: Context? = null

    val state: StateFlow<LabState> = mutableState.asStateFlow()

    private val audioCache: AudioCacheManager get() = KoinPlatform.getKoin().get()
    private val metrolistDatabase: MusicDatabase get() = KoinPlatform.getKoin().get()

    /** Starts watching the DJ database. Safe to call on every composition; it only acts once. */
    fun attach(context: Context) {
        if (attachedTo != null) return
        val appContext = context.applicationContext
        attachedTo = appContext
        val repository = repository(appContext)
        scope.launch {
            repository.observeSummaries().collect { summaries ->
                mutableState.update { it.copy(analysed = summaries) }
                refreshCounts(repository)
            }
        }
    }

    /** Reloads the list of songs on the phone (they change as the user listens and downloads). */
    fun refreshLocalSongs() {
        val repository = repository(attachedTo ?: return)
        scope.launch {
            val local =
                withContext(Dispatchers.IO) {
                    val analysed = repository.analysedIds(0)
                    val ids = audioCache.cachedSongIds()
                    Timber.tag(TAG).d("Audio files known to Metrolist: %d", ids.size)
                    ids
                        .take(MAX_LOCAL_SONGS)
                        .mapNotNull { id -> metrolistDatabase.dao().getSongById(id)?.toSongItem() }
                        .map { song -> LabLocalSong(song.id, song.title, artistNames(song), song.id in analysed) }
                        .sortedBy { it.title.lowercase() }
                }
            Timber.tag(TAG).d("Songs with audio on this phone: %d", local.size)
            mutableState.update { it.copy(local = local) }
        }
    }

    fun open(videoId: String) {
        val repository = repository(attachedTo ?: return)
        scope.launch {
            val stored = repository.load(videoId) ?: return@launch
            val (title, artists) = describe(videoId)
            mutableState.update { it.copy(detail = LabDetail(videoId, title, artists, stored)) }
        }
    }

    fun closeDetail() = mutableState.update { it.copy(detail = null) }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun delete(videoId: String) {
        val repository = repository(attachedTo ?: return)
        scope.launch {
            repository.delete(videoId)
            mutableState.update { it.copy(detail = null) }
            refreshLocalSongs()
        }
    }

    /** Analyses (or re-analyses) one song and opens the result. Ignored while another analysis runs. */
    fun analyse(videoId: String) {
        val context = attachedTo ?: return
        if (job?.isActive == true) return
        job = scope.launch { guarded(videoId) { analyseNow(context, videoId) } }
    }

    /**
     * Analyses a music file the user picked on the phone (a `content://` or `file://` URI). Songs analysed
     * this way get an id of the form `local-<hash>` and live only in the Lab; the DJ never plays them.
     */
    fun analyseFile(
        uri: String,
        displayName: String,
    ) {
        val context = attachedTo ?: return
        if (job?.isActive == true) return
        val videoId = "local-" + uri.hashCode().toUInt().toString(16)
        job =
            scope.launch {
                guarded(videoId) {
                    runPipeline(context, videoId, displayName, "", uri, DjTrack(videoId, displayName, emptyList(), null, null, null))
                }
            }
    }

    private suspend fun analyseNow(
        context: Context,
        videoId: String,
    ) {
        val song = withContext(Dispatchers.IO) { metrolistDatabase.dao().getSongById(videoId)?.toSongItem() }
        val location = song?.let { localAudioLocation(it) }
        if (song == null || location == null) {
            mutableState.update { it.copy(message = LabMessage.NoAudioFile) }
            return
        }
        runPipeline(context, videoId, song.title, artistNames(song), location, song.toDjTrack())
    }

    /** decode -> analyse -> save -> show. Shared by Metrolist's songs and picked files. */
    private suspend fun runPipeline(
        context: Context,
        videoId: String,
        title: String,
        artists: String,
        location: String,
        track: DjTrack,
    ) {
        val repository = repository(context)
        if (!location.contains("://")) {
            mutableState.update { it.copy(message = LabMessage.UnsupportedFile) }
            return
        }
        mutableState.update { it.copy(busy = LabBusy(title, LabBusy.Step.DECODING), message = null) }
        val decodeStart = System.nanoTime()
        val pcm = MediaCodecPcmDecoder(context).decodeToMono(AudioSource(location))
        val decodeMs = (System.nanoTime() - decodeStart) / 1_000_000
        mutableState.update { it.copy(busy = LabBusy(title, LabBusy.Step.ANALYSING)) }
        val analyseStart = System.nanoTime()
        val result = NativeAudioAnalyzer().analyze(pcm)
        Timber.tag(TAG).d(
            "%s: decode %d ms, analyse %d ms (%.0f s of audio, %s)",
            title,
            decodeMs,
            (System.nanoTime() - analyseStart) / 1_000_000,
            pcm.durationMs / 1000,
            location.substringBefore(":"),
        )
        repository.save(videoId, result, track)

        val stored = checkNotNull(repository.load(videoId))
        mutableState.update { it.copy(busy = null, detail = LabDetail(videoId, title, artists, stored)) }
        refreshLocalSongs()
    }

    private suspend fun guarded(
        videoId: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Analysis failed for $videoId")
            mutableState.update { it.copy(busy = null, message = LabMessage.Failed(e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** `file://` or `content://` URI of the song's audio on this phone, or null if there is none. */
    private suspend fun localAudioLocation(song: SongItem): String? {
        val url = audioCache.getCachedPlaybackResource(song, touch = false)?.url ?: return null
        return url.takeIf { it.startsWith("file:") || it.startsWith("content:") }
    }

    private suspend fun describe(videoId: String): Pair<String, String> {
        val song = withContext(Dispatchers.IO) { metrolistDatabase.dao().getSongById(videoId)?.toSongItem() }
        if (song != null) return song.title to artistNames(song)
        val stored = repository(checkNotNull(attachedTo)).trackInfo(videoId)
        return if (stored != null) stored.title to stored.artists.joinToString(", ") else videoId to ""
    }

    private fun artistNames(song: SongItem) = song.artists.joinToString(", ") { it.name }

    private suspend fun refreshCounts(repository: DjAnalysisRepository) {
        val counts = repository.counts()
        mutableState.update { it.copy(counts = counts) }
    }

    private fun repository(context: Context) = DjData.analysisRepository(context)
}
