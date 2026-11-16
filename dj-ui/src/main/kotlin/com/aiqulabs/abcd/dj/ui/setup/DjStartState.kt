package com.aiqulabs.abcd.dj.ui.setup

import com.aiqulabs.abcd.dj.core.prep.PreparationProgress

/** What happens after the user taps "Start DJ", as shown on the setup screen. */
sealed interface DjStartState {
    /** Nothing in progress. */
    data object Idle : DjStartState

    /** Searching YouTube Music and building the playlist. */
    data object Building : DjStartState

    /** Analysing the tracks of the mix before playback ("Preparing your mix", brief §5.1). */
    data class Preparing(
        val progress: PreparationProgress,
    ) : DjStartState

    /** The mix was built and handed to the player. */
    data class Started(
        val trackCount: Int,
    ) : DjStartState

    /** The mix could not be built. */
    data class Failed(
        val reason: Reason,
        val foundCount: Int = 0,
    ) : DjStartState

    enum class Reason {
        /** Fewer playable songs were found than a mix needs. */
        NOT_ENOUGH_SONGS,

        /** A request failed, for example because the device is offline. */
        ERROR,

        /** The phone does not have room to download the songs for analysis. */
        NOT_ENOUGH_STORAGE,
    }
}
