package com.aiqulabs.abcd.dj.core.prep

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.KeyResult
import com.aiqulabs.abcd.dj.core.brain.PlaylistPlan
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun fakeAnalysis(
    bpm: Double = 120.0,
    camelot: String = "8A",
    energy: Double = 0.5,
    bpmConfidence: Double = 0.9,
    keyConfidence: Double = 0.9,
) = AnalysisResult(
    analysisVersion = 1,
    durationMs = 200_000.0,
    bpm = bpm,
    bpmConfidence = bpmConfidence,
    key = KeyResult("A", KeyMode.MINOR, camelot, 9, keyConfidence),
    lufs = -10.0,
    energyCurve = listOf(energy, energy),
)

internal fun fakeTrack(
    id: String,
    artist: String = "artist-$id",
) = DjTrack(videoId = id, title = "Song $id", artists = listOf(artist))

class SessionPreparerTest {
    private val settings = DjSessionSettings()

    private fun plan(
        count: Int,
        core: Int,
    ) = PlaylistPlan(settings, (1..count).map { fakeTrack("t$it") }, core, count)

    private class ScriptedPreparer(
        private val failing: Set<String> = emptySet(),
        private val known: Set<String> = emptySet(),
        private val throwing: Set<String> = emptySet(),
    ) : TrackPreparer {
        val prepared = mutableListOf<String>()

        override suspend fun prepare(track: DjTrack): TrackPreparation {
            prepared += track.videoId
            if (track.videoId in throwing) error("boom")
            return if (track.videoId in failing) {
                TrackPreparation.Failed("no stream")
            } else {
                TrackPreparation.Ready(fakeAnalysis(), alreadyKnown = track.videoId in known)
            }
        }
    }

    @Test
    fun preparesTheCoreCountAndLeavesTheSparesUntouched() =
        runTest {
            val preparer = ScriptedPreparer()
            val result = SessionPreparer(preparer).prepare(plan(count = 8, core = 5))
            assertEquals(5, result.tracks.size)
            assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), result.tracks.map { it.track.videoId })
            assertEquals(5, preparer.prepared.size, "spares are not analysed when nothing fails")
            assertFalse(result.isShort)
        }

    @Test
    fun aFailedTrackIsReplacedByTheNextSpareInPlanOrder() =
        runTest {
            val preparer = ScriptedPreparer(failing = setOf("t2"))
            val result = SessionPreparer(preparer, parallelism = 1).prepare(plan(count = 8, core = 5))
            assertEquals(listOf("t1", "t3", "t4", "t5", "t6"), result.tracks.map { it.track.videoId })
            assertEquals(listOf("t2"), result.failed.map { it.videoId })
        }

    @Test
    fun parallelBatchesGiveTheSameOutcomeInTheSameOrder() =
        runTest {
            val failing = setOf("t2", "t5")
            val sequential = SessionPreparer(ScriptedPreparer(failing = failing), parallelism = 1).prepare(plan(10, 6))
            val parallel = SessionPreparer(ScriptedPreparer(failing = failing), parallelism = 3).prepare(plan(10, 6))
            assertEquals(6, parallel.tracks.size)
            assertEquals(sequential.tracks.map { it.track.videoId }, parallel.tracks.map { it.track.videoId })
            assertEquals(sequential.failed.map { it.videoId }, parallel.failed.map { it.videoId })
        }

    @Test
    fun runningOutOfSparesGivesAShortSessionInsteadOfLooping() =
        runTest {
            val preparer = ScriptedPreparer(failing = setOf("t1", "t2", "t3", "t4"))
            val result = SessionPreparer(preparer).prepare(plan(count = 6, core = 5))
            assertEquals(2, result.tracks.size)
            assertEquals(4, result.failed.size)
            assertTrue(result.isShort)
        }

    @Test
    fun anUnexpectedExceptionCountsAsAFailedTrack() =
        runTest {
            val preparer = ScriptedPreparer(throwing = setOf("t1"))
            val result = SessionPreparer(preparer, parallelism = 1).prepare(plan(count = 5, core = 3))
            assertEquals(listOf("t2", "t3", "t4"), result.tracks.map { it.track.videoId })
            assertEquals(listOf("t1"), result.failed.map { it.videoId })
        }

    @Test
    fun progressCountsAlreadyAnalysedTracksAndReportsTheCurrentNumber() =
        runTest {
            val seen = mutableListOf<PreparationProgress>()
            val preparer = ScriptedPreparer(known = setOf("t1", "t2"))
            SessionPreparer(preparer, parallelism = 1).prepare(plan(count = 5, core = 4)) { seen += it }
            val last = seen.last()
            assertEquals(4, last.ready)
            assertEquals(4, last.total)
            assertEquals(2, last.alreadyKnown)
            assertEquals(1f, last.fraction)
            assertEquals(1, seen.first().current)
            assertTrue(seen.all { it.current in 1..4 }, "current never exceeds total")
        }

    @Test
    fun aPlanSmallerThanTheCoreCountUsesWhatItHas() =
        runTest {
            val result = SessionPreparer(ScriptedPreparer()).prepare(plan(count = 3, core = 10))
            assertEquals(3, result.tracks.size)
            assertTrue(result.isShort)
        }

    @Test
    fun anEmptyPlanFinishesImmediately() =
        runTest {
            val result = SessionPreparer(ScriptedPreparer()).prepare(plan(count = 0, core = 5))
            assertTrue(result.tracks.isEmpty())
            assertTrue(result.isShort)
        }
}
