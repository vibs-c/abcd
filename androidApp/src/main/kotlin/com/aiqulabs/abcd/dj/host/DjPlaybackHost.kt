package com.aiqulabs.abcd.dj.host

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.engine.DjEngineEvent
import com.aiqulabs.abcd.dj.engine.DjMixEngine
import com.aiqulabs.abcd.dj.engine.DjMixSession
import com.aiqulabs.abcd.dj.engine.DjSessionRequests
import com.aiqulabs.abcd.dj.engine.DjTrackSource
import com.aiqulabs.abcd.dj.engine.ExoDjMixEngine
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The hand-over between Metrolist's player and the DJ engine (docs/DJ_ENGINE_DESIGN.md §5). Lives in
 * `MusicService`, which supplies [Ops]; nothing else switches between the two.
 *
 * Entering: Metrolist's player is stopped (queue kept, position remembered), a new engine is created and
 * the media session is pointed at the engine's player. Leaving: the engine fades out and releases its
 * decks and focus, the session goes back to Metrolist's player, and Metrolist's song is re-prepared,
 * paused, where it was (unless the exit was "the user started something else").
 *
 * Main thread only.
 */
internal class DjPlaybackHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ops: Ops,
) {
    /** What the host needs from `MusicService`; all of these are existing Metrolist calls. */
    interface Ops {
        /** False while casting or in a Listen Together session (v1 refuses DJ Mode then). */
        fun canStartDj(): Boolean

        /** Saves Metrolist's state and stops its player (queue kept). Returns the position to restore, or null. */
        fun stopNormalPlayback(): Long?

        /** Re-prepares Metrolist's current queue song, paused, at [positionMs]. */
        fun restoreNormalPlayback(positionMs: Long)

        /** Puts [player] in the media session, or Metrolist's own player when null. */
        fun setSessionPlayer(player: Player?)

        /** Plays items a controller (e.g. Android Auto) asked for, through Metrolist's player. */
        fun playForeign(
            items: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        )
    }

    private var engine: DjMixEngine? = null
    private var observers: List<Job> = emptyList()
    private var savedPositionMs: Long? = null
    private var songs: Map<String, SongItem> = emptyMap()
    private val files = HashMap<String, DjAudioFile>()

    /** True from [enter] until an exit starts; the guard in MusicService ends DJ Mode while this is true. */
    val isActive: Boolean get() = engine != null

    /** True only during [enter], when stopping Metrolist's player must not be read as "user played something". */
    var isEntering: Boolean = false
        private set

    private val source =
        DjTrackSource { track ->
            val song = songs[track.videoId] ?: return@DjTrackSource null
            val file =
                try {
                    withContext(Dispatchers.IO) { DjAudioProvider.get(context).acquire(song) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "No audio for %s", song.id)
                    null
                } ?: return@DjTrackSource null
            files[track.videoId] = file
            Uri.parse(file.location)
        }

    private val requests =
        object : DjSessionRequests {
            override fun onStopRequested() = exit()

            override fun onForeignPlaybackRequested(
                mediaItems: List<MediaItem>,
                startIndex: Int,
                startPositionMs: Long,
            ) = exit(fadeOutMs = 0L, restoreNormal = false) { ops.playForeign(mediaItems, startIndex, startPositionMs) }
        }

    /** Starts DJ Mode with [tracks] (prepared and ordered). Returns false if it cannot start now. */
    fun enter(
        tracks: List<PreparedTrack>,
        songs: Map<String, SongItem>,
        settings: DjSessionSettings,
    ): Boolean {
        if (tracks.isEmpty() || !ops.canStartDj()) {
            Timber.tag(TAG).w("DJ Mode refused: %d tracks, casting or Listen Together = %s", tracks.size, !ops.canStartDj())
            return false
        }
        // A new mix replaces the one playing (it kept playing while the new one was prepared).
        val earlierPosition = if (engine != null) savedPositionMs else null
        if (engine != null) exitNow()
        isEntering = true
        try {
            this.songs = songs
            // Metrolist is already stopped when one mix replaces another; keep the position from before the first.
            savedPositionMs = ops.stopNormalPlayback() ?: earlierPosition
            val newEngine = ExoDjMixEngine(context, source) { requests }
            engine = newEngine
            ops.setSessionPlayer(newEngine.sessionPlayer)
            observe(newEngine)
            newEngine.start(DjMixSession(tracks, settings))
            Timber.tag(TAG).i("DJ Mode on: %d tracks", tracks.size)
        } finally {
            isEntering = false
        }
        return true
    }

    fun play() = engine?.play()

    fun pause() = engine?.pause()

    fun skipNext() = engine?.skipNext()

    /**
     * Ends DJ Mode: fade, release the decks, give the session back to Metrolist. [restoreNormal] re-prepares
     * Metrolist's previous song (paused); it is false when the user is starting something else. [then] runs
     * once the hand-back is complete.
     */
    fun exit(
        fadeOutMs: Long = DjMixEngine.DEFAULT_END_FADE_MS,
        restoreNormal: Boolean = true,
        then: (() -> Unit)? = null,
    ) {
        val ending = engine ?: return
        engine = null
        stopObserving()
        scope.launch {
            ending.end(fadeOutMs)
            handBack(restoreNormal)
            then?.invoke()
        }
    }

    /** Immediate exit without a fade, for the service being destroyed. */
    fun exitNow() {
        val ending = engine ?: return
        engine = null
        stopObserving()
        ending.close()
        handBack(restoreNormal = false)
    }

    private fun handBack(restoreNormal: Boolean) {
        ops.setSessionPlayer(null)
        DjPlayback.publish(null)
        // The "Your mix is playing" result belongs to the mix that just ended.
        DjController.dismissResult()
        val position = savedPositionMs
        savedPositionMs = null
        if (restoreNormal && position != null) ops.restoreNormalPlayback(position)
        files.values.forEach { it.release() }
        files.clear()
        songs = emptyMap()
        Timber.tag(TAG).i("DJ Mode off")
    }

    private fun observe(engine: DjMixEngine) {
        observers =
            listOf(
                scope.launch { engine.state.collect { DjPlayback.publish(it) } },
                scope.launch {
                    engine.events.collect { event ->
                        when (event) {
                            is DjEngineEvent.TrackFinished -> files.remove(event.track.videoId)?.release()
                            is DjEngineEvent.SessionFinished -> exit(fadeOutMs = 0L)
                            is DjEngineEvent.TransitionStarted -> Timber.tag(TAG).d("Mixing into %s: %s", event.to.title, event.plan.reason)
                            is DjEngineEvent.TrackSkipped -> Timber.tag(TAG).w("Skipped %s: %s", event.track.title, event.reason)
                            else -> Unit
                        }
                    }
                },
            )
    }

    private fun stopObserving() {
        observers.forEach { it.cancel() }
        observers = emptyList()
    }

    private companion object {
        const val TAG = "DjPlaybackHost"
    }
}
