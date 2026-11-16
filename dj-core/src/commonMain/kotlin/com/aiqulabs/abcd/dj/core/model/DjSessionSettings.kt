package com.aiqulabs.abcd.dj.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the user chose for one DJ session (brief §4.2). Stored as JSON in `DjSession`.
 *
 * v1 sets [languages], [mood], [mixStyle] and [sessionLengthMinutes]. The remaining fields are
 * v2 hooks with defaults that mean "no extra constraint", so v2 only adds UI.
 */
@Serializable
data class DjSessionSettings(
    val languages: Set<DjLanguage> = setOf(DjLanguage.ANY),
    val mood: DjMood = DjMood.PARTY,
    val mixStyle: MixStyle = MixStyle.DEFAULT,
    val sessionLengthMinutes: Int = DEFAULT_LENGTH_MINUTES,
    /** [v2] Genre filter. Empty means no filter. */
    val genres: Set<DjGenre> = emptySet(),
    /** [v2] Free-text vibe, parsed by a VibeParser into the other fields. */
    val vibeText: String? = null,
    /** [v2] User-chosen arc. Null means "use the mood's default arc". */
    val energyArc: EnergyArc? = null,
    /** [v2] Anchor songs the DJ must include, as YouTube video ids. */
    val seedVideoIds: List<String> = emptyList(),
    /** [v2] Endless session (needs runtime analysis). Always false in v1. */
    val endless: Boolean = false,
) {
    /** The arc actually used: the user's choice in v2, otherwise the mood default. */
    val effectiveEnergyArc: EnergyArc get() = energyArc ?: mood.defaultArc

    /** True when the user did not restrict languages. */
    val anyLanguage: Boolean get() = DjLanguage.ANY in languages

    /** Returns a copy with values clamped to what v1 supports. */
    fun normalized(): DjSessionSettings =
        copy(
            languages = if (languages.isEmpty()) setOf(DjLanguage.ANY) else languages,
            mixStyle = if (mixStyle.availableInV1) mixStyle else MixStyle.DEFAULT,
            sessionLengthMinutes = sessionLengthMinutes.coerceIn(MIN_LENGTH_MINUTES, MAX_LENGTH_MINUTES),
            endless = false,
        )

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MIN_LENGTH_MINUTES = 15
        const val MAX_LENGTH_MINUTES = 240
        const val DEFAULT_LENGTH_MINUTES = 60

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** Parses stored settings; unknown or missing fields fall back to defaults. */
        fun fromJson(text: String): DjSessionSettings = json.decodeFromString(serializer(), text).normalized()
    }
}
