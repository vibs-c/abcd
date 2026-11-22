package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RomanizedIndicHintsTest {
    private fun track(
        title: String,
        vararg artists: String,
        album: String? = null,
    ) = DjTrack(videoId = title, title = title, artists = artists.toList(), album = album)

    private val strong = 0.6f

    @Test
    fun indianSongsWithLatinTitlesFromAnEnglishPartyMixAreRecognised() {
        // The songs an English-only mix let through on the emulator (2026-11-22).
        val indian =
            listOf(
                track("Shararat (From \"Dhurandhar\")", "Shashwat Sachdev", "Jasmine Sandlas"),
                track("The Humma Song (From \"OK Jaanu\")", "A.R. Rahman", "Badshah"),
                track("Chikni Chameli", "Shreya Ghoshal", "Ajay-Atul"),
                track("Tera Ban Jaunga", "Akhil Sachdeva", "Tulsi Kumar"),
                track("Kesariya (From \"Brahmastra\")", "Pritam", "Arijit Singh"),
                track("Lover", "Diljit Dosanjh"),
            )
        indian.forEach { assertTrue(RomanizedIndicHints.indianScore(it) >= strong, "should be Indian: ${it.title}") }
    }

    @Test
    fun englishSongsAreNotMistakenForIndianOnes() {
        val english =
            listOf(
                track("I Gotta Feeling", "Black Eyed Peas"),
                track("Toxic", "Britney Spears"),
                track("Get Low (feat. Ying Yang Twins)", "Lil Jon & The East Side Boyz"),
                track("I've Got You Under My Skin", "Frank Sinatra"),
                track("Pillowtalk", "Zayn Malik"),
                track("Only Girl (In The World)", "Rihanna"),
                track("Mr. Vain", "Culture Beat"),
                track("Gal Like You", "Some Band"),
                track("Main Thing", "Some Band"),
            )
        english.forEach { assertTrue(RomanizedIndicHints.indianScore(it) < strong, "should not be Indian: ${it.title} (${RomanizedIndicHints.indianScore(it)})") }
    }

    @Test
    fun distinctiveWordsTagHindiOrPunjabi() {
        assertEquals(setOf(DjLanguage.HINDI), RomanizedIndicHints.languageWords(track("Tera Ban Jaunga Meri Jaan")).keys)
        assertTrue(DjLanguage.PUNJABI in RomanizedIndicHints.languageWords(track("Kudi Nu Nachne De Tenu")).keys)
        assertTrue(RomanizedIndicHints.languageWords(track("Dil")).isEmpty(), "one word is not enough for a language tag")
        assertTrue(RomanizedIndicHints.languageWords(track("Toxic")).isEmpty())
    }

    @Test
    fun aTamilFilmSongIsIndianButNotTaggedHindi() {
        val song = track("Hukum (From \"Jailer\")", "Anirudh Ravichander")
        assertTrue(RomanizedIndicHints.indianScore(song) >= strong)
        assertTrue(RomanizedIndicHints.languageWords(song).isEmpty())
    }
}
