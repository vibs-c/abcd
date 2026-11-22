package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.mix.TransitionPlanner
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.engine.DjEngineState
import com.aiqulabs.abcd.dj.ui.live.DjTimelineUi
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * App-wide handle on DJ Mode for the UI (DJ tab, mini bar) and [DjController]. `MusicService` registers
 * its [DjPlaybackHost] here while it runs. [state] is null whenever DJ Mode is off.
 */
object DjPlayback {
    private val mutableState = MutableStateFlow<DjEngineState?>(null)
    val state: StateFlow<DjEngineState?> = mutableState.asStateFlow()

    private val mutableLastTransition = MutableStateFlow<DjRateableTransition?>(null)

    /** The latest transition of the running mix, for the thumbs on the live screen. */
    val lastTransition: StateFlow<DjRateableTransition?> = mutableLastTransition.asStateFlow()

    @Volatile internal var host: DjPlaybackHost? = null

    val isActive: Boolean get() = host?.isActive == true

    /** Hands a prepared, ordered mix to the engine. False if the music service is not running or refuses. */
    internal fun start(
        tracks: List<PreparedTrack>,
        songs: Map<String, SongItem>,
        settings: DjSessionSettings,
        planner: TransitionPlanner = MixPlanner(),
    ): Boolean {
        val current = host
        if (current == null) Timber.tag("DjPlayback").w("Music service not running; cannot start DJ Mode")
        return current?.enter(tracks, songs, settings, planner) ?: false
    }

    /** The live timeline (stage 12): position, mix window and the song's waveform and beats. Main thread. */
    fun timeline(): DjTimelineUi? {
        val current = host ?: return null
        val progress = current.progress() ?: return null
        val analysis = current.analysisOf(progress.videoId)
        val next = state.value?.next?.title
        return DjTimelineUi(
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            mixStartMs = progress.mixStartMs,
            mixEndMs = progress.mixEndMs,
            mixing = progress.mixing,
            mixProgress = progress.mixProgress,
            waveform = analysis?.waveformPeaks.orEmpty(),
            beatsMs = analysis?.beatsMs.orEmpty(),
            beatsPerBar = analysis?.beatsPerBar ?: 4,
            choruses = analysis?.chorusCandidates.orEmpty().map { it.startMs to it.endMs },
            bpm = analysis?.bpm ?: 0.0,
            camelot = analysis?.key?.camelot.orEmpty(),
            nextTitle = next,
        )
    }

    fun togglePlayPause() {
        if (state.value?.playWhenReady == true) host?.pause() else host?.play()
    }

    fun skipNext() {
        host?.skipNext()
    }

    fun rateLastTransition(up: Boolean) {
        host?.rateLastTransition(up)
    }

    fun removeUpcoming(
        videoId: String,
        neverPlay: Boolean = false,
    ) {
        host?.removeUpcoming(videoId, neverPlay)
    }

    fun moveUpcoming(
        videoId: String,
        by: Int,
    ) {
        host?.moveUpcoming(videoId, by)
    }

    /** "End session". */
    fun end() {
        host?.exit()
    }

    internal fun publish(state: DjEngineState?) {
        mutableState.value = state
    }

    internal fun publishLastTransition(transition: DjRateableTransition?) {
        mutableLastTransition.value = transition
    }
}
