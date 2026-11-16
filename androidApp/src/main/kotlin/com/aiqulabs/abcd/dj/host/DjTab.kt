package com.aiqulabs.abcd.dj.host

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
 * Content of the DJ tab: the setup screen wired to storage ([DjSetupStore]) and to [DjController], and,
 * when the owner has switched the Lab on, the Lab ([DjLabController]).
 */
@Composable
fun DjTab(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val playerConnection = LocalPlayerConnection.current
    val store = remember { DjSetupStore(appContext) }
    var draft by remember { mutableStateOf(store.load()) }
    var labEnabled by remember { mutableStateOf(store.labEnabled) }
    var labOpen by remember { mutableStateOf(false) }
    val startState by DjController.state.collectAsState()
    val libraryState by DjLibraryAnalyzer.state.collectAsState()
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
                ),
            modifier = modifier.fillMaxSize(),
            contentPadding = insets,
        )
        return
    }

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
        modifier = modifier.fillMaxSize(),
        contentPadding = insets,
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
