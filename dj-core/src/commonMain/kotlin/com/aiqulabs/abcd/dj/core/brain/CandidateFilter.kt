package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjTrack

/**
 * Removes tracks that do not belong in a mix (brief §7.1): long talk segments, live or poor-quality
 * recordings, compilations, very long or very short tracks, banned tracks and duplicates.
 */
class CandidateFilter(
    private val minDurationMs: Long = 90_000,
    private val maxDurationMs: Long = 8 * 60_000,
) {
    /** Why a track was rejected, for the Lab screen and logs. */
    enum class Rejection { TOO_SHORT, TOO_LONG, NON_MUSIC_TITLE, BANNED, DUPLICATE }

    /** Returns null when [track] is acceptable, otherwise the first reason it was rejected. */
    fun rejectionFor(
        track: DjTrack,
        bannedVideoIds: Set<String> = emptySet(),
    ): Rejection? {
        if (track.videoId in bannedVideoIds) return Rejection.BANNED
        track.durationMs?.let { duration ->
            if (duration < minDurationMs) return Rejection.TOO_SHORT
            if (duration > maxDurationMs) return Rejection.TOO_LONG
        }
        val title = track.title.lowercase()
        if (LIVE_RECORDING.containsMatchIn(title) ||
            EXCLUDED_TITLE_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(title) }
        ) {
            return Rejection.NON_MUSIC_TITLE
        }
        return null
    }

    /**
     * Filters [tracks], keeping the first of any duplicate (same video id, or same normalised title
     * and artist, which catches re-uploads of the same song).
     */
    fun filter(
        tracks: List<DjTrack>,
        bannedVideoIds: Set<String> = emptySet(),
    ): List<DjTrack> {
        val seenIds = HashSet<String>()
        val seenSongs = HashSet<String>()
        return tracks.filter { track ->
            if (rejectionFor(track, bannedVideoIds) != null) return@filter false
            val songKey = songKey(track)
            seenIds.add(track.videoId) && seenSongs.add(songKey)
        }
    }

    private fun songKey(track: DjTrack): String {
        val title =
            track.title
                .lowercase()
                .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
                .replace(Regex("[^\\p{L}\\p{N} ]"), "")
                .trim()
        return title + "|" + track.artistKeys.sorted().joinToString(",")
    }

    private companion object {
        /**
         * Live recordings: "(Live)", "[Live]", "Live at/in/from…", "live performance/version".
         * A bare "live" is allowed so songs like "Live It Up" pass.
         */
        val LIVE_RECORDING =
            Regex("[(\\[]\\s*live\\b|\\blive\\s+(at|in|from|performance|version|session|concert)\\b")

        /** Title words that mark talk, live, compilation or non-song uploads. */
        val EXCLUDED_TITLE_WORDS =
            listOf(
                "concert",
                "podcast",
                "interview",
                "episode",
                "jukebox",
                "nonstop",
                "non stop",
                "non-stop",
                "full album",
                "audiobook",
                "reaction",
                "karaoke",
                "8d audio",
                "slowed",
                "reverb",
            )
    }
}
