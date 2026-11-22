package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.brain.PlaylistOrderer
import com.aiqulabs.abcd.dj.core.feedback.TransitionOutcome
import com.aiqulabs.abcd.dj.core.mix.MixPlanner
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import com.aiqulabs.abcd.dj.data.DjFeedbackRepository
import com.aiqulabs.abcd.dj.engine.DjEngineEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** The transition the live screen offers thumbs for. [rating] is null until the user rates it. */
data class DjRateableTransition(
    val serial: Int,
    val fromTitle: String,
    val toTitle: String,
    val rating: Boolean? = null,
)

/**
 * Records one DJ session's feedback in the DJ database (brief §6.3, stage 10): a `DjSession` row with the
 * tracks played, a `TransitionFeedback` row per transition (COMPLETED, SKIPPED when the user pressed Next,
 * replaced by THUMBS_UP/DOWN when rated), and per-track plays, skips, removals and bans.
 *
 * Writes go through one queue on the IO dispatcher, in order, so a thumb is always stored after its
 * transition. Failures are logged and never affect playback.
 */
internal class DjFeedbackRecorder(
    private val repository: DjFeedbackRepository,
    scope: CoroutineScope,
    tracks: List<PreparedTrack>,
    settings: DjSessionSettings,
) {
    private val analyses: Map<String, AnalysisResult> = tracks.associate { it.track.videoId to it.analysis }
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableLast = MutableStateFlow<DjRateableTransition?>(null)
    private var serial = 0

    // Touched only inside the write queue.
    private var sessionId: Long? = null
    private val played = ArrayList<String>()
    private var lastRowId: Long? = null
    private var lastOutcome = TransitionOutcome.COMPLETED

    /** The latest transition, for the live screen's thumbs. */
    val lastTransition: StateFlow<DjRateableTransition?> = mutableLast.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Could not save DJ feedback")
                }
            }
        }
        enqueue { sessionId = repository.startSession(settings) }
    }

    fun onEvent(event: DjEngineEvent) {
        when (event) {
            is DjEngineEvent.TrackStarted -> {
                val id = event.track.videoId
                enqueue {
                    repository.trackPlayed(id)
                    played += id
                    sessionId?.let { repository.updateSession(it, played.toList(), ended = false) }
                }
            }

            is DjEngineEvent.TransitionStarted -> {
                val from = event.from.videoId
                val to = event.to.videoId
                val outcome = if (event.byUser) TransitionOutcome.SKIPPED else TransitionOutcome.COMPLETED
                val technique = event.plan.technique
                val a = analyses[from]
                val b = analyses[to]
                // After half/double-time folding, as the planner matches tempos (171 vs 86 BPM is a 1 BPM gap).
                val bpmDelta = if (a != null && b != null) listOf(0.5, 1.0, 2.0).map { b.bpm * it - a.bpm }.minBy { kotlin.math.abs(it) } else 0.0
                val keyCompat = if (a != null && b != null) keyLabel(a, b) else UNKNOWN
                mutableLast.value = DjRateableTransition(++serial, event.from.title, event.to.title)
                enqueue {
                    if (event.byUser) repository.trackSkipped(from)
                    lastRowId = repository.recordTransition(from, to, technique, bpmDelta, keyCompat, outcome)
                    lastOutcome = outcome
                }
            }

            else -> Unit
        }
    }

    /** Thumbs on the latest transition; tapping the same thumb again takes the rating back. */
    fun rate(up: Boolean) {
        val current = mutableLast.value ?: return
        val rating = if (current.rating == up) null else up
        mutableLast.value = current.copy(rating = rating)
        enqueue {
            val row = lastRowId ?: return@enqueue
            val outcome =
                when (rating) {
                    true -> TransitionOutcome.THUMBS_UP
                    false -> TransitionOutcome.THUMBS_DOWN
                    null -> lastOutcome
                }
            repository.rateTransition(row, outcome)
        }
    }

    /** The user removed [videoId] from the queue; [neverPlay] also bans it from future mixes. */
    fun removed(
        videoId: String,
        neverPlay: Boolean,
    ) = enqueue {
        repository.trackRemoved(videoId)
        if (neverPlay) repository.setBanned(videoId, true)
    }

    /** Closes the session row and stops the queue once everything before it is written. */
    fun finish() {
        enqueue { sessionId?.let { repository.updateSession(it, played.toList(), ended = true) } }
        writes.close()
    }

    private fun enqueue(write: suspend () -> Unit) {
        writes.trySend(write)
    }

    private companion object {
        const val TAG = "DjFeedback"
        const val UNKNOWN = "UNKNOWN"
        const val KEY_TRUST = 0.5
        val planner = MixPlanner()
        val orderer = PlaylistOrderer()

        /** How the two keys sit on the Camelot wheel, for the transition row. */
        fun keyLabel(
            a: AnalysisResult,
            b: AnalysisResult,
        ): String =
            when {
                a.key.confidence < KEY_TRUST || b.key.confidence < KEY_TRUST -> UNKNOWN
                planner.keysClash(a, b) -> "CLASH"
                orderer.keyCompat(a, b) >= 0.9 -> "COMPATIBLE"
                else -> "NEAR"
            }
    }
}
