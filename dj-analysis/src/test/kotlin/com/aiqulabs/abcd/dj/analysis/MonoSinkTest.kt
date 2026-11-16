package com.aiqulabs.abcd.dj.analysis

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MonoSinkTest {
    private fun shorts(vararg values: Short): ByteBuffer =
        ByteBuffer.allocate(values.size * 2).order(ByteOrder.nativeOrder()).also { buffer ->
            values.forEach { buffer.putShort(it) }
            buffer.flip()
        }

    private fun floats(vararg values: Float): ByteBuffer =
        ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).also { buffer ->
            values.forEach { buffer.putFloat(it) }
            buffer.flip()
        }

    @Test
    fun stereoPcm16IsAveragedToMonoAndScaledToUnitRange() {
        val sink = MonoSink(initialCapacity = 2)
        // Frames: (16384, 16384) -> 0.5, (-32768, 0) -> -0.5, (0, 0) -> 0
        sink.append(shorts(16384, 16384, -32768, 0, 0, 0), AudioFormat.ENCODING_PCM_16BIT, channels = 2)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f, 0f), sink.toFloatArray(), 1e-6f)
    }

    @Test
    fun floatPcmIsAveragedAcrossChannels() {
        val sink = MonoSink()
        sink.append(floats(1f, 0f, 0.25f, 0.75f), AudioFormat.ENCODING_PCM_FLOAT, channels = 2)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f), sink.toFloatArray(), 1e-6f)
    }

    @Test
    fun growsPastItsInitialCapacityAndAppendsAcrossCalls() {
        val sink = MonoSink(initialCapacity = 1)
        repeat(5) { sink.append(shorts(1000, 2000, 3000), AudioFormat.ENCODING_PCM_16BIT, channels = 1) }
        assertEquals(15, sink.size)
        assertEquals(1000f / 32768f, sink.toFloatArray()[3], 1e-9f)
    }

    @Test
    fun handsOverItsBufferWithoutCopyingAndCountsOnlyTheAudio() {
        val sink = MonoSink(initialCapacity = 10)
        sink.append(shorts(16384, 16384), AudioFormat.ENCODING_PCM_16BIT, channels = 1)
        val first = sink.toPcmAudio(sampleRate = 4)
        assertEquals(2, first.sampleCount)
        assertEquals(500.0, first.durationMs, 1e-9)
        assertSame("no copy of the whole song", first.samples, sink.toPcmAudio(sampleRate = 4).samples)
    }

    @Test
    fun capacityComesFromTheDurationWithAMarginAndIsCapped() {
        val fourMinutes = MonoSink.capacityFor(durationUs = 240_000_000L, sampleRate = 48_000)
        assertTrue("room for the whole song", fourMinutes >= 240 * 48_000)
        assertTrue("but not double", fourMinutes < 240 * 48_000 * 11 / 10)
        assertEquals(MonoSink.DEFAULT_CAPACITY, MonoSink.capacityFor(durationUs = -1L, sampleRate = 48_000))
        assertEquals(30 * 60 * 48_000, MonoSink.capacityFor(durationUs = 10L * 3_600_000_000L, sampleRate = 48_000))
    }
}
