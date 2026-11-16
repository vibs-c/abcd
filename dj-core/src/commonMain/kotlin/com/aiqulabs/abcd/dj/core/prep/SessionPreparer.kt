package com.aiqulabs.abcd.dj.core.prep

import com.aiqulabs.abcd.dj.core.brain.PlaylistPlan
import com.aiqulabs.abcd.dj.core.model.DjTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Prepares a [PlaylistPlan] before playback (brief §5.1): every track needs an analysis. Tracks that
 * fail are dropped and replaced from the spares, in plan order, until the session has its core count of
 * ready tracks or the spares run out.
 *
 * Up to [parallelism] tracks are in flight at once, as a rolling window: as soon as one finishes the next
 * one starts, so a download always overlaps the current analysis and one slow track never holds up the
 * others. Progress is reported after every single track. A track is only started while it can still be
 * needed (ready + in flight < core count), so spares stay untouched when nothing fails, and results are
 * returned in plan order: the outcome does not depend on which track finished first.
 */
class SessionPreparer(
    private val preparer: TrackPreparer,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {
    suspend fun prepare(
        plan: PlaylistPlan,
        onProgress: (PreparationProgress) -> Unit = {},
    ): PreparedSession =
        coroutineScope {
            val total = minOf(plan.coreCount, plan.tracks.size)
            val window = parallelism.coerceAtLeast(1)
            val ready = ArrayList<IndexedValue<PreparedTrack>>()
            val failed = ArrayList<IndexedValue<DjTrack>>()
            val inFlight = ArrayList<Int>()
            val finished = Channel<Pair<Int, TrackPreparation>>(Channel.UNLIMITED)
            var alreadyKnown = 0
            var lastFailure: String? = null
            var next = 0

            fun report() {
                val working = inFlight.minOrNull() ?: next
                onProgress(PreparationProgress(ready.size, total, alreadyKnown, failed.size, plan.tracks.getOrNull(working)?.title, lastFailure))
            }

            fun startMore() {
                while (inFlight.size < window && ready.size + inFlight.size < total && next < plan.tracks.size) {
                    val index = next++
                    inFlight += index
                    launch { finished.send(index to prepareSafely(plan.tracks[index])) }
                }
            }

            startMore()
            report()
            while (inFlight.isNotEmpty()) {
                val (index, outcome) = finished.receive()
                inFlight.remove(index)
                val track = plan.tracks[index]
                when (outcome) {
                    is TrackPreparation.Ready -> {
                        ready += IndexedValue(index, PreparedTrack(track, outcome.analysis))
                        if (outcome.alreadyKnown) alreadyKnown++
                    }

                    is TrackPreparation.Failed -> {
                        failed += IndexedValue(index, track)
                        lastFailure = outcome.reason
                    }
                }
                startMore()
                report()
            }
            PreparedSession(
                plan,
                ready.sortedBy { it.index }.map { it.value },
                failed.sortedBy { it.index }.map { it.value },
                lastFailure,
            )
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
