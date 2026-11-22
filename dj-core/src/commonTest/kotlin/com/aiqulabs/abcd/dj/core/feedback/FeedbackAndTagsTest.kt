package com.aiqulabs.abcd.dj.core.feedback

import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.KeyResult
import com.aiqulabs.abcd.dj.core.brain.CandidateSource
import com.aiqulabs.abcd.dj.core.brain.DjPlaylistBuilder
import com.aiqulabs.abcd.dj.core.brain.PlaylistOrderer
import com.aiqulabs.abcd.dj.core.brain.SearchQueryBuilder
import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique
import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.core.prep.fakeAnalysis
import com.aiqulabs.abcd.dj.core.prep.fakeTrack
import com.aiqulabs.abcd.dj.core.tagging.AnalysisTagger
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import com.aiqulabs.abcd.dj.core.tagging.TagType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackAndTagsTest {
    private fun prepared(
        id: String,
        bpm: Double = 120.0,
    ) = PreparedTrack(fakeTrack(id), fakeAnalysis(bpm = bpm))

    @Test
    fun aThumbsUpPairIsPreferredAndAnOftenRemovedTrackSinks() {
        val a = prepared("a")
        val b = prepared("b")
        val c = prepared("c")
        val settings = DjSessionSettings()
        val neutral = PlaylistOrderer()
        val liked = PlaylistOrderer(feedback = FeedbackSnapshot(pairScores = mapOf(("a" to "c") to 2)))
        assertTrue(liked.score(a, c, 0.5, 0.5) > neutral.score(a, c, 0.5, 0.5))
        val removed = PlaylistOrderer(feedback = FeedbackSnapshot(removeCounts = mapOf("b" to 3)))
        assertTrue(removed.score(a, b, 0.5, 0.5) < neutral.score(a, b, 0.5, 0.5))
        assertEquals(3, removed.order(listOf(a, b, c), settings).size, "feedback reorders, never drops")
    }

    @Test
    fun aDislikedBlendIsReplacedByEchoOutOnlyWhereKeysClash() {
        val disliked = mapOf(TransitionTechnique.BEATMATCHED_BLEND to -3)
        fun song(
            id: String,
            camelot: String,
        ): PreparedTrack {
            val bars = generateSequence(500.0) { it + 2_000.0 }.takeWhile { it < 200_000.0 }.toList()
            val analysis =
                fakeAnalysis(bpm = 120.0, camelot = camelot).copy(
                    durationMs = 200_000.0,
                    barStartsMs = bars,
                    key = KeyResult("A", KeyMode.MINOR, camelot, 9, 0.9),
                )
            return PreparedTrack(fakeTrack(id), analysis)
        }
        val settings = DjSessionSettings(mixStyle = MixStyle.RADIO)
        assertEquals(TransitionTechnique.ECHO_OUT, MixPlanner(techniqueScores = disliked).plan(song("a", "8A"), song("b", "2B"), settings).technique)
        assertEquals(TransitionTechnique.BEATMATCHED_BLEND, MixPlanner(techniqueScores = disliked).plan(song("a", "8A"), song("b", "9A"), settings).technique)
    }

    @Test
    fun energyAndMoodTagsFollowTempoLoudnessAndMode() {
        val party = fakeAnalysis(bpm = 128.0, energy = 0.8).copy(lufs = -7.0, key = KeyResult("C", KeyMode.MAJOR, "8B", 0, 0.9))
        val ballad = fakeAnalysis(bpm = 72.0, energy = 0.3).copy(lufs = -16.0, key = KeyResult("A", KeyMode.MINOR, "8A", 9, 0.9))
        assertEquals(AnalysisTagger.EnergyClass.HIGH, AnalysisTagger.energyClass(party))
        assertEquals(AnalysisTagger.EnergyClass.LOW, AnalysisTagger.energyClass(ballad))
        assertEquals(DjMood.PARTY, AnalysisTagger.moods(party).first())
        assertEquals(DjMood.SAD, AnalysisTagger.moods(ballad).first())
        val tags = AnalysisTagger.tags(party)
        assertTrue(tags.any { it.type == TagType.ENERGY_CLASS && it.value == "HIGH" })
        assertTrue(tags.any { it.type == TagType.MOOD && it.value == "PARTY" })
    }

    @Test
    fun librarySongsNeedProofOfALanguageButSearchResultsDoNot() =
        runTest {
            val search = listOf(DjTrack("s1", "Search One", listOf("A"), durationMs = 200_000), DjTrack("s2", "Search Two", listOf("B"), durationMs = 200_000))
            val library =
                listOf(
                    DjTrack("l1", "Tera Ban Jaunga Meri Jaan", listOf("C"), durationMs = 200_000), // Hindi words: proven Hindi
                    DjTrack("l2", "Plain Latin Title", listOf("D"), durationMs = 200_000), // no evidence
                    DjTrack("l3", "ਪੰਜਾਬੀ ਗੀਤ", listOf("E"), durationMs = 200_000), // Gurmukhi: Punjabi
                )
            val source =
                object : CandidateSource {
                    override suspend fun search(query: String) = search

                    override suspend fun libraryTracks() = library
                }
            val hindi = DjSessionSettings(languages = setOf(DjLanguage.HINDI), mixStyle = MixStyle.CHORUS_HITS, sessionLengthMinutes = 30)
            val ids = DjPlaylistBuilder(source, MetadataTrackTagger()).build(hindi).tracks.map { it.videoId }
            assertTrue("l1" in ids, "a proven-Hindi library song joins a Hindi mix: $ids")
            assertFalse("l2" in ids, "a library song with no language evidence stays out")
            assertFalse("l3" in ids, "a Punjabi library song stays out of a Hindi-only mix")
            assertTrue(ids.containsAll(listOf("s1", "s2")))
            assertTrue(SearchQueryBuilder.queries(hindi).isNotEmpty())
        }
}
