package com.aiqulabs.abcd.dj.core.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChorusRankerTest {
    private fun candidate(
        startMs: Double,
        score: Double,
        main: Boolean = false,
    ) = ChorusCandidate(
        startMs = startMs,
        endMs = startMs + 10_000,
        startBar = (startMs / 2000).toInt(),
        endBar = (startMs / 2000).toInt() + 5,
        score = score,
        signals = listOf(ChorusSignal.REPETITION),
        isMain = main,
    )

    private fun result(vararg candidates: ChorusCandidate) =
        AnalysisResult(
            analysisVersion = 1,
            durationMs = 120_000.0,
            bpm = 120.0,
            bpmConfidence = 1.0,
            key = KeyResult("A", KeyMode.MINOR, "8A", 9, 0.9),
            lufs = -14.0,
            chorusCandidates = candidates.toList(),
        )

    // Verse lines are unique; the chorus line "We dance all night" returns in the 2nd and 3rd window.
    private val lyrics =
        listOf(
            LyricLine(1_000, "First verse line one"),
            LyricLine(4_000, "First verse line two"),
            LyricLine(21_000, "We dance all night"),
            LyricLine(24_000, "We dance all night!"),
            LyricLine(41_000, "Second verse line one"),
            LyricLine(44_000, "Second verse line two"),
            LyricLine(61_000, "we dance all night"),
            LyricLine(64_000, "We Dance, all night"),
        )

    @Test
    fun noLyricsLeavesResultUntouched() {
        val input = result(candidate(20_000.0, 0.8, main = true))
        assertSame(input, ChorusRanker.withLyrics(input, emptyList()))
    }

    @Test
    fun repeatedLyricLinesRaiseThatCandidateAndAddTheSignal() {
        val verseLike = candidate(0.0, 0.9, main = true) // audio liked it, but its lyrics never repeat
        val chorusLike = candidate(20_000.0, 0.7)
        val out = ChorusRanker.withLyrics(result(verseLike, chorusLike), lyrics)

        val first = out.chorusCandidates[0]
        val second = out.chorusCandidates[1]
        assertTrue(second.score > first.score, "lyrics should lift the repeated-lines window above the verse")
        assertTrue(ChorusSignal.LYRICS in second.signals)
        assertTrue(ChorusSignal.LYRICS !in first.signals)
        assertTrue(second.isMain)
        assertTrue(!first.isMain)
    }

    @Test
    fun punctuationAndCaseDoNotHideRepeats() {
        assertEquals(ChorusRanker.normalise("We dance, all night!"), ChorusRanker.normalise("we  DANCE all night"))
    }

    @Test
    fun tieGoesToTheLaterOccurrence() {
        val out = ChorusRanker.withLyrics(result(candidate(20_000.0, 0.7), candidate(60_000.0, 0.7)), lyrics)
        assertTrue(out.chorusCandidates[1].isMain)
    }

    @Test
    fun tooFewLyricLinesAreIgnored() {
        val input = result(candidate(20_000.0, 0.8, main = true))
        assertSame(input, ChorusRanker.withLyrics(input, lyrics.take(3)))
    }
}
