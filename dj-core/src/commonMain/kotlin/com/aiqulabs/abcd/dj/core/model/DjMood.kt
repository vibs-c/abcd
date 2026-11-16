package com.aiqulabs.abcd.dj.core.model

import kotlinx.serialization.Serializable

/**
 * Mood cards on the setup screen (brief §4.2).
 *
 * [searchTerms] are YouTube Music query terms, not UI text. [defaultArc] is the v1 energy arc for
 * the mood (§7.3); a user-chosen arc replaces it in v2. [targetEnergy] is the 0–1 energy level the
 * arc is centred on.
 */
@Serializable
enum class DjMood(
    val searchTerms: List<String>,
    val defaultArc: EnergyArc,
    val targetEnergy: Float,
) {
    PARTY(listOf("party songs", "dance hits"), EnergyArc.BUILD_UP, 0.8f),
    CHILL(listOf("chill songs", "lofi"), EnergyArc.STEADY, 0.35f),
    ROMANTIC(listOf("romantic songs", "love songs"), EnergyArc.STEADY, 0.4f),
    ROAD_TRIP(listOf("road trip songs", "travel songs"), EnergyArc.WAVE, 0.6f),
    WORKOUT(listOf("workout songs", "gym motivation songs"), EnergyArc.BUILD_UP, 0.9f),
    SAD(listOf("sad songs", "emotional songs"), EnergyArc.STEADY, 0.3f),
    RETRO(listOf("retro songs", "old classic songs"), EnergyArc.WAVE, 0.5f),
    DEVOTIONAL(listOf("devotional songs", "bhajan"), EnergyArc.STEADY, 0.35f),
    WEDDING(listOf("wedding songs", "sangeet dance songs"), EnergyArc.PEAK_AND_COOLDOWN, 0.85f),
}

/** Shape of the energy level over a session (§4.2 v2 field, defaulted from the mood in v1). */
@Serializable
enum class EnergyArc {
    STEADY,
    BUILD_UP,
    PEAK_AND_COOLDOWN,
    WAVE,
}

/** Genres for the v2 "More options" page. Stored in settings now, no UI in v1. */
@Serializable
enum class DjGenre {
    BOLLYWOOD,
    PUNJABI_POP,
    HIP_HOP,
    EDM,
    HOUSE,
    POP,
    ROCK,
    INDIE,
    LOFI,
    SUFI,
    CLASSICAL_FUSION,
    REGIONAL_FOLK,
}
