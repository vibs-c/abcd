package com.aiqulabs.abcd.dj.core.mix

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlin.math.roundToLong

/**
 * Stage 8 planner: every transition is an equal-power classic fade (brief §9.2, the v1 fallback).
 *
 * Exit point: Smooth starts its longer fade where the analysis says the outro becomes mixable, if that
 * lies in the second half of the song and leaves room for the fade; otherwise every style fades out over
 * the last [fadeMsFor] milliseconds before the end, minus a short tail so the fade finishes on music,
 * not on trailing silence. Beatmatching, echo out, chorus jumps and the per-style song lengths come in
 * stage 9 as further planners behind the same interface.
 */
class ClassicFadePlanner : TransitionPlanner {
    override fun firstEntryMs(
        first: PreparedTrack,
        settings: DjSessionSettings,
    ): Long = 0L

    override fun plan(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        settings: DjSessionSettings,
    ): TransitionPlan {
        val durationMs = outgoing.analysis.durationMs.roundToLong()
        val fadeMs = fadeMsFor(settings.mixStyle).coerceAtMost((durationMs / 3).coerceAtLeast(MIN_FADE_MS))
        val endExit = (durationMs - fadeMs - END_TAIL_MS).coerceAtLeast(0L)
        val outro = outgoing.analysis.outroMixableStartMs.roundToLong()
        val useOutro = settings.mixStyle == MixStyle.SMOOTH && outro > durationMs / 2 && outro < endExit
        val exitMs = if (useOutro) outro else endExit
        val why = if (useOutro) "outro starts at ${exitMs / 1000} s" else "end of song"
        return TransitionPlan(
            technique = TransitionTechnique.CLASSIC_FADE,
            outgoingExitMs = exitMs,
            incomingEntryMs = 0L,
            fadeMs = fadeMs,
            reason = "classic fade (${settings.mixStyle.name.lowercase()}, ${fadeMs / 1000} s): $why",
        )
    }

    override fun planSkip(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        outgoingPositionMs: Long,
        settings: DjSessionSettings,
    ): TransitionPlan =
        TransitionPlan(
            technique = TransitionTechnique.CLASSIC_FADE,
            outgoingExitMs = outgoingPositionMs.coerceAtLeast(0L),
            incomingEntryMs = 0L,
            fadeMs = SKIP_FADE_MS,
            reason = "skipped by the user",
        )

    companion object {
        const val SKIP_FADE_MS = 1_500L
        const val MIN_FADE_MS = 2_000L

        /** Fade ends this long before the file ends: most songs close on a few seconds of silence. */
        const val END_TAIL_MS = 1_500L

        fun fadeMsFor(style: MixStyle): Long =
            when (style) {
                MixStyle.SMOOTH -> 8_000L
                MixStyle.RADIO -> 4_000L
                else -> 5_000L
            }
    }
}
