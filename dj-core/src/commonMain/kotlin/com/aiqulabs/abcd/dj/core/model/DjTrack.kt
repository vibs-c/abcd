package com.aiqulabs.abcd.dj.core.model

import kotlinx.serialization.Serializable

/**
 * A track the DJ can play, independent of any platform media type. Linked to analysis and
 * feedback by [videoId] (brief §6.1).
 */
@Serializable
data class DjTrack(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
) {
    /** Lower-cased artist names, for same-artist spacing. */
    val artistKeys: Set<String> get() = artists.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
