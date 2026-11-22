package com.aiqulabs.abcd.dj.core.feedback

import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique

/**
 * What the user's feedback says, read once when a mix is built (brief §6.3, v1 "simple, explainable
 * adjustment"): banned and often-removed or often-skipped tracks sink, and song pairs and techniques with
 * thumbs up are preferred.
 */
data class FeedbackSnapshot(
    val banned: Set<String> = emptySet(),
    /** Times each track was removed from a DJ queue. */
    val removeCounts: Map<String, Int> = emptyMap(),
    /** Times each track was skipped in a DJ mix. */
    val skipCounts: Map<String, Int> = emptyMap(),
    /** Thumbs up minus thumbs down for each (from, to) pair. */
    val pairScores: Map<Pair<String, String>, Int> = emptyMap(),
    /** Thumbs up minus thumbs down per technique, over all pairs. */
    val techniqueScores: Map<TransitionTechnique, Int> = emptyMap(),
) {
    /** 0 for a track nobody minded, growing with removals (strong signal) and skips (weak), capped at 2. */
    fun trackPenalty(videoId: String): Double =
        ((removeCounts[videoId] ?: 0) * REMOVE_PENALTY + (skipCounts[videoId] ?: 0) * SKIP_PENALTY).coerceAtMost(MAX_PENALTY)

    /** -1..1 for playing [to] right after [from]: thumbs on this exact pair. */
    fun pairBonus(
        from: String,
        to: String,
    ): Double = ((pairScores[from to to] ?: 0).coerceIn(-PAIR_CAP, PAIR_CAP)).toDouble() / PAIR_CAP

    fun techniqueScore(technique: TransitionTechnique): Int = techniqueScores[technique] ?: 0

    companion object {
        val EMPTY = FeedbackSnapshot()
        private const val REMOVE_PENALTY = 0.5
        private const val SKIP_PENALTY = 0.15
        private const val MAX_PENALTY = 2.0
        private const val PAIR_CAP = 2
    }
}
