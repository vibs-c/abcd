package com.aiqulabs.abcd.dj.engine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.aiqulabs.abcd.dj.core.mix.CrossfadeCurve
import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.mix.TransitionPlan
import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique
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
import kotlin.math.abs

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

    /** The planned transition's window on the playing song (start, end), recomputed every tick (stage 12). */
    private var plannedWindow: Pair<Long, Long>? = null
    private var ending = false
    private var released = false
    private var audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE

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

    /** A transition in progress. Times are on the outgoing deck's media clock; [exitMs] is where the blend starts. */
    private class Running(
        val plan: TransitionPlan,
        val from: Int,
        val to: Int,
        val exitMs: Long,
    ) {
        var incomingStarted = false
        var reseeked = false
        var lastSpeedChangeNs = 0L
        var phaseErrorAtStartMs: Double? = null
        var lastPhaseErrorMs = 0.0
    }

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
        this.audioSessionId = audioSessionId
        announceEffectSession(open = true)
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

    override fun progress(): DjProgress? {
        val d = decks ?: return null
        val deck = d[main]
        val track = deck.track ?: return null
        val running = transition
        val window = if (running != null) mixWindow(running.plan, running.exitMs) else plannedWindow
        return DjProgress(
            videoId = track.track.videoId,
            positionMs = deck.positionMs,
            durationMs = deck.durationMs,
            mixStartMs = window?.first,
            mixEndMs = window?.second,
            mixing = running != null,
        )
    }

    /** The audible part of a transition on the outgoing song's clock: echo outs start with the echo build-up. */
    private fun mixWindow(
        plan: TransitionPlan,
        exitMs: Long,
    ): Pair<Long, Long> =
        if (plan.isEchoOut) {
            (exitMs - plan.echoLeadMs) to (exitMs + maxOf(plan.fadeMs, plan.echoDelayMs * ECHO_TAIL_BEATS))
        } else {
            exitMs to (exitMs + plan.fadeMs)
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
            if (running.plan.isBeatmatched) lockPhase(d, running)
            if (applyVolumes()) finishTransition()
            return
        }
        applyVolumes()
        glideToNormalTempo(d[main])
        val m = d[main]
        val other = d[1 - main]
        val current = m.track ?: return
        val mix = mix ?: return
        if (other.isReady) {
            val next = checkNotNull(other.track)
            if (skipRequested) {
                skipRequested = false
                val skip = mix.planner.planSkip(current, next, m.positionMs, mix.settings)
                begin(skip, skip.outgoingExitMs, byUser = true)
                return
            }
            if (!isPlayingNow() && !m.hasEnded) return
            val plan = mix.planner.plan(current, next, mix.settings)
            // The real file can be shorter than the analysis said: keep the whole transition inside it.
            val room = if (plan.isEchoOut) plan.echoDelayMs * ECHO_TAIL_BEATS else plan.fadeMs
            val latestExit = (m.durationMs - room - MixPlanner.END_TAIL_MS).coerceAtLeast(0L)
            val exit = minOf(plan.outgoingExitMs, latestExit)
            val startAt =
                when {
                    plan.isBeatmatched -> exit - plan.preRollMs
                    plan.isEchoOut -> exit - plan.echoLeadMs
                    else -> exit
                }
            plannedWindow = mixWindow(plan, exit)
            if (m.hasEnded) {
                val cut = plan.copy(technique = TransitionTechnique.CLASSIC_FADE, fadeMs = 0L, reason = plan.reason + " (outgoing ended early)")
                begin(cut, m.positionMs)
            } else if (m.positionMs >= startAt) {
                begin(plan, exit)
            }
        } else if (m.hasEnded && other.track == null && loadJob?.isActive != true && !hasPlayableAfter(currentIndex)) {
            emit(DjEngineEvent.TrackFinished(current.track))
            finish()
        }
    }

    private fun begin(
        plan: TransitionPlan,
        exitMs: Long,
        byUser: Boolean = false,
    ) {
        val d = decks ?: return
        val from = d[main]
        val to = d[1 - main]
        val fromTrack = from.track ?: return
        val toTrack = to.track ?: return
        val running = Running(plan, from = main, to = 1 - main, exitMs = exitMs)
        transition = running
        // The deck was cued for the planned transition; a skip (or a changed plan) needs the cue and tempo it asks for.
        if (abs(to.positionMs - plan.incomingCueMs) > CUE_TOLERANCE_MS) to.player.seekTo(plan.incomingCueMs)
        to.setSpeed((plan.incomingSpeed * if (plan.isBeatmatched) from.speed else 1f).toFloat())
        to.player.volume = 0f
        // Echo out starts the incoming song only at the exit; the others start now (silent until the blend opens).
        if (!plan.isEchoOut) {
            to.player.playWhenReady = shouldPlay()
            running.incomingStarted = true
        }
        Log.i(TAG, "Transition ${fromTrack.track.title} -> ${toTrack.track.title}: ${plan.reason}")
        emit(DjEngineEvent.TransitionStarted(fromTrack.track, toTrack.track, plan, byUser))
        applyVolumes()
        publish()
    }

    private fun finishTransition() {
        val d = decks ?: return
        val running = transition ?: return
        transition = null
        plannedWindow = null
        val from = d[running.from]
        val finished = from.track
        if (running.plan.isBeatmatched) {
            val atStart = running.phaseErrorAtStartMs?.toLong()
            Log.i(TAG, "Beat sync: phase error $atStart ms when the blend opened, ${running.lastPhaseErrorMs.toLong()} ms at the end")
        }
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
                    val (cueMs, speed) = cueFor(index)
                    d[deck].load(track, index, uri, cueMs, speed)
                    return index
                }
                markUnplayable(index, "audio file not available")
            }
            index++
        }
        return -1
    }

    /** Where (and at what tempo) to cue [index]: the first song at its entry, later ones as the plan says. */
    private fun cueFor(index: Int): Pair<Long, Float> {
        val m = checkNotNull(mix)
        val previous = tracks.getOrNull(currentIndex) ?: return m.planner.firstEntryMs(tracks[index], m.settings) to 1f
        val plan = m.planner.plan(previous, tracks[index], m.settings)
        return plan.incomingCueMs to plan.incomingSpeed.toFloat()
    }

    private fun applyQueue(upcomingVideoIds: List<String>) {
        val d = decks ?: return
        val head = tracks.take(currentIndex + 1)
        val rest = tracks.drop(currentIndex + 1).filterIndexed { i, _ -> (currentIndex + 1 + i) !in unplayable }
        val byId = rest.associateBy { it.track.videoId }
        plannedWindow = null
        val loadedNext = d[1 - main].track?.track?.videoId
        tracks = head + upcomingVideoIds.mapNotNull(byId::get)
        unplayable.removeAll { it > currentIndex }
        // The song already cued on the free deck stays when it is still next (no reload, no gap).
        if (loadedNext == null || loadedNext != tracks.getOrNull(currentIndex + 1)?.track?.videoId) {
            loadJob?.cancel()
            d[1 - main].clear()
        }
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

    /**
     * Sets both decks' volumes (and the echo) for the current moment, from the transition, the duck and the master
     * fade. Returns true when a running transition is complete.
     */
    private fun applyVolumes(): Boolean {
        val d = decks ?: return false
        val level = masterGain * duckGain
        val running = transition
        if (running == null) {
            d[main].player.volume = level
            return false
        }
        val from = d[running.from]
        val to = d[running.to]
        val plan = running.plan
        val t = if (from.hasEnded) Long.MAX_VALUE / 4 else from.positionMs - running.exitMs
        if (plan.isEchoOut) return applyEchoOut(running, from, to, t, level)
        val progress = if (plan.fadeMs <= 0) 1.0 else (t.toDouble() / plan.fadeMs).coerceIn(0.0, 1.0)
        from.player.volume = CrossfadeCurve.outgoing(progress) * level
        to.player.volume = CrossfadeCurve.incoming(progress) * level
        if (plan.isBeatmatched && plan.fadeMs > 0) {
            // Bass swap (stage 12): one bassline at a time, handed over in the middle of the blend.
            val beat = from.track?.analysis?.let(BEAT_GRID::beatMs) ?: DEFAULT_BEAT_MS
            val width = CrossfadeCurve.BASS_SWAP_BEATS * beat.toDouble() / plan.fadeMs
            from.setBass(CrossfadeCurve.outgoingBass(progress, width))
            to.setBass(CrossfadeCurve.incomingBass(progress, width))
        }
        val done = progress >= 1.0 || to.hasEnded
        if (done) to.setBass(1f)
        return done
    }

    /**
     * Echo out (§9.2): over the last bars the echo send builds up; at the exit the outgoing song's dry signal drops
     * within one beat, its repeats decay, and the incoming song fades in on its downbeat.
     */
    private fun applyEchoOut(
        running: Running,
        from: Deck,
        to: Deck,
        t: Long,
        level: Float,
    ): Boolean {
        val plan = running.plan
        val beat = plan.echoDelayMs.coerceAtLeast(1L)
        from.player.volume = level
        if (t < 0) {
            val lead = plan.echoLeadMs.coerceAtLeast(1L)
            val send = ECHO_SEND * (1f + t.toFloat() / lead).coerceIn(0f, 1f)
            from.setEcho(DeckGainProcessor.EchoParams(beat, send, ECHO_FEEDBACK, dry = 1f, wet = 1f))
            to.player.volume = 0f
            return false
        }
        if (!running.incomingStarted) {
            to.player.playWhenReady = shouldPlay()
            running.incomingStarted = true
        }
        // The dry signal eases out over one beat (smooth ends: no click, no sudden hole).
        val dry = CrossfadeCurve.outgoing(t.toDouble() / beat)
        val send = if (t < beat) ECHO_SEND * dry else 0f
        from.setEcho(DeckGainProcessor.EchoParams(beat, send, ECHO_TAIL_FEEDBACK, dry = dry, wet = 1f))
        val fadeIn = if (plan.fadeMs <= 0) 1.0 else (t.toDouble() / plan.fadeMs).coerceIn(0.0, 1.0)
        to.player.volume = CrossfadeCurve.incoming(fadeIn) * level
        return (t >= beat * ECHO_TAIL_BEATS && fadeIn >= 1.0) || to.hasEnded
    }

    /**
     * Beat sync (§9.1): keeps the incoming deck where it should be relative to the outgoing one, by tiny speed nudges
     * around the matched tempo (position error in ms → up to ±3% speed). Both positions come from each player's
     * audio-output clock. A large error while the incoming deck is still silent is fixed by one re-seek.
     */
    private fun lockPhase(
        d: Array<Deck>,
        running: Running,
    ) {
        val from = d[running.from]
        val to = d[running.to]
        if (!from.player.isPlaying || !to.player.isPlaying) return
        val plan = running.plan
        val t = from.positionMs - running.exitMs
        val target = plan.incomingEntryMs + t * plan.incomingSpeed
        val error = to.positionMs - target
        running.lastPhaseErrorMs = error
        if (t >= 0 && running.phaseErrorAtStartMs == null) running.phaseErrorAtStartMs = error
        if (t < 0 && abs(error) > RESEEK_ERROR_MS && !running.reseeked) {
            running.reseeked = true
            to.player.seekTo((target + SEEK_LATENCY_MS).toLong().coerceAtLeast(0L))
            return
        }
        val now = System.nanoTime()
        if (now - running.lastSpeedChangeNs < SPEED_CHANGE_INTERVAL_NS) return
        val base = plan.incomingSpeed * from.speed
        // While still silent, correct hard (up to 8%); once audible, only gentle nudges (3%) nobody hears.
        val limit = if (t < 0) MAX_SILENT_NUDGE else MAX_NUDGE
        val nudge = (-error / PHASE_GAIN_MS).coerceIn(-limit, limit)
        val wanted = (base * (1.0 + nudge)).toFloat()
        if (abs(wanted - to.speed) > MIN_SPEED_STEP) {
            to.setSpeed(wanted)
            running.lastSpeedChangeNs = now
        }
    }

    /** After a beatmatched blend the new song returns to its own tempo slowly enough not to be noticed. */
    private fun glideToNormalTempo(deck: Deck) {
        val speed = deck.speed
        if (speed == 1f || !deck.player.isPlaying) return
        deck.setSpeed(if (abs(speed - 1f) <= GLIDE_STEP) 1f else if (speed > 1f) speed - GLIDE_STEP else speed + GLIDE_STEP)
    }

    private fun releaseAll() {
        if (released) return
        released = true
        tickJob?.cancel()
        loadJob?.cancel()
        decks?.forEach { it.release() }
        decks = null
        transition = null
        announceEffectSession(open = false)
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

    /**
     * Tells system equaliser apps about the decks' shared audio session (design §4.2), so a user's EQ applies to
     * the mix as it does to normal playback. Close is sent once when the session ends.
     */
    private fun announceEffectSession(open: Boolean) {
        if (audioSessionId == AudioManager.AUDIO_SESSION_ID_GENERATE) return
        val action = if (open) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        runCatching {
            appContext.sendBroadcast(
                Intent(action)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, appContext.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
            )
        }
        if (!open) audioSessionId = AudioManager.AUDIO_SESSION_ID_GENERATE
    }

    private fun emit(event: DjEngineEvent) {
        mutableEvents.tryEmit(event)
    }

    private companion object {
        const val TAG = "DjMixEngine"
        const val TICK_MS = 40L
        const val DEFAULT_BEAT_MS = 500L
        val BEAT_GRID = MixPlanner()
        const val FADE_STEP_MS = 30L
        const val DUCK_GAIN = 0.2f
        const val CUE_TOLERANCE_MS = 80L

        // Echo out
        const val ECHO_SEND = 0.6f
        const val ECHO_FEEDBACK = 0.45f
        const val ECHO_TAIL_FEEDBACK = 0.55f
        const val ECHO_TAIL_BEATS = 6

        // Beat sync
        const val PHASE_GAIN_MS = 600.0
        const val MAX_NUDGE = 0.03
        const val MAX_SILENT_NUDGE = 0.08
        const val MIN_SPEED_STEP = 0.0015f
        const val SPEED_CHANGE_INTERVAL_NS = 80_000_000L
        const val RESEEK_ERROR_MS = 150.0
        const val SEEK_LATENCY_MS = 40.0
        const val GLIDE_STEP = 0.00015f
    }
}
