package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.DjTrack

/**
 * Supplies candidate tracks for a playlist (brief §7.1). Implemented on each platform over
 * Metrolist's existing YouTube Music layer (search, radio, library), so dj-core stays platform-free.
 */
interface CandidateSource {
    /** Songs matching a search query. Returns an empty list on failure; never throws for network errors. */
    suspend fun search(query: String): List<DjTrack>

    /** "Up next" radio tracks seeded from [videoId]. Empty when unavailable. */
    suspend fun radio(videoId: String): List<DjTrack> = emptyList()

    /** The user's liked and library songs (stage 10). Empty when there are none. */
    suspend fun libraryTracks(): List<DjTrack> = emptyList()

    /** Songs from YouTube Music's own mood page for [mood] (the Home mood chips), if one matches. */
    suspend fun moodPage(mood: DjMood): List<DjTrack> = emptyList()
}
