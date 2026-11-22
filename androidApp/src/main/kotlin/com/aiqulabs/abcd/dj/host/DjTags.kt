package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.tagging.AnalysisTagger
import com.aiqulabs.abcd.dj.core.tagging.GenreTagger
import com.aiqulabs.abcd.dj.core.tagging.LyricsLanguage
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue
import com.aiqulabs.abcd.dj.data.DjFeedbackRepository
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Writes a song's details when it is analysed (brief §6.2; stages 10 and 12):
 * - LANGUAGE from the title, album and singers (source METADATA) and from the lyrics (source LYRICS);
 * - type (GENRE) guessed from the title, singers, language and tempo (METADATA);
 * - MOOD, ENERGY_CLASS and VIBE from the analysis (ANALYSIS).
 *
 * Each source's rows are replaced on re-tagging; the user's own (USER, from the Lab) are never touched.
 * When the lyrics were looked up but none were found, a `LANGUAGE = UNKNOWN` row with confidence 0 records that
 * they were checked, so songs are not looked up again on every mix. Tagging never fails a preparation.
 */
internal object DjTags {
    private const val TAG = "DjTags"
    private const val LYRICS_CHECKED = "UNKNOWN"
    private val metadata = MetadataTrackTagger()

    /** All tags after an analysis. [lyrics] is null when the lyrics were not looked up. */
    suspend fun save(
        repository: DjFeedbackRepository,
        track: DjTrack,
        result: AnalysisResult,
        lyrics: String?,
        lyricsLookedUp: Boolean = lyrics != null,
    ) = guarded(track) {
        val fromTitle = metadata.tag(track, null)
        repository.saveTags(track.videoId, TagSource.ANALYSIS, AnalysisTagger.tags(result))
        val fromLyrics = if (lyricsLookedUp) saveLyrics(repository, track.videoId, lyrics) else repository.storedTags(track.videoId).filter { it.source == TagSource.LYRICS }
        saveMetadata(repository, track, fromTitle, fromLyrics, result)
    }

    /** Songs analysed before stage 12 have no lyrics check yet. */
    suspend fun needsLyricsCheck(
        repository: DjFeedbackRepository,
        videoId: String,
    ): Boolean =
        try {
            repository.storedTags(videoId).none { it.source == TagSource.LYRICS }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    /** Looks up the lyrics of an already-analysed song once and stores their language (stage 12). */
    suspend fun checkLyrics(
        repository: DjFeedbackRepository,
        track: DjTrack,
        song: SongItem,
        result: AnalysisResult,
    ) = guarded(track) {
        val lyrics = DjLyrics.textFor(song) ?: return@guarded
        val fromLyrics = saveLyrics(repository, track.videoId, lyrics.ifBlank { null })
        repository.saveTags(track.videoId, TagSource.ANALYSIS, AnalysisTagger.tags(result))
        saveMetadata(repository, track, metadata.tag(track, null), fromLyrics, result)
    }

    /** Tags for songs analysed before stage 10 that have none at all (no lyrics lookup). */
    suspend fun saveIfMissing(
        repository: DjFeedbackRepository,
        track: DjTrack,
        result: AnalysisResult,
    ) {
        val hasTags =
            try {
                repository.tags(track.videoId).isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true
            }
        if (!hasTags) save(repository, track, result, lyrics = null)
    }

    private suspend fun saveLyrics(
        repository: DjFeedbackRepository,
        videoId: String,
        lyrics: String?,
    ): List<TrackTagValue> {
        val tags = lyrics?.let(LyricsLanguage::tags).orEmpty()
        val rows = tags.ifEmpty { listOf(TrackTagValue(TagType.LANGUAGE, LYRICS_CHECKED, TagSource.LYRICS, 0f)) }
        repository.saveTags(videoId, TagSource.LYRICS, rows)
        if (tags.isNotEmpty()) Timber.tag(TAG).d("%s lyrics: %s", videoId, tags.joinToString { "${it.value} ${it.confidence}" })
        return tags
    }

    private suspend fun saveMetadata(
        repository: DjFeedbackRepository,
        track: DjTrack,
        fromTitle: List<TrackTagValue>,
        fromLyrics: List<TrackTagValue>,
        result: AnalysisResult,
    ) {
        val languages =
            (fromTitle + fromLyrics)
                .filter { it.type == TagType.LANGUAGE && it.confidence >= 0.6f }
                .mapNotNull { tag -> DjLanguage.entries.firstOrNull { it.name == tag.value } }
                .toSet()
        repository.saveTags(track.videoId, TagSource.METADATA, fromTitle + GenreTagger.tags(track, languages, result))
    }

    private suspend fun guarded(
        track: DjTrack,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not save tags for %s", track.videoId)
        }
    }
}
