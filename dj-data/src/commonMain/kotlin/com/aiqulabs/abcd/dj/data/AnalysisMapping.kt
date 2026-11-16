package com.aiqulabs.abcd.dj.data

import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.ChorusCandidate
import com.aiqulabs.abcd.dj.core.analysis.ChorusSignal
import com.aiqulabs.abcd.dj.core.analysis.ChorusSource
import com.aiqulabs.abcd.dj.core.analysis.KeyMode
import com.aiqulabs.abcd.dj.core.analysis.KeyResult
import com.aiqulabs.abcd.dj.core.analysis.SectionResult
import com.aiqulabs.abcd.dj.core.analysis.SectionType
import com.aiqulabs.abcd.dj.core.analysis.TrackSummary
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.data.codec.toByteBlob
import com.aiqulabs.abcd.dj.data.codec.toDoubleBlob
import com.aiqulabs.abcd.dj.data.codec.toDoubleList
import com.aiqulabs.abcd.dj.data.codec.toIntList
import com.aiqulabs.abcd.dj.data.db.ChorusSegmentEntity
import com.aiqulabs.abcd.dj.data.db.TrackAnalysisEntity
import com.aiqulabs.abcd.dj.data.db.TrackMetaEntity
import com.aiqulabs.abcd.dj.data.db.TrackSectionEntity
import com.aiqulabs.abcd.dj.data.db.TrackSummaryRow

// Conversions between database rows and the platform-free models in dj-core.

private const val ARTIST_SEPARATOR = "\n"

internal fun AnalysisResult.toEntity(
    videoId: String,
    analyzedAt: Long,
) = TrackAnalysisEntity(
    videoId = videoId,
    analysisVersion = analysisVersion,
    durationMs = durationMs,
    bpm = bpm,
    bpmConfidence = bpmConfidence,
    bpmConfidenceRaw = bpmConfidenceRaw,
    beatsPerBar = beatsPerBar,
    downbeatConfidence = downbeatConfidence,
    beatGrid = beatsMs.toDoubleBlob(),
    downbeats = barStartsMs.toDoubleBlob(),
    phraseStartsMs = phraseStartsMs.toDoubleBlob(),
    keyRoot = key.root,
    keyMode = key.mode.name,
    camelot = key.camelot,
    keyPitchClass = key.pitchClass,
    keyConfidence = key.confidence,
    lufs = lufs,
    energyCurve = energyCurve.toDoubleBlob(),
    waveformPeaks = waveformPeaks.toByteBlob(),
    introMixableEndMs = introMixableEndMs,
    outroMixableStartMs = outroMixableStartMs,
    analyzedAt = analyzedAt,
)

internal fun AnalysisResult.sectionEntities(videoId: String) =
    sections.map {
        TrackSectionEntity(videoId = videoId, type = it.type.name, startMs = it.startMs, endMs = it.endMs, confidence = it.confidence)
    }

/** The automatic chorus rows for this result. User rows are written by the chorus editor, not here. */
internal fun AnalysisResult.autoChorusEntities(videoId: String) =
    chorusCandidates.map {
        ChorusSegmentEntity(
            videoId = videoId,
            startMs = it.startMs,
            endMs = it.endMs,
            startBar = it.startBar,
            endBar = it.endBar,
            isMainChorus = it.isMain,
            score = it.score,
            signals = it.signals.joinToString(",") { signal -> signal.name },
            source = ChorusSource.AUTO.name,
            editedAt = null,
        )
    }

internal fun TrackAnalysisEntity.toResult(
    sections: List<TrackSectionEntity>,
    choruses: List<ChorusSegmentEntity>,
) = AnalysisResult(
    analysisVersion = analysisVersion,
    durationMs = durationMs,
    bpm = bpm,
    bpmConfidence = bpmConfidence,
    bpmConfidenceRaw = bpmConfidenceRaw,
    beatsMs = beatGrid.toDoubleList(),
    beatsPerBar = beatsPerBar,
    downbeatConfidence = downbeatConfidence,
    barStartsMs = downbeats.toDoubleList(),
    phraseStartsMs = phraseStartsMs.toDoubleList(),
    key = KeyResult(keyRoot, enumOrDefault(keyMode, KeyMode.MAJOR), camelot, keyPitchClass, keyConfidence),
    lufs = lufs,
    energyCurve = energyCurve.toDoubleList(),
    waveformPeaks = waveformPeaks.toIntList(),
    introMixableEndMs = introMixableEndMs,
    outroMixableStartMs = outroMixableStartMs,
    sections =
        sections.map {
            SectionResult(enumOrDefault(it.type, SectionType.VERSE), it.startMs, it.endMs, it.confidence)
        },
    chorusCandidates = choruses.toCandidates(),
)

/**
 * Turns stored chorus rows into candidates. If the user marked a main chorus it is the only main one;
 * otherwise the automatic main stands (brief §6.1: a user's main chorus always wins).
 */
internal fun List<ChorusSegmentEntity>.toCandidates(): List<ChorusCandidate> {
    val userMain = any { it.source == ChorusSource.USER.name && it.isMainChorus }
    return map { row ->
        val source = enumOrDefault(row.source, ChorusSource.AUTO)
        ChorusCandidate(
            startMs = row.startMs,
            endMs = row.endMs,
            startBar = row.startBar,
            endBar = row.endBar,
            score = row.score,
            signals =
                row.signals
                    .split(',')
                    .filter { it.isNotBlank() }
                    .map { enumOrDefault(it, ChorusSignal.ENERGY) },
            isMain = row.isMainChorus && (!userMain || source == ChorusSource.USER),
            source = source,
            editedAt = row.editedAt,
        )
    }
}

internal fun DjTrack.toMetaEntity() =
    TrackMetaEntity(
        videoId = videoId,
        title = title,
        artists = artists.joinToString(ARTIST_SEPARATOR),
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
    )

internal fun TrackMetaEntity.toTrack() =
    DjTrack(
        videoId = videoId,
        title = title,
        artists = artists.split(ARTIST_SEPARATOR).filter { it.isNotBlank() },
        album = album,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
    )

internal fun TrackSummaryRow.toSummary() =
    TrackSummary(
        videoId = videoId,
        title = title,
        artists = artists?.split(ARTIST_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty(),
        bpm = bpm,
        bpmConfidence = bpmConfidence,
        camelot = camelot,
        analysisVersion = analysisVersion,
        analyzedAt = analyzedAt,
    )

/** Reads an enum stored by name; an unknown name (written by a newer version) falls back instead of crashing. */
private inline fun <reified T : Enum<T>> enumOrDefault(
    name: String,
    default: T,
): T = enumValues<T>().firstOrNull { it.name == name } ?: default
