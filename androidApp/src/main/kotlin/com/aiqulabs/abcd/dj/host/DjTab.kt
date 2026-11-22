package com.aiqulabs.abcd.dj.host

import com.aiqulabs.abcd.dj.engine.DjEngineState
import com.aiqulabs.abcd.dj.ui.live.DjLiveActions
import com.aiqulabs.abcd.dj.ui.live.DjLiveScreen
import com.aiqulabs.abcd.dj.ui.live.DjLiveScreenUi
import com.aiqulabs.abcd.dj.ui.live.DjLiveUi
import com.aiqulabs.abcd.dj.ui.live.DjQueueItem
import com.aiqulabs.abcd.dj.ui.live.DjThumbsUi
import com.aiqulabs.abcd.dj.ui.setup.DjStartState
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aiqulabs.abcd.dj.data.DjSetupStore
import com.aiqulabs.abcd.dj.ui.lab.LabActions
import com.aiqulabs.abcd.dj.ui.lab.LabScreen
import com.aiqulabs.abcd.dj.ui.setup.DjSetupScreen
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.tabContentTopPadding

/**
 * Content of the DJ tab: the live screen while a mix runs (stage 10), the setup screen wired to storage
 * ([DjSetupStore]) and to [DjController], and, when the owner has switched the Lab on, the Lab ([DjLabController]).
 */
@Composable
fun DjTab(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val playerConnection = LocalPlayerConnection.current
    val store = remember { DjSetupStore(appContext) }
    var draft by remember { mutableStateOf(store.load()) }
    var labUnlocked by remember { mutableStateOf(store.labUnlocked) }
    var labEnabled by remember { mutableStateOf(store.labEnabled && store.labUnlocked) }
    var labOpen by remember { mutableStateOf(false) }
    val startState by DjController.state.collectAsState()
    val libraryState by DjLibraryAnalyzer.state.collectAsState()
    val engineState by DjPlayback.state.collectAsState()
    val lastTransition by DjPlayback.lastTransition.collectAsState()
    val live = engineState?.let(::liveUiOf)
    // While a mix runs the tab shows the live screen; "Set up a new mix" switches to the setup screen.
    var showSetup by remember { mutableStateOf(false) }
    LaunchedEffect(live == null, startState is DjStartState.Started) {
        if (live == null || startState is DjStartState.Started) showSetup = false
    }
    // Same insets as the Home and Library tabs: below the top bar, above the mini player.
    val insets = PaddingValues(top = tabContentTopPadding(), bottom = LocalMiniPlayerBottomInset.current)

    if (labOpen && labEnabled) {
        val labState by DjLabController.state.collectAsState()
        LaunchedEffect(Unit) {
            DjLabController.attach(appContext)
            DjLabController.refreshLocalSongs()
        }
        val picker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) DjLabController.analyseFile(uri.toString(), displayNameOf(appContext, uri))
            }
        // Back closes the song first, then the Lab, instead of leaving the DJ tab.
        BackHandler {
            if (labState.detail != null) DjLabController.closeDetail() else labOpen = false
        }
        LabScreen(
            state = labState,
            actions =
                LabActions(
                    onAnalyse = DjLabController::analyse,
                    onOpen = DjLabController::open,
                    onCloseDetail = DjLabController::closeDetail,
                    onDelete = DjLabController::delete,
                    onDismissMessage = DjLabController::dismissMessage,
                    onPickFile = { picker.launch(arrayOf("audio/*")) },
                    onAnalyseLibrary = DjLabController::analyseLibrary,
                    onStopLibrary = DjLabController::stopLibrary,
                    onClearAudioFiles = DjLabController::clearAudioFiles,
                    onBack = { labOpen = false },
                    onCopyLog = DjLabController::copyLog,
                    onAudition = { from, to, technique ->
                        DjLabController.audition(from, to, technique) { playerConnection?.awaitConnected() }
                    },
                    onStopAudition = DjLabController::stopAudition,
                    onSetTags = DjLabController::setTags,
                ),
            modifier = modifier.fillMaxSize(),
            contentPadding = insets,
        )
        return
    }

    val running = engineState
    if (live != null && running != null && !showSetup) {
        val timeline by rememberDjTimeline()
        DjLiveScreen(
            ui = DjLiveScreenUi(live, queueOf(running), lastTransition?.let { DjThumbsUi(it.fromTitle, it.toTitle, it.rating) }, timeline),
            actions =
                DjLiveActions(
                    onPlayPause = DjPlayback::togglePlayPause,
                    onNext = DjPlayback::skipNext,
                    onEnd = DjPlayback::end,
                    onThumb = DjPlayback::rateLastTransition,
                    onRemove = DjPlayback::removeUpcoming,
                    onMove = DjPlayback::moveUpcoming,
                    onNewMix = { showSetup = true },
                ),
            modifier = modifier.fillMaxSize(),
            contentPadding = insets,
        )
        return
    }
    BackHandler(enabled = live != null) { showSetup = false }

    DjSetupScreen(
        draft = draft,
        startState = startState,
        onDraftChange = {
            draft = it
            store.save(it)
        },
        onStart = {
            val settings = draft.toSettings()
            if (settings != null && playerConnection != null) DjController.start(appContext, settings, playerConnection)
        },
        onCancel = DjController::cancel,
        onDismissResult = DjController::dismissResult,
        libraryState = libraryState,
        onAnalyseLibrary = { DjLibraryAnalyzer.request(appContext) },
        onStopLibrary = DjLibraryAnalyzer::stop,
        labEnabled = labEnabled,
        onLabEnabledChange = {
            labEnabled = it
            store.labEnabled = it
        },
        onOpenLab = { labOpen = true },
        labUnlocked = labUnlocked,
        onLabUnlockedChange = {
            labUnlocked = it
            store.labUnlocked = it
            if (!it) {
                // Hiding the developer switch also switches the Lab off.
                labEnabled = false
                store.labEnabled = false
            }
        },
        modifier = modifier.fillMaxSize(),
        contentPadding = insets,
        live = live,
        onLivePlayPause = DjPlayback::togglePlayPause,
        onLiveNext = DjPlayback::skipNext,
        onLiveEnd = DjPlayback::end,
        onLiveOpen = { showSetup = false },
    )
}

