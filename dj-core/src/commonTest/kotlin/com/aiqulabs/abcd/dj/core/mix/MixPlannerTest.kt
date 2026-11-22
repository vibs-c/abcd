package com.aiqulabs.abcd.dj.core.mix

import com.aiqulabs.abcd.dj.core.analysis.ChorusCandidate
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.KeyResult
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.core.prep.fakeAnalysis
import com.aiqulabs.abcd.dj.core.prep.fakeTrack
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MixPlannerTest {
    private val planner = MixPlanner()

    /**
     * A 200 s song at [bpm] with a bar grid from [firstBarMs], a chorus at 60-90 s (main) and 140-170 s, and the
     * mixable outro at 185 s.
     */
    private fun song(
        id: String,
        bpm: Double = 120.0,
        confidence: Double = 0.8,
        firstBarMs: Double = 500.0,
        camelot: String = "8A",
        withGrid: Boolean = true,
    ): PreparedTrack {
        val barMs = 240_000.0 / bpm
        val bars = if (withGrid) generateSequence(firstBarMs) { it + barMs }.takeWhile { it < 200_000.0 }.toList() else emptyList()
        fun bar(ms: Double) = (ms / barMs).toInt()
        val analysis =
            fakeAnalysis(bpm = bpm, camelot = camelot, bpmConfidence = confidence).copy(
                durationMs = 200_000.0,
                barStartsMs = bars,
                outroMixableStartMs = 185_000.0,
                chorusCandidates =
                    listOf(
                        ChorusCandidate(60_000.0, 90_000.0, bar(60_000.0), bar(90_000.0), 0.9, isMain = true),
                        ChorusCandidate(140_000.0, 170_000.0, bar(140_000.0), bar(170_000.0), 0.8),
                    ),
                key = KeyResult("A", KeyMode.MINOR, camelot, 9, 0.9),
            )
        return PreparedTrack(fakeTrack(id), analysis)
    }

    private fun settings(style: MixStyle) = DjSessionSettings(mixStyle = style)

    private fun nearBar(
        track: PreparedTrack,
        ms: Long,
    ) = track.analysis.barStartsMs.any { abs(it - ms) < 1.0 }

    @Test
    fun chorusHitsLeavesAfterTheMainChorusAndJumpsInBeforeTheNextOne() {
        val a = song("a")
        val b = song("b")
        val plan = planner.plan(a, b, settings(MixStyle.CHORUS_HITS))
        assertTrue(abs(plan.outgoingExitMs - 90_000) <= 2_000, "exit after the main chorus: ${plan.outgoingExitMs}")
        assertTrue(nearBar(a, plan.outgoingExitMs), "exit on a bar line")
        // 8 bars (16 s at 120 BPM) before the main chorus at 60 s.
        assertTrue(abs(plan.incomingEntryMs - 44_500) <= 2_000, "chorus jump entry: ${plan.incomingEntryMs}")
        assertTrue("chorus jump" in plan.reason)
    }

    @Test
    fun eachStyleLeavesAtADifferentPoint() {
        val a = song("a")
        val b = song("b")
        val chorusHits = planner.plan(a, b, settings(MixStyle.CHORUS_HITS)).outgoingExitMs
        val smooth = planner.plan(a, b, settings(MixStyle.SMOOTH)).outgoingExitMs
        val radio = planner.plan(a, b, settings(MixStyle.RADIO)).outgoingExitMs
        assertTrue(chorusHits < smooth && smooth < radio, "chorus hits $chorusHits < smooth $smooth < radio $radio")
        // After the last chorus (170 s), pulled earlier just enough for its 32 s blend to end before the song does.
        assertTrue(smooth in 160_000L..170_500L, "smooth leaves around the last chorus: $smooth")
        assertTrue(radio >= 180_000, "radio plays most of the song: $radio")
        // Never in the song's own fade-out: the whole blend ends before the last 1.5 s.
        val radioPlan = planner.plan(a, b, settings(MixStyle.RADIO))
        assertTrue(radioPlan.outgoingExitMs + radioPlan.fadeMs <= 200_000 - MixPlanner.END_TAIL_MS)
    }

    @Test
    fun otherStylesEnterOnTheFirstDownbeat() {
        val b = song("b", firstBarMs = 3_200.0)
        assertEquals(3_200L, planner.plan(song("a"), b, settings(MixStyle.SMOOTH)).incomingEntryMs)
        assertEquals(3_200L, planner.firstEntryMs(b, settings(MixStyle.RADIO)))
    }

    @Test
    fun closeTemposAreBeatmatchedWithATempoChangeAndPreRoll() {
        val plan = planner.plan(song("a", bpm = 128.0), song("b", bpm = 124.0), settings(MixStyle.SMOOTH))
        assertEquals(TransitionTechnique.BEATMATCHED_BLEND, plan.technique)
        assertTrue(abs(plan.incomingSpeed - 128.0 / 124.0) < 1e-9)
        assertTrue(plan.preRollMs > 0)
        // 16 bars at 128 BPM = 30 s.
        assertTrue(abs(plan.fadeMs - 30_000) <= 100, "smooth blends over 16 bars: ${plan.fadeMs}")
    }

    @Test
    fun doubleTimeCountsAsAMatch() {
        val plan = planner.plan(song("a", bpm = 140.0), song("b", bpm = 70.5), settings(MixStyle.RADIO))
        assertEquals(TransitionTechnique.BEATMATCHED_BLEND, plan.technique)
    }

    @Test
    fun aBigTempoGapOrAnUntrustedGridUsesEchoOut() {
        val gap = planner.plan(song("a", bpm = 128.0), song("b", bpm = 95.0), settings(MixStyle.RADIO))
        assertEquals(TransitionTechnique.ECHO_OUT, gap.technique)
        assertTrue(abs(gap.echoDelayMs - 469) <= 1, "one beat of the outgoing song: ${gap.echoDelayMs}")
        assertTrue(gap.echoLeadMs > 0 && gap.fadeMs in 600L..1_500L)
        assertNull(planner.tempoMatch(song("a", confidence = 0.1).analysis, song("b").analysis))
        assertEquals(TransitionTechnique.ECHO_OUT, planner.plan(song("a", confidence = 0.1), song("b"), settings(MixStyle.SMOOTH)).technique)
    }

    @Test
    fun withoutABarGridItFallsBackToAClassicFade() {
        val plan = planner.plan(song("a", withGrid = false), song("b", withGrid = false), settings(MixStyle.RADIO))
        assertEquals(TransitionTechnique.CLASSIC_FADE, plan.technique)
    }

    @Test
    fun clashingKeysShortenTheBlend() {
        val calm = planner.plan(song("a", camelot = "8A"), song("b", camelot = "9A"), settings(MixStyle.RADIO))
        val clash = planner.plan(song("a", camelot = "8A"), song("b", camelot = "2B"), settings(MixStyle.RADIO))
        assertTrue(clash.fadeMs < calm.fadeMs, "clash ${clash.fadeMs} < calm ${calm.fadeMs}")
        assertTrue("keys clash" in clash.reason)
    }

    @Test
    fun theCrossfadeNeverDips() {
        for (step in 0..20) {
            val p = step / 20.0
            val out = CrossfadeCurve.outgoing(p)
            val inc = CrossfadeCurve.incoming(p)
            assertTrue(out * out + inc * inc >= 0.999f, "power at $p: ${out * out + inc * inc}")
        }
        assertEquals(1f, CrossfadeCurve.outgoing(0.0))
        assertEquals(0f, CrossfadeCurve.incoming(0.0))
        assertEquals(1f, CrossfadeCurve.incoming(1.0))
    }
}
