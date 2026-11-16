package com.aiqulabs.abcd.dj.data.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

// Tables for the analysis of a track (brief §6.1). Everything is keyed by the YouTube `videoId`, and
// every value that could be shared with a server later carries its source and confidence (§6.4).
// Enums are stored as their names (text), so adding a value never breaks old rows.
// Arrays are stored as BLOBs: doubles as 8 little-endian bytes, waveform peaks as 1 byte each.

/** One row per analysed track: tempo, key, loudness and the arrays derived from them. */
@Entity(tableName = "track_analysis")
@Suppress("ArrayInDataClass")
data class TrackAnalysisEntity(
    @PrimaryKey val videoId: String,
    /** Version of the analysis algorithms; lets stale rows be re-analysed. */
    val analysisVersion: Int,
    val durationMs: Double,
    val bpm: Double,
    val bpmConfidence: Double,
    val bpmConfidenceRaw: Double,
    val beatsPerBar: Int,
    val downbeatConfidence: Double,
    /** Beat times in ms, as doubles. */
    val beatGrid: ByteArray,
    /** Start of every bar (the downbeats), ms, as doubles. */
    val downbeats: ByteArray,
    val phraseStartsMs: ByteArray,
    val keyRoot: String,
    val keyMode: String,
    val camelot: String,
    val keyPitchClass: Int,
    val keyConfidence: Double,
    val lufs: Double,
    /** Loudness per bar, 0..1, as doubles. */
    val energyCurve: ByteArray,
    /** Waveform overview, one byte (0..255) per slice. */
    val waveformPeaks: ByteArray,
    val introMixableEndMs: Double,
    val outroMixableStartMs: Double,
    val analyzedAt: Long,
)

/** Sections of a track (intro, verse, chorus, ...). Replaced as a whole on every analysis. */
@Entity(tableName = "track_section", indices = [Index("videoId")])
data class TrackSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val type: String,
    val startMs: Double,
    val endMs: Double,
    val confidence: Double,
)

/**
 * Chorus candidates. `source = AUTO` rows come from the analysis; `source = USER` rows are the user's
 * edits and are never overwritten by re-analysis. There is deliberately no foreign key to
 * `track_analysis`: a user's edit must survive even if the analysis row is deleted and recreated.
 */
@Entity(tableName = "chorus_segment", indices = [Index("videoId")])
data class ChorusSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val startMs: Double,
    val endMs: Double,
    val startBar: Int,
    val endBar: Int,
    val isMainChorus: Boolean,
    val score: Double,
    /** Comma-separated [com.aiqulabs.abcd.dj.core.analysis.ChorusSignal] names. */
    val signals: String,
    /** `AUTO` or `USER`. */
    val source: String,
    val editedAt: Long?,
)

/**
 * What we know about a song as text: title and artists. Not part of the analysis (and never shared);
 * it lets the Lab show names for songs that are not in Metrolist's own library.
 */
@Entity(tableName = "track_meta")
data class TrackMetaEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    /** Artist names separated by a line feed. */
    val artists: String,
    val album: String?,
    val durationMs: Long?,
    val thumbnailUrl: String?,
)
