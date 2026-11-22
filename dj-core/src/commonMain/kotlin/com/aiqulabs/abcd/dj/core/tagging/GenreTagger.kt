package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.model.DjGenre
import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjTrack

/**
 * A first guess at a song's type (genre, brief §6.2) from its title, album, singers, language and tempo. YouTube
 * Music gives no genre, so these are hints with modest confidence; the user can correct them in the Lab (USER
 * tags win). Source METADATA.
 */
object GenreTagger {
    fun tags(
        track: DjTrack,
        languages: Set<DjLanguage>,
        analysis: AnalysisResult?,
    ): List<TrackTagValue> {
        val text = (listOfNotNull(track.title, track.album) + track.artists).joinToString(" ").lowercase()
        val genres = LinkedHashMap<DjGenre, Float>()
        fun add(
            genre: DjGenre,
            confidence: Float,
        ) {
            genres.merge(genre, confidence, ::maxOf)
        }
        if (KEYWORDS_LOFI.any { it in text }) add(DjGenre.LOFI, 0.8f)
        if (KEYWORDS_SUFI.any { it in text }) add(DjGenre.SUFI, 0.7f)
        if (KEYWORDS_HIP_HOP.any { it in text }) add(DjGenre.HIP_HOP, 0.6f)
        if (KEYWORDS_EDM.any { it in text }) add(DjGenre.EDM, 0.5f)
        if (KEYWORDS_ROCK.any { it in text }) add(DjGenre.ROCK, 0.5f)
        if (KEYWORDS_INDIE.any { it in text }) add(DjGenre.INDIE, 0.4f)
        if (DjLanguage.PUNJABI in languages) add(DjGenre.PUNJABI_POP, 0.6f)
        val indian = languages.any { it != DjLanguage.ENGLISH && it != DjLanguage.ANY }
        val film = RomanizedIndicHints.indianScore(track) >= RomanizedIndicHints.FILM_SCORE && "from" in text
        if (DjLanguage.HINDI in languages || film) add(DjGenre.BOLLYWOOD, if (film) 0.7f else 0.5f)
        if (indian && DjLanguage.HINDI !in languages && DjLanguage.PUNJABI !in languages) add(DjGenre.REGIONAL_FOLK, 0.3f)
        if (analysis != null && genres.isEmpty()) {
            val bpm = analysis.bpm
            when {
                analysis.bpmConfidence >= 0.5 && bpm in 118.0..132.0 && AnalysisTagger.energyClass(analysis) == AnalysisTagger.EnergyClass.HIGH ->
                    add(DjGenre.HOUSE, 0.3f)
                !indian -> add(DjGenre.POP, 0.3f)
            }
        }
        return genres.map { (genre, confidence) -> TrackTagValue(TagType.GENRE, genre.name, TagSource.METADATA, confidence) }
    }

    private val KEYWORDS_LOFI = listOf("lofi", "lo-fi", "lo fi", "slowed", "reverb")
    private val KEYWORDS_SUFI = listOf("qawwali", "sufi", "nusrat fateh", "rahat fateh")
    private val KEYWORDS_HIP_HOP = listOf(" rap", "hip hop", "hip-hop", "badshah", "raftaar", "divine", "emiway", "eminem", "drake", "kendrick", "travis scott", "honey singh")
    private val KEYWORDS_EDM = listOf("remix", "edm", "dj snake", "martin garrix", "avicii", "david guetta", "calvin harris", "marshmello", "nucleya", "club mix")
    private val KEYWORDS_ROCK = listOf("rock", "linkin park", "coldplay", "imagine dragons", "queen", "nirvana")
    private val KEYWORDS_INDIE = listOf("acoustic", "unplugged", "prateek kuhad", "anuv jain", "ritviz")
}
