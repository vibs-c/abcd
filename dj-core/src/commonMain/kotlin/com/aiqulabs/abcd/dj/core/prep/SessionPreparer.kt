package com.aiqulabs.abcd.dj.core.prep

import com.aiqulabs.abcd.dj.core.brain.PlaylistPlan
import com.aiqulabs.abcd.dj.core.model.DjTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Prepares a [PlaylistPlan] before playback (brief §5.1): every track needs an analysis. Tracks that
 * fail are dropped and replaced from the spares, in plan order, until the session has its core count of
 * ready tracks or the spares run out.
 *
 * Up to [parallelism] tracks are prepared at once, so one track's download overlaps another's analysis.
 * Results are collected in plan order, so the outcome does not depend on which finished first.
 */
class SessionPreparer(
    private val preparer: TrackPreparer,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    suspend fun prepare(
        plan: PlaylistPlan,
        onProgress: (PreparationProgress) -> Unit = {},
    ): PreparedSession {
        val total = minOf(plan.coreCount, plan.tracks.size)
        val ready = ArrayList<PreparedTrack>()
        val failed = ArrayList<DjTrack>()
        var alreadyKnown = 0
        var next = 0

        fun report(currentTitle: String?) = onProgress(PreparationProgress(ready.size, total, alreadyKnown, failed.size, currentTitle))
        report(plan.tracks.firstOrNull()?.title)

        while (ready.size < total && next < plan.tracks.size) {
            val batchSize = minOf(parallelism.coerceAtLeast(1), total - ready.size, plan.tracks.size - next)
            val batch = plan.tracks.subList(next, next + batchSize).toList()
            next += batchSize
            report(batch.first().title)

            val outcomes =
                coroutineScope {
                    batch.map { track -> async { track to prepareSafely(track) } }.awaitAll()
                }
            for ((track, outcome) in outcomes) {
                when (outcome) {
                    is TrackPreparation.Ready -> {
                        ready += PreparedTrack(track, outcome.analysis)
                        if (outcome.alreadyKnown) alreadyKnown++
                    }

                    is TrackPreparation.Failed -> {
                        failed += track
                    }
                }
            }
            report(plan.tracks.getOrNull(next)?.title)
        }
        return PreparedSession(plan, ready, failed)
    }

    /** An unexpected exception counts as a failed track; only cancellation stops the whole preparation. */
    private suspend fun prepareSafely(track: DjTrack): TrackPreparation =
        try {
            preparer.prepare(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackPreparation.Failed(e.message ?: e::class.simpleName.orEmpty())
        }

    companion object {
        const val DEFAULT_PARALLELISM = 2
    }
}
