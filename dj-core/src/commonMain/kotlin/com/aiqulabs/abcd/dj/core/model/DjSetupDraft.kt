package com.aiqulabs.abcd.dj.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The setup form as the user is filling it in (brief §4.2).
 *
 * Unlike [DjSessionSettings] it can be incomplete: on first run nothing is chosen, and [isComplete]
 * stays false until at least one language and a mood are picked. It is stored so the last selection
 * is remembered.
 */
@Serializable
data class DjSetupDraft(
    val languages: Set<DjLanguage> = emptySet(),
    val mood: DjMood? = null,
    val mixStyle: MixStyle = MixStyle.DEFAULT,
    val sessionLengthMinutes: Int = DjSessionSettings.DEFAULT_LENGTH_MINUTES,
) {
    /** All four required inputs are set. */
    val isComplete: Boolean get() = languages.isNotEmpty() && mood != null

    /** Which required input is still missing, for the hint under the Start button. */
    val missing: Missing?
        get() =
            when {
                languages.isEmpty() -> Missing.LANGUAGE
                mood == null -> Missing.MOOD
                else -> null
            }

    enum class Missing { LANGUAGE, MOOD }

    /** Applies a language chip tap using the chip rules in [DjLanguage.toggle]. */
    fun toggleLanguage(tapped: DjLanguage): DjSetupDraft = copy(languages = DjLanguage.toggle(languages, tapped))

    /** The session settings to run, or null while the form is incomplete. v2 fields keep their defaults. */
    fun toSettings(): DjSessionSettings? {
        val chosenMood = mood ?: return null
        if (languages.isEmpty()) return null
        return DjSessionSettings(
            languages = languages,
            mood = chosenMood,
            mixStyle = mixStyle,
            sessionLengthMinutes = sessionLengthMinutes,
        ).normalized()
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    /** Returns this draft with values clamped to what v1 supports. */
    fun normalized(): DjSetupDraft =
        copy(
            mixStyle = if (mixStyle.availableInV1) mixStyle else MixStyle.DEFAULT,
            sessionLengthMinutes =
                sessionLengthMinutes.coerceIn(
                    DjSessionSettings.MIN_LENGTH_MINUTES,
                    DjSessionSettings.MAX_LENGTH_MINUTES,
                ),
        )

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** Parses a stored draft. Corrupt or empty text gives an empty draft instead of an error. */
        fun fromJson(text: String?): DjSetupDraft {
            if (text.isNullOrBlank()) return DjSetupDraft()
            return try {
                json.decodeFromString(serializer(), text).normalized()
            } catch (_: Exception) {
                DjSetupDraft()
            }
        }
    }
}
