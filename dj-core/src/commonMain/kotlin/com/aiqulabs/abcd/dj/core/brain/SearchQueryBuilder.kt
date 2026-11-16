package com.aiqulabs.abcd.dj.core.brain

import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings

/**
 * Builds YouTube Music search queries from language + mood (brief §7.1), for example
 * "punjabi party songs". The queries go through Metrolist's existing search; no new endpoint.
 */
object SearchQueryBuilder {
    fun queries(settings: DjSessionSettings): List<String> {
        val languageTerms: List<String?> =
            if (settings.anyLanguage) {
                listOf(null)
            } else {
                settings.languages.filter { it != DjLanguage.ANY }.map { it.searchTerm }
            }
        return languageTerms
            .flatMap { language ->
                settings.mood.searchTerms.map { mood -> listOfNotNull(language, mood).joinToString(" ") }
            }.distinct()
    }
}
