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
