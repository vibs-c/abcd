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
    override val analysisVersion: Int get() = native { NativeAnalyzer.analysisVersion() }

    /**
     * A missing or broken native library throws an Error (UnsatisfiedLinkError) that ordinary `catch (e: Exception)`
     * blocks miss, which would crash the app. Turn it into an exception so the song (or the Lab) fails cleanly.
     */
    private inline fun <T> native(block: () -> T): T =
        try {
            block()
        } catch (e: LinkageError) {
            throw IllegalStateException("Native analysis library unavailable on this device: ${e.message}", e)
        } catch (e: ExceptionInInitializerError) {
            throw IllegalStateException("Native analysis library failed to load: ${e.message}", e)
        }

    override suspend fun analyze(pcm: PcmAudio): AnalysisResult =
        nativeLock.withLock {
            withContext(dispatcher) {
                AnalysisResult.fromWireJson(native { NativeAnalyzer.analyzeJson(pcm.samples, pcm.sampleCount, pcm.sampleRate) })
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
