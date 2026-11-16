package com.aiqulabs.abcd.dj.core.model

import kotlinx.serialization.Serializable

/**
 * A music language the user can pick on the setup screen (brief §4.2).
 *
 * This is a DJ content filter, not the app's UI locale. [searchTerm] is the word added to
 * YouTube Music search queries; it is a query term, never shown to the user. [scripts] lists the
 * Unicode scripts whose presence in a title is evidence for this language (§6.2).
 */
@Serializable
enum class DjLanguage(
    val searchTerm: String?,
    val scripts: Set<Script>,
) {
    HINDI("hindi", setOf(Script.DEVANAGARI)),
    ENGLISH("english", setOf(Script.LATIN)),
    PUNJABI("punjabi", setOf(Script.GURMUKHI)),
    MARATHI("marathi", setOf(Script.DEVANAGARI)),
    TAMIL("tamil", setOf(Script.TAMIL)),
    TELUGU("telugu", setOf(Script.TELUGU)),
    KANNADA("kannada", setOf(Script.KANNADA)),
    MALAYALAM("malayalam", setOf(Script.MALAYALAM)),
    BENGALI("bengali", setOf(Script.BENGALI)),
    GUJARATI("gujarati", setOf(Script.GUJARATI)),

    /** No language filter. Mutually exclusive with the other values in a selection. */
    ANY(null, emptySet()),
    ;

    companion object {
        /** Languages shown as chips, in display order. */
        val selectable: List<DjLanguage> = entries.toList()

        /**
         * Applies the chip rules: picking [ANY] clears the others, picking a specific language
         * clears [ANY], and an empty result falls back to [ANY] so the selection is never empty.
         */
        fun toggle(
            current: Set<DjLanguage>,
            tapped: DjLanguage,
        ): Set<DjLanguage> {
            val next =
                when {
                    tapped == ANY -> setOf(ANY)
                    tapped in current -> current - tapped
                    else -> (current - ANY) + tapped
                }
            return next.ifEmpty { setOf(ANY) }
        }
    }
}

/** Writing systems detected from track titles (§6.2). */
@Serializable
enum class Script {
    LATIN,
    DEVANAGARI,
    GURMUKHI,
    GUJARATI,
    BENGALI,
    TAMIL,
    TELUGU,
    KANNADA,
    MALAYALAM,
    ARABIC,
    OTHER,
}
