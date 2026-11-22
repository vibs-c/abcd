package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.tagging.RomanizedIndicHints
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagger
import kotlin.random.Random

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
    private val random: Random = Random.Default,
) {
    suspend fun build(
        settings: DjSessionSettings,
        bannedVideoIds: Set<String> = emptySet(),
    ): PlaylistPlan {
        val normalized = settings.normalized()
        val target = PlaylistSizer.targetTrackCount(normalized)
        val core = PlaylistSizer.coreTrackCount(normalized)

        val perQuery = SearchQueryBuilder.queries(normalized).map { source.search(it) }
        // Mood pages and the user's own songs are not chosen by language, so they need positive evidence of a
        // selected language to get in (brief 7.1; stage 10). The user's songs are capped so searches still lead.
        val moodPage = source.moodPage(normalized.mood).filter { provenLanguage(it, normalized) }
        val library = source.libraryTracks().filter { provenLanguage(it, normalized) }.shuffled(random).take(maxOf(2, target / LIBRARY_SHARE))
        val picked = ArrayList<DjTrack>()
        val usedIds = HashSet<String>()
        // Singers with a clearly Indian song among the candidates: their other songs stay out of a mix with no
        // Indian language selected (stage 12; their English-titled Hindi songs gave themselves away nowhere else).
        val indianArtists = HashSet<String>()
        noteIndianArtists(perQuery.flatten() + moodPage + library, indianArtists)
        addInOrder(interleave(perQuery + listOf(moodPage, library)), normalized, bannedVideoIds, picked, usedIds, target, indianArtists)

        var seedIndex = 0
        while (picked.size < target && seedIndex < minOf(picked.size, maxRadioSeeds)) {
            val radio = source.radio(picked[seedIndex].videoId)
            noteIndianArtists(radio, indianArtists)
            addInOrder(radio, normalized, bannedVideoIds, picked, usedIds, target, indianArtists)
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
        indianArtists: Set<String>,
    ) {
        val accepted =
            filter
                .filter(candidates.filter { it.videoId !in usedIds }, banned)
                .filter { matchesLanguage(it, settings, indianArtists) }
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
    /** The shared language rule ([LanguageGate]) with this mix's known Indian singers. */
    private suspend fun matchesLanguage(
        track: DjTrack,
        settings: DjSessionSettings,
        indianArtists: Set<String>,
    ): Boolean {
        if (settings.anyLanguage) return true
        return LanguageGate.allows(track, tagger.tag(track), settings, indianArtist = track.artistKeys.any { it in indianArtists })
    }

    private suspend fun noteIndianArtists(
        candidates: List<DjTrack>,
        into: MutableSet<String>,
    ) {
        for (track in candidates) {
            val tags = tagger.tag(track).filter { it.type == TagType.LANGUAGE }
            val indianTag = tags.any { it.value != DjLanguage.ENGLISH.name && it.confidence >= STRONG_LANGUAGE_EVIDENCE }
            val englishByUser = tags.any { it.source == TagSource.USER && it.value == DjLanguage.ENGLISH.name }
            if (!englishByUser && (indianTag || RomanizedIndicHints.indianScore(track) >= STRONG_LANGUAGE_EVIDENCE)) {
                // Only solo songs: a collaboration does not make every artist on it an Indian singer.
                track.artistKeys.singleOrNull()?.let(into::add)
            }
        }
    }

    /**
     * Stricter than [matchesLanguage], for sources that are not language-targeted: the track must show a selected
     * language (a tag), or, for English, Latin script with no Indian evidence at all.
     */
    private suspend fun provenLanguage(
        track: DjTrack,
        settings: DjSessionSettings,
    ): Boolean {
        if (settings.anyLanguage) return true
        val selected = settings.languages.map(DjLanguage::name).toSet()
        val all = tagger.tag(track)
        if (!LanguageGate.allows(track, all, settings)) return false
        val tags = all.filter { it.type == TagType.LANGUAGE }
        if (tags.any { it.value in selected }) return true
        return DjLanguage.ENGLISH in settings.languages && tags.isEmpty() && RomanizedIndicHints.indianScore(track) < WEAK_INDIAN_EVIDENCE
    }

    private companion object {
        const val WEAK_INDIAN_EVIDENCE = 0.3f
        const val LIBRARY_SHARE = 4
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
