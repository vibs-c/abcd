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
