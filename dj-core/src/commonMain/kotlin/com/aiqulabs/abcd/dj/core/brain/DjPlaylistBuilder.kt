package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.tagging.RomanizedIndicHints
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagger

/**
 * The tracks prepared for a session. [tracks] holds [coreCount] tracks that fill the session
 * followed by spares. Order is provisional: the final order is chosen after analysis, when BPM,
 * key and energy are known (brief §7.2).
 */
data class PlaylistPlan(
    val settings: DjSessionSettings,
    val tracks: List<DjTrack>,
    val coreCount: Int,
    val targetCount: Int,
) {
    /** True when the sources could not supply enough tracks for the whole session. */
    val isShort: Boolean get() = tracks.size < targetCount
}

/**
 * Builds the candidate playlist for a session (brief §7.1–7.2).
 *
 * 1. Search with language × mood queries.
 * 2. Drop non-music, banned, duplicate and wrong-language tracks.
 * 3. Interleave the query results so one query does not dominate.
 * 4. Keep the same artist at least [artistSpacing] tracks apart.
 * 5. Top up from YouTube Music radio seeded with picked tracks if still short.
 */
class DjPlaylistBuilder(
    private val source: CandidateSource,
    private val tagger: TrackTagger,
    private val filter: CandidateFilter = CandidateFilter(),
    private val artistSpacing: Int = 3,
    private val maxRadioSeeds: Int = 5,
) {
    suspend fun build(
        settings: DjSessionSettings,
        bannedVideoIds: Set<String> = emptySet(),
    ): PlaylistPlan {
        val normalized = settings.normalized()
        val target = PlaylistSizer.targetTrackCount(normalized)
        val core = PlaylistSizer.coreTrackCount(normalized)

        val perQuery = SearchQueryBuilder.queries(normalized).map { source.search(it) }
        val picked = ArrayList<DjTrack>()
        val usedIds = HashSet<String>()
        addInOrder(interleave(perQuery), normalized, bannedVideoIds, picked, usedIds, target)

        var seedIndex = 0
        while (picked.size < target && seedIndex < minOf(picked.size, maxRadioSeeds)) {
            val radio = source.radio(picked[seedIndex].videoId)
            addInOrder(radio, normalized, bannedVideoIds, picked, usedIds, target)
            seedIndex++
        }
        return PlaylistPlan(normalized, picked, core, target)
    }

    private suspend fun addInOrder(
        candidates: List<DjTrack>,
        settings: DjSessionSettings,
        banned: Set<String>,
        picked: MutableList<DjTrack>,
        usedIds: MutableSet<String>,
        target: Int,
    ) {
        val accepted =
            filter
                .filter(candidates.filter { it.videoId !in usedIds }, banned)
                .filter { matchesLanguage(it, settings) }
        val deferred = ArrayList<DjTrack>()
        for (track in accepted) {
            if (picked.size >= target) return
            if (isAudibleRepeatOfPicked(track, picked)) continue
            if (sharesArtistWithRecent(track, picked)) {
                deferred += track
                continue
            }
            picked += track
            usedIds += track.videoId
            retryDeferred(deferred, picked, usedIds, target)
        }
        retryDeferred(deferred, picked, usedIds, target)
    }

    private fun retryDeferred(
        deferred: MutableList<DjTrack>,
        picked: MutableList<DjTrack>,
        usedIds: MutableSet<String>,
        target: Int,
    ) {
        val iterator = deferred.iterator()
        while (iterator.hasNext() && picked.size < target) {
            val track = iterator.next()
            if (!sharesArtistWithRecent(track, picked)) {
                picked += track
                usedIds += track.videoId
                iterator.remove()
            }
        }
    }

    private fun isAudibleRepeatOfPicked(
        track: DjTrack,
        picked: List<DjTrack>,
    ): Boolean = filter.filter(picked + track).size == picked.size

    private fun sharesArtistWithRecent(
        track: DjTrack,
        picked: List<DjTrack>,
    ): Boolean {
        val keys = track.artistKeys
        if (keys.isEmpty()) return false
        return picked.takeLast(artistSpacing).any { recent -> recent.artistKeys.any { it in keys } }
    }

    /**
     * Rejects a track only on strong evidence of another language (for example Tamil script when
     * the user picked Hindi). Transliterated Latin titles carry no language tag, so they pass.
     */
    private suspend fun matchesLanguage(
        track: DjTrack,
        settings: DjSessionSettings,
    ): Boolean {
        if (settings.anyLanguage) return true
        // No Indian language picked (e.g. English only): Latin-script titles are not enough, because most Hindi
        // songs have transliterated titles. Drop songs with Indian evidence (film marker, Hindi/Punjabi words,
        // Indian artists).
        val indianPicked = settings.languages.any { it != DjLanguage.ENGLISH && it != DjLanguage.ANY }
        if (!indianPicked && RomanizedIndicHints.indianScore(track) >= STRONG_LANGUAGE_EVIDENCE) return false
        val languageTags = tagger.tag(track).filter { it.type == TagType.LANGUAGE }
        if (languageTags.isEmpty()) return true
        val selected = settings.languages.map(DjLanguage::name).toSet()
        if (languageTags.any { it.value in selected }) return true
        return languageTags.none { it.confidence >= STRONG_LANGUAGE_EVIDENCE }
    }

    private companion object {
        const val STRONG_LANGUAGE_EVIDENCE = 0.6f
    }
}

/** Round-robin merge: first item of each list, then the second of each, and so on. */
internal fun <T> interleave(lists: List<List<T>>): List<T> {
    val result = ArrayList<T>(lists.sumOf { it.size })
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) {
        for (list in lists) list.getOrNull(i)?.let(result::add)
    }
    return result
}
