package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.model.Script

/** Kind of tag stored in `TrackTag` (brief §6.1). */
enum class TagType { LANGUAGE, MOOD, GENRE, ERA, ENERGY_CLASS }

/** Where a tag came from. USER always wins over the others. */
enum class TagSource { METADATA, ANALYSIS, LLM, USER }

/** One tag with its provenance, ready to sync to a shared server later (§6.4). */
data class TrackTagValue(
    val type: TagType,
    val value: String,
    val source: TagSource,
    val confidence: Float,
)

/**
 * Tags a track with language, mood, genre and so on (brief §8).
 *
 * v1 uses [MetadataTrackTagger] (rules over title/artist/album). v3 can plug in an on-device model
 * behind the same interface.
 */
interface TrackTagger {
    /** Returns tags for [track]. [lyrics] is plain lyric text when available. */
    suspend fun tag(
        track: DjTrack,
        lyrics: String? = null,
    ): List<TrackTagValue>
}

/**
 * v1 language tagging from metadata (§6.2).
 *
 * A non-Latin script in the title is strong evidence (Gurmukhi means Punjabi). Devanagari is shared
 * by Hindi and Marathi, so both get a tag with lower confidence. A language name in the title or
 * album ("Punjabi Hits") is weaker evidence. Latin-only titles say little, because Hindi film songs
 * are usually transliterated, so they get no language tag here.
 */
class MetadataTrackTagger : TrackTagger {
    override suspend fun tag(
        track: DjTrack,
        lyrics: String?,
    ): List<TrackTagValue> {
        val tags = LinkedHashMap<DjLanguage, Float>()
        val text = listOfNotNull(track.title, track.album, lyrics).joinToString(" ")
        val script = ScriptDetector.dominantScript(text)
        if (script != null && script != Script.LATIN) {
            val matching = DjLanguage.entries.filter { script in it.scripts }
            val confidence = if (matching.size == 1) SCRIPT_CONFIDENCE else SHARED_SCRIPT_CONFIDENCE
            matching.forEach { tags[it] = confidence }
        }
        val words = text.lowercase()
        DjLanguage.entries.forEach { language ->
            val term = language.searchTerm ?: return@forEach
            if (language != DjLanguage.ENGLISH && Regex("\\b$term\\b").containsMatchIn(words)) {
                tags[language] = maxOf(tags[language] ?: 0f, NAME_CONFIDENCE)
            }
        }
        return tags.map { (language, confidence) ->
            TrackTagValue(TagType.LANGUAGE, language.name, TagSource.METADATA, confidence)
        }
    }

    private companion object {
        const val SCRIPT_CONFIDENCE = 0.9f
        const val SHARED_SCRIPT_CONFIDENCE = 0.6f
        const val NAME_CONFIDENCE = 0.5f
    }
}
