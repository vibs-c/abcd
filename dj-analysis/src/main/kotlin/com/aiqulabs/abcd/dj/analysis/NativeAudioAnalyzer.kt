package com.aiqulabs.abcd.dj.analysis

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.AudioAnalyzer
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import com.aiqulabs.abcd.dj.nativelib.NativeAnalyzer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AudioAnalyzer] backed by the C++ analysis in `dj-native` (Essentia plus our own structure code).
 *
 * Heavy work runs on [dispatcher], never on the main thread (brief §2.7).
 */
class NativeAudioAnalyzer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AudioAnalyzer {
    override val analysisVersion: Int get() = NativeAnalyzer.analysisVersion()

    override suspend fun analyze(pcm: PcmAudio): AnalysisResult =
        withContext(dispatcher) {
            AnalysisResult.fromWireJson(NativeAnalyzer.analyzeJson(pcm.samples, pcm.sampleRate))
        }
}
