package com.aiqulabs.abcd.dj

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.aiqulabs.abcd.dj.analysis.MediaCodecPcmDecoder
import com.aiqulabs.abcd.dj.analysis.NativeAudioAnalyzer
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.AudioSource
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Runs the real decode -> JNI -> native analysis -> JSON -> Kotlin chain on a device or emulator.
 *
 * Test inputs are pushed by hand and not committed:
 *   adb push abcd_synth.wav  /sdcard/Android/data/in.aiqulabs.abcd.debug/files/
 *   adb push abcd_song.<ext> /sdcard/Android/data/in.aiqulabs.abcd.debug/files/   (any real song, optional)
 */
class DjAnalysisInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val inputDir get() = checkNotNull(context.getExternalFilesDir(null))

    /** 40 s of a kick drum every beat at 120 BPM, as float PCM. No file needed. */
    private fun clickTrack(): PcmAudio {
        val rate = 44_100
        val beat = rate / 2 // 120 BPM
        val samples = FloatArray(rate * 40)
        var start = 0
        while (start < samples.size) {
            for (n in 0 until rate / 5) {
                if (start + n >= samples.size) break
                val t = n.toDouble() / rate
                samples[start + n] += (0.8 * exp(-t * 14.0) * sin(2 * PI * (60.0 + 80.0 * exp(-t * 30.0)) * t)).toFloat()
            }
            start += beat
        }
        return PcmAudio(samples, rate)
    }

    @Test
    fun clickTrackIsAnalysedThroughJni() =
        runBlocking {
            val result = NativeAudioAnalyzer().analyze(clickTrack())
            Log.i(TAG, "click track: bpm=${result.bpm} conf=${result.bpmConfidence} bars=${result.barStartsMs.size}")
            assertEquals(120.0, result.bpm, 2.0)
            assertTrue(result.beatsMs.size > 60)
            assertEquals(NativeAudioAnalyzer().analysisVersion, result.analysisVersion)
        }

    @Test
    fun tooShortAudioIsRejectedWithAnException() {
        val result = runCatching { runBlocking { NativeAudioAnalyzer().analyze(PcmAudio(FloatArray(44_100), 44_100)) } }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun decodedSyntheticSongMatchesItsKnownTempoKeyAndChoruses() =
        runBlocking {
            val file = File(inputDir, "abcd_synth.wav")
            assumeTrue("push abcd_synth.wav first", file.exists())
            val pcm = MediaCodecPcmDecoder(context).decodeToMono(AudioSource(file.absolutePath))
            Log.i(TAG, "decoded ${pcm.sampleCount} samples at ${pcm.sampleRate} Hz (${pcm.durationMs / 1000} s)")
            val result = NativeAudioAnalyzer().analyze(pcm)
            log(result)

            assertEquals(126.0, result.bpm, 1.5)
            assertEquals(KeyMode.MINOR, result.key.mode)
            assertEquals("8A", result.key.camelot)
            val barMs = 4 * 60_000.0 / 126.0
            val truth = listOf(12 to 20, 28 to 36, 40 to 48)
            val matches =
                result.chorusCandidates.count { c ->
                    truth.any { (from, to) ->
                        val overlap = minOf(c.endMs, to * barMs) - maxOf(c.startMs, from * barMs)
                        overlap >= 0.75 * c.durationMs && kotlin.math.abs(c.startMs - from * barMs) < 0.5 * barMs
                    }
                }
            assertEquals(3, matches)
            assertTrue(result.mainChorus != null)
        }

    @Test
    fun realSongIsDecodedAndAnalysed() =
        runBlocking {
            val file = inputDir.listFiles { f -> f.name.startsWith("abcd_song.") }?.firstOrNull()
            assumeTrue("push abcd_song.<ext> first", file != null)
            val pcm = MediaCodecPcmDecoder(context).decodeToMono(AudioSource(checkNotNull(file).absolutePath))
            Log.i(TAG, "real song decoded: ${pcm.sampleCount} samples at ${pcm.sampleRate} Hz (${pcm.durationMs / 1000} s)")
            // Keep the decoded samples next to the input so the command-line analyser can be run on them
            // without decoding again: abcd_analyze_cli --raw abcd_song_decoded.f32 <rate>
            File(inputDir, "abcd_song_decoded.f32").outputStream().buffered().use { out ->
                val bytes = ByteBuffer.allocate(pcm.sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
                bytes.asFloatBuffer().put(pcm.samples, 0, pcm.sampleCount)
                out.write(bytes.array())
            }
            val started = System.nanoTime()
            val result = NativeAudioAnalyzer().analyze(pcm)
            Log.i(TAG, "analysis took ${(System.nanoTime() - started) / 1e9} s")
            log(result)
            assertTrue(result.bpm in 70.0..180.0)
            assertTrue(result.chorusCandidates.isNotEmpty())
        }

    private fun log(r: AnalysisResult) {
        Log.i(
            TAG,
            "RESULT bpm=%.2f conf=%.2f (raw %.2f) key=%s %s (%s) conf=%.2f lufs=%.1f".format(
                r.bpm,
                r.bpmConfidence,
                r.bpmConfidenceRaw,
                r.key.root,
                r.key.mode,
                r.key.camelot,
                r.key.confidence,
                r.lufs,
            ),
        )
        Log.i(TAG, "RESULT beats=${r.beatsMs.size} bars=${r.barStartsMs.size} downbeatConf=%.2f".format(r.downbeatConfidence))
        r.sections.forEach { Log.i(TAG, "RESULT section ${it.type} %.1f-%.1f s".format(it.startMs / 1000, it.endMs / 1000)) }
        r.chorusCandidates.forEach {
            Log.i(
                TAG,
                "RESULT chorus %.1f-%.1f s score=%.2f %s%s".format(
                    it.startMs / 1000,
                    it.endMs / 1000,
                    it.score,
                    it.signals,
                    if (it.isMain) " MAIN" else "",
                ),
            )
        }
    }

    private companion object {
        const val TAG = "AbcdAnalysis"
    }
}
