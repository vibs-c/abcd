package com.aiqulabs.abcd.dj.core.mix

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.core.prep.fakeAnalysis
import com.aiqulabs.abcd.dj.core.prep.fakeTrack
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassicFadePlannerTest {
    private val planner = ClassicFadePlanner()

    private fun track(
        id: String,
        durationMs: Double = 200_000.0,
        outroMs: Double = 0.0,
    ) = PreparedTrack(fakeTrack(id), fakeAnalysis().copy(durationMs = durationMs, outroMixableStartMs = outroMs))

    @Test
    fun radioFadesOutOverTheLastFourSecondsBeforeTheTail() {
        val plan = planner.plan(track("a"), track("b"), DjSessionSettings(mixStyle = MixStyle.RADIO))
        assertEquals(TransitionTechnique.CLASSIC_FADE, plan.technique)
        assertEquals(4_000L, plan.fadeMs)
        assertEquals(200_000L - 4_000L - ClassicFadePlanner.END_TAIL_MS, plan.outgoingExitMs)
        assertEquals(0L, plan.incomingEntryMs)
    }

    @Test
    fun smoothUsesTheMixableOutroWhenItIsInTheSecondHalf() {
        val plan = planner.plan(track("a", outroMs = 170_000.0), track("b"), DjSessionSettings(mixStyle = MixStyle.SMOOTH))
        assertEquals(8_000L, plan.fadeMs)
        assertEquals(170_000L, plan.outgoingExitMs)
        assertTrue("outro" in plan.reason)
    }

    @Test
    fun smoothIgnoresAnOutroThatIsTooEarlyOrTooLate() {
        val settings = DjSessionSettings(mixStyle = MixStyle.SMOOTH)
        val early = planner.plan(track("a", outroMs = 60_000.0), track("b"), settings)
        val late = planner.plan(track("a", outroMs = 199_000.0), track("b"), settings)
        val endExit = 200_000L - 8_000L - ClassicFadePlanner.END_TAIL_MS
        assertEquals(endExit, early.outgoingExitMs)
        assertEquals(endExit, late.outgoingExitMs)
    }

    @Test
    fun aVeryShortTrackGetsAShorterFadeThatStillFits() {
        val plan = planner.plan(track("a", durationMs = 9_000.0), track("b"), DjSessionSettings(mixStyle = MixStyle.SMOOTH))
        assertEquals(3_000L, plan.fadeMs)
        assertTrue(plan.outgoingExitMs >= 0L)
        assertTrue(plan.outgoingExitMs + plan.fadeMs <= 9_000L)
    }

    @Test
    fun skipStartsAShortFadeRightNow() {
        val plan = planner.planSkip(track("a"), track("b"), outgoingPositionMs = 42_000L, settings = DjSessionSettings())
        assertEquals(42_000L, plan.outgoingExitMs)
        assertEquals(ClassicFadePlanner.SKIP_FADE_MS, plan.fadeMs)
    }

    @Test
    fun equalPowerKeepsTheSummedPowerLevel() {
        for (step in 0..10) {
            val p = step / 10.0
            val out = EqualPowerFade.outgoingGain(p)
            val inc = EqualPowerFade.incomingGain(p)
            assertTrue(abs(out * out + inc * inc - 1f) < 1e-5f, "power at $p")
        }
        assertEquals(1f, EqualPowerFade.outgoingGain(0.0))
        assertEquals(1f, EqualPowerFade.incomingGain(1.0))
        assertEquals(0.5, EqualPowerFade.progress(nowMs = 1_500, startMs = 1_000, fadeMs = 1_000))
        assertEquals(1.0, EqualPowerFade.progress(nowMs = 9_000, startMs = 1_000, fadeMs = 1_000))
    }
}
