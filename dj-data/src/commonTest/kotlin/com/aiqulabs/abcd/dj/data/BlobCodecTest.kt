package com.aiqulabs.abcd.dj.data

import com.aiqulabs.abcd.dj.data.codec.toByteBlob
import com.aiqulabs.abcd.dj.data.codec.toDoubleBlob
import com.aiqulabs.abcd.dj.data.codec.toDoubleList
import com.aiqulabs.abcd.dj.data.codec.toIntList
import kotlin.test.Test
import kotlin.test.assertEquals

class BlobCodecTest {
    @Test
    fun doublesRoundTripExactly() {
        val values = listOf(0.0, -0.0, 476.190476190476, 1e-300, 1e300, 99_000.123, Double.MAX_VALUE, Double.MIN_VALUE)
        assertEquals(values, values.toDoubleBlob().toDoubleList())
    }

    @Test
    fun eachDoubleTakesEightBytes() {
        assertEquals(24, listOf(1.0, 2.0, 3.0).toDoubleBlob().size)
        assertEquals(0, emptyList<Double>().toDoubleBlob().size)
    }

    @Test
    fun aTruncatedTrailingValueIsIgnoredNotFatal() {
        val blob = listOf(1.5, 2.5).toDoubleBlob()
        assertEquals(listOf(1.5), blob.copyOf(blob.size - 3).toDoubleList())
    }

    @Test
    fun peaksRoundTripThroughUnsignedBytes() {
        val peaks = listOf(0, 1, 127, 128, 200, 255)
        assertEquals(peaks, peaks.toByteBlob().toIntList())
    }

    @Test
    fun outOfRangePeaksAreClamped() {
        assertEquals(listOf(0, 255), listOf(-5, 999).toByteBlob().toIntList())
    }
}
