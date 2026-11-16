package com.aiqulabs.abcd.dj.core.model

import kotlinx.serialization.Serializable

/**
 * How tracks are cut and blended (brief §4.2, §9.3).
 *
 * [averageTrackSeconds] is the typical time one track occupies in the mix, used to size the
 * playlist (§7.2). [availableInV1] hides styles reserved for later versions from the UI.
 */
@Serializable
enum class MixStyle(
    val averageTrackSeconds: Int,
    val availableInV1: Boolean,
) {
    /** Long blends; exit at the outro start or after the last chorus. */
    SMOOTH(averageTrackSeconds = 170, availableInV1 = true),

    /** Quick fades, nearly full songs. */
    RADIO(averageTrackSeconds = 195, availableInV1 = true),

    /** Pre-chorus into main chorus, about 60–90 s per track. */
    CHORUS_HITS(averageTrackSeconds = 75, availableInV1 = true),

    /** [v2] EQ/filter-heavy; exit after the second chorus. */
    CLUB(averageTrackSeconds = 150, availableInV1 = false),
    ;

    companion object {
        val DEFAULT = SMOOTH
        val v1Styles: List<MixStyle> = entries.filter { it.availableInV1 }
    }
}
