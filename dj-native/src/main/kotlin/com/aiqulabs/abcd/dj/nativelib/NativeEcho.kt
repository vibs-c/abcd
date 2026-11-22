package com.aiqulabs.abcd.dj.nativelib

import java.nio.FloatBuffer

/**
 * The echo-out DSP for one deck (C++ `abcd::EchoLine`, src/main/cpp/core/echo.h): a feedback delay with a
 * darkening low-pass in the loop. Not thread-safe; one instance per deck, used from that deck's audio thread.
 * Call [release] when the deck goes away.
 */
class NativeEcho(
    sampleRate: Int,
    channels: Int,
) {
    init {
        NativeAnalyzer.analysisVersion() // makes sure libabcd_jni is loaded
    }

    private var handle: Long = nativeCreate(sampleRate, channels)

    /**
     * Processes [frames] interleaved frames of the direct buffer [samples] in place. [send]: how much input enters
     * the echo; [feedback]: how much of each repeat returns (< 0.95); [dry]/[wet]: levels of the input and the
     * repeats.
     */
    fun process(
        samples: FloatBuffer,
        frames: Int,
        delayFrames: Int,
        send: Float,
        feedback: Float,
        dry: Float,
        wet: Float,
    ) {
        if (handle != 0L) nativeProcess(handle, samples, frames, delayFrames, send, feedback, dry, wet)
    }

    fun clear() {
        if (handle != 0L) nativeClear(handle)
    }

    fun release() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0L
    }

    private external fun nativeCreate(
        sampleRate: Int,
        channels: Int,
    ): Long

    private external fun nativeProcess(
        handle: Long,
        samples: FloatBuffer,
        frames: Int,
        delayFrames: Int,
        send: Float,
        feedback: Float,
        dry: Float,
        wet: Float,
    )

    private external fun nativeClear(handle: Long)

    private external fun nativeRelease(handle: Long)
}
