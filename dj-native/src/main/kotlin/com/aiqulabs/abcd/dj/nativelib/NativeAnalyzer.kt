package com.aiqulabs.abcd.dj.nativelib

/**
 * The JNI entry point to the C++ analysis (src/main/cpp/jni/abcd_jni.cpp). Everything else about
 * analysis is in the portable C++ core; this object only moves a sample array in and a JSON string
 * out. Use `NativeAudioAnalyzer` in `dj-analysis` rather than calling this directly.
 */
object NativeAnalyzer {
    init {
        System.loadLibrary("abcd_jni")
    }

    /**
     * Analyses mono PCM and returns the result as JSON ("wire format", see `AnalysisResult.fromWireJson`).
     * Blocks for seconds on a full song, so call it off the main thread.
     *
     * @throws IllegalStateException if the audio is too short or silent, or analysis fails.
     */
    external fun analyzeJson(
        pcm: FloatArray,
        sampleRate: Int,
    ): String

    /** Version of the analysis algorithms; stored with every result. */
    external fun analysisVersion(): Int
}
