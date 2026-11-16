package com.aiqulabs.abcd.dj.engine

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.aiqulabs.abcd.dj.core.mix.ClassicFadePlanner
import com.aiqulabs.abcd.dj.core.mix.EqualPowerFade
import com.aiqulabs.abcd.dj.core.mix.TransitionPlan
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1 [DjMixEngine]: two ExoPlayer decks (design §4).
 *
 * The main deck plays the current track while the other deck holds the next one, loaded paused and silent.
 * A tick every [TICK_MS] checks the main deck against the planned exit point; when it is reached the other
 * deck starts and an equal-power fade runs. Fade progress is measured on the **incoming deck's media clock**,
 * so pausing (or a phone call) freezes a fade exactly where it was, and the fade cannot outrun the audio.
 * When the fade completes the decks swap roles and the next track is loaded.
 *
 * Single-use: one engine per DJ session. All methods on the main thread.
 */
@OptIn(UnstableApi::class)
class ExoDjMixEngine(
    context: Context,
    private val source: DjTrackSource,
    private val requests: () -> DjSessionRequests? = { null },
) : DjMixEngine {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(DjEngineState())
    override val state: StateFlow<DjEngineState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<DjEngineEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<DjEngineEvent> = mutableEvents.asSharedFlow()

    private var mix: DjMixSession? = null
    private var tracks: List<PreparedTrack> = emptyList()
    private val unplayable = HashSet<Int>()
    private var decks: Array<Deck>? = null
    private var main = 0
    private var currentIndex = -1
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var transition: Running? = null
    private var phase = DjEngineState.Phase.IDLE
    private var userWantsPlay = false
    private var pausedByFocus = false
    private var duckGain = 1f
    private var masterGain = 1f
    private var skipRequested = false
    private var pendingQueue: List<String>? = null
    private var ending = false
    private var released = false

    private val deckListener: Deck.Listener =
        object : Deck.Listener {
            override fun onDeckStateChanged(deck: Deck) = publish()

            override fun onDeckError(
                deck: Deck,
                error: PlaybackException,
            ) = handleDeckError(deck, error)
        }

    private val focus: DjAudioFocus =
        DjAudioFocus(
            appContext,
            object : DjAudioFocus.Listener {
                override fun onFocusGained() {
                    pausedByFocus = false
                    applyPlayback()
                }

                override fun onFocusLostTransient() {
                    pausedByFocus = true
                    applyPlayback()
                }

                override fun onFocusLost() {
                    userWantsPlay = false
                    pausedByFocus = false
                    applyPlayback()
                }

                override fun onDuck(ducked: Boolean) {
                    duckGain = if (ducked) DUCK_GAIN else 1f
                    applyVolumes()
                }

                override fun onBecomingNoisy() = pause()
            },
        )

    // Created on first use, after every field above is initialised (SimpleBasePlayer may read state early).
    private val session: DjSessionPlayer by lazy { DjSessionPlayer(this, requests) }
    override val sessionPlayer: Player get() = session

    private class Running(
        val plan: TransitionPlan,
        val from: Int,
        val to: Int,
    )

    // ---- DjMixEngine --------------------------------------------------------------------------------

    override fun start(session: DjMixSession) {
        check(mix == null && !released) { "An engine plays one session" }
        mix = session
        tracks = session.tracks
        if (tracks.isEmpty()) {
            finish()
            return
        }
        val audioSessionId = appContext.getSystemService(AudioManager::class.java).generateAudioSessionId()
        decks = arrayOf(Deck(appContext, "A", audioSessionId, deckListener), Deck(appContext, "B", audioSessionId, deckListener))
        phase = DjEngineState.Phase.STARTING
        userWantsPlay = true
        publish()
        scope.launch {
            val first = loadNextPlayable(deck = main, after = -1)
            if (first < 0) {
                finish()
                return@launch
            }
            currentIndex = first
            emit(DjEngineEvent.TrackStarted(tracks[first].track, first))
            requestFocusAndPlay()
            preloadNext()
        }
        tickJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(TICK_MS)
                }
            }
    }

    override fun play() {
        if (ending || released || phase == DjEngineState.Phase.FINISHED) return
        userWantsPlay = true
        pausedByFocus = false
        requestFocusAndPlay()
    }

    override fun pause() {
        userWantsPlay = false
        applyPlayback()
    }

    override fun skipNext() {
        if (ending || released) return
        if (transition != null) {
            finishTransition()
        } else {
            skipRequested = true
            tick()
        }
    }

    override fun updateQueue(upcomingVideoIds: List<String>) {
        if (transition != null) {
            pendingQueue = upcomingVideoIds
            return
        }
        applyQueue(upcomingVideoIds)
    }

    override suspend fun end(fadeOutMs: Long) =
        withContext(Dispatchers.Main.immediate) {
            if (ending || released) return@withContext
            ending = true
            phase = DjEngineState.Phase.ENDING
            publish()
            if (fadeOutMs > 0 && isAudible()) {
                val steps = (fadeOutMs / FADE_STEP_MS).coerceAtLeast(1L)
                for (step in 1..steps) {
                    masterGain = 1f - step.toFloat() / steps
                    applyVolumes()
                    delay(FADE_STEP_MS)
                }
            }
            releaseAll()
        }

    override fun close() {
        ending = true
        releaseAll()
    }

    // ---- what the session player reads ----------------------------------------------------------------

    internal fun mainPositionMs(): Long = decks?.get(main)?.positionMs ?: 0L

    internal fun isMainDeckReady(): Boolean = decks?.get(main)?.player?.playbackState == Player.STATE_READY

    // ---- the mix --------------------------------------------------------------------------------------

    private fun tick() {
        val d = decks ?: return
        if (ending || released) return
        val running = transition
        if (running != null) {
            val progress = applyVolumes()
            if (progress >= 1.0 || d[running.to].hasEnded) finishTransition()
            return
        }
        applyVolumes()
        val m = d[main]
        val other = d[1 - main]
        val current = m.track ?: return
        val mix = mix ?: return
        if (other.isReady) {
            val next = checkNotNull(other.track)
            if (skipRequested) {
                skipRequested = false
                begin(mix.planner.planSkip(current, next, m.positionMs, mix.settings))
                return
            }
            if (!isPlayingNow() && !m.hasEnded) return
            val plan = mix.planner.plan(current, next, mix.settings)
            val latestExit = (m.durationMs - plan.fadeMs - ClassicFadePlanner.END_TAIL_MS).coerceAtLeast(0L)
            if (m.hasEnded) {
                begin(plan.copy(fadeMs = 0L, reason = plan.reason + " (outgoing ended early)"))
            } else if (m.positionMs >= minOf(plan.outgoingExitMs, latestExit)) {
                begin(plan)
            }
        } else if (m.hasEnded && other.track == null && loadJob?.isActive != true && !hasPlayableAfter(currentIndex)) {
            emit(DjEngineEvent.TrackFinished(current.track))
            finish()
        }
    }

    private fun begin(plan: TransitionPlan) {
        val d = decks ?: return
        val from = d[main]
        val to = d[1 - main]
        val fromTrack = from.track ?: return
        val toTrack = to.track ?: return
        transition = Running(plan, from = main, to = 1 - main)
        to.player.volume = 0f
        to.player.playWhenReady = shouldPlay()
        Log.i(TAG, "Transition ${fromTrack.track.title} -> ${toTrack.track.title}: ${plan.reason}")
        emit(DjEngineEvent.TransitionStarted(fromTrack.track, toTrack.track, plan))
        applyVolumes()
        publish()
    }

    private fun finishTransition() {
        val d = decks ?: return
        val running = transition ?: return
        transition = null
        val from = d[running.from]
        val finished = from.track
        from.clear()
        main = running.to
        currentIndex = d[main].trackIndex
        applyVolumes()
        finished?.let { emit(DjEngineEvent.TrackFinished(it.track)) }
        d[main].track?.let { emit(DjEngineEvent.TrackStarted(it.track, currentIndex)) }
        pendingQueue?.let {
            pendingQueue = null
            applyQueue(it)
        }
        preloadNext()
        publish()
    }

    /** Loads the next playable track after the current one onto the free deck. */
    private fun preloadNext() {
        val d = decks ?: return
        val free = 1 - main
        if (d[free].track != null || ending || released) return
        loadJob?.cancel()
        loadJob =
            scope.launch {
                loadNextPlayable(deck = free, after = currentIndex)
                publish()
            }
    }

    /** Loads the first track after index [after] that has a file onto [deck]. Returns its index, or -1. */
    private suspend fun loadNextPlayable(
        deck: Int,
        after: Int,
    ): Int {
        val d = decks ?: return -1
        var index = after + 1
        while (index < tracks.size) {
            if (index !in unplayable) {
                val track = tracks[index]
                val uri =
                    try {
                        source.open(track.track)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "No file for track $index", e)
                        null
                    }
                if (ending || released) return -1
                if (uri != null) {
                    d[deck].load(track, index, uri, entryFor(index))
                    return index
                }
                markUnplayable(index, "audio file not available")
            }
            index++
        }
        return -1
    }

    private fun entryFor(index: Int): Long {
        val m = checkNotNull(mix)
        val previous = tracks.getOrNull(currentIndex)
        return if (previous == null) {
            m.planner.firstEntryMs(tracks[index], m.settings)
        } else {
            m.planner.plan(previous, tracks[index], m.settings).incomingEntryMs
        }
    }

    private fun applyQueue(upcomingVideoIds: List<String>) {
        val d = decks ?: return
        val head = tracks.take(currentIndex + 1)
        val rest = tracks.drop(currentIndex + 1).filterIndexed { i, _ -> (currentIndex + 1 + i) !in unplayable }
        val byId = rest.associateBy { it.track.videoId }
        tracks = head + upcomingVideoIds.mapNotNull(byId::get)
        unplayable.removeAll { it > currentIndex }
        d[1 - main].clear()
        preloadNext()
        publish()
    }

    private fun markUnplayable(
        index: Int,
        reason: String,
    ) {
        unplayable += index
        tracks.getOrNull(index)?.let { emit(DjEngineEvent.TrackSkipped(it.track, reason)) }
        Log.w(TAG, "Skipping track $index: $reason")
    }

    private fun hasPlayableAfter(index: Int): Boolean = ((index + 1) until tracks.size).any { it !in unplayable }

    private fun finish() {
        userWantsPlay = false
        phase = DjEngineState.Phase.FINISHED
        decks?.forEach { it.player.playWhenReady = false }
        publish()
        emit(DjEngineEvent.SessionFinished)
    }

    // ---- deck errors ------------------------------------------------------------------------------------

    private fun handleDeckError(
        deck: Deck,
        error: PlaybackException,
    ) {
        val d = decks ?: return
        val index = deck.trackIndex
        Log.w(TAG, "Deck ${deck.name} failed on track $index", error)
        val running = transition
        when {
            running != null && deck === d[running.from] -> finishTransition()

            running != null && deck === d[running.to] -> {
                transition = null
                deck.clear()
                markUnplayable(index, "playback error: ${error.errorCodeName}")
                applyVolumes()
                preloadNext()
            }

            deck === d[main] -> {
                markUnplayable(index, "playback error: ${error.errorCodeName}")
                deck.clear()
                val other = d[1 - main]
                if (other.track != null) {
                    main = 1 - main
                    currentIndex = other.trackIndex
                    applyPlayback()
                    preloadNext()
                } else if (hasPlayableAfter(index)) {
                    scope.launch {
                        val next = loadNextPlayable(deck = main, after = index)
                        if (next < 0) finish() else currentIndex = next
                        applyPlayback()
                        preloadNext()
                    }
                } else {
                    finish()
                }
            }

            else -> {
                deck.clear()
                markUnplayable(index, "playback error: ${error.errorCodeName}")
                preloadNext()
            }
        }
        publish()
    }

    // ---- playback state, volumes, focus ----------------------------------------------------------------------

    private fun requestFocusAndPlay() {
        when (focus.request()) {
            DjAudioFocus.Result.GRANTED -> Unit
            DjAudioFocus.Result.DELAYED -> Log.i(TAG, "Audio focus delayed (call in progress?); waiting")
            DjAudioFocus.Result.FAILED -> userWantsPlay = false
        }
        applyPlayback()
    }

    private fun shouldPlay(): Boolean = userWantsPlay && !pausedByFocus && focus.held && !ending && !released

    private fun isPlayingNow(): Boolean = shouldPlay() && decks?.get(main)?.player?.isPlaying == true

    private fun isAudible(): Boolean = decks?.any { it.player.isPlaying && it.player.volume > 0f } == true

    private fun applyPlayback() {
        val d = decks
        if (d == null) {
            publish()
            return
        }
        val play = shouldPlay()
        d[main].player.playWhenReady = play && d[main].track != null
        transition?.let { d[it.to].player.playWhenReady = play }
        if (phase != DjEngineState.Phase.FINISHED && !ending) {
            phase =
                when {
                    currentIndex < 0 -> DjEngineState.Phase.STARTING
                    play -> DjEngineState.Phase.PLAYING
                    userWantsPlay && !focus.held -> DjEngineState.Phase.WAITING_FOR_FOCUS
                    else -> DjEngineState.Phase.PAUSED
                }
        }
        applyVolumes()
        publish()
    }

    /** Sets both decks' volumes from the fade, the duck and the master fade. Returns the fade progress. */
    private fun applyVolumes(): Double {
        val d = decks ?: return 1.0
        val level = masterGain * duckGain
        val running = transition
        if (running == null) {
            d[main].player.volume = level
            return 1.0
        }
        val to = d[running.to]
        val progress = EqualPowerFade.progress(to.positionMs, running.plan.incomingEntryMs, running.plan.fadeMs)
        d[running.from].player.volume = EqualPowerFade.outgoingGain(progress) * level
        to.player.volume = EqualPowerFade.incomingGain(progress) * level
        return progress
    }

    private fun releaseAll() {
        if (released) return
        released = true
        tickJob?.cancel()
        loadJob?.cancel()
        decks?.forEach { it.release() }
        decks = null
        transition = null
        focus.abandon()
        phase = DjEngineState.Phase.IDLE
        publish()
        scope.cancel()
    }

    private fun publish() {
        val d = decks
        val idle = phase == DjEngineState.Phase.IDLE
        val current = d?.get(main)?.track?.track ?: tracks.getOrNull(currentIndex)?.track
        val upcoming =
            if (currentIndex < 0 || idle) {
                emptyList()
            } else {
                ((currentIndex + 1) until tracks.size).filter { it !in unplayable }.map { tracks[it].track }
            }
        mutableState.value =
            DjEngineState(
                phase = phase,
                current = if (idle) null else current,
                next = upcoming.firstOrNull(),
                currentIndex = currentIndex,
                trackCount = tracks.size,
                upcoming = upcoming,
                playWhenReady = shouldPlay(),
                transition = transition?.plan,
            )
        session.refresh()
    }

    private fun emit(event: DjEngineEvent) {
        mutableEvents.tryEmit(event)
    }

    private companion object {
        const val TAG = "DjMixEngine"
        const val TICK_MS = 40L
        const val FADE_STEP_MS = 30L
        const val DUCK_GAIN = 0.2f
    }
}
