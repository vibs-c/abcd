package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.tagging.LyricsLanguage
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import com.aiqulabs.abcd.dj.core.tagging.StoredTagTagger
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Stage 12: "Hindi songs play during an English-only selection". */
class LanguageGateTest {
    private val english = DjSessionSettings(languages = setOf(DjLanguage.ENGLISH))
    private val hindi = DjSessionSettings(languages = setOf(DjLanguage.HINDI))

    private fun track(
        id: String,
        title: String,
        artist: String = "Someone",
    ) = DjTrack(id, title, listOf(artist), durationMs = 200_000)

    private fun lang(
        language: DjLanguage,
        confidence: Float,
        source: TagSource = TagSource.LYRICS,
    ) = TrackTagValue(TagType.LANGUAGE, language.name, source, confidence)

    @Test
    fun hindiLyricsUnderAnEnglishTitleAreCaught() {
        val lyrics = "Tera pyaar hai mera dil, tujhe dekha toh yeh jaana sanam\nBaby tonight we party all night"
        val tags = LyricsLanguage.tags(lyrics)
        assertTrue(tags.any { it.value == "HINDI" && it.confidence >= 0.8f }, "$tags")
        assertFalse(LanguageGate.allows(track("a", "Party All Night"), tags, english))
        assertTrue(LanguageGate.allows(track("a", "Party All Night"), tags, hindi))
    }

    @Test
    fun englishLyricsAreEnglish() {
        val lyrics = "I know you want me, baby tonight we never stop\nAll the love that we feel is right now, yeah"
        val tags = LyricsLanguage.tags(lyrics)
        assertEquals(listOf("ENGLISH"), tags.map { it.value })
        assertTrue(LanguageGate.allows(track("b", "Tonight"), tags, english))
    }

    @Test
    fun devanagariLyricsAreHindi() {
        assertEquals("HINDI", LyricsLanguage.tags("तेरा प्यार है मेरा दिल").single().value)
    }

    @Test
    fun theUsersTagIsFinal() {
        // An Indian singer, but the user says this song is English: it may play in an English mix.
        val song = track("c", "Cold/Mess", artist = "Arijit Singh")
        assertFalse(LanguageGate.allows(song, emptyList(), english))
        assertTrue(LanguageGate.allows(song, listOf(lang(DjLanguage.ENGLISH, 1f, TagSource.USER)), english))
        // And the other way round.
        assertFalse(LanguageGate.allows(track("d", "Plain"), listOf(lang(DjLanguage.HINDI, 1f, TagSource.USER)), english))
    }

    @Test
    fun aKnownIndianSingerKeepsTheirOtherSongsOut() {
        val song = track("e", "Lovely Night", artist = "Unknown Desi Singer")
        assertTrue(LanguageGate.allows(song, emptyList(), english))
        assertFalse(LanguageGate.allows(song, emptyList(), english, indianArtist = true))
    }

    @Test
    fun storedLyricsTagsReachTheBuilder() =
        runTest {
            val songs =
                listOf(
                    track("s1", "Night Drive", "Band One"),
                    track("s2", "Summer Love", "Band Two"),
                    track("s3", "Party All Night", "Band Three"), // Hindi lyrics, stored from an earlier analysis
                    track("s4", "Kal Ho Na Ho Tera Pyaar", "Desi Star"), // Hindi title
                    track("s5", "Lovely", "Desi Star"), // same singer, English-looking title
                )
            val stored = mapOf("s3" to listOf(lang(DjLanguage.HINDI, 0.85f)))
            val source =
                object : CandidateSource {
                    override suspend fun search(query: String) = songs
                }
            val tagger = StoredTagTagger(MetadataTrackTagger()) { stored[it].orEmpty() }
            val settings = english.copy(mixStyle = MixStyle.CHORUS_HITS, sessionLengthMinutes = 15)
            val ids = DjPlaylistBuilder(source, tagger).build(settings).tracks.map { it.videoId }
            assertEquals(listOf("s1", "s2"), ids.sorted(), "only the English songs: $ids")
        }
}
