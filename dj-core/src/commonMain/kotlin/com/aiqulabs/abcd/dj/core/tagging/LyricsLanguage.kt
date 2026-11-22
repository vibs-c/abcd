package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.Script

/**
 * The language of a song from its lyrics (brief §6.2, stage 12). Lyrics are much stronger evidence than a title:
 * a Hindi film song with an English title ("Party All Night", "Love Me Thoda Aur") still has Hindi lyrics.
 *
 * - Script: Devanagari lyrics → Hindi, Gurmukhi → Punjabi, Tamil → Tamil, and so on.
 * - Transliterated lyrics: distinctive Hindi/Urdu and Punjabi words (the same lists as for titles).
 * - English: many common English words and almost no Hindi ones.
 *
 * A song with Hindi verses and an English hook is tagged Hindi (strong) and English (weak), so an English-only
 * mix leaves it out. Tags have source [TagSource.LYRICS].
 */
object LyricsLanguage {
    fun tags(lyrics: String): List<TrackTagValue> {
        if (lyrics.isBlank()) return emptyList()
        val result = LinkedHashMap<DjLanguage, Float>()
        val script = ScriptDetector.dominantScript(lyrics)
        if (script != null && script != Script.LATIN) {
            val matching = DjLanguage.entries.filter { script in it.scripts }
            // Devanagari is shared by Hindi and Marathi; in song lyrics it is Hindi far more often.
            val language = if (script == Script.DEVANAGARI) DjLanguage.HINDI else matching.singleOrNull()
            if (language != null) result[language] = SCRIPT_CONFIDENCE else matching.forEach { result[it] = SHARED_CONFIDENCE }
        }
        val words = RomanizedIndicHints.wordsIn(lyrics)
        val hindi = words.count { it in RomanizedIndicHints.HINDI_WORDS }
        val punjabi = words.count { it in RomanizedIndicHints.PUNJABI_WORDS }
        val english = words.count { it in ENGLISH_WORDS }
        if (punjabi >= MIN_PUNJABI_WORDS && hindi + punjabi >= MIN_INDIC_WORDS) result.merge(DjLanguage.PUNJABI, WORDS_CONFIDENCE, ::maxOf)
        if (hindi >= MIN_INDIC_WORDS) result.merge(DjLanguage.HINDI, WORDS_CONFIDENCE, ::maxOf)
        if (english >= MIN_ENGLISH_WORDS) {
            val indian = result.keys.any { it != DjLanguage.ENGLISH }
            result[DjLanguage.ENGLISH] = if (indian || hindi + punjabi >= 2) MIXED_ENGLISH_CONFIDENCE else ENGLISH_CONFIDENCE
        }
        return result.map { (language, confidence) -> TrackTagValue(TagType.LANGUAGE, language.name, TagSource.LYRICS, confidence) }
    }

    private const val SCRIPT_CONFIDENCE = 0.9f
    private const val SHARED_CONFIDENCE = 0.6f
    private const val WORDS_CONFIDENCE = 0.85f
    private const val ENGLISH_CONFIDENCE = 0.75f
    private const val MIXED_ENGLISH_CONFIDENCE = 0.4f
    private const val MIN_INDIC_WORDS = 4
    private const val MIN_PUNJABI_WORDS = 2
    private const val MIN_ENGLISH_WORDS = 8

    /** Common English words that are not Hindi words too. */
    private val ENGLISH_WORDS =
        setOf(
            "the", "you", "and", "your", "that", "this", "what", "when", "with", "for", "but", "don't", "dont", "can't",
            "cant", "it's", "its", "i'm", "im", "just", "know", "love", "baby", "like", "all", "got", "get", "never",
            "feel", "want", "need", "tonight", "night", "my", "me", "we", "they", "she", "he", "is", "are", "was", "be",
            "been", "have", "has", "will", "would", "could", "should", "there", "where", "how", "why", "who", "come",
            "go", "say", "said", "take", "make", "made", "right", "back", "down", "out", "up", "now", "time", "way",
            "heart", "life", "world", "every", "never", "always", "gonna", "wanna", "yeah", "oh", "into", "from", "of",
        )
}
