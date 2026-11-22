package com.aiqulabs.abcd.dj.core.mix

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.ChorusCandidate
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Plans each hand-over from the analysis (brief §9.2, §9.3).
 *
 * **Where to leave the outgoing song** (style rules, §9.3), always on a bar line and while the song is still
 * full, not in its fade-out:
 * - Chorus Hits: right after the main chorus (or the chorus that follows it, if the song would still be
 *   short), so each song lasts roughly 45-90 s.
 * - Smooth: after the last chorus, else where the mixable outro starts.
 * - Radio: where the mixable outro starts (most of the song plays).
 *
 * **Where to enter the incoming song:** Chorus Hits jumps in 8 bars before the main chorus (§9.2 "chorus
 * jump"); the other styles start on the first downbeat, skipping leading silence.
 *
 * **How (technique selector, §9.2):** when both beat grids are trusted and the tempos are within 6% (allowing
 * half/double time), a beatmatched blend: the incoming song is tempo-matched, cued two beats early and
 * phase-locked by the engine, and the blend runs over 8 bars (16 in Smooth; half that when the keys clash).
 * Otherwise, when the outgoing song has a bar grid, echo out: its last two bars echo away on the beat and the
 * incoming song lands on its downbeat (no two tempos ever overlap). Classic fade only without a grid. Fades use
 * [CrossfadeCurve], which has no mid-fade dip. The reason for each choice is in [TransitionPlan.reason].
 */
class MixPlanner(
    private val minTrustedBpmConfidence: Double = MIN_TRUSTED_BPM_CONFIDENCE,
    /** Thumbs up minus down per technique (brief 6.3): where two techniques would both work, the liked one wins. */
    private val techniqueScores: Map<TransitionTechnique, Int> = emptyMap(),
) : TransitionPlanner {
    override fun firstEntryMs(
        first: PreparedTrack,
        settings: DjSessionSettings,
    ): Long = entryMs(first.analysis, settings.mixStyle)

    override fun plan(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        settings: DjSessionSettings,
    ): TransitionPlan {
        val out = outgoing.analysis
        val inc = incoming.analysis
        val style = settings.mixStyle
        val speed = tempoMatch(out, inc)
        val keyClash = keysClash(out, inc)
        val technique =
            when {
                // A key clash is the one case where both a short blend and an echo out are fine: feedback decides.
                speed != null && keyClash && prefers(TransitionTechnique.ECHO_OUT, over = TransitionTechnique.BEATMATCHED_BLEND) ->
                    TransitionTechnique.ECHO_OUT
                speed != null -> TransitionTechnique.BEATMATCHED_BLEND
                out.barStartsMs.size >= MIN_BARS -> TransitionTechnique.ECHO_OUT
                else -> TransitionTechnique.CLASSIC_FADE
            }
        val fadeMs = fadeMs(out, style, technique, keyClash)
        val entry = entryMs(inc, style)
        val (target, why) = exitTarget(out, style)
        val earliest = entryMs(out, style) + MIN_PLAY_MS
        // Echo out needs room after the exit for its tail; a blend needs room for the whole blend.
        val room = if (technique == TransitionTechnique.ECHO_OUT) beatMs(out) * ECHO_TAIL_BEATS else fadeMs
        val latest = latestExit(out, room)
        val exit = snapToBar(out, target).coerceAtMost(latest).coerceAtLeast(earliest.coerceAtMost(latest))
        val chorusJump = style == MixStyle.CHORUS_HITS && mainChorus(inc) != null
        val how =
            when (technique) {
                TransitionTechnique.BEATMATCHED_BLEND -> {
                    "beatmatched blend at x${fmt(checkNotNull(speed), 3)} over ${fmt(fadeMs / 1000.0, 1)} s" +
                        if (keyClash) " (shortened: keys clash)" else ""
                }

                TransitionTechnique.ECHO_OUT -> {
                    "echo out (${whyNoBeatmatch(out, inc)})"
                }

                else -> {
                    "classic fade over ${fmt(fadeMs / 1000.0, 1)} s (no bar grid)"
                }
            } + if (chorusJump) ", chorus jump" else ""
        return TransitionPlan(
            technique = technique,
            outgoingExitMs = exit,
            incomingEntryMs = entry,
            fadeMs = fadeMs,
            reason = "${style.name.lowercase()}: $how; leave $why at ${exit / 1000} s, enter at ${entry / 1000} s",
            incomingSpeed = speed ?: 1.0,
            // One bar of silent lead-in gives the phase lock time to line the beats up before they are heard.
            preRollMs = if (speed != null) minOf(barMs(out), (entry / speed).roundToLong()) else 0L,
            echoDelayMs = if (technique == TransitionTechnique.ECHO_OUT) beatMs(out) else 0L,
            echoLeadMs = if (technique == TransitionTechnique.ECHO_OUT) barMs(out) * ECHO_LEAD_BARS else 0L,
        )
    }

    private fun prefers(
        technique: TransitionTechnique,
        over: TransitionTechnique,
    ): Boolean = (techniqueScores[over] ?: 0) <= -DISLIKED && (techniqueScores[technique] ?: 0) >= 0

    /** True when the two keys are trusted and far apart on the Camelot wheel (two harmonies would grate). */
    fun keysClash(
        out: AnalysisResult,
        inc: AnalysisResult,
    ): Boolean {
        if (out.key.confidence < KEY_TRUST || inc.key.confidence < KEY_TRUST) return false
        val a = camelot(out.key.camelot) ?: return false
        val b = camelot(inc.key.camelot) ?: return false
        val steps = minOf((a.first - b.first + 12) % 12, (b.first - a.first + 12) % 12)
        return steps >= 3 || (steps == 2 && a.second != b.second)
    }

    private fun camelot(code: String): Pair<Int, Char>? {
        if (code.length < 2) return null
        val number = code.dropLast(1).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        return number to code.last().uppercaseChar()
    }

    /** One beat of [a] in ms. */
    fun beatMs(a: AnalysisResult): Long = (barMs(a) / a.beatsPerBar.coerceAtLeast(1)).coerceAtLeast(1L)

    override fun planSkip(
        outgoing: PreparedTrack,
        incoming: PreparedTrack,
        outgoingPositionMs: Long,
        settings: DjSessionSettings,
    ): TransitionPlan =
        TransitionPlan(
            technique = TransitionTechnique.CLASSIC_FADE,
            outgoingExitMs = outgoingPositionMs.coerceAtLeast(0L),
            incomingEntryMs = entryMs(incoming.analysis, settings.mixStyle),
            fadeMs = SKIP_FADE_MS,
            reason = "skipped by the user",
        )

    /** The incoming speed that matches tempos, or null when the songs should not be beatmatched. */
    fun tempoMatch(
        out: AnalysisResult,
        inc: AnalysisResult,
    ): Double? {
        if (out.bpmConfidence < minTrustedBpmConfidence || inc.bpmConfidence < minTrustedBpmConfidence) return null
        if (out.bpm <= 0.0 || inc.bpm <= 0.0 || out.barStartsMs.size < MIN_BARS || inc.barStartsMs.size < MIN_BARS) return null
        val speed = listOf(1.0, 2.0, 0.5).map { k -> out.bpm / (inc.bpm * k) }.minBy { abs(it - 1.0) }
        return speed.takeIf { abs(it - 1.0) <= MAX_TEMPO_CHANGE }
    }

    private fun whyNoBeatmatch(
        out: AnalysisResult,
        inc: AnalysisResult,
    ): String =
        when {
            out.bpmConfidence < minTrustedBpmConfidence || inc.bpmConfidence < minTrustedBpmConfidence -> "beat grid not trusted"
            out.barStartsMs.size < MIN_BARS || inc.barStartsMs.size < MIN_BARS -> "no bar grid"
            else -> "tempos ${fmt(out.bpm, 0)} and ${fmt(inc.bpm, 0)} BPM too far apart"
        }

    /** Where the song starts playing. */
    fun entryMs(
        a: AnalysisResult,
        style: MixStyle,
    ): Long {
        val bars = a.barStartsMs
        val firstDownbeat = bars.firstOrNull()?.takeIf { it < MAX_SILENT_LEAD_MS } ?: 0.0
        if (style != MixStyle.CHORUS_HITS) return firstDownbeat.roundToLong()
        val chorus = mainChorus(a) ?: return firstDownbeat.roundToLong()
        val barIndex = bars.indexOfFirst { it >= chorus.startMs - 1.0 }
        if (barIndex < 0) return firstDownbeat.roundToLong()
        return bars[(barIndex - CHORUS_JUMP_BARS).coerceAtLeast(0)].roundToLong()
    }

    /** Where the blend should start, before snapping to a bar, and why (for the log). */
    private fun exitTarget(
        a: AnalysisResult,
        style: MixStyle,
    ): Pair<Double, String> {
        val duration = a.durationMs
        val outro = a.outroMixableStartMs.takeIf { it > duration * 0.5 && it < duration - 5_000 }
        return when (style) {
            MixStyle.CHORUS_HITS -> {
                val main = mainChorus(a) ?: return duration * 0.55 to "the middle (no chorus found)"
                val entry = entryMs(a, style)
                // A second chorus straight after the main one is played too while the song is still short.
                val follower =
                    a.chorusCandidates
                        .filter { it.startMs >= main.endMs - 1.0 && it.startMs - main.endMs < barMs(a) * 2 }
                        .minByOrNull { it.startMs }
                if (follower != null && follower.endMs - entry <= CHORUS_HITS_MAX_MS) {
                    follower.endMs to "after the second chorus"
                } else {
                    main.endMs to "after the main chorus"
                }
            }

            MixStyle.SMOOTH -> {
                val lastChorusEnd = a.chorusCandidates.map { it.endMs }.filter { it >= duration * 0.5 }.maxOrNull()
                when {
                    lastChorusEnd != null -> lastChorusEnd to "after the last chorus"
                    outro != null -> outro to "at the outro"
                    else -> duration * 0.85 to "near the end"
                }
            }

            else -> {
                if (outro != null) outro to "at the outro" else duration to "at the end"
            }
        }
    }

    /**
     * Blend length (beatmatched: 8 bars, 16 in Smooth, halved when keys clash), the incoming fade-in for echo out
     * (two beats: it lands cleanly on its downbeat while the echo tail decays), or a short classic fade.
     */
    private fun fadeMs(
        a: AnalysisResult,
        style: MixStyle,
        technique: TransitionTechnique,
        keyClash: Boolean,
    ): Long =
        when (technique) {
            TransitionTechnique.BEATMATCHED_BLEND -> {
                val bars = (if (style == MixStyle.SMOOTH) 16 else 8) / (if (keyClash) 2 else 1)
                (barMs(a) * bars).coerceIn(MIN_BLEND_MS / (if (keyClash) 2 else 1), MAX_BLEND_MS)
            }

            TransitionTechnique.ECHO_OUT -> {
                (beatMs(a) * 2).coerceIn(ECHO_FADE_IN_MIN_MS, ECHO_FADE_IN_MAX_MS)
            }

            else -> {
                when (style) {
                    MixStyle.SMOOTH -> 6_000L
                    MixStyle.RADIO -> 4_000L
                    else -> 3_500L
                }
            }
        }

    private fun latestExit(
        a: AnalysisResult,
        fadeMs: Long,
    ): Long = (a.durationMs.roundToLong() - fadeMs - END_TAIL_MS).coerceAtLeast(0L)

    /** The bar line nearest to [ms], or [ms] itself when there is no bar grid. */
    fun snapToBar(
        a: AnalysisResult,
        ms: Double,
    ): Long {
        val bars = a.barStartsMs
        if (bars.isEmpty()) return ms.roundToLong()
        return bars.minBy { abs(it - ms) }.roundToLong()
    }

    /** Length of one bar in ms: from the grid, else from the tempo, else 2 s. */
    fun barMs(a: AnalysisResult): Long {
        val bars = a.barStartsMs
        if (bars.size >= 2) {
            val gaps = bars.zipWithNext { x, y -> y - x }.sorted()
            return gaps[gaps.size / 2].roundToLong().coerceAtLeast(1L)
        }
        return if (a.bpm > 0) (60_000.0 * a.beatsPerBar / a.bpm).roundToLong() else 2_000L
    }

    /** [value] with [decimals] digits after the point (common code has no String.format). */
    private fun fmt(
        value: Double,
        decimals: Int,
    ): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale
        val frac = (scaled % scale).toString().padStart(decimals, '0')
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return if (decimals == 0) "$sign$whole" else "$sign$whole.$frac"
    }

    private fun mainChorus(a: AnalysisResult): ChorusCandidate? = a.mainChorus ?: a.chorusCandidates.maxByOrNull { it.score }

    companion object {
        const val MIN_TRUSTED_BPM_CONFIDENCE = 0.25
        const val MAX_TEMPO_CHANGE = 0.06
        const val MIN_BARS = 8
        const val CHORUS_JUMP_BARS = 8
        const val CHORUS_HITS_MAX_MS = 95_000L
        const val MIN_PLAY_MS = 25_000L
        const val MIN_BLEND_MS = 6_000L
        const val MAX_BLEND_MS = 32_000L
        const val SKIP_FADE_MS = 2_000L
        const val END_TAIL_MS = 1_500L
        const val ECHO_LEAD_BARS = 2
        const val ECHO_TAIL_BEATS = 6
        const val ECHO_FADE_IN_MIN_MS = 600L
        const val ECHO_FADE_IN_MAX_MS = 1_500L
        const val KEY_TRUST = 0.5
        const val DISLIKED = 2

        /** A first downbeat later than this is a beat-tracking oddity; then the song starts at 0. */
        const val MAX_SILENT_LEAD_MS = 8_000.0
    }
}
