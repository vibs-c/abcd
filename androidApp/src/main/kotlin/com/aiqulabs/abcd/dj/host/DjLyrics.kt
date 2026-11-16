package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.ChorusRanker
import com.aiqulabs.abcd.dj.core.analysis.LyricLine
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.repository.LyricsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform
import timber.log.Timber

/**
 * Synced lyrics as a chorus signal (brief §5.5, point 2): lines that repeat are very likely the chorus.
 *
 * Uses Metrolist's own lyrics repository (the same providers and cache as the lyrics screen; no new
 * endpoint). Lyrics are optional: no lyrics, unsynced lyrics, a slow provider or any error simply leave the
 * audio-only ranking unchanged.
 */
internal object DjLyrics {
    private const val TAG = "DjLyrics"
    private const val TIMEOUT_MS = 8_000L

    /** [result] with its chorus candidates re-ranked by repeated lyric lines, when lyrics are available. */
    suspend fun rankChoruses(
        song: SongItem,
        result: AnalysisResult,
    ): AnalysisResult {
        if (result.chorusCandidates.isEmpty()) return result
        val lines = linesFor(song)
        if (lines.isEmpty()) return result
        return ChorusRanker.withLyrics(result, lines).also { ranked ->
            val before = result.mainChorus?.startMs
            val after = ranked.mainChorus?.startMs
            Timber.tag(TAG).d("%s: %d lyric lines, main chorus %s -> %s ms", song.id, lines.size, before, after)
        }
    }

    private suspend fun linesFor(song: SongItem): List<LyricLine> =
        try {
            val repository = KoinPlatform.getKoinOrNull()?.getOrNull<LyricsRepository>() ?: return emptyList()
            val lyrics =
                withTimeoutOrNull(TIMEOUT_MS) {
                    repository.getLyrics(
                        videoId = song.id,
                        title = song.title,
                        artist = song.artists.joinToString(", ") { it.name },
                        duration = song.duration,
                        album = song.album?.name,
                    )
                }?.lyrics ?: return emptyList()
            LyricsUtils
                .parseLyrics(lyrics)
                .filter { it.text.isNotBlank() }
                .map { LyricLine(it.time, it.text) }
                // Unsynced lyrics come back with every line at 0; they cannot place a chorus.
                .takeIf { lines -> lines.map { it.timeMs }.distinct().size > 1 }
                .orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "No lyrics for %s", song.id)
            emptyList()
        }
}
