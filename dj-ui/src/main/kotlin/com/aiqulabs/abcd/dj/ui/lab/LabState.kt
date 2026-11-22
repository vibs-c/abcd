package com.aiqulabs.abcd.dj.ui.lab

import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique
import com.aiqulabs.abcd.dj.core.analysis.DjDatabaseCounts
import com.aiqulabs.abcd.dj.core.analysis.StoredAnalysis
import com.aiqulabs.abcd.dj.core.analysis.TrackSummary
import com.aiqulabs.abcd.dj.ui.library.LibraryAnalysisState

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

    /** The DJ log was copied to the clipboard for a bug report. */
    data object LogCopied : LabMessage
}

/** Everything the Lab screens show. */
data class LabState(
    val analysed: List<TrackSummary> = emptyList(),
    val local: List<LabLocalSong> = emptyList(),
    val counts: DjDatabaseCounts = DjDatabaseCounts(),
    val detail: LabDetail? = null,
    val busy: LabBusy? = null,
    val message: LabMessage? = null,
    val library: LibraryAnalysisState = LibraryAnalysisState.Idle,
    /** Bytes of songs abcd downloaded for analysis (not Metrolist's own downloads or cache). */
    val audioFilesBytes: Long = 0L,
)

/** What the Lab screens can ask for. */
data class LabActions(
    val onAnalyse: (videoId: String) -> Unit,
    val onOpen: (videoId: String) -> Unit,
    val onCloseDetail: () -> Unit,
    val onDelete: (videoId: String) -> Unit,
    val onDismissMessage: () -> Unit,
    val onPickFile: () -> Unit,
    val onAnalyseLibrary: () -> Unit,
    val onStopLibrary: () -> Unit,
    val onClearAudioFiles: () -> Unit,
    val onBack: () -> Unit,
    /** Copies the recent DJ log (failures and their reasons) for a bug report. */
    val onCopyLog: () -> Unit = {},
    /** Audition [fromId] into [toId] with a technique (null = automatic), brief §4.5. */
    val onAudition: (fromId: String, toId: String, technique: TransitionTechnique?) -> Unit = { _, _, _ -> },
    val onStopAudition: () -> Unit = {},
)
