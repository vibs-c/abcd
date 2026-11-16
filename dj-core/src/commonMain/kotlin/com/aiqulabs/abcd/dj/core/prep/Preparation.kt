package com.aiqulabs.abcd.dj.core.prep

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.brain.PlaylistPlan
import com.aiqulabs.abcd.dj.core.model.DjTrack

/** What happened when one track was prepared for a session (brief §5.1). */
sealed interface TrackPreparation {
    /**
     * The track has an up-to-date analysis. [alreadyKnown] is true when it was found in the database, so
     * no audio had to be fetched or analysed (repeat sessions are fast because of this).
     */
    data class Ready(
        val analysis: AnalysisResult,
        val alreadyKnown: Boolean,
    ) : TrackPreparation

    /** The track cannot be used (unavailable stream, decode error, no space...). [reason] is technical, for bug reports. */
    data class Failed(
        val reason: String,
    ) : TrackPreparation
}

/**
 * Makes one track ready: finds or fetches its audio, analyses it and saves the result. Implemented on
 * the platform (Android: `DjTrackPreparer`); the preparation logic here only needs the outcome.
 */
interface TrackPreparer {
    /** Must not throw for ordinary failures; return [TrackPreparation.Failed]. Cancellation must propagate. */
    suspend fun prepare(track: DjTrack): TrackPreparation
}

/**
 * Progress of "Preparing your mix". The screen shows "Analysing [current] of [total]".
 *
 * [ready] counts tracks that are done (including ones that were already analysed), [total] is how many
 * the session needs. Failed tracks are replaced by spares, so [total] stays the same.
 */
data class PreparationProgress(
    val ready: Int,
    val total: Int,
    val alreadyKnown: Int = 0,
    val failed: Int = 0,
    val currentTitle: String? = null,
    /** Why the most recent failed track failed (technical; shown small so the owner can report it). */
    val lastFailureReason: String? = null,
) {
    /** The 1-based number of the track being worked on, never above [total]. */
    val current: Int get() = (ready + 1).coerceAtMost(total.coerceAtLeast(1))

    /** 0..1 for the progress bar. */
    val fraction: Float get() = if (total <= 0) 0f else (ready.toFloat() / total).coerceIn(0f, 1f)
}

/** A track together with its analysis, ready to be ordered and played. */
data class PreparedTrack(
    val track: DjTrack,
    val analysis: AnalysisResult,
)

/**
 * The outcome of preparing a session: the tracks that are ready (at most the plan's core count), the ones
 * that had to be dropped, and whether the session is short because the spares ran out.
 */
data class PreparedSession(
    val plan: PlaylistPlan,
    val tracks: List<PreparedTrack>,
    val failed: List<DjTrack>,
    /** Why the last failed track failed, if any (technical). */
    val lastFailureReason: String? = null,
) {
    /** True when fewer tracks than the session needs could be prepared. */
    val isShort: Boolean get() = tracks.size < plan.coreCount
}
