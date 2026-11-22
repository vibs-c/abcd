package com.aiqulabs.abcd.dj.core.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The gain curves of a blend, chosen so the music never gets quieter in the middle.
 *
 * A plain equal-power fade (cos/sin) keeps out² + in² = 1, which is only level when both songs are equally
 * loud at that moment; in practice one side is usually fading or building, so listeners hear a dip. Here both
 * curves are raised with an exponent below 1: the incoming song is already near full volume before the
 * outgoing one drops, and the summed power peaks about +1.2 dB at the midpoint instead of sagging. Decks are
 * trimmed with headroom and limited, so the small bump cannot clip.
 */
object CrossfadeCurve {
    /** < 1 lifts the middle of both curves. 0.6 gives ~0.81 each at the midpoint (equal power: 0.71). */
    const val SHAPE = 0.6

    fun outgoing(progress: Double): Float = cos(clamp(progress) * PI / 2).coerceAtLeast(0.0).pow(SHAPE).toFloat()

    fun incoming(progress: Double): Float = sin(clamp(progress) * PI / 2).coerceAtLeast(0.0).pow(SHAPE).toFloat()

    private fun clamp(p: Double) = p.coerceIn(0.0, 1.0)
}
