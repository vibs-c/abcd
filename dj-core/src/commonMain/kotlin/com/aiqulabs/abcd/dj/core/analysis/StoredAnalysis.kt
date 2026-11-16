package com.aiqulabs.abcd.dj.core.analysis

/** An analysis as saved in the DJ database. */
data class StoredAnalysis(
    val videoId: String,
    val result: AnalysisResult,
    /** When the analysis was saved, in epoch milliseconds. */
    val analyzedAt: Long,
) {
    /** True when the user has edited this track's chorus, so re-analysis must not replace it. */
    val hasUserChorus: Boolean get() = result.chorusCandidates.any { it.source == ChorusSource.USER }
}

/** One row of the Lab's list of analysed tracks. [title] is null when the song was never described to us. */
data class TrackSummary(
    val videoId: String,
    val title: String?,
    val artists: List<String>,
    val bpm: Double,
    val bpmConfidence: Double,
    val camelot: String,
    val analysisVersion: Int,
    val analyzedAt: Long,
)

/** Row counts of the DJ database, shown in the Lab. */
data class DjDatabaseCounts(
    val analyses: Int = 0,
    val sections: Int = 0,
    val autoChoruses: Int = 0,
    val userChoruses: Int = 0,
    val tags: Int = 0,
    val transitionFeedback: Int = 0,
    val trackFeedback: Int = 0,
    val sessions: Int = 0,
)
