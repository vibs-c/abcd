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
 * The blend starts when the outgoing track reaches [outgoingExitMs]; at that moment the incoming track is at
 * [incomingEntryMs]; the blend lasts [fadeMs] (outgoing media time). For a beatmatched blend the incoming
 * track plays at [incomingSpeed] (tempo matched, pitch kept) and is started [preRollMs] early, silent, so its
 * beats can be pulled into line before it becomes audible. [reason] is logged (and shown on the v2 details
 * page) so every choice can be checked.
 *
 * Echo out: from [echoLeadMs] before the exit an echo of [echoDelayMs] (one beat) builds on the outgoing song;
 * at the exit its dry signal drops and only the echo tail decays while the incoming song fades in over
 * [fadeMs] on its downbeat.
 */
data class TransitionPlan(
    val technique: TransitionTechnique,
    val outgoingExitMs: Long,
    val incomingEntryMs: Long,
    val fadeMs: Long,
    val reason: String,
    val incomingSpeed: Double = 1.0,
    val preRollMs: Long = 0L,
    val echoDelayMs: Long = 0L,
    val echoLeadMs: Long = 0L,
) {
    val isBeatmatched: Boolean get() = technique == TransitionTechnique.BEATMATCHED_BLEND

    val isEchoOut: Boolean get() = technique == TransitionTechnique.ECHO_OUT && echoDelayMs > 0

    /** Where the incoming deck is cued: [preRollMs] of its own (tempo-scaled) time before the entry point. */
    val incomingCueMs: Long get() = (incomingEntryMs - (preRollMs * incomingSpeed).toLong()).coerceAtLeast(0L)
}

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
