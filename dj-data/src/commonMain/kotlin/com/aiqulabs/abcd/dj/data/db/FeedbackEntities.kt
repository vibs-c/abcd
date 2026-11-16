package com.aiqulabs.abcd.dj.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Tags, feedback and sessions (brief §6.1). Written from stage 10 on; the tables exist now so the
// first database version already holds every v1 entity and no early migration is needed.

/** A language, mood, genre, era or energy class of a track, with where it came from. */
@Entity(tableName = "track_tag", primaryKeys = ["videoId", "tagType", "value", "source"], indices = [Index("videoId")])
data class TrackTagEntity(
    val videoId: String,
    /** `LANGUAGE`, `MOOD`, `GENRE`, `ERA` or `ENERGY_CLASS`. */
    val tagType: String,
    val value: String,
    /** `METADATA`, `ANALYSIS`, `LLM` or `USER`. User corrections always win. */
    val source: String,
    val confidence: Double,
)

/** How one transition between two tracks went, for learning which techniques suit which pairs. */
@Entity(tableName = "transition_feedback", indices = [Index("fromVideoId"), Index("toVideoId")])
data class TransitionFeedbackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromVideoId: String,
    val toVideoId: String,
    /** Name of the transition technique; text so v2 techniques need no migration. */
    val technique: String,
    val bpmDelta: Double,
    val keyCompat: String,
    /** A [com.aiqulabs.abcd.dj.core.feedback.TransitionOutcome] name. */
    val outcome: String,
    val timestamp: Long,
)

/** Per-track play history and the user's verdicts. */
@Entity(tableName = "track_feedback")
data class TrackFeedbackEntity(
    @PrimaryKey val videoId: String,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val djPlayCount: Int = 0,
    /** Times the user removed this track from a DJ queue; often-removed tracks sink (brief §6.3). */
    val removeCount: Int = 0,
    val banned: Boolean = false,
    val lastPlayedAt: Long? = null,
)

/** One DJ session: the settings it ran with and the tracks it played. */
@Entity(tableName = "dj_session")
data class DjSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** `DjSessionSettings` as JSON, including the v2 fields. */
    val settingsJson: String,
    val startedAt: Long,
    val endedAt: Long?,
    /** JSON array of the video ids played, in order. */
    val tracksPlayedJson: String,
)
