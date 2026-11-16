package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.brain.CandidateSource
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds the DJ playlist builder from Metrolist's existing YouTube Music layer (brief §7.1): song
 * search and "up next" radio. It uses only calls Metrolist already makes; no new endpoint.
 *
 * It remembers the [SongItem] behind every [DjTrack] it returns, so the chosen tracks can be handed
 * to the normal player without a second lookup ([songFor]).
 */
internal class YouTubeCandidateSource : CandidateSource {
    private val songs = ConcurrentHashMap<String, SongItem>()

    /** True once any search or radio request threw (for example, no network). */
    @Volatile
    var requestFailed: Boolean = false
        private set

    override suspend fun search(query: String): List<DjTrack> =
        guarded {
            GlobalYouTubeRepository.instance
                .searchFiltered(query, SearchFilter.SONG, allowOfflineFallback = false)
                .items
                .filterIsInstance<SongItem>()
        }

    override suspend fun radio(videoId: String): List<DjTrack> = guarded { GlobalYouTubeRepository.instance.startRadio(videoId) }

    /** The original song for a track returned earlier by [search] or [radio]. */
    fun songFor(videoId: String): SongItem? = songs[videoId]

    private suspend fun guarded(load: suspend () -> List<SongItem>): List<DjTrack> =
        try {
            load().filter(::isMixable).map { song ->
                songs.putIfAbsent(song.id, song)
                song.toDjTrack()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            requestFailed = true
            emptyList()
        }

    /** Streamable songs only: no podcasts, uploads, local files or unavailable items. */
    private fun isMixable(song: SongItem): Boolean = !song.isUnavailable && !song.isLocal && !song.isUploaded && !song.isPodcast
}

/** SongItem durations are in seconds; DjTrack uses milliseconds. */
internal fun SongItem.toDjTrack(): DjTrack =
    DjTrack(
        videoId = id,
        title = title,
        artists = artists.map { it.name },
        album = album?.name,
        durationMs = duration?.times(1_000L),
        thumbnailUrl = thumbnail,
    )
