package com.aiqulabs.abcd.dj.data.codec

// Arrays are stored as BLOBs. Written without any platform class so the same code runs on every
// target: doubles as 8 little-endian bytes, small integers (waveform peaks) as one byte each.

private const val DOUBLE_BYTES = 8

/** Encodes [values] as 8 little-endian bytes each. */
fun List<Double>.toDoubleBlob(): ByteArray {
    val out = ByteArray(size * DOUBLE_BYTES)
    forEachIndexed { index, value ->
        val bits = value.toRawBits()
        for (byte in 0 until DOUBLE_BYTES) out[index * DOUBLE_BYTES + byte] = (bits ushr (8 * byte)).toByte()
    }
    return out
}

/** Decodes a blob written by [toDoubleBlob]. A trailing partial value is ignored. */
fun ByteArray.toDoubleList(): List<Double> {
    val count = size / DOUBLE_BYTES
    return List(count) { index ->
        var bits = 0L
        for (byte in 0 until DOUBLE_BYTES) bits = bits or ((this[index * DOUBLE_BYTES + byte].toLong() and 0xFF) shl (8 * byte))
        Double.fromBits(bits)
    }
}

/** Encodes values 0..255 as one byte each; out-of-range values are clamped. */
fun List<Int>.toByteBlob(): ByteArray = ByteArray(size) { this[it].coerceIn(0, 255).toByte() }

/** Decodes a blob written by [toByteBlob] back to values 0..255. */
fun ByteArray.toIntList(): List<Int> = map { it.toInt() and 0xFF }
