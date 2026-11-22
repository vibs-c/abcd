package com.aiqulabs.abcd.dj.ui.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_next
import com.metrolist.shared.generated.resources.dj_now_mixing
import com.metrolist.shared.generated.resources.dj_pause
import com.metrolist.shared.generated.resources.dj_play
import com.metrolist.shared.generated.resources.dj_starting_mix
import com.metrolist.shared.ui.icons.MetrolistIconsFilled
import org.jetbrains.compose.resources.stringResource

/**
 * The DJ mini bar on the other tabs (stage 10): artwork, the song playing, play/pause and Next. Tapping it
 * opens the DJ tab. Shown in the mini player's place while a mix runs (Metrolist's own player is stopped then).
 */
@Composable
fun DjMiniBar(
    live: DjLiveUi,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    timeline: DjTimelineUi? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Box(Modifier.fillMaxHeight()) {
        timeline?.let { DjMiniProgress(it, Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 2.dp)) }
        Row(
            modifier = Modifier.fillMaxHeight().clickable(onClick = onOpen).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(live.thumbnailUrl, Modifier.size(44.dp), corner = 8.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    live.mixingInto ?: live.title ?: stringResource(Res.string.dj_starting_mix),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(Res.string.dj_now_mixing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
            val enabled = live.status != DjLiveUi.Status.ENDING
            IconButton(onClick = onPlayPause, enabled = enabled) {
                Icon(
                    if (live.isPlaying) MetrolistIconsFilled.Pause else MetrolistIconsFilled.PlayArrow,
                    contentDescription = stringResource(if (live.isPlaying) Res.string.dj_pause else Res.string.dj_play),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onNext, enabled = enabled && live.nextTitle != null) {
                Icon(MetrolistIconsFilled.SkipNext, contentDescription = stringResource(Res.string.dj_next), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        }
    }
}
