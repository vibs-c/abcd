package com.aiqulabs.abcd.dj.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisResultTest {
    /** Same shape as the native analyzer's output (dj-native core/json_writer.cpp), shortened. */
    private val wire =
        """
        {"analysisVersion":1,"durationMs":99000.000,"analysisSampleRate":44100,
         "bpm":126.000,"bpmConfidence":1.000,"bpmConfidenceRaw":3.710,
         "beatsMs":[0.000,476.190,952.381],"beatsPerBar":4,"downbeatConfidence":0.520,
         "barStartsMs":[0.000,1904.762],"phraseStartsMs":[0.000],
         "key":{"root":"A","mode":"minor","camelot":"8A","pitchClass":9,"confidence":0.890},
         "lufs":-21.200,"energyCurve":[0.4,1.0],"waveformPeaks":[0,128,255],
         "introMixableEndMs":7619.048,"outroMixableStartMs":91428.571,
         "sections":[{"type":"INTRO","startMs":0.000,"endMs":7619.048,"confidence":0.700},
                     {"type":"PRE_CHORUS","startMs":7619.048,"endMs":22857.143,"confidence":0.700}],
         "chorusCandidates":[{"startMs":22857.143,"endMs":38095.238,"startBar":12,"endBar":20,"score":1.000,
                              "signals":["REPETITION","ENERGY"],"isMain":true}],
         "someFutureField":42}
        """.trimIndent()

    @Test
    fun parsesNativeWireJson() {
        val result = AnalysisResult.fromWireJson(wire)
        assertEquals(126.0, result.bpm)
        assertEquals(KeyMode.MINOR, result.key.mode)
        assertEquals("8A", result.key.camelot)
        assertEquals(9, result.key.pitchClass)
        assertEquals(listOf(SectionType.INTRO, SectionType.PRE_CHORUS), result.sections.map { it.type })
        assertEquals(listOf(ChorusSignal.REPETITION, ChorusSignal.ENERGY), result.chorusCandidates.single().signals)
        assertEquals(3, result.waveformPeaks.size)
    }

    @Test
    fun mainChorusIsTheMarkedCandidate() {
        val result = AnalysisResult.fromWireJson(wire)
        val main = assertNotNull(result.mainChorus)
        assertEquals(12, main.startBar)
        assertEquals(15_238.095, main.durationMs, 0.01)
    }

    @Test
    fun gridConfidenceNeedsBothTempoConfidenceAndEnoughBars() {
        val result = AnalysisResult.fromWireJson(wire)
        assertFalse(result.hasConfidentGrid, "only 2 bars")
        val longer = result.copy(barStartsMs = List(AnalysisResult.MIN_BARS) { it * 1900.0 })
        assertTrue(longer.hasConfidentGrid)
        assertFalse(longer.copy(bpmConfidence = 0.2).hasConfidentGrid)
    }
}
