package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjTrack

/**
 * Tags from the title (via [base]) plus everything stored for the song in the DJ database: the language found in
 * its lyrics at an earlier analysis and the user's own corrections (stage 12). A stored USER tag of a type
 * replaces every other tag of that type.
 */
class StoredTagTagger(
    private val base: TrackTagger,
    private val stored: suspend (videoId: String) -> List<TrackTagValue>,
) : TrackTagger {
    override suspend fun tag(
        track: DjTrack,
        lyrics: String?,
    ): List<TrackTagValue> {
        val saved = stored(track.videoId)
        val userTypes = saved.filter { it.source == TagSource.USER }.map { it.type }.toSet()
        val all = base.tag(track, lyrics) + saved.filter { it.source != TagSource.METADATA }
        return all.filter { it.type !in userTypes || it.source == TagSource.USER }
    }
}
