package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.EnergyArc
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.core.prep.fakeAnalysis
import com.aiqulabs.abcd.dj.core.prep.fakeTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaylistOrdererTest {
    private val orderer = PlaylistOrderer()

    private fun prepared(
        id: String,
        bpm: Double = 120.0,
        camelot: String = "8A",
        energy: Double = 0.5,
        artist: String = "artist-$id",
        bpmConfidence: Double = 0.9,
        keyConfidence: Double = 0.9,
    ) = PreparedTrack(fakeTrack(id, artist), fakeAnalysis(bpm, camelot, energy, bpmConfidence, keyConfidence))

    @Test
    fun bpmWithinSixPercentIsPerfectAndHalfOrDoubleTimeCounts() {
        val a = fakeAnalysis(bpm = 120.0)
        assertEquals(1.0, orderer.bpmCompat(a, fakeAnalysis(bpm = 126.0)), 1e-9)
        assertEquals(1.0, orderer.bpmCompat(a, fakeAnalysis(bpm = 60.0)), 1e-9)
        assertEquals(1.0, orderer.bpmCompat(a, fakeAnalysis(bpm = 240.0)), 1e-9)
        assertTrue(orderer.bpmCompat(a, fakeAnalysis(bpm = 140.0)) < 0.7)
        assertEquals(0.0, orderer.bpmCompat(a, fakeAnalysis(bpm = 160.0)), 1e-9)
    }

    @Test
    fun anUntrustedTempoOrKeyScoresNeutralNotAsAMismatch() {
        val a = fakeAnalysis(bpm = 120.0)
        val shaky = fakeAnalysis(bpm = 170.0, bpmConfidence = 0.2, keyConfidence = 0.2, camelot = "3B")
        assertEquals(0.5, orderer.bpmCompat(a, shaky), 1e-9)
        assertEquals(0.5, orderer.keyCompat(a, shaky), 1e-9)
    }

    @Test
    fun camelotRulesCoverSameRelativeAdjacentAndWrapAround() {
        val a = fakeAnalysis(camelot = "8A")
        assertEquals(1.0, orderer.keyCompat(a, fakeAnalysis(camelot = "8A")), 1e-9)
        assertEquals(0.9, orderer.keyCompat(a, fakeAnalysis(camelot = "8B")), 1e-9)
        assertEquals(0.9, orderer.keyCompat(a, fakeAnalysis(camelot = "9A")), 1e-9)
        assertEquals(0.9, orderer.keyCompat(a, fakeAnalysis(camelot = "7A")), 1e-9)
        assertEquals(0.5, orderer.keyCompat(a, fakeAnalysis(camelot = "10A")), 1e-9)
        assertEquals(0.0, orderer.keyCompat(a, fakeAnalysis(camelot = "2A")), 1e-9)
        assertEquals(0.9, orderer.keyCompat(fakeAnalysis(camelot = "12A"), fakeAnalysis(camelot = "1A")), 1e-9)
    }

    @Test
    fun buildUpOrdersQuietTracksBeforeLoudOnes() {
        val tracks = listOf(0.9, 0.2, 0.6, 0.4, 0.8).mapIndexed { i, e -> prepared("t$i", energy = e) }
        val settings = DjSessionSettings(mood = DjMood.PARTY)
        assertEquals(EnergyArc.BUILD_UP, settings.effectiveEnergyArc)
        val energies = orderer.order(tracks, settings).map { it.analysis.energyCurve.first() }
        assertEquals(energies.sorted(), energies, "energy rises through the set")
    }

    @Test
    fun peakAndCooldownPeaksInTheLaterMiddleAndEndsQuieterThanThePeak() {
        val tracks = listOf(0.1, 0.3, 0.5, 0.7, 0.9, 0.2, 0.4, 0.6, 0.8, 1.0).mapIndexed { i, e -> prepared("t$i", energy = e) }
        val ordered = orderer.order(tracks, DjSessionSettings(mood = DjMood.WEDDING))
        val energies = ordered.map { it.analysis.energyCurve.first() }
        val peakAt = energies.indexOf(energies.max())
        assertTrue(peakAt in 4..8, "peak at $peakAt of ${energies.size}")
        assertTrue(energies.last() < energies.max())
    }

    @Test
    fun sameArtistTracksAreKeptApart() {
        val tracks =
            listOf(
                prepared("a1", artist = "Same"),
                prepared("a2", artist = "Same"),
                prepared("b", artist = "B"),
                prepared("c", artist = "C"),
                prepared("d", artist = "D"),
                prepared("e", artist = "E"),
            )
        val ordered = orderer.order(tracks, DjSessionSettings(mood = DjMood.CHILL))
        val positions = ordered.mapIndexedNotNull { i, t -> if ("Same" in t.track.artists) i else null }
        assertTrue(positions[1] - positions[0] >= 3, "same artist $positions")
    }

    @Test
    fun everyTrackIsReturnedExactlyOnceAndTinyListsAreUntouched() {
        val tracks = (1..12).map { prepared("t$it", bpm = 90.0 + it, energy = it / 12.0) }
        val ordered = orderer.order(tracks, DjSessionSettings())
        assertEquals(tracks.map { it.track.videoId }.toSet(), ordered.map { it.track.videoId }.toSet())
        assertEquals(12, ordered.size)
        val one = listOf(prepared("solo"))
        assertEquals(one, orderer.order(one, DjSessionSettings()))
        assertEquals(emptyList(), orderer.order(emptyList(), DjSessionSettings()))
    }

    @Test
    fun closeTemposAreChosenBeforeFarOnesWhenEnergyIsEqual() {
        val tracks =
            listOf(
                prepared("start", bpm = 120.0),
                prepared("far", bpm = 175.0),
                prepared("near", bpm = 122.0),
            )
        val ordered = orderer.order(tracks, DjSessionSettings(mood = DjMood.CHILL)).map { it.track.videoId }
        assertTrue(ordered.indexOf("near") < ordered.indexOf("far"), "order $ordered")
    }
}
