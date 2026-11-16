package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.Script

/**
 * Finds the dominant writing system of a string with Unicode block range checks (brief §6.2).
 * Digits, punctuation and spaces are ignored.
 */
object ScriptDetector {
    /**
     * Returns the dominant script of [text], or null if it has no letters.
     *
     * Titles often append a Latin transliteration ("तुम ही हो (Tum Hi Ho)"), so any non-Latin
     * script with at least [MIN_NATIVE_CHARS] characters wins over Latin. Vowel signs count toward
     * their script even though they are not letters.
     */
    fun dominantScript(text: String): Script? {
        val counts = HashMap<Script, Int>()
        for (ch in text) {
            val script = scriptOf(ch)
            if (script == Script.OTHER && !ch.isLetter()) continue
            if (script == Script.LATIN && !ch.isLetter()) continue
            counts[script] = (counts[script] ?: 0) + 1
        }
        val native = counts.filterKeys { it != Script.LATIN && it != Script.OTHER }.maxByOrNull { it.value }
        if (native != null && native.value >= MIN_NATIVE_CHARS) return native.key
        return counts.maxByOrNull { it.value }?.key
    }

    private const val MIN_NATIVE_CHARS = 2

    /** Maps one letter to its script by Unicode block. */
    fun scriptOf(ch: Char): Script =
        when (ch.code) {
            in 0x0041..0x024F -> Script.LATIN
            in 0x0900..0x097F -> Script.DEVANAGARI
            in 0x0980..0x09FF -> Script.BENGALI
            in 0x0A00..0x0A7F -> Script.GURMUKHI
            in 0x0A80..0x0AFF -> Script.GUJARATI
            in 0x0B80..0x0BFF -> Script.TAMIL
            in 0x0C00..0x0C7F -> Script.TELUGU
            in 0x0C80..0x0CFF -> Script.KANNADA
            in 0x0D00..0x0D7F -> Script.MALAYALAM
            in 0x0600..0x06FF -> Script.ARABIC
            else -> Script.OTHER
        }
}
