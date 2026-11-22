package com.aiqulabs.abcd.dj.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.aiqulabs.abcd.dj.ui.live.DjMiniBar
import com.aiqulabs.abcd.dj.ui.live.DjTimelineUi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay

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
    val timeline by rememberDjTimeline()
    DjMiniBar(
        live = live,
        onOpen = onOpen,
        onPlayPause = DjPlayback::togglePlayPause,
        onNext = DjPlayback::skipNext,
        modifier = modifier,
        timeline = timeline,
    )
}

/** The live timeline, refreshed about 20 times a second while on screen (stage 12). */
@Composable
fun rememberDjTimeline(): State<DjTimelineUi?> {
    val timeline = remember { mutableStateOf(DjPlayback.timeline()) }
    LaunchedEffect(Unit) {
        while (true) {
            timeline.value = DjPlayback.timeline()
            delay(TIMELINE_REFRESH_MS)
        }
    }
    return timeline
}

private const val TIMELINE_REFRESH_MS = 50L
