package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.brain.CandidateSource
import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.flow.first
import org.koin.mp.KoinPlatform
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds the DJ playlist builder from Metrolist's existing YouTube Music layer (brief §7.1): song
 * search, "up next" radio, the Home mood chips, and the user's liked and library songs (stage 10). It uses
 * only calls Metrolist already makes; no new endpoint.
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

    /**
     * Liked songs, then the rest of the library, from Metrolist's own database (read only). Stage 10, brief §7.1.
     * Blocked songs are left out here, the rest of the filtering happens in the builder.
     */
    override suspend fun libraryTracks(): List<DjTrack> =
        guarded(countsAsFailure = false) {
            val dao = KoinPlatform.getKoin().get<MusicDatabase>().dao()
            val songs = LinkedHashMap<String, SongItem>()
            (dao.likedSongsByRowIdAsc().first() + dao.songsByRowIdAsc().first()).forEach { songs.putIfAbsent(it.id, it.toSongItem()) }
            BlockedContent.filterPlayableSongs(songs.values.toList())
        }

    /**
     * Songs from YouTube Music's own Home mood chip for [mood] ("Party", "Workout", ...), through the same
     * Home browse call Metrolist makes when the user taps that chip. Empty when no chip matches.
     */
    override suspend fun moodPage(mood: DjMood): List<DjTrack> =
        guarded(countsAsFailure = false) {
            val names = MOOD_CHIPS[mood].orEmpty()
            val home = GlobalYouTubeRepository.instance.getHomePageData()
            val chip = home.chips.firstOrNull { chip -> names.any { chip.title.equals(it, ignoreCase = true) } } ?: return@guarded emptyList()
            GlobalYouTubeRepository.instance
                .getHomePageData(chip.params, chip.browseId ?: "FEmusic_home")
                .sections
                .flatMap { it.items }
                .filterIsInstance<SongItem>()
                .filterNot { it.isVideoSong }
        }

    /** The original song for a track returned earlier by [search] or [radio]. */
    fun songFor(videoId: String): SongItem? = songs[videoId]

    private suspend fun guarded(
        countsAsFailure: Boolean = true,
        load: suspend () -> List<SongItem>,
    ): List<DjTrack> =
        try {
            load().filter(::isMixable).map { song ->
                songs.putIfAbsent(song.id, song)
                song.toDjTrack()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The extra sources are a bonus: their failure alone does not mean "no connection".
            if (countsAsFailure) requestFailed = true
            emptyList()
        }

    /** Streamable songs only: no podcasts, uploads, local files or unavailable items. */
    private fun isMixable(song: SongItem): Boolean = !song.isUnavailable && !song.isLocal && !song.isUploaded && !song.isPodcast

    private companion object {
        /** Home chip titles (English UI) that fit each mood; moods without a chip get none. */
        val MOOD_CHIPS =
            mapOf(
                DjMood.PARTY to listOf("Party", "Energize"),
                DjMood.CHILL to listOf("Relax", "Chill"),
                DjMood.ROMANTIC to listOf("Romance"),
                DjMood.ROAD_TRIP to listOf("Commute", "Feel good"),
                DjMood.WORKOUT to listOf("Workout", "Energize"),
                DjMood.SAD to listOf("Sad"),
            )
    }
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
