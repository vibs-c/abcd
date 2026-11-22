package com.aiqulabs.abcd.dj.data

import com.aiqulabs.abcd.dj.core.feedback.FeedbackSnapshot
import com.aiqulabs.abcd.dj.core.feedback.TransitionOutcome
import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue
import com.aiqulabs.abcd.dj.data.db.DjDao
import com.aiqulabs.abcd.dj.data.db.DjSessionEntity
import com.aiqulabs.abcd.dj.data.db.TrackTagEntity
import com.aiqulabs.abcd.dj.data.db.TransitionFeedbackEntity
import kotlin.time.Clock

/**
 * Feedback, tags and sessions in the DJ database (brief §6.1-§6.3, stage 10). Everything stays on the phone.
 *
 * Writes are small and frequent (one per transition), reads happen once per mix ([snapshot]).
 */
class DjFeedbackRepository(
    private val dao: DjDao,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    // ---- transitions --------------------------------------------------------------------------------

    /** Records a transition as it starts; returns its row id so a thumb can be put on it later. */
    suspend fun recordTransition(
        fromVideoId: String,
        toVideoId: String,
        technique: TransitionTechnique,
        bpmDelta: Double,
        keyCompat: String,
        outcome: TransitionOutcome,
    ): Long =
        dao.insertTransitionFeedback(
            TransitionFeedbackEntity(
                fromVideoId = fromVideoId,
                toVideoId = toVideoId,
                technique = technique.name,
                bpmDelta = bpmDelta,
                keyCompat = keyCompat,
                outcome = outcome.name,
                timestamp = now(),
            ),
        )

    /** Thumbs up or down on a transition recorded earlier (replaces COMPLETED/SKIPPED; one verdict per transition). */
    suspend fun rateTransition(
        id: Long,
        outcome: TransitionOutcome,
    ) = dao.setTransitionOutcome(id, outcome.name)

    // ---- tracks -------------------------------------------------------------------------------------

    suspend fun trackPlayed(videoId: String) {
        dao.ensureTrackFeedback(videoId)
        dao.addDjPlay(videoId, now())
    }

    suspend fun trackSkipped(videoId: String) {
        dao.ensureTrackFeedback(videoId)
        dao.addSkip(videoId)
    }

    suspend fun trackRemoved(videoId: String) {
        dao.ensureTrackFeedback(videoId)
        dao.addRemove(videoId)
    }

    suspend fun setBanned(
        videoId: String,
        banned: Boolean,
    ) {
        dao.ensureTrackFeedback(videoId)
        dao.setBanned(videoId, banned)
    }

    // ---- sessions -----------------------------------------------------------------------------------

    suspend fun startSession(settings: DjSessionSettings): Long =
        dao.insertSession(DjSessionEntity(settingsJson = settings.toJson(), startedAt = now(), endedAt = null, tracksPlayedJson = "[]"))

    /** Saves the tracks played so far; pass [ended] when the session is over. */
    suspend fun updateSession(
        id: Long,
        playedVideoIds: List<String>,
        ended: Boolean,
    ) = dao.updateSession(id, if (ended) now() else null, jsonArray(playedVideoIds))

    // ---- tags ---------------------------------------------------------------------------------------

    /** Replaces the tags [source] gave [videoId]. Tags from other sources (the user's above all) are kept. */
    suspend fun saveTags(
        videoId: String,
        source: TagSource,
        tags: List<TrackTagValue>,
    ) = dao.replaceTags(
        videoId,
        source.name,
        tags.filter { it.source == source }.distinctBy { it.type to it.value }.map {
            TrackTagEntity(videoId, it.type.name, it.value, it.source.name, it.confidence.toDouble())
        },
    )

    suspend fun tags(videoId: String) = dao.tags(videoId)

    // ---- what the DJ reads ----------------------------------------------------------------------------

    /** Everything feedback says, for building and ordering a mix and choosing techniques (brief §6.3). */
    suspend fun snapshot(): FeedbackSnapshot {
        val tracks = dao.trackFeedbackWithVerdicts()
        val techniques = TransitionTechnique.entries.associateBy { it.name }
        return FeedbackSnapshot(
            banned = tracks.filter { it.banned }.map { it.videoId }.toSet(),
            removeCounts = tracks.filter { it.removeCount > 0 }.associate { it.videoId to it.removeCount },
            skipCounts = tracks.filter { it.skipCount > 0 }.associate { it.videoId to it.skipCount },
            pairScores = dao.pairScores().associate { (it.fromVideoId to it.toVideoId) to it.score },
            techniqueScores =
                dao.techniqueScores().mapNotNull { row -> techniques[row.technique]?.let { it to row.score } }.toMap(),
        )
    }

    private fun jsonArray(ids: List<String>): String =
        ids.joinToString(prefix = "[", postfix = "]", separator = ",") { id ->
            "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
}
