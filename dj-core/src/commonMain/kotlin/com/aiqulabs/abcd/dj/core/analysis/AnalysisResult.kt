package com.aiqulabs.abcd.dj.core.analysis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Kind of song section found by the analysis (brief §6.1 `TrackSection.type`). */
@Serializable
enum class SectionType { INTRO, VERSE, PRE_CHORUS, CHORUS, BRIDGE, OUTRO }

/** Evidence behind a chorus candidate (brief §5.5). MOST_REPLAYED is reserved for v2. */
@Serializable
enum class ChorusSignal { REPETITION, LYRICS, ENERGY, MOST_REPLAYED }

@Serializable
enum class KeyMode {
    @SerialName("major")
    MAJOR,

    @SerialName("minor")
    MINOR,
}

/** Musical key. [pitchClass] counts semitones above C (0 = C, 9 = A). */
@Serializable
data class KeyResult(
    val root: String,
    val mode: KeyMode,
    /** Camelot wheel code, for example "8A" (A minor) or "8B" (C major). */
    val camelot: String,
    val pitchClass: Int,
    val confidence: Double,
)

@Serializable
data class SectionResult(
    val type: SectionType,
    val startMs: Double,
    val endMs: Double,
    val confidence: Double,
)

/** One place in the song that may be the chorus. Bars are 0-based; [endBar] is exclusive. */
@Serializable
data class ChorusCandidate(
    val startMs: Double,
    val endMs: Double,
    val startBar: Int,
    val endBar: Int,
    /** 0..1: how likely this is a chorus. */
    val score: Double,
    val signals: List<ChorusSignal> = emptyList(),
    val isMain: Boolean = false,
) {
    val durationMs: Double get() = endMs - startMs
}

/**
 * Everything the native analysis finds out about one track (brief §5.3).
 *
 * All times are milliseconds from the start of the track. The result is versioned
 * ([analysisVersion]) so tracks can be re-analysed when the algorithms improve, and it is what the
 * database stores and what a shared server could exchange later (brief §6.4).
 */
@Serializable
data class AnalysisResult(
    val analysisVersion: Int,
    val durationMs: Double,
    val analysisSampleRate: Int = 44_100,
    val bpm: Double,
    /** 0..1. Low for tracks with rubato or a weak beat; store it, never hide it (brief §5.3). */
    val bpmConfidence: Double,
    /** Essentia's own scale, 0..5.32. */
    val bpmConfidenceRaw: Double = 0.0,
    val beatsMs: List<Double> = emptyList(),
    val beatsPerBar: Int = 4,
    val downbeatConfidence: Double = 0.0,
    /** First beat of every bar. */
    val barStartsMs: List<Double> = emptyList(),
    /** Starts of 8-bar phrases; transitions begin on these. */
    val phraseStartsMs: List<Double> = emptyList(),
    val key: KeyResult,
    /** Approximate integrated loudness of the mono downmix, in LUFS. */
    val lufs: Double,
    /** Loudness per bar, 0..1. */
    val energyCurve: List<Double> = emptyList(),
    /** Overview of the waveform for drawing, 0..255 per slice. */
    val waveformPeaks: List<Int> = emptyList(),
    val introMixableEndMs: Double = 0.0,
    val outroMixableStartMs: Double = 0.0,
    val sections: List<SectionResult> = emptyList(),
    val chorusCandidates: List<ChorusCandidate> = emptyList(),
) {
    /** The chorus the DJ uses for chorus jumps and Chorus Hits, if any. */
    val mainChorus: ChorusCandidate? get() = chorusCandidates.firstOrNull { it.isMain }

    /** Tempo is trustworthy enough for beat-matched blends (brief §9.2: both grids confident). */
    val hasConfidentGrid: Boolean get() = bpmConfidence >= CONFIDENT_GRID && barStartsMs.size >= MIN_BARS

    companion object {
        const val CONFIDENT_GRID = 0.5
        const val MIN_BARS = 8

        private val json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            }

        /** Parses the JSON produced by the native analyzer ("wire format"). */
        fun fromWireJson(text: String): AnalysisResult = json.decodeFromString(serializer(), text)
    }
}
