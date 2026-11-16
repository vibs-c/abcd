package com.aiqulabs.abcd.dj.host

import android.content.Context
import com.aiqulabs.abcd.dj.analysis.MediaCodecPcmDecoder
import com.aiqulabs.abcd.dj.analysis.NativeAudioAnalyzer
import com.aiqulabs.abcd.dj.core.brain.DjPlaylistBuilder
import com.aiqulabs.abcd.dj.core.brain.PlaylistOrderer
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.core.prep.SessionPreparer
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.ui.setup.DjStartState
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_queue_title
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import timber.log.Timber

/**
 * Runs "Start DJ": builds the playlist from the setup choices (stage 3), analyses every track before
 * playback (stage 6, brief §5.1), orders the analysed tracks (brief §7.2) and hands them to Metrolist's
 * normal player. The mixing engine (stage 8) later replaces the hand-off, not these steps.
 *
 * Application-wide on purpose: the work keeps running if the user switches tabs, and its progress is
 * shown again when they come back.
 */
object DjController {
    /** Fewer playable songs than this is reported as a failure instead of playing a tiny mix. */
    internal const val MIN_PLAYABLE_TRACKS = 5

    private const val TAG = "DjController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<DjStartState>(DjStartState.Idle)
    private var job: Job? = null

    val state: StateFlow<DjStartState> = mutableState.asStateFlow()

    /** Starts building, preparing and playing a mix. Ignored while one is already running. */
    fun start(
        context: Context,
        settings: DjSessionSettings,
        playerConnection: PlayerConnection,
    ) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        mutableState.value = DjStartState.Building
        job =
            scope.launch {
                val result =
                    try {
                        buildPrepareAndPlay(appContext, settings, playerConnection)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Building the DJ mix failed")
                        DjStartState.Failed(DjStartState.Reason.ERROR)
                    }
                mutableState.value = result
            }
    }

    /** Stops a running build or preparation and returns to the idle setup screen. Analysed songs are kept. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = DjStartState.Idle
    }

    /** Clears a finished or failed status card. Does not touch a running build. */
    fun dismissResult() {
        val current = mutableState.value
        if (current !is DjStartState.Building && current !is DjStartState.Preparing) mutableState.value = DjStartState.Idle
    }

    private suspend fun buildPrepareAndPlay(
        context: Context,
        settings: DjSessionSettings,
        playerConnection: PlayerConnection,
    ): DjStartState {
        val source = YouTubeCandidateSource()
        val built = DjPlaylistBuilder(source, MetadataTrackTagger()).build(settings)
        val playableIds =
            BlockedContent
                .filterPlayableSongs(
                    built.tracks.mapNotNull { source.songFor(it.videoId) },
                ).mapTo(HashSet()) { it.id }
        val plan = built.copy(tracks = built.tracks.filter { it.videoId in playableIds })
        if (plan.tracks.isEmpty() && source.requestFailed) return DjStartState.Failed(DjStartState.Reason.ERROR)
        if (plan.tracks.size < MIN_PLAYABLE_TRACKS) {
            return DjStartState.Failed(DjStartState.Reason.NOT_ENOUGH_SONGS, foundCount = plan.tracks.size)
        }

        val repository = DjData.analysisRepository(context)
        val analyzer = NativeAudioAnalyzer()
        val audio = DjAudioProvider.get(context)
        val known = repository.analysedIds(analyzer.analysisVersion)
        val firstTracks = plan.tracks.take(plan.coreCount)
        val toFetch = firstTracks.count { it.videoId !in known }
        if (!audio.hasSpaceFor(toFetch)) return DjStartState.Failed(DjStartState.Reason.NOT_ENOUGH_STORAGE)
        audio.prune(plan.tracks.mapTo(HashSet()) { it.videoId })

        val preparer =
            DjTrackPreparer(
                repository = repository,
                audio = audio,
                decoder = MediaCodecPcmDecoder(context),
                analyzer = analyzer,
                songFor = source::songFor,
                keepAudio = true,
            )
        val startedAt = System.nanoTime()
        val prepared =
            SessionPreparer(preparer).prepare(plan) { progress ->
                mutableState.value = DjStartState.Preparing(progress)
            }
        Timber.tag(TAG).i(
            "Prepared %d of %d tracks (%d dropped, %d already analysed) in %d s",
            prepared.tracks.size,
            plan.coreCount,
            prepared.failed.size,
            firstTracks.size - toFetch,
            (System.nanoTime() - startedAt) / 1_000_000_000,
        )
        if (prepared.tracks.size < MIN_PLAYABLE_TRACKS) {
            // Nothing at all could be analysed: most likely a connection problem rather than a thin catalogue.
            val noneWorked = prepared.tracks.isEmpty() && prepared.failed.isNotEmpty()
            val reason = if (noneWorked) DjStartState.Reason.ERROR else DjStartState.Reason.NOT_ENOUGH_SONGS
            return DjStartState.Failed(reason, foundCount = prepared.tracks.size)
        }

        val ordered = PlaylistOrderer().order(prepared.tracks, plan.settings)
        logOrder(ordered)
        val songs = ordered.mapNotNull { source.songFor(it.track.videoId) }
        val title = getString(Res.string.dj_queue_title)
        withContext(Dispatchers.Main) {
            playerConnection.playQueue(
                songs = songs,
                startIndex = 0,
                queueTitle = title,
                finite = true,
                shuffleModeEnabled = false,
            )
        }
        return DjStartState.Started(songs.size)
    }

    /** One line per track so the chosen order can be checked in the log (BPM, key, energy). */
    private fun logOrder(ordered: List<PreparedTrack>) {
        ordered.forEachIndexed { index, t ->
            Timber.tag(TAG).d(
                "#%d %s | %.1f BPM | %s | energy %.2f",
                index + 1,
                t.track.title,
                t.analysis.bpm,
                t.analysis.key.camelot,
                t.analysis.energyCurve
                    .takeIf { it.isNotEmpty() }
                    ?.average() ?: Double.NaN,
            )
        }
    }
}
