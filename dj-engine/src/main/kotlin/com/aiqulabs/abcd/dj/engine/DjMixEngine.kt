package com.aiqulabs.abcd.dj.engine

import android.net.Uri
import androidx.media3.common.Player
import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.mix.TransitionPlan
import com.aiqulabs.abcd.dj.core.mix.TransitionPlanner
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The DJ's playback engine (brief §9.1, docs/DJ_ENGINE_DESIGN.md). While a session runs it owns its two
 * decks, the session's audio focus and the player shown in the media session; Metrolist's player is
 * stopped. The UI and the DJ brain only see this interface, so the v2 single-stream (Oboe) engine can
 * replace the ExoPlayer one without touching them.
 *
 * Call everything on the main thread.
 */
interface DjMixEngine {
    val state: StateFlow<DjEngineState>
    val events: SharedFlow<DjEngineEvent>

    /** The player to put in the media session while DJ Mode is on (notification, lock screen, Auto). */
    val sessionPlayer: Player

    fun start(session: DjMixSession)

    fun play()

    fun pause()

    /** "Next": a short transition to the next track now. */
    fun skipNext()

    /** The user removed or reordered upcoming tracks (stage 10). The current track keeps playing. */
    fun updateQueue(upcomingVideoIds: List<String>)

    /** Fades out, releases both decks and the audio focus. Safe to call more than once. */
    suspend fun end(fadeOutMs: Long = DEFAULT_END_FADE_MS)

    /** Releases everything immediately, without a fade (service shutting down). */
    fun close()

    /** Where the playing song is and when the next mix happens, for the live screen (stage 12). Main thread. */
    fun progress(): DjProgress? = null

    companion object {
        const val DEFAULT_END_FADE_MS = 1_500L
    }
}

/** What to play: the prepared, ordered tracks and the choices they were made for. */
data class DjMixSession(
    val tracks: List<PreparedTrack>,
    val settings: DjSessionSettings,
    val planner: TransitionPlanner = MixPlanner(),
)

/** Gives the engine a playable local file for a track (stage 6 files); null when it cannot. */
fun interface DjTrackSource {
    suspend fun open(track: DjTrack): Uri?
}

/** Called when the media session's controls ask for something the DJ cannot do itself. */
interface DjSessionRequests {
    /** Stop pressed in the notification / by a controller: end DJ Mode. */
    fun onStopRequested()

    /** A controller (e.g. Android Auto) asked to play other items: end DJ Mode, then play them normally. */
    fun onForeignPlaybackRequested(
        mediaItems: List<androidx.media3.common.MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    )
}

/**
 * The playing song's position and the next (or running) transition's window on that song's clock, in ms.
 * [mixStartMs]/[mixEndMs] are null until the next song is ready and the transition is planned.
 */
data class DjProgress(
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val mixStartMs: Long?,
    val mixEndMs: Long?,
    val mixing: Boolean,
) {
    /** 0..1 through the transition while it runs. */
    val mixProgress: Float
        get() {
            val start = mixStartMs ?: return 0f
            val end = mixEndMs ?: return 0f
            if (end <= start) return if (positionMs >= end) 1f else 0f
            return ((positionMs - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        }
}

data class DjEngineState(
    val phase: Phase = Phase.IDLE,
    val current: DjTrack? = null,
    val next: DjTrack? = null,
    val currentIndex: Int = -1,
    val trackCount: Int = 0,
    /** The tracks still to come after [current], in order. */
    val upcoming: List<DjTrack> = emptyList(),
    val playWhenReady: Boolean = false,
    val transition: TransitionPlan? = null,
) {
    enum class Phase {
        IDLE,
        STARTING,

        /** Session started but another app (e.g. a call) holds audio focus; starts when it is released. */
        WAITING_FOR_FOCUS,
        PLAYING,
        PAUSED,
        ENDING,

        /** The last track finished. */
        FINISHED,
    }

    val isActive: Boolean get() = phase != Phase.IDLE && phase != Phase.FINISHED
}

sealed interface DjEngineEvent {
    data class TrackStarted(
        val track: DjTrack,
        val index: Int,
    ) : DjEngineEvent

    data class TransitionStarted(
        val from: DjTrack,
        val to: DjTrack,
        val plan: TransitionPlan,
        /** True when the user pressed "Next" (a skip of [from]); false for a planned transition. */
        val byUser: Boolean = false,
    ) : DjEngineEvent

    data class TrackSkipped(
        val track: DjTrack,
        val reason: String,
    ) : DjEngineEvent

    /** A track finished playing; its session file is no longer needed. */
    data class TrackFinished(
        val track: DjTrack,
    ) : DjEngineEvent

    data object SessionFinished : DjEngineEvent

    data class Error(
        val message: String,
    ) : DjEngineEvent

    /** [v2] An effect from the effects channel (brief §9.4). Never emitted in v1. */
    data class EffectTriggered(
        val effectId: String,
    ) : DjEngineEvent
}
