package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.tagging.RomanizedIndicHints
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue

/**
 * One language rule for every place that decides whether a song fits the selected languages (stage 12, owner
 * report "Hindi songs play during an English-only selection"): the playlist builder before analysis, and the
 * check after analysis when lyrics are known.
 *
 * 1. The user's own language tags (Lab, source USER) are final.
 * 2. Otherwise the strongest evidence wins: a song is out when a language that was not selected is tagged with
 *    at least [STRONG] confidence and at least as strongly as any selected one (Hindi verses beat an English hook).
 * 3. With no Indian language selected, title hints (film marker, Hindi words, Indian singers) and a singer
 *    already known to sing Indian songs in this mix ([indianArtist]) also keep a song out, unless the song
 *    itself shows a selected language more strongly.
 */
object LanguageGate {
    const val STRONG = 0.6f

    fun allows(
        track: DjTrack,
        tags: List<TrackTagValue>,
        settings: DjSessionSettings,
        indianArtist: Boolean = false,
    ): Boolean {
        if (settings.anyLanguage) return true
        val selected = settings.languages.map(DjLanguage::name).toSet()
        val languageTags = tags.filter { it.type == TagType.LANGUAGE }
        val user = languageTags.filter { it.source == TagSource.USER }
        if (user.isNotEmpty()) return user.any { it.value in selected }

        val bestSelected = languageTags.filter { it.value in selected }.maxOfOrNull { it.confidence } ?: 0f
        val bestOther = languageTags.filter { it.value !in selected }.maxOfOrNull { it.confidence } ?: 0f
        if (bestOther >= STRONG && bestOther >= bestSelected) return false

        val indianPicked = settings.languages.any { it != DjLanguage.ENGLISH && it != DjLanguage.ANY }
        if (!indianPicked) {
            val hint = maxOf(RomanizedIndicHints.indianScore(track), if (indianArtist) ARTIST_HINT else 0f)
            if (hint >= STRONG && hint > bestSelected) return false
        }
        return true
    }

    /** Evidence weight of "this singer has an Indian song in this mix". */
    private const val ARTIST_HINT = 0.7f
}
