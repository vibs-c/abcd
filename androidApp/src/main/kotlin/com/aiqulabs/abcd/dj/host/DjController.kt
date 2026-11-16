package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.brain.DjPlaylistBuilder
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
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
 * Runs "Start DJ" (stage 3): builds the playlist from the setup choices and hands it to Metrolist's
 * normal player. The mixing engine (stage 8) later replaces the hand-off, not the build step.
 *
 * Application-wide on purpose: the build keeps running if the user switches tabs, and its progress
 * is shown again when they come back.
 */
object DjController {
    /** Fewer playable songs than this is reported as a failure instead of playing a tiny mix. */
    internal const val MIN_PLAYABLE_TRACKS = 5

    private const val TAG = "DjController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableState = MutableStateFlow<DjStartState>(DjStartState.Idle)
    private var job: Job? = null

    val state: StateFlow<DjStartState> = mutableState.asStateFlow()

    /** Starts building and playing a mix. Ignored while a build is already running. */
    fun start(
        settings: DjSessionSettings,
        playerConnection: PlayerConnection,
    ) {
        if (job?.isActive == true) return
        mutableState.value = DjStartState.Building
        job =
            scope.launch {
                val result =
                    try {
                        buildAndPlay(settings, playerConnection)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Building the DJ mix failed")
                        DjStartState.Failed(DjStartState.Reason.ERROR)
                    }
                mutableState.value = result
            }
    }

    /** Stops a running build and returns to the idle setup screen. */
    fun cancel() {
        job?.cancel()
        job = null
        mutableState.value = DjStartState.Idle
    }

    /** Clears a finished or failed status card. Does not touch a running build. */
    fun dismissResult() {
        if (mutableState.value !is DjStartState.Building) mutableState.value = DjStartState.Idle
    }

    private suspend fun buildAndPlay(
        settings: DjSessionSettings,
        playerConnection: PlayerConnection,
    ): DjStartState {
        val source = YouTubeCandidateSource()
        val plan = DjPlaylistBuilder(source, MetadataTrackTagger()).build(settings)
        val songs = BlockedContent.filterPlayableSongs(plan.tracks.mapNotNull { source.songFor(it.videoId) })
        if (songs.isEmpty() && source.requestFailed) return DjStartState.Failed(DjStartState.Reason.ERROR)
        if (songs.size < MIN_PLAYABLE_TRACKS) {
            return DjStartState.Failed(DjStartState.Reason.NOT_ENOUGH_SONGS, foundCount = songs.size)
        }
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
}
