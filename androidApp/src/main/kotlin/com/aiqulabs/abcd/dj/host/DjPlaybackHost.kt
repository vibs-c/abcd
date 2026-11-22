package com.aiqulabs.abcd.dj.host

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.mix.TransitionPlanner
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.data.DjData
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.engine.DjEngineEvent
import com.aiqulabs.abcd.dj.engine.DjProgress
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
    private var order: List<DjTrack> = emptyList()
    private var recorder: DjFeedbackRecorder? = null
    private var analyses: Map<String, AnalysisResult> = emptyMap()

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
        planner: TransitionPlanner = MixPlanner(),
    ): Boolean {
        if (tracks.isEmpty() || !ops.canStartDj()) {
            Timber.tag(TAG).w("DJ Mode refused: %d tracks, casting or Listen Together = %s", tracks.size, !ops.canStartDj())
            return false
        }
        // A new mix replaces the one playing (it kept playing while the new one was prepared).
        val earlierPosition = if (engine != null) savedPositionMs else null
        val nextIds = tracks.mapTo(HashSet()) { it.track.videoId }
        if (engine != null) exitNow(keepFilesOf = nextIds)
        isEntering = true
        try {
            this.songs = songs
            order = tracks.map { it.track }
            analyses = tracks.associate { it.track.videoId to it.analysis }
            DjAudioProvider.get(context).pin(nextIds)
            // Metrolist is already stopped when one mix replaces another; keep the position from before the first.
            savedPositionMs = ops.stopNormalPlayback() ?: earlierPosition
            val newEngine = ExoDjMixEngine(context, source) { requests }
            engine = newEngine
            ops.setSessionPlayer(newEngine.sessionPlayer)
            recorder = DjFeedbackRecorder(DjData.feedbackRepository(context), scope, tracks, settings)
            observe(newEngine)
            newEngine.start(DjMixSession(tracks, settings, planner))
            Timber.tag(TAG).i("DJ Mode on: %d tracks", tracks.size)
        } finally {
            isEntering = false
        }
        return true
    }

    fun play() = engine?.play()

    /** Position and mix window for the live screen (stage 12). */
    fun progress(): DjProgress? = engine?.progress()

    fun analysisOf(videoId: String): AnalysisResult? = analyses[videoId]

    fun pause() = engine?.pause()

    fun skipNext() = engine?.skipNext()

    /** Thumbs on the transition that just happened (brief §4.3). */
    fun rateLastTransition(up: Boolean) = recorder?.rate(up)

    /** Removes [videoId] from the songs still to come; the DJ re-plans the transitions around it. */
    fun removeUpcoming(
        videoId: String,
        neverPlay: Boolean,
    ) {
        val current = engine ?: return
        val upcoming = current.state.value.upcoming.map { it.videoId }
        if (videoId !in upcoming) return
        current.updateQueue(upcoming - videoId)
        recorder?.removed(videoId, neverPlay)
    }

    /** Moves [videoId] [by] places up (negative) or down (positive) in the songs still to come. */
    fun moveUpcoming(
        videoId: String,
        by: Int,
    ) {
        val current = engine ?: return
        val upcoming = current.state.value.upcoming.map { it.videoId }.toMutableList()
        val from = upcoming.indexOf(videoId)
        if (from < 0) return
        val to = (from + by).coerceIn(0, upcoming.lastIndex)
        if (to == from) return
        upcoming.add(to, upcoming.removeAt(from))
        current.updateQueue(upcoming)
    }

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
        val leftovers = detach()
        scope.launch {
            ending.end(fadeOutMs)
            handBack(leftovers, restoreNormal)
            then?.invoke()
        }
    }

    /** Immediate exit without a fade (service destroyed, or a new mix replacing this one). */
    fun exitNow(keepFilesOf: Set<String> = emptySet()) {
        val ending = engine ?: return
        engine = null
        stopObserving()
        val leftovers = detach()
        ending.close()
        handBack(leftovers, restoreNormal = false, keepFilesOf = keepFilesOf)
    }

    /** What an ending mix leaves behind, taken at once so a new mix started during the fade-out keeps its own. */
    private class Leftovers(
        val savedPositionMs: Long?,
        val files: List<DjAudioFile>,
    )

    private fun detach(): Leftovers {
        recorder?.finish()
        recorder = null
        DjPlayback.publishLastTransition(null)
        val leftovers = Leftovers(savedPositionMs, files.values.toList())
        savedPositionMs = null
        files.clear()
        songs = emptyMap()
        order = emptyList()
        analyses = emptyMap()
        return leftovers
    }

    /**
     * Gives the session back to Metrolist. If a new mix started while this one faded out, the session, the
     * card and the pinned files belong to the new mix: only the old files go, and Metrolist's position from
     * before the first mix is handed on so the new mix can restore it when it ends.
     */
    private fun handBack(
        leftovers: Leftovers,
        restoreNormal: Boolean,
        keepFilesOf: Set<String> = emptySet(),
    ) {
        if (engine == null) {
            ops.setSessionPlayer(null)
            DjPlayback.publish(null)
            // The "Your mix is playing" result belongs to the mix that just ended.
            DjController.dismissResult()
            if (restoreNormal) leftovers.savedPositionMs?.let(ops::restoreNormalPlayback)
            DjAudioProvider.get(context).pin(keepFilesOf)
        } else if (savedPositionMs == null) {
            savedPositionMs = leftovers.savedPositionMs
        }
        leftovers.files.forEach { it.release() }
        Timber.tag(TAG).i("DJ Mode off")
    }

    private fun observe(engine: DjMixEngine) {
        val feedback = recorder
        observers =
            listOfNotNull(
                scope.launch { engine.state.collect { DjPlayback.publish(it) } },
                feedback?.let { r -> scope.launch { r.lastTransition.collect { DjPlayback.publishLastTransition(it) } } },
                scope.launch {
                    engine.events.collect { event ->
                        feedback?.onEvent(event)
                        when (event) {
                            is DjEngineEvent.TrackFinished -> {
                                // Played: unpin and delete (abcd's own downloads only; Metrolist's files are never touched).
                                DjAudioProvider.get(context).unpin(event.track.videoId)
                                files.remove(event.track.videoId)?.release()
                            }

                            is DjEngineEvent.TrackStarted -> prefetchAfter(event.track.videoId)
                            is DjEngineEvent.SessionFinished -> exit(fadeOutMs = 0L)
                            is DjEngineEvent.TransitionStarted -> Timber.tag(TAG).d("Mixing into %s: %s", event.to.title, event.plan.reason)
                            is DjEngineEvent.TrackSkipped -> Timber.tag(TAG).w("Skipped %s: %s", event.track.title, event.reason)
                            else -> Unit
                        }
                    }
                },
            )
    }

    /**
     * Keeps the files of the next [PREFETCH_TRACKS] tracks ready (design §7). The engine loads only the very
     * next one; a file Android removed from the cache is downloaded again here, well before it is needed.
     */
    private fun prefetchAfter(videoId: String) {
        val position = order.indexOfFirst { it.videoId == videoId }
        if (position < 0) return
        order.drop(position + 1).take(PREFETCH_TRACKS).forEach { track ->
            if (track.videoId !in files) scope.launch { source.open(track) }
        }
    }

    private fun stopObserving() {
        observers.forEach { it.cancel() }
        observers = emptyList()
    }

    private companion object {
        const val TAG = "DjPlaybackHost"
        const val PREFETCH_TRACKS = 2
    }
}
