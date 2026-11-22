package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.core.tagging.AnalysisTagger
import com.aiqulabs.abcd.dj.core.tagging.MetadataTrackTagger
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.data.DjFeedbackRepository
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Writes a track's tags after analysis (brief §6.2, stage 10): LANGUAGE from metadata and lyrics script
 * (source METADATA), MOOD and ENERGY_CLASS from the analysis (source ANALYSIS). Tagging never fails a
 * preparation: an error is logged and the track plays anyway.
 */
internal object DjTags {
    private const val TAG = "DjTags"
    private val metadata = MetadataTrackTagger()

    suspend fun save(
        repository: DjFeedbackRepository,
        track: DjTrack,
        result: AnalysisResult,
        lyrics: String?,
    ) {
        try {
            repository.saveTags(track.videoId, TagSource.METADATA, metadata.tag(track, lyrics))
            repository.saveTags(track.videoId, TagSource.ANALYSIS, AnalysisTagger.tags(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not save tags for %s", track.videoId)
        }
    }

    /** For tracks analysed before stage 10: tag them once, from metadata and analysis (no lyrics fetch). */
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
}
