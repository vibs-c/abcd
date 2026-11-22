package com.aiqulabs.abcd.dj.core.brain

/**
 * Every weight and threshold of the next-track score in one place (brief §7.3):
 * `score = w1·vibeMatch + w2·bpmCompat + w3·keyCompat + w4·energyFit + w5·freshness + w6·feedback − penalties`.
 *
 * v1 orders a finished, already-matching playlist, so vibe and freshness weights default to 0.
 * They exist now so v2 (look-ahead planning, feedback learning) only changes numbers, not structure.
 */
data class ScoringConfig(
    val vibeWeight: Double = 0.0,
    /** Highest weight: neighbours within ±6% can be beatmatched, which makes the transition seamless (stage 9). */
    val bpmWeight: Double = 1.6,
    val keyWeight: Double = 0.8,
    val energyWeight: Double = 1.2,
    val freshnessWeight: Double = 0.0,
    /** Thumbs on a pair and removals/skips of a track (stage 10, brief 6.3). */
    val feedbackWeight: Double = 0.8,
    /** Subtracted when the same artist played within the last [artistSpacing] tracks. */
    val sameArtistPenalty: Double = 2.0,
    val artistSpacing: Int = 3,
    /** A BPM difference up to this fraction (after half/double-time) is a perfect match: brief says ±6%. */
    val idealBpmDifference: Double = 0.06,
    /** From this fraction up, the BPM score is 0 (beyond beatmatching range the gap size hardly matters). */
    val maxBpmDifference: Double = 0.12,
    /** Below this confidence a key is not trusted and scores neutral. */
    val minTrustedConfidence: Double = 0.5,
    /** Below this BPM confidence the tempo scores neutral; same threshold as the beatmatch decision (`MixPlanner`). */
    val minTrustedBpmConfidence: Double = 0.25,
    /** Score given when tempo or key is not trusted: neither a reward nor a penalty. */
    val neutralScore: Double = 0.5,
)
