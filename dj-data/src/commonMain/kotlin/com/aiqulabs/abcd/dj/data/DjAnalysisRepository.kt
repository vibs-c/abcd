package com.aiqulabs.abcd.dj.data

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.ChorusSource
import com.aiqulabs.abcd.dj.core.analysis.DjDatabaseCounts
import com.aiqulabs.abcd.dj.core.analysis.StoredAnalysis
import com.aiqulabs.abcd.dj.core.analysis.TrackSummary
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.data.db.DjDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * Saves and loads track analyses in the DJ database (brief §6.1).
 *
 * The rule that matters most: **re-analysis never overwrites the user's chorus edits.** That is
 * enforced in one transaction inside [DjDao.replaceAnalysis].
 */
class DjAnalysisRepository(
    private val dao: DjDao,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * Saves [result] for [videoId]. Pass [track] to remember the song's name for the Lab.
     * Existing sections are replaced; automatic chorus segments are replaced only if the user has not
     * edited the chorus.
     */
    suspend fun save(
        videoId: String,
        result: AnalysisResult,
        track: DjTrack? = null,
    ) {
        track?.let { dao.upsertMeta(it.toMetaEntity()) }
        dao.replaceAnalysis(
            analysis = result.toEntity(videoId, now()),
            sections = result.sectionEntities(videoId),
            autoChoruses = result.autoChorusEntities(videoId),
        )
    }

    /** The stored analysis of [videoId], with user chorus edits applied, or null if never analysed. */
    suspend fun load(videoId: String): StoredAnalysis? {
        val entity = dao.analysis(videoId) ?: return null
        val result = entity.toResult(dao.sections(videoId), dao.choruses(videoId))
        return StoredAnalysis(videoId, result, entity.analyzedAt)
    }

    /** The name we stored for [videoId], if any (songs analysed from a file on the phone have only this). */
    suspend fun trackInfo(videoId: String): DjTrack? = dao.meta(videoId)?.toTrack()

    /** True if [videoId] was analysed with an algorithm at least as new as [minVersion]. */
    suspend fun isAnalysed(
        videoId: String,
        minVersion: Int,
    ): Boolean = dao.analysis(videoId)?.let { it.analysisVersion >= minVersion } == true

    /** Ids analysed with [minVersion] or newer, so a batch can skip them. */
    suspend fun analysedIds(minVersion: Int): Set<String> = dao.analysedIds(minVersion).toSet()

    /** Every analysed track, newest first, for the Lab. */
    fun observeSummaries(): Flow<List<TrackSummary>> = dao.observeSummaries().map { rows -> rows.map { it.toSummary() } }

    /** Deletes the analysis of [videoId]. The user's own chorus edits are kept. */
    suspend fun delete(videoId: String) = dao.removeAnalysis(videoId)

    /** Row counts of every table, for the Lab's "database contents". */
    suspend fun counts() =
        DjDatabaseCounts(
            analyses = dao.analysisCount(),
            sections = dao.sectionCount(),
            autoChoruses = dao.chorusCount(ChorusSource.AUTO.name),
            userChoruses = dao.chorusCount(ChorusSource.USER.name),
            tags = dao.tagCount(),
            transitionFeedback = dao.transitionFeedbackCount(),
            trackFeedback = dao.trackFeedbackCount(),
            sessions = dao.sessionCount(),
        )
}
