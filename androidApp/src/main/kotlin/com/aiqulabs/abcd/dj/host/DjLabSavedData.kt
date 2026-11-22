package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.core.analysis.StoredAnalysis
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue
import com.aiqulabs.abcd.dj.data.db.TrackFeedbackEntity
import com.aiqulabs.abcd.dj.data.db.TransitionFeedbackEntity
import com.aiqulabs.abcd.dj.core.model.DjTrack
import com.aiqulabs.abcd.dj.ui.lab.LabSavedGroup
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the DJ database holds for one song, formatted table by table for the Lab (stage 12). Developer
 * screen: plain English labels, raw values.
 */
internal object DjLabSavedData {
    fun groups(
        videoId: String,
        meta: DjTrack?,
        stored: StoredAnalysis,
        tags: List<TrackTagValue>,
        feedback: TrackFeedbackEntity?,
        transitions: List<TransitionFeedbackEntity>,
    ): List<LabSavedGroup> {
        val a = stored.result
        val groups = ArrayList<LabSavedGroup>()
        groups +=
            LabSavedGroup(
                "track_meta",
                listOf(
                    "videoId" to videoId,
                    "title" to (meta?.title ?: "-"),
                    "artists" to (meta?.artists?.joinToString(", ") ?: "-"),
                    "album" to (meta?.album ?: "-"),
                    "durationMs" to (meta?.durationMs?.toString() ?: "-"),
                    "thumbnailUrl" to (meta?.thumbnailUrl ?: "-"),
                ),
            )
        groups +=
            LabSavedGroup(
                "track_analysis",
                listOf(
                    "analysisVersion" to a.analysisVersion.toString(),
                    "analyzedAt" to time(stored.analyzedAt),
                    "durationMs" to num(a.durationMs, 0),
                    "analysisSampleRate" to a.analysisSampleRate.toString(),
                    "bpm" to num(a.bpm, 2),
                    "bpmConfidence" to num(a.bpmConfidence, 3) + "  (raw " + num(a.bpmConfidenceRaw, 2) + ")",
                    "beatGrid" to "${a.beatsMs.size} beats, first ${a.beatsMs.take(4).joinToString { num(it, 0) }} ms",
                    "beatsPerBar" to a.beatsPerBar.toString(),
                    "downbeatConfidence" to num(a.downbeatConfidence, 3),
                    "downbeats (bars)" to "${a.barStartsMs.size}, first ${a.barStartsMs.take(3).joinToString { num(it, 0) }} ms",
                    "phraseStartsMs" to "${a.phraseStartsMs.size}: ${a.phraseStartsMs.take(6).joinToString { clock(it) }}",
                    "key" to "${a.key.root} ${a.key.mode.name.lowercase()} (${a.key.camelot}, pitch class ${a.key.pitchClass})",
                    "keyConfidence" to num(a.key.confidence, 3),
                    "lufs" to num(a.lufs, 1),
                    "energyCurve" to
                        if (a.energyCurve.isEmpty()) {
                            "-"
                        } else {
                            "${a.energyCurve.size} bars, min ${num(a.energyCurve.min(), 2)} avg ${num(a.energyCurve.average(), 2)} max ${num(a.energyCurve.max(), 2)}"
                        },
                    "waveformPeaks" to "${a.waveformPeaks.size} points",
                    "introMixableEndMs" to clock(a.introMixableEndMs),
                    "outroMixableStartMs" to clock(a.outroMixableStartMs),
                ),
            )
        if (a.sections.isNotEmpty()) {
            groups +=
                LabSavedGroup(
                    "track_section (${a.sections.size})",
                    a.sections.map { it.type.name to "${clock(it.startMs)} - ${clock(it.endMs)}  conf ${num(it.confidence, 2)}" },
                )
        }
        if (a.chorusCandidates.isNotEmpty()) {
            groups +=
                LabSavedGroup(
                    "chorus_segment (${a.chorusCandidates.size})",
                    a.chorusCandidates.map {
                        val main = if (it.isMain) " MAIN" else ""
                        "${it.source.name}$main" to
                            "${clock(it.startMs)} - ${clock(it.endMs)}  bars ${it.startBar}-${it.endBar}  score ${num(it.score, 2)}  " +
                            it.signals.joinToString("+") { s -> s.name } + (it.editedAt?.let { t -> "  edited ${time(t)}" } ?: "")
                    },
                )
        }
        groups +=
            LabSavedGroup(
                "track_tag (${tags.size})",
                tags.sortedWith(compareBy({ it.type.ordinal }, { -it.confidence })).map {
                    it.type.name to "${it.value}  · ${it.source.name} · ${num(it.confidence.toDouble(), 2)}"
                }.ifEmpty { listOf("-" to "no tags yet") },
            )
        groups +=
            LabSavedGroup(
                "track_feedback",
                if (feedback == null) {
                    listOf("-" to "not played in a mix yet")
                } else {
                    listOf(
                        "djPlayCount" to feedback.djPlayCount.toString(),
                        "skipCount" to feedback.skipCount.toString(),
                        "removeCount" to feedback.removeCount.toString(),
                        "banned" to feedback.banned.toString(),
                        "lastPlayedAt" to (feedback.lastPlayedAt?.let(::time) ?: "-"),
                    )
                },
            )
        if (transitions.isNotEmpty()) {
            groups +=
                LabSavedGroup(
                    "transition_feedback (${transitions.size} latest)",
                    transitions.map {
                        val direction = if (it.fromVideoId == videoId) "→ ${it.toVideoId}" else "← ${it.fromVideoId}"
                        direction to "${it.technique}  ${it.outcome}  Δ${num(it.bpmDelta, 1)} BPM  ${it.keyCompat}  ${time(it.timestamp)}"
                    },
                )
        }
        return groups
    }

    private fun num(
        v: Double,
        decimals: Int,
    ): String = if (v.isNaN()) "-" else String.format(Locale.US, "%.${decimals}f", v)

    private fun clock(ms: Double): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun time(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
}
