package com.aiqulabs.abcd.dj.ui.lab

import com.aiqulabs.abcd.dj.core.analysis.DjDatabaseCounts
import com.aiqulabs.abcd.dj.core.analysis.StoredAnalysis
import com.aiqulabs.abcd.dj.core.analysis.TrackSummary

/** A song whose audio file is on the phone (downloaded, or played recently and cached). */
data class LabLocalSong(
    val videoId: String,
    val title: String,
    val artists: String,
    val analysed: Boolean,
)

/** The analysis of one song, open on the detail page. */
data class LabDetail(
    val videoId: String,
    val title: String,
    val artists: String,
    val stored: StoredAnalysis,
)

/** What the analysis is doing right now. */
data class LabBusy(
    val title: String,
    val step: Step,
) {
    enum class Step { DECODING, ANALYSING }
}

/** Something the user should be told. */
sealed interface LabMessage {
    data object NoAudioFile : LabMessage

    data object UnsupportedFile : LabMessage

    data class Failed(
        val reason: String,
    ) : LabMessage
}

/** Everything the Lab screens show. */
data class LabState(
    val analysed: List<TrackSummary> = emptyList(),
    val local: List<LabLocalSong> = emptyList(),
    val counts: DjDatabaseCounts = DjDatabaseCounts(),
    val detail: LabDetail? = null,
    val busy: LabBusy? = null,
    val message: LabMessage? = null,
)

/** What the Lab screens can ask for. */
data class LabActions(
    val onAnalyse: (videoId: String) -> Unit,
    val onOpen: (videoId: String) -> Unit,
    val onCloseDetail: () -> Unit,
    val onDelete: (videoId: String) -> Unit,
    val onDismissMessage: () -> Unit,
    val onPickFile: () -> Unit,
    val onBack: () -> Unit,
)
