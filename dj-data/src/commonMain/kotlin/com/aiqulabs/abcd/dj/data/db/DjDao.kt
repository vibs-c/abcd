package com.aiqulabs.abcd.dj.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** One row of the Lab's list, joined with the track's name when we have it. */
data class TrackSummaryRow(
    val videoId: String,
    val title: String?,
    val artists: String?,
    val bpm: Double,
    val bpmConfidence: Double,
    val camelot: String,
    val analysisVersion: Int,
    val analyzedAt: Long,
)

/** Thumbs up minus thumbs down for one technique. */
data class TechniqueScoreRow(
    val technique: String,
    val score: Int,
)

/** Thumbs up minus thumbs down for one song pair. */
data class PairScoreRow(
    val fromVideoId: String,
    val toVideoId: String,
    val score: Int,
)

@Dao
interface DjDao {
    // ---- analysis -------------------------------------------------------------------------------
    @Upsert
    suspend fun upsertAnalysis(entity: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE videoId = :videoId")
    suspend fun analysis(videoId: String): TrackAnalysisEntity?

    @Query("DELETE FROM track_analysis WHERE videoId = :videoId")
    suspend fun deleteAnalysis(videoId: String)

    /** Ids already analysed with an algorithm at least as new as [minVersion]; pre-analysis skips these. */
    @Query("SELECT videoId FROM track_analysis WHERE analysisVersion >= :minVersion")
    suspend fun analysedIds(minVersion: Int): List<String>

    // ---- sections -------------------------------------------------------------------------------
    @Query("SELECT * FROM track_section WHERE videoId = :videoId ORDER BY startMs")
    suspend fun sections(videoId: String): List<TrackSectionEntity>

    @Insert
    suspend fun insertSections(sections: List<TrackSectionEntity>)

    @Query("DELETE FROM track_section WHERE videoId = :videoId")
    suspend fun deleteSections(videoId: String)

    // ---- chorus segments ------------------------------------------------------------------------
    @Query("SELECT * FROM chorus_segment WHERE videoId = :videoId ORDER BY startMs")
    suspend fun choruses(videoId: String): List<ChorusSegmentEntity>

    @Insert
    suspend fun insertChoruses(choruses: List<ChorusSegmentEntity>)

    @Query("DELETE FROM chorus_segment WHERE videoId = :videoId AND source = 'AUTO'")
    suspend fun deleteAutoChoruses(videoId: String)

    @Query("SELECT COUNT(*) FROM chorus_segment WHERE videoId = :videoId AND source = 'USER'")
    suspend fun userChorusCount(videoId: String): Int

    @Query("DELETE FROM chorus_segment WHERE videoId = :videoId")
    suspend fun deleteAllChoruses(videoId: String)

    // ---- names ----------------------------------------------------------------------------------
    @Upsert
    suspend fun upsertMeta(meta: TrackMetaEntity)

    @Query("SELECT * FROM track_meta WHERE videoId = :videoId")
    suspend fun meta(videoId: String): TrackMetaEntity?

    // ---- Lab ------------------------------------------------------------------------------------
    @Query(
        "SELECT a.videoId AS videoId, m.title AS title, m.artists AS artists, a.bpm AS bpm, " +
            "a.bpmConfidence AS bpmConfidence, a.camelot AS camelot, a.analysisVersion AS analysisVersion, " +
            "a.analyzedAt AS analyzedAt FROM track_analysis a LEFT JOIN track_meta m ON m.videoId = a.videoId " +
            "ORDER BY a.analyzedAt DESC",
    )
    fun observeSummaries(): Flow<List<TrackSummaryRow>>

    @Query("SELECT COUNT(*) FROM track_analysis")
    suspend fun analysisCount(): Int

    @Query("SELECT COUNT(*) FROM track_section")
    suspend fun sectionCount(): Int

    @Query("SELECT COUNT(*) FROM chorus_segment WHERE source = :source")
    suspend fun chorusCount(source: String): Int

    @Query("SELECT COUNT(*) FROM track_tag")
    suspend fun tagCount(): Int

    @Query("SELECT COUNT(*) FROM transition_feedback")
    suspend fun transitionFeedbackCount(): Int

    @Query("SELECT COUNT(*) FROM track_feedback")
    suspend fun trackFeedbackCount(): Int

    @Query("SELECT COUNT(*) FROM dj_session")
    suspend fun sessionCount(): Int

    // ---- tags (stage 10) --------------------------------------------------------------------------
    @Query("SELECT * FROM track_tag WHERE videoId = :videoId")
    suspend fun tags(videoId: String): List<TrackTagEntity>

    @Query("DELETE FROM track_tag WHERE videoId = :videoId AND source = :source")
    suspend fun deleteTags(
        videoId: String,
        source: String,
    )

    @Upsert
    suspend fun upsertTags(tags: List<TrackTagEntity>)

    /** Replaces the tags one source gave [videoId]; other sources' tags (the user's above all) stay. */
    @Transaction
    suspend fun replaceTags(
        videoId: String,
        source: String,
        tags: List<TrackTagEntity>,
    ) {
        deleteTags(videoId, source)
        if (tags.isNotEmpty()) upsertTags(tags)
    }

    // ---- transition feedback ----------------------------------------------------------------------
    @Insert
    suspend fun insertTransitionFeedback(entity: TransitionFeedbackEntity): Long

    @Query("UPDATE transition_feedback SET outcome = :outcome WHERE id = :id")
    suspend fun setTransitionOutcome(
        id: Long,
        outcome: String,
    )

    @Query(
        "SELECT technique AS technique, SUM(CASE outcome WHEN 'THUMBS_UP' THEN 1 WHEN 'THUMBS_DOWN' THEN -1 ELSE 0 END) AS score " +
            "FROM transition_feedback GROUP BY technique",
    )
    suspend fun techniqueScores(): List<TechniqueScoreRow>

    @Query(
        "SELECT fromVideoId AS fromVideoId, toVideoId AS toVideoId, " +
            "SUM(CASE outcome WHEN 'THUMBS_UP' THEN 1 WHEN 'THUMBS_DOWN' THEN -1 ELSE 0 END) AS score " +
            "FROM transition_feedback GROUP BY fromVideoId, toVideoId HAVING score != 0",
    )
    suspend fun pairScores(): List<PairScoreRow>

    // ---- track feedback ---------------------------------------------------------------------------
    @Query(
        "INSERT OR IGNORE INTO track_feedback (videoId, playCount, skipCount, djPlayCount, removeCount, banned, lastPlayedAt) " +
            "VALUES (:videoId, 0, 0, 0, 0, 0, NULL)",
    )
    suspend fun ensureTrackFeedback(videoId: String)

    @Query("UPDATE track_feedback SET djPlayCount = djPlayCount + 1, lastPlayedAt = :now WHERE videoId = :videoId")
    suspend fun addDjPlay(
        videoId: String,
        now: Long,
    )

    @Query("UPDATE track_feedback SET skipCount = skipCount + 1 WHERE videoId = :videoId")
    suspend fun addSkip(videoId: String)

    @Query("UPDATE track_feedback SET removeCount = removeCount + 1 WHERE videoId = :videoId")
    suspend fun addRemove(videoId: String)

    @Query("UPDATE track_feedback SET banned = :banned WHERE videoId = :videoId")
    suspend fun setBanned(
        videoId: String,
        banned: Boolean,
    )

    /** Only rows that change anything: skipped, removed or banned tracks. */
    @Query("SELECT * FROM track_feedback WHERE skipCount > 0 OR removeCount > 0 OR banned = 1")
    suspend fun trackFeedbackWithVerdicts(): List<TrackFeedbackEntity>

    // ---- sessions ---------------------------------------------------------------------------------
    @Insert
    suspend fun insertSession(entity: DjSessionEntity): Long

    @Query("UPDATE dj_session SET endedAt = :endedAt, tracksPlayedJson = :tracksPlayedJson WHERE id = :id")
    suspend fun updateSession(
        id: Long,
        endedAt: Long?,
        tracksPlayedJson: String,
    )

    // ---- one atomic save --------------------------------------------------------------------------

    /**
     * Saves a fresh analysis in one transaction.
     *
     * Brief §6.1: re-analysis replaces only `AUTO` chorus segments, and only when the track has no
     * `USER` segments. If the user has edited the chorus, their segments and the old automatic ones are
     * left exactly as they are.
     */
    @Transaction
    suspend fun replaceAnalysis(
        analysis: TrackAnalysisEntity,
        sections: List<TrackSectionEntity>,
        autoChoruses: List<ChorusSegmentEntity>,
    ) {
        upsertAnalysis(analysis)
        deleteSections(analysis.videoId)
        if (sections.isNotEmpty()) insertSections(sections)
        if (userChorusCount(analysis.videoId) == 0) {
            deleteAutoChoruses(analysis.videoId)
            if (autoChoruses.isNotEmpty()) insertChoruses(autoChoruses)
        }
    }

    /** Removes an analysis and its sections; automatic chorus segments go too, the user's stay. */
    @Transaction
    suspend fun removeAnalysis(videoId: String) {
        deleteAnalysis(videoId)
        deleteSections(videoId)
        deleteAutoChoruses(videoId)
    }
}
