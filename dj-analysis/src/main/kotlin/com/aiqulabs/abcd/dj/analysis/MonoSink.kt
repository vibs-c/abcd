package com.aiqulabs.abcd.dj.analysis

import android.media.AudioFormat
import java.nio.ByteBuffer

/**
 * Collects decoder output into one growing mono float array.
 *
 * Idea: the decoder hands over interleaved samples (L R L R ...) in 16-bit or float form. For each
 * frame we average the channels into one value in [-1, 1] and append it.
 */
internal class MonoSink(
    initialCapacity: Int = 1 shl 20,
) {
    private var data = FloatArray(initialCapacity)

    var size = 0
        private set

    fun append(
        buffer: ByteBuffer,
        encoding: Int,
        channels: Int,
    ) {
        val channelCount = channels.coerceAtLeast(1)
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val frames = floats.remaining() / channelCount
            ensureCapacity(size + frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) sum += floats.get()
                data[size++] = sum / channelCount
            }
        } else {
            val shorts = buffer.asShortBuffer()
            val frames = shorts.remaining() / channelCount
            ensureCapacity(size + frames)
            for (frame in 0 until frames) {
                var sum = 0
                for (channel in 0 until channelCount) sum += shorts.get()
                data[size++] = sum / (channelCount * PCM16_SCALE)
            }
        }
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)

    private fun ensureCapacity(needed: Int) {
        if (needed <= data.size) return
        var capacity = data.size
        while (capacity < needed) capacity *= 2
        data = data.copyOf(capacity)
    }

    private companion object {
        const val PCM16_SCALE = 32768f
    }
}
