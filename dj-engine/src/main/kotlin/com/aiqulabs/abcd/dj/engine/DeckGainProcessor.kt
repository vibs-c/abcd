package com.aiqulabs.abcd.dj.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.aiqulabs.abcd.dj.nativelib.NativeEcho
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * One deck's audio chain (brief §9.1): loudness trim → echo (stage 9, native) → safety limiter.
 *
 * Trim: [setTrimDb] makes tracks of different loudness sit at the same perceived level (from the analysis LUFS).
 * Fades are not done here; the engine sets the player's volume, which comes after.
 *
 * Echo: [setEcho] drives the C++ echo line (`NativeEcho`) for the echo-out technique. It runs only while an echo
 * is set or its tail is still sounding, so normal playback costs nothing extra.
 *
 * Limiter: a per-frame peak limiter with instant attack and a ~120 ms release keeps every frame under [CEILING]
 * (-1 dBFS). With two decks the app has no shared output stage, so each deck has its own (DJ_ENGINE_DESIGN.md §10).
 */
@OptIn(UnstableApi::class)
internal class DeckGainProcessor : BaseAudioProcessor() {
    @Volatile private var trimGain = 1f

    @Volatile private var echo = EchoParams.OFF
    private var echoLine: NativeEcho? = null
    private var echoTailFrames = 0

    private var envelope = 1f
    private var releaseStep = 0f
    private var shorts = ShortArray(0)
    private var floats: FloatBuffer = ByteBuffer.allocateDirect(0).asFloatBuffer()

    fun setTrimDb(db: Double) {
        trimGain = 10.0.pow(db / 20.0).toFloat()
    }

    /** Echo settings, applied from the next audio buffer. [EchoParams.OFF] lets the tail ring out, then bypasses. */
    fun setEcho(params: EchoParams) {
        echo = params
    }

    /** Forgets any echo (new song on the deck). */
    fun resetEcho() {
        echo = EchoParams.OFF
        echoTailFrames = 0
        echoLine?.clear()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        releaseStep = (1.0 - exp(-1.0 / (RELEASE_SECONDS * inputAudioFormat.sampleRate))).toFloat()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val count = bytes / 2
        val frames = count / channels
        if (shorts.size < count) shorts = ShortArray(count)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(shorts, 0, count)
        inputBuffer.position(inputBuffer.position() + count * 2)

        val params = echo
        if (params.isActive || echoTailFrames > 0) applyEcho(params, count, frames, channels) else applyTrimAndLimit(count, channels)

        val out = replaceOutputBuffer(count * 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().put(shorts, 0, count)
        out.position(count * 2)
        out.flip()
    }

    /** Fast path: trim and limit the 16-bit samples directly. */
    private fun applyTrimAndLimit(
        count: Int,
        channels: Int,
    ) {
        val gain = trimGain
        val samples = shorts
        var env = envelope
        var i = 0
        while (i + channels <= count) {
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[i + c] * gain))
            env = nextEnvelope(env, peak / FULL_SCALE)
            val g = gain * env
            for (c in 0 until channels) samples[i + c] = clampShort(samples[i + c] * g)
            i += channels
        }
        envelope = env
    }

    /** Echo path: to float (with trim), through the native echo, then limit back to 16 bit. */
    private fun applyEcho(
        params: EchoParams,
        count: Int,
        frames: Int,
        channels: Int,
    ) {
        val line = echoLine ?: NativeEcho(inputAudioFormat.sampleRate, channels).also { echoLine = it }
        if (floats.capacity() < count) floats = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val gain = trimGain / FULL_SCALE
        for (k in 0 until count) floats.put(k, shorts[k] * gain)
        val delayFrames = (params.delayMs * inputAudioFormat.sampleRate / 1000L).toInt().coerceAtLeast(1)
        line.process(floats, frames, delayFrames, params.send, params.feedback, params.dry, params.wet)
        // Keep processing after the echo is switched off until the repeats have died away.
        echoTailFrames =
            if (params.isActive) {
                (delayFrames * TAIL_REPEATS).coerceAtLeast(delayFrames)
            } else {
                (echoTailFrames - frames).coerceAtLeast(0)
            }
        var env = envelope
        var i = 0
        while (i + channels <= count) {
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(floats.get(i + c)))
            env = nextEnvelope(env, peak)
            for (c in 0 until channels) shorts[i + c] = clampShort(floats.get(i + c) * env * FULL_SCALE)
            i += channels
        }
        envelope = env
    }

    private fun nextEnvelope(
        env: Float,
        peak: Float,
    ): Float {
        val needed = if (peak > CEILING) CEILING / peak else 1f
        return if (needed < env) needed else env + (1f - env) * releaseStep
    }

    private fun clampShort(v: Float): Short = v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    override fun onFlush() {
        envelope = 1f
        echoTailFrames = 0
        echoLine?.clear()
    }

    override fun onReset() {
        envelope = 1f
        trimGain = 1f
        echo = EchoParams.OFF
        echoTailFrames = 0
        echoLine?.release()
        echoLine = null
    }

    /** Echo controls. [send] feeds the echo, [feedback] keeps repeats going, [dry] is the direct signal, [wet] the repeats. */
    data class EchoParams(
        val delayMs: Long,
        val send: Float,
        val feedback: Float,
        val dry: Float,
        val wet: Float,
    ) {
        val isActive: Boolean get() = delayMs > 0 && (send > 0f || dry < 1f)

        companion object {
            val OFF = EchoParams(delayMs = 0, send = 0f, feedback = 0f, dry = 1f, wet = 1f)
        }
    }

    companion object {
        /** -1 dBFS. */
        const val CEILING = 0.891f
        private const val FULL_SCALE = 32768f
        private const val RELEASE_SECONDS = 0.12

        /** After the echo is switched off, keep running this many delay periods for the tail. */
        private const val TAIL_REPEATS = 12

        /** Loudness the decks are matched to (brief §9.1). */
        const val TARGET_LUFS = -14.0

        /** Kept free so two overlapping decks still sum under full scale. */
        const val HEADROOM_DB = 2.0
        const val MAX_TRIM_DB = 12.0

        /** Trim for a track measured at [lufs]: towards [TARGET_LUFS], clamped, minus the headroom. */
        fun trimDbFor(lufs: Double): Double =
            if (lufs.isNaN() || lufs < -70.0) -HEADROOM_DB else (TARGET_LUFS - lufs).coerceIn(-MAX_TRIM_DB, MAX_TRIM_DB) - HEADROOM_DB
    }
}
