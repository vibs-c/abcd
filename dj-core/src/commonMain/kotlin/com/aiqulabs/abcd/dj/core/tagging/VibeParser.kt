package com.aiqulabs.abcd.dj.core.tagging

import com.aiqulabs.abcd.dj.core.model.DjSessionSettings

/**
 * Turns the free-text vibe ("90s Bollywood wedding dance") into structured settings (brief §8).
 *
 * v1 has no vibe field, so [PassThroughVibeParser] returns the settings unchanged. v2 adds a
 * keyword parser (including Hindi/Hinglish); v3 can use an on-device LLM.
 */
interface VibeParser {
    suspend fun parse(settings: DjSessionSettings): DjSessionSettings
}

/** v1 implementation: no parsing. */
object PassThroughVibeParser : VibeParser {
    override suspend fun parse(settings: DjSessionSettings): DjSessionSettings = settings
}
