package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.feedback.FeedbackSnapshot
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.EnergyArc
import com.aiqulabs.abcd.dj.core.prep.PreparedTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Puts analysed tracks in a playing order (brief §7.2, §7.3): after analysis, when BPM, key and energy
 * are known, so neighbouring tracks mix well and the session follows the mood's energy arc.
 *
 * Greedy: start with the track that best fits the arc's opening energy, then repeatedly pick the
 * remaining track with the best [score] against the previous one. v2 swaps this for a look-ahead beam
 * search (brief §7.4) behind the same function.
 */
class PlaylistOrderer(
    private val config: ScoringConfig = ScoringConfig(),
    private val feedback: FeedbackSnapshot = FeedbackSnapshot.EMPTY,
) {
    /** [tracks] in the order they should play. All input tracks are returned. */
    fun order(
        tracks: List<PreparedTrack>,
        settings: DjSessionSettings,
    ): List<PreparedTrack> {
        if (tracks.size <= 1) return tracks
        val energies = relativeEnergies(tracks)
        val arc = settings.effectiveEnergyArc
        val base = settings.mood.targetEnergy.toDouble()

        val remaining = tracks.indices.toMutableList()
        val ordered = ArrayList<Int>(tracks.size)

        fun target(position: Int) = arcTarget(arc, base, position.toDouble() / (tracks.size - 1))

        val first = remaining.maxBy { energyFit(energies[it], target(0)) }
        remaining.remove(first)
        ordered += first
        while (remaining.isNotEmpty()) {
            val previous = tracks[ordered.last()]
            val recent = ordered.takeLast(config.artistSpacing).map { tracks[it] }
            val position = ordered.size
            val best =
                remaining.maxBy { index ->
                    score(previous, tracks[index], energies[index], target(position), recent)
                }
            remaining.remove(best)
            ordered += best
        }
        return ordered.map { tracks[it] }
    }

    /** The score of playing [candidate] after [previous]. Higher is better. */
    fun score(
        previous: PreparedTrack,
        candidate: PreparedTrack,
        candidateEnergy: Double,
        targetEnergy: Double,
        recent: List<PreparedTrack> = emptyList(),
    ): Double {
        val sameArtist = recent.any { r -> r.track.artistKeys.any { it in candidate.track.artistKeys } }
        return config.bpmWeight * bpmCompat(previous.analysis, candidate.analysis) +
            config.keyWeight * keyCompat(previous.analysis, candidate.analysis) +
            config.energyWeight * energyFit(candidateEnergy, targetEnergy) +
            config.feedbackWeight * (feedback.pairBonus(previous.track.videoId, candidate.track.videoId) - feedback.trackPenalty(candidate.track.videoId)) -
            (if (sameArtist) config.sameArtistPenalty else 0.0)
    }

    /**
     * 1.0 when the tempos match within ±6% after allowing half or double time, falling to 0 at 12%.
     * Tempos that are not trusted score neutral: the mix then uses a technique that needs no beat-match.
     */
    fun bpmCompat(
        a: AnalysisResult,
        b: AnalysisResult,
    ): Double {
        if (a.bpmConfidence < config.minTrustedBpmConfidence || b.bpmConfidence < config.minTrustedBpmConfidence) return config.neutralScore
        if (a.bpm <= 0.0 || b.bpm <= 0.0) return config.neutralScore
        val ratio = b.bpm / a.bpm
        val difference = minOf(abs(ratio - 1.0), abs(ratio / 2.0 - 1.0), abs(ratio * 2.0 - 1.0))
        val span = config.maxBpmDifference - config.idealBpmDifference
        return (1.0 - (difference - config.idealBpmDifference) / span).coerceIn(0.0, 1.0)
    }

    /** Camelot wheel: same key 1.0, relative major/minor or one step on the wheel 0.9, two steps 0.5, else 0. */
    fun keyCompat(
        a: AnalysisResult,
        b: AnalysisResult,
    ): Double {
        if (a.key.confidence < config.minTrustedConfidence || b.key.confidence < config.minTrustedConfidence) return config.neutralScore
        val x = parseCamelot(a.key.camelot) ?: return config.neutralScore
        val y = parseCamelot(b.key.camelot) ?: return config.neutralScore
        val steps = min((x.first - y.first + 12) % 12, (y.first - x.first + 12) % 12)
        val sameMode = x.second == y.second
        return when {
            steps == 0 && sameMode -> 1.0
            steps == 0 -> 0.9
            steps == 1 && sameMode -> 0.9
            steps == 2 && sameMode -> 0.5
            steps == 1 -> 0.3
            else -> 0.0
        }
    }

    private fun energyFit(
        energy: Double,
        target: Double,
    ): Double = (1.0 - abs(energy - target)).coerceIn(0.0, 1.0)

    /** The energy the set should have [progress] (0..1) of the way through, for [arc]. */
    internal fun arcTarget(
        arc: EnergyArc,
        base: Double,
        progress: Double,
    ): Double =
        when (arc) {
            EnergyArc.STEADY -> {
                base
            }

            EnergyArc.BUILD_UP -> {
                LOW_START + (1.0 - LOW_START) * progress
            }

            EnergyArc.PEAK_AND_COOLDOWN -> {
                if (progress < PEAK_POSITION) {
                    LOW_START + (1.0 - LOW_START) * (progress / PEAK_POSITION)
                } else {
                    1.0 - 0.6 * ((progress - PEAK_POSITION) / (1.0 - PEAK_POSITION))
                }
            }

            EnergyArc.WAVE -> {
                0.5 + 0.35 * sin(2.0 * PI * WAVES * progress)
            }
        }

    /**
     * Each track's average bar loudness, stretched so the quietest track of this playlist is 0 and the
     * loudest 1. Absolute loudness varies between songs and sources; what matters is "higher or lower
     * than the others in this set".
     */
    private fun relativeEnergies(tracks: List<PreparedTrack>): List<Double> {
        val raw =
            tracks.map { t ->
                t.analysis.energyCurve
                    .takeIf { it.isNotEmpty() }
                    ?.average() ?: Double.NaN
            }
        val known = raw.filterNot { it.isNaN() }
        if (known.isEmpty()) return raw.map { 0.5 }
        val low = known.min()
        val high = known.max()
        val span = high - low
        return raw.map { value ->
            when {
                value.isNaN() -> 0.5
                span < 1e-9 -> 0.5
                else -> (value - low) / span
            }
        }
    }

    private fun parseCamelot(code: String): Pair<Int, Char>? {
        if (code.length < 2) return null
        val number = code.dropLast(1).toIntOrNull() ?: return null
        val letter = code.last().uppercaseChar()
        if (number !in 1..12 || (letter != 'A' && letter != 'B')) return null
        return number to letter
    }

    private companion object {
        const val PEAK_POSITION = 0.6

        /** Opening energy of a build-up, on the 0..1 scale where 0 is the quietest track of the set. */
        const val LOW_START = 0.05
        const val WAVES = 2.0
    }
}
