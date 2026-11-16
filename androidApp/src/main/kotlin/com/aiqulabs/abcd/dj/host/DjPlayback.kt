package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.engine.DjEngineState
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

    @Volatile internal var host: DjPlaybackHost? = null

    val isActive: Boolean get() = host?.isActive == true

    /** Hands a prepared, ordered mix to the engine. False if the music service is not running or refuses. */
    internal fun start(
        tracks: List<PreparedTrack>,
        songs: Map<String, SongItem>,
        settings: DjSessionSettings,
    ): Boolean {
        val current = host
        if (current == null) Timber.tag("DjPlayback").w("Music service not running; cannot start DJ Mode")
        return current?.enter(tracks, songs, settings) ?: false
    }

    fun togglePlayPause() {
        if (state.value?.playWhenReady == true) host?.pause() else host?.play()
    }

    fun skipNext() {
        host?.skipNext()
    }

    /** "End session". */
    fun end() {
        host?.exit()
    }

    internal fun publish(state: DjEngineState?) {
        mutableState.value = state
    }
}
