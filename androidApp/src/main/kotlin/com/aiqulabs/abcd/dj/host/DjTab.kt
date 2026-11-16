package com.aiqulabs.abcd.dj.host

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aiqulabs.abcd.dj.data.DjSetupStore
import com.aiqulabs.abcd.dj.ui.setup.DjSetupScreen
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.tabContentTopPadding

/**
 * Content of the DJ tab: the setup screen wired to storage ([DjSetupStore]) and to [DjController].
 */
@Composable
fun DjTab(modifier: Modifier = Modifier) {
    val appContext = LocalContext.current.applicationContext
    val playerConnection = LocalPlayerConnection.current
    val store = remember { DjSetupStore(appContext) }
    var draft by remember { mutableStateOf(store.load()) }
    val startState by DjController.state.collectAsState()

    DjSetupScreen(
        draft = draft,
        startState = startState,
        onDraftChange = {
            draft = it
            store.save(it)
        },
        onStart = {
            val settings = draft.toSettings()
            if (settings != null && playerConnection != null) DjController.start(settings, playerConnection)
        },
        onCancel = DjController::cancel,
        onDismissResult = DjController::dismissResult,
        modifier = modifier.fillMaxSize(),
        // Same insets as the Home and Library tabs: below the top bar, above the mini player.
        contentPadding = PaddingValues(top = tabContentTopPadding(), bottom = LocalMiniPlayerBottomInset.current),
    )
}
