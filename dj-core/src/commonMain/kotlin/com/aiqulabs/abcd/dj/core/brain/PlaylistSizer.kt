package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings

/**
 * Estimates how many tracks fill a session (brief §7.2).
 *
 * Each mix style has a typical time per track (Chorus Hits uses short segments, Radio nearly full
 * songs). Spare tracks cover drops from failed analysis and user removals.
 */
object PlaylistSizer {
    const val MIN_SPARE_TRACKS = 3

    /** Tracks needed to fill the session, without spares. */
    fun coreTrackCount(settings: DjSessionSettings): Int {
        val sessionSeconds = settings.sessionLengthMinutes * 60
        val perTrack = settings.mixStyle.averageTrackSeconds
        return (sessionSeconds + perTrack - 1) / perTrack
    }

    /** Spare tracks: at least [MIN_SPARE_TRACKS], or 10% of the core count. */
    fun spareTrackCount(coreCount: Int): Int = maxOf(MIN_SPARE_TRACKS, (coreCount + 9) / 10)

    /** Total tracks to prepare and pre-analyse. */
    fun targetTrackCount(settings: DjSessionSettings): Int {
        val core = coreTrackCount(settings)
        return core + spareTrackCount(core)
    }
}
