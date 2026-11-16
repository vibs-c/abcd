package com.aiqulabs.abcd.dj.core.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Equal-power crossfade: the outgoing gain follows cos and the incoming gain sin of the progress, so
 * out² + in² = 1 and the perceived loudness stays level through the fade (a linear fade dips in the
 * middle).
 */
object EqualPowerFade {
    fun outgoingGain(progress: Double): Float = cos(progress.coerceIn(0.0, 1.0) * PI / 2).toFloat()

    fun incomingGain(progress: Double): Float = sin(progress.coerceIn(0.0, 1.0) * PI / 2).toFloat()

    /** Progress 0..1 of a fade that started at [startMs] and lasts [fadeMs], measured on the same clock. */
    fun progress(
        nowMs: Long,
        startMs: Long,
        fadeMs: Long,
    ): Double = if (fadeMs <= 0) 1.0 else ((nowMs - startMs).toDouble() / fadeMs).coerceIn(0.0, 1.0)
}
