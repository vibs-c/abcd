package com.aiqulabs.abcd.dj.core.mix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The gain curves of a blend (stage 12 rework after "sudden drops and increases in volume while blending").
 *
 * Volume: equal power (out² + in² = 1 at every moment, so no dip between two different songs), driven through
 * a smoothstep so both ends are gentle: the incoming song creeps in instead of jumping to -13 dB in the first
 * moments, and the outgoing one fades to silence instead of being cut off at the end. (The stage 9 curve raised
 * both sides with an exponent of 0.6: no dip, but abrupt at both ends and up to +4 dB in the middle when two
 * beat-matched songs' kicks lined up.)
 *
 * Bass: in a beat-matched blend the incoming song's bass is held back until the middle of the blend and the two
 * basslines then swap over [BASS_SWAP_BEATS] beats, like a DJ using the mixer's EQ. Two kick drums never pile up,
 * which is what made the middle of a blend swell and the limiter pump.
 */
object CrossfadeCurve {
    /** Where in the blend (0..1) the basslines swap: the middle, which is a bar line for blends of whole bars. */
    const val BASS_SWAP_AT = 0.5

    /** The bass swap takes this many beats. */
    const val BASS_SWAP_BEATS = 2

    fun outgoing(progress: Double): Float = cos(smooth(progress) * PI / 2).coerceAtLeast(0.0).toFloat()

    fun incoming(progress: Double): Float = sin(smooth(progress) * PI / 2).coerceAtLeast(0.0).toFloat()

    /**
     * Bass level (0 = bass removed, 1 = full) of the incoming song at [progress]; [swapWidth] is the swap's length
     * as a fraction of the blend. The outgoing song's bass is the mirror image ([outgoingBass]).
     */
    fun incomingBass(
        progress: Double,
        swapWidth: Double,
    ): Float {
        val width = swapWidth.coerceIn(MIN_SWAP_WIDTH, MAX_SWAP_WIDTH)
        val start = BASS_SWAP_AT - width / 2
        return smooth((progress - start) / width).toFloat()
    }

    fun outgoingBass(
        progress: Double,
        swapWidth: Double,
    ): Float = 1f - incomingBass(progress, swapWidth)

    /** 3p² - 2p³: 0 and 1 at the ends with zero slope, so nothing starts or stops abruptly. */
    private fun smooth(p: Double): Double {
        val x = p.coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    private const val MIN_SWAP_WIDTH = 0.02
    private const val MAX_SWAP_WIDTH = 0.5
}