/** The songs still to come; the first is locked while the DJ is already mixing into it. */
private fun queueOf(state: DjEngineState): List<DjQueueItem> =
    state.upcoming.mapIndexed { index, track ->
        DjQueueItem(
            videoId = track.videoId,
            title = track.title,
            artist = track.artists.joinToString(", ").ifBlank { null },
            thumbnailUrl = track.thumbnailUrl,
            locked = index == 0 && state.transition != null,
        )
    }

/** The engine's state as the live screen, the "Now mixing" card and the mini bar show it. */
internal fun liveUiOf(state: DjEngineState): DjLiveUi? {
    if (!state.isActive) return null
    val status =
        when (state.phase) {
            DjEngineState.Phase.STARTING -> DjLiveUi.Status.STARTING
            DjEngineState.Phase.WAITING_FOR_FOCUS -> DjLiveUi.Status.WAITING_FOR_FOCUS
            DjEngineState.Phase.PAUSED -> DjLiveUi.Status.PAUSED
            DjEngineState.Phase.ENDING -> DjLiveUi.Status.ENDING
            else -> DjLiveUi.Status.PLAYING
        }
    val number = state.currentIndex + 1
    return DjLiveUi(
        title = state.current?.title,
        artist = state.current?.artists?.joinToString(", "),
        nextTitle = state.next?.title,
        songNumber = if (state.current != null) number else 0,
        songCount = number + state.upcoming.size,
        isPlaying = state.playWhenReady,
        status = status,
        mixingInto = state.transition?.let { state.next?.title },
        thumbnailUrl = state.current?.thumbnailUrl,
    )
}

/** The file's name as the system file picker shows it, or a generic name if it has none. */
private fun displayNameOf(
    context: Context,
    uri: Uri,
): String =
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment.orEmpty()
