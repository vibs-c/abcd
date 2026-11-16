package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrainTest {
    @Test
    fun sizerUsesStyleAndAddsSpares() {
        val hour = DjSessionSettings(sessionLengthMinutes = 60, mixStyle = MixStyle.CHORUS_HITS)
        assertEquals(48, PlaylistSizer.coreTrackCount(hour)) // 3600 / 75
        assertEquals(53, PlaylistSizer.targetTrackCount(hour)) // + 10% rounded up
        val short = DjSessionSettings(sessionLengthMinutes = 15, mixStyle = MixStyle.RADIO)
        assertEquals(5, PlaylistSizer.coreTrackCount(short)) // ceil(900 / 195)
        assertEquals(8, PlaylistSizer.targetTrackCount(short)) // + minimum 3 spares
    }

    @Test
    fun queriesCombineLanguageAndMood() {
        val settings = DjSessionSettings(languages = setOf(DjLanguage.HINDI, DjLanguage.PUNJABI), mood = DjMood.PARTY)
        assertEquals(
            listOf("hindi party songs", "hindi dance hits", "punjabi party songs", "punjabi dance hits"),
            SearchQueryBuilder.queries(settings),
        )
        assertEquals(DjMood.CHILL.searchTerms, SearchQueryBuilder.queries(DjSessionSettings(mood = DjMood.CHILL)))
    }

    @Test
    fun filterRejectsNonMusicAndDuplicates() {
        val filter = CandidateFilter()
        assertEquals(CandidateFilter.Rejection.NON_MUSIC_TITLE, filter.rejectionFor(track("1", "Song (Live at Wembley)")))
        assertEquals(CandidateFilter.Rejection.NON_MUSIC_TITLE, filter.rejectionFor(track("2", "Bollywood Nonstop Jukebox")))
        assertEquals(CandidateFilter.Rejection.TOO_LONG, filter.rejectionFor(track("3", "Mix", durationMs = 20 * 60_000)))
        assertEquals(CandidateFilter.Rejection.BANNED, filter.rejectionFor(track("4", "Song"), setOf("4")))
        assertNull(filter.rejectionFor(track("5", "Live It Up")))

        val kept =
            filter.filter(
                listOf(
                    track("a", "Kesariya", artist = "Arijit"),
                    track("b", "Kesariya (Official Video)", artist = "Arijit"),
                    track("a", "Kesariya", artist = "Arijit"),
                ),
            )
        assertEquals(listOf("a"), kept.map { it.videoId })
    }

    @Test
    fun builderFillsTargetSpacesArtistsAndDropsWrongLanguage() =
        runTest {
            val source =
                FakeSource(
                    mapOf(
                        "hindi party songs" to (1..20).map { track("h$it", "Song $it", artist = "Artist ${it % 2}") },
                        "hindi dance hits" to listOf(track("t1", "ரவுடி பேபி", artist = "Dhanush")),
                    ),
                )
            val settings =
                DjSessionSettings(
                    languages = setOf(DjLanguage.HINDI),
                    mood = DjMood.PARTY,
                    mixStyle = MixStyle.RADIO,
                    sessionLengthMinutes = 15,
                )
            val plan = DjPlaylistBuilder(source, MetadataTrackTagger()).build(settings)

            assertFalse(plan.tracks.any { it.videoId == "t1" }, "Tamil-script title must be dropped for Hindi")
            // Only two artists, so spacing cannot hold: tracks are deferred, never duplicated or overfilled.
            assertTrue(plan.tracks.size <= plan.targetCount)
            assertEquals(
                plan.tracks
                    .map { it.videoId }
                    .toSet()
                    .size,
                plan.tracks.size,
            )
        }

    @Test
    fun builderKeepsSameArtistApartWhenPoolAllows() =
        runTest {
            val pool = (1..12).map { track("v$it", "Song $it", artist = "Artist ${it % 4}") }
            val plan =
                DjPlaylistBuilder(FakeSource(mapOf("chill songs" to pool)), MetadataTrackTagger())
                    .build(DjSessionSettings(mood = DjMood.CHILL, mixStyle = MixStyle.RADIO, sessionLengthMinutes = 15))
            assertEquals(8, plan.tracks.size)
            plan.tracks.windowed(4).forEach { window ->
                assertEquals(4, window.map { it.artists.single() }.toSet().size, "artists repeat within 3: $window")
            }
        }

    @Test
    fun builderTopsUpFromRadioWhenSearchIsShort() =
        runTest {
            val source =
                FakeSource(
                    search = mapOf("chill songs" to listOf(track("s1", "Seed", artist = "X"))),
                    radio = mapOf("s1" to (1..10).map { track("r$it", "Radio $it", artist = "R$it") }),
                )
            val plan =
                DjPlaylistBuilder(source, MetadataTrackTagger())
                    .build(DjSessionSettings(mood = DjMood.CHILL, mixStyle = MixStyle.RADIO, sessionLengthMinutes = 15))
            assertEquals(plan.targetCount, plan.tracks.size)
            assertEquals("s1", plan.tracks.first().videoId)
            assertFalse(plan.isShort)
        }

    @Test
    fun interleaveAlternatesLists() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
    }

    private class FakeSource(
        private val search: Map<String, List<DjTrack>>,
        private val radio: Map<String, List<DjTrack>> = emptyMap(),
    ) : CandidateSource {
        override suspend fun search(query: String) = search[query].orEmpty()

        override suspend fun radio(videoId: String) = radio[videoId].orEmpty()
    }

    private fun track(
        id: String,
        title: String,
        artist: String = "Artist",
        durationMs: Long = 200_000,
    ) = DjTrack(videoId = id, title = title, artists = listOf(artist), durationMs = durationMs)
}
