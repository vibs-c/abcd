package com.aiqulabs.abcd.dj.engine

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * One deck's audio chain (brief §9.1): a fixed loudness trim, then a safety limiter.
 *
 * Trim: [setTrimDb] makes tracks of different loudness sit at the same perceived level (from the
 * analysis LUFS). Fades are not done here; the engine sets the player's volume, which comes after.
 *
 * Limiter: a per-frame peak limiter with instant attack and a ~120 ms release. Each frame's loudest
 * channel, after the trim, is held under [CEILING] (-1 dBFS); the gain then recovers smoothly. It is a
 * safety net against clipping when a quiet track is boosted, not a loudness maximiser. With two decks
 * the app has no shared output stage, so each deck has its own limiter (see DJ_ENGINE_DESIGN.md §10).
 */
@OptIn(UnstableApi::class)
internal class DeckGainProcessor : BaseAudioProcessor() {
    @Volatile private var trimGain = 1f
    private var envelope = 1f
    private var releaseStep = 0f
    private var scratch = ShortArray(0)

    fun setTrimDb(db: Double) {
        trimGain = 10.0.pow(db / 20.0).toFloat()
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
        if (scratch.size < count) scratch = ShortArray(count)
        inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(scratch, 0, count)
        inputBuffer.position(inputBuffer.position() + count * 2)

        val gain = trimGain
        val samples = scratch
        var env = envelope
        var i = 0
        while (i + channels <= count) {
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(samples[i + c] * gain))
            peak /= FULL_SCALE
            val needed = if (peak > CEILING) CEILING / peak else 1f
            env = if (needed < env) needed else env + (1f - env) * releaseStep
            val g = gain * env
            for (c in 0 until channels) {
                val v = samples[i + c] * g
                samples[i + c] = v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
            }
            i += channels
        }
        envelope = env

        val out = replaceOutputBuffer(count * 2)
        out.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, 0, count)
        out.position(count * 2)
        out.flip()
    }

    override fun onFlush() {
        envelope = 1f
    }

    override fun onReset() {
        envelope = 1f
        trimGain = 1f
    }

    companion object {
        /** -1 dBFS. */
        const val CEILING = 0.891f
        private const val FULL_SCALE = 32768f
        private const val RELEASE_SECONDS = 0.12

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
