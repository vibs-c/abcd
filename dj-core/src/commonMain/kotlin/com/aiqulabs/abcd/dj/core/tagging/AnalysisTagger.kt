package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.model.DjMood

/**
 * Mood and energy class from the audio analysis (brief §6.2: "a rule-based mapping from BPM, energy, mode
 * (major/minor) and tags"). Tags are stored with source ANALYSIS and a modest confidence; user corrections
 * (v2) always win.
 *
 * Energy: tempo carries most of it (dance music is 115-135 BPM), loudness some (LUFS of the mastered track),
 * and how busy the bars are (the share of bars above the track's own average energy). A low BPM confidence
 * means the tempo says little, so those tags get a lower confidence.
 */
object AnalysisTagger {
    enum class EnergyClass { LOW, MEDIUM, HIGH }

    fun energyScore(a: AnalysisResult): Double {
        val tempo = ((a.bpm - 70.0) / 60.0).coerceIn(0.0, 1.0) // 70 BPM -> 0, 130+ -> 1
        val loud = ((a.lufs + 20.0) / 14.0).coerceIn(0.0, 1.0) // -20 LUFS -> 0, -6 -> 1
        val busy =
            a.energyCurve
                .takeIf { it.isNotEmpty() }
                ?.let { curve -> curve.count { it >= 0.6 }.toDouble() / curve.size } ?: 0.5
        return 0.5 * tempo + 0.3 * loud + 0.2 * busy
    }

    fun energyClass(a: AnalysisResult): EnergyClass {
        val score = energyScore(a)
        return when {
            score >= HIGH_FROM -> EnergyClass.HIGH
            score <= LOW_UNTIL -> EnergyClass.LOW
            else -> EnergyClass.MEDIUM
        }
    }

    /** Moods this track suits, strongest first: energy × mode. */
    fun moods(a: AnalysisResult): List<DjMood> {
        val major = a.key.mode == KeyMode.MAJOR
        return when (energyClass(a)) {
            EnergyClass.HIGH -> if (major) listOf(DjMood.PARTY, DjMood.WEDDING, DjMood.WORKOUT) else listOf(DjMood.WORKOUT, DjMood.PARTY)
            EnergyClass.MEDIUM -> if (major) listOf(DjMood.ROAD_TRIP, DjMood.ROMANTIC) else listOf(DjMood.ROAD_TRIP, DjMood.SAD)
            EnergyClass.LOW -> if (major) listOf(DjMood.CHILL, DjMood.ROMANTIC) else listOf(DjMood.SAD, DjMood.CHILL)
        }
    }

    fun tags(a: AnalysisResult): List<TrackTagValue> {
        val confidence = if (a.bpmConfidence >= 0.5) STRONG else WEAK
        val energy = TrackTagValue(TagType.ENERGY_CLASS, energyClass(a).name, TagSource.ANALYSIS, confidence)
        val moods = moods(a).mapIndexed { i, mood -> TrackTagValue(TagType.MOOD, mood.name, TagSource.ANALYSIS, confidence - i * 0.1f) }
        return listOf(energy) + moods
    }

    private const val HIGH_FROM = 0.62
    private const val LOW_UNTIL = 0.38
    private const val STRONG = 0.6f
    private const val WEAK = 0.4f
}
