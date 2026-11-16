package com.aiqulabs.abcd.dj.core.analysis

/**
 * Decoded audio, ready for analysis: one channel of samples in [-1, 1].
 *
 * Any sample rate is fine; the analyzer resamples internally.
 */
class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    val durationMs: Double get() = if (sampleRate > 0) 1000.0 * samples.size / sampleRate else 0.0
}

/** Where audio comes from: a file path or a platform URI string. */
data class AudioSource(
    val location: String,
)

/** Turns an audio file into mono PCM. Implemented per platform (Android: MediaExtractor + MediaCodec). */
interface PcmDecoder {
    /** Decodes [source] to mono. Throws if the file cannot be read or decoded. */
    suspend fun decodeToMono(source: AudioSource): PcmAudio
}

/**
 * Finds tempo, beat grid, key, loudness and structure in audio (brief §5.4).
 *
 * The native implementation lives in `dj-analysis`; the interface is here, free of platform types,
 * so another implementation (a server, another platform) can replace it without touching callers.
 */
interface AudioAnalyzer {
    /** Version stamped on every result. Bump when algorithms change so stored results can be redone. */
    val analysisVersion: Int

    /** Analyses [pcm]. Heavy: call off the main thread. Throws if the audio cannot be analysed. */
    suspend fun analyze(pcm: PcmAudio): AnalysisResult
}
