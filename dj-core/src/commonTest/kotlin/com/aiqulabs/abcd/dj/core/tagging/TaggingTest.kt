package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.model.Script
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaggingTest {
    @Test
    fun detectsDominantScript() {
        assertEquals(Script.DEVANAGARI, ScriptDetector.dominantScript("तुम ही हो (Tum Hi Ho)"))
        assertEquals(Script.GURMUKHI, ScriptDetector.dominantScript("ਲੌਂਗ ਲਾਚੀ"))
        assertEquals(Script.TAMIL, ScriptDetector.dominantScript("ரவுடி பேபி"))
        assertEquals(Script.LATIN, ScriptDetector.dominantScript("Shape of You"))
        assertNull(ScriptDetector.dominantScript("123 - !!"))
    }

    @Test
    fun gurmukhiTitleIsPunjabi() =
        runTest {
            val tags = MetadataTrackTagger().tag(track("ਲੌਂਗ ਲਾਚੀ"))
            assertEquals(listOf("PUNJABI"), tags.map { it.value })
            assertTrue(tags.single().confidence >= 0.9f)
        }

    @Test
    fun devanagariTagsHindiAndMarathiWithLowerConfidence() =
        runTest {
            val tags = MetadataTrackTagger().tag(track("झिंगाट"))
            assertEquals(setOf("HINDI", "MARATHI"), tags.map { it.value }.toSet())
            assertTrue(tags.all { it.confidence < 0.9f })
        }

    @Test
    fun transliteratedTitleHasNoLanguageTag() =
        runTest {
            assertTrue(MetadataTrackTagger().tag(track("Tum Hi Ho")).isEmpty())
        }

    @Test
    fun languageNameInAlbumIsWeakEvidence() =
        runTest {
            val tags = MetadataTrackTagger().tag(track("Lover", album = "Punjabi Hits 2024"))
            assertEquals("PUNJABI", tags.single().value)
            assertEquals(TagSource.METADATA, tags.single().source)
        }

    private fun track(
        title: String,
        album: String? = null,
    ) = DjTrack(videoId = title, title = title, artists = listOf("A"), album = album)
}
