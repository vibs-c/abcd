package com.aiqulabs.abcd.dj.core.mix

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlin.math.roundToLong

/**
 * The Lab's "audition a transition" (brief §4.5): plays [next] after the first song with the technique picked by
 * hand ([forced], or the automatic choice when null), starting the first song [leadInMs] before the blend so the
 * transition is heard straight away.
 */
class AuditionPlanner(
    private val next: PreparedTrack,
    private val forced: TransitionTechnique?,
    private val leadInMs: Long = 12_000L,
    private val base: MixPlanner = MixPlanner(),
) : TransitionPlanner {
    override fun firstEntryMs(
        first: PreparedTrack,
        settings: DjSessionSettings,
    ): Long {
        val plan = plan(first, next, settings)
        val warmUp = maxOf(plan.preRollMs, plan.echoLeadMs)
        return (plan.outgoingExitMs - warmUp - leadInMs).coerceAtLeast(0L)
    }

    override fun plan(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        settings: DjSessionSettings,
    ): TransitionPlan {
        val auto = base.plan(outgoing, incoming, settings)
        val out = outgoing.analysis
        return when (forced) {
            null, auto.technique -> auto

            TransitionTechnique.ECHO_OUT -> {
                val beat = base.beatMs(out)
                auto.copy(
                    technique = TransitionTechnique.ECHO_OUT,
                    incomingSpeed = 1.0,
                    preRollMs = 0L,
                    echoDelayMs = beat,
                    echoLeadMs = base.barMs(out) * MixPlanner.ECHO_LEAD_BARS,
                    fadeMs = (beat * 2).coerceIn(MixPlanner.ECHO_FADE_IN_MIN_MS, MixPlanner.ECHO_FADE_IN_MAX_MS),
                    reason = "audition, echo out by hand; ${auto.reason}",
                )
            }

            TransitionTechnique.BEATMATCHED_BLEND -> {
                // By hand even when the tempos are far apart: the nearest tempo ratio, capped at ±8%.
                val speed =
                    base.tempoMatch(out, incoming.analysis)
                        ?: (out.bpm / incoming.analysis.bpm.coerceAtLeast(1.0)).coerceIn(1.0 - MAX_FORCED_CHANGE, 1.0 + MAX_FORCED_CHANGE)
                val entry = auto.incomingEntryMs
                auto.copy(
                    technique = TransitionTechnique.BEATMATCHED_BLEND,
                    incomingSpeed = speed,
                    preRollMs = minOf(base.barMs(out), (entry / speed).roundToLong()),
                    echoDelayMs = 0L,
                    echoLeadMs = 0L,
                    fadeMs = (base.barMs(out) * 8).coerceIn(MixPlanner.MIN_BLEND_MS, MixPlanner.MAX_BLEND_MS),
                    reason = "audition, beatmatched by hand at x$speed; ${auto.reason}",
                )
            }

            else -> {
                auto.copy(
                    technique = TransitionTechnique.CLASSIC_FADE,
                    incomingSpeed = 1.0,
                    preRollMs = 0L,
                    echoDelayMs = 0L,
                    echoLeadMs = 0L,
                    fadeMs = CLASSIC_AUDITION_FADE_MS,
                    reason = "audition, classic fade by hand; ${auto.reason}",
                )
            }
        }
    }

    override fun planSkip(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        outgoingPositionMs: Long,
        settings: DjSessionSettings,
    ): TransitionPlan = base.planSkip(outgoing, incoming, outgoingPositionMs, settings)

    private companion object {
        const val MAX_FORCED_CHANGE = 0.08
        const val CLASSIC_AUDITION_FADE_MS = 6_000L
    }
}
