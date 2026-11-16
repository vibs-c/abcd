package com.aiqulabs.abcd.dj.analysis

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.AudioAnalyzer
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import com.aiqulabs.abcd.dj.nativelib.NativeAnalyzer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        nativeLock.withLock {
            withContext(dispatcher) {
                AnalysisResult.fromWireJson(NativeAnalyzer.analyzeJson(pcm.samples, pcm.sampleCount, pcm.sampleRate))
            }
        }

    private companion object {
        /**
         * One native analysis at a time, across all instances. Pre-analysis downloads and decodes the next
         * track while this one is being analysed, but Essentia's one-time setup is not guaranteed to be
         * thread-safe, and two analyses at once would also double the memory use.
         */
        val nativeLock = Mutex()
    }
}
