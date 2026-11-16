package com.aiqulabs.abcd.dj.core.mix

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack

/** The transition techniques of brief §9.2. v2 values exist as hooks and are never chosen in v1. */
enum class TransitionTechnique {
    CLASSIC_FADE,
    BEATMATCHED_BLEND,
    ECHO_OUT,
    CHORUS_JUMP,

    /** v2. */
    BASS_SWAP,

    /** v2. */
    FILTER_SWEEP,

    /** v2. */
    DROP_CUT,
}

/**
 * How one track hands over to the next, in each track's own media time.
 *
 * The outgoing track starts fading at [outgoingExitMs]; the incoming track starts playing at
 * [incomingEntryMs] at the same moment; the fade lasts [fadeMs]. [reason] is logged (and shown on the v2
 * details page) so every choice can be checked.
 */
data class TransitionPlan(
    val technique: TransitionTechnique,
    val outgoingExitMs: Long,
    val incomingEntryMs: Long,
    val fadeMs: Long,
    val reason: String,
)

/** Chooses how to get from one prepared track to the next (brief §9.2, §9.3). Pure logic, no Android. */
interface TransitionPlanner {
    /** Where the first track of a session starts playing. */
    fun firstEntryMs(
        first: PreparedTrack,
        settings: DjSessionSettings,
    ): Long

    fun plan(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        settings: DjSessionSettings,
    ): TransitionPlan

    /** A short transition for "next" pressed by the user, starting at [outgoingPositionMs]. */
    fun planSkip(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        outgoingPositionMs: Long,
        settings: DjSessionSettings,
    ): TransitionPlan
}
