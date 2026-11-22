package com.aiqulabs.abcd.dj.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aiqulabs.abcd.dj.ui.live.DjMiniBar

/** True while a DJ mix runs: `MetrolistApp` then keeps the mini player's space for [DjMiniBarHost]. */
@Composable
fun isDjMixRunning(): Boolean {
    val state by DjPlayback.state.collectAsState()
    return state?.isActive == true
}

/** The DJ mini bar for `MetrolistApp`'s mini-player slot (stage 10). Draws nothing when no mix runs. */
@Composable
fun DjMiniBarHost(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by DjPlayback.state.collectAsState()
    val live = state?.let(::liveUiOf) ?: return
    DjMiniBar(
        live = live,
        onOpen = onOpen,
        onPlayPause = DjPlayback::togglePlayPause,
        onNext = DjPlayback::skipNext,
        modifier = modifier,
    )
}
