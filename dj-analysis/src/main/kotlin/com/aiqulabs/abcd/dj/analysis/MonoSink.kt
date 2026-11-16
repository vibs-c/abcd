package com.aiqulabs.abcd.dj.analysis

import android.media.AudioFormat
import com.aiqulabs.abcd.dj.core.analysis.PcmAudio
import java.nio.ByteBuffer

/**
 * Collects decoder output into one growing mono float array.
 *
 * Idea: the decoder hands over interleaved samples (L R L R ...) in 16-bit or float form. For each
 * frame we average the channels into one value in [-1, 1] and append it.
 *
 * Memory matters here: a 4-minute 48 kHz song is about 46 MB of floats, and the app's heap limit can
 * be under 200 MB. So the decoder sizes the array up front from the track's duration
 * ([initialCapacity]), growth is by 1.5x rather than 2x when the estimate is short, and [toPcmAudio]
 * hands over the array itself instead of copying it to an exact size.
 */
internal class MonoSink(
    initialCapacity: Int = DEFAULT_CAPACITY,
) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(1))

    var size = 0
        private set


    // Reused scratch arrays: the decoder's buffer is copied out in one bulk call per output buffer.
    // Reading it one sample at a time through ShortBuffer.get() cost about 29 million calls for a
    // 5-minute stereo song and made decoding several times slower than the analysis itself.
    private var shortScratch = ShortArray(0)
    private var floatScratch = FloatArray(0)

    fun append(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
    ) {
        val channelCount = channels.coerceAtLeast(1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val count = floats.remaining()
            if (floatScratch.size < count) floatScratch = FloatArray(count)
            floats.get(floatScratch, 0, count)
            val frames = count / channelCount
            ensureCapacity(size + frames)
            val src = floatScratch
            val out = data
            var i = 0
            var o = size
            if (channelCount == 2) {
                for (frame in 0 until frames) {
                    out[o++] = (src[i] + src[i + 1]) * 0.5f
                    i += 2
                }
            } else {
                for (frame in 0 until frames) {
                    var sum = 0f
                    for (channel in 0 until channelCount) sum += src[i++]
                    out[o++] = sum / channelCount
                }
            }
            size = o
        } else {
            val shorts = buffer.asShortBuffer()
            val count = shorts.remaining()
            if (shortScratch.size < count) shortScratch = ShortArray(count)
            shorts.get(shortScratch, 0, count)
            val frames = count / channelCount
            ensureCapacity(size + frames)
            val src = shortScratch
            val out = data
            val scale = 1f / (channelCount * PCM16_SCALE)
            var i = 0
            var o = size
            if (channelCount == 2) {
                for (frame in 0 until frames) {
                    out[o++] = (src[i] + src[i + 1]) * scale
                    i += 2
                }
            } else {
                for (frame in 0 until frames) {
                    var sum = 0
                    for (channel in 0 until channelCount) sum += src[i++]
                    out[o++] = sum * scale
                }
            }
            size = o
        }
    }

    /** The collected audio without copying: the array may be longer than [size]. */
    fun toPcmAudio(sampleRate: Int): PcmAudio = PcmAudio(data, sampleRate, sampleCount = size)

    /** An exact-size copy (tests and small buffers only; a full song should use [toPcmAudio]). */
    fun toFloatArray(): FloatArray = data.copyOf(size)

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var capacity = data.size.toLong()
        while (capacity < needed) capacity += capacity / 2 + 1
        data = data.copyOf(capacity.coerceAtMost(Int.MAX_VALUE.toLong() - 8).toInt())
    }

    companion object {
        private const val PCM16_SCALE = 32768f
        const val DEFAULT_CAPACITY = 1 shl 20

        /**
         * Room for [durationUs] of audio at [sampleRate], plus 2% and a little extra, because the
         * container's duration is only an estimate. Unknown duration falls back to [DEFAULT_CAPACITY].
         */
        fun capacityFor(
            durationUs: Long,
            sampleRate: Int,
        ): Int {
            if (durationUs <= 0 || sampleRate <= 0) return DEFAULT_CAPACITY
            val frames = durationUs * sampleRate / 1_000_000.0 * 1.02 + sampleRate
            return frames.coerceAtMost(MAX_PRESIZE.toDouble()).toInt()
        }

        /** Never pre-allocate more than 30 min at 48 kHz from a (possibly wrong) duration header. */
        private const val MAX_PRESIZE = 30 * 60 * 48_000
    }
}
