package com.aiqulabs.abcd.dj.ui.lab

import java.util.Locale
import kotlin.math.roundToInt

/** "3:07" for 187 000 ms. Hours are folded into minutes, which is enough for a song. */
internal fun formatClock(ms: Double): String {
    val totalSeconds = (ms / 1000.0).roundToInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/** "85%" for 0.85. Numbers only, so no language text lives here. */
internal fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt().coerceIn(0, 100)}%"

/** "138.02" with a dot as decimal separator whatever the phone's language is. */
internal fun formatDecimal(
    value: Double,
    digits: Int = 1,
): String = String.format(Locale.ROOT, "%.${digits}f", value)
