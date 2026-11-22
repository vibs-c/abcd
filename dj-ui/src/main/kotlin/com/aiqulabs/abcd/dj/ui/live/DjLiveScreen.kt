package com.aiqulabs.abcd.dj.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_end_session
import com.metrolist.shared.generated.resources.dj_last_song
import com.metrolist.shared.generated.resources.dj_live_new_mix
import com.metrolist.shared.generated.resources.dj_live_queue
import com.metrolist.shared.generated.resources.dj_live_rate_pair
import com.metrolist.shared.generated.resources.dj_live_rate_thanks
import com.metrolist.shared.generated.resources.dj_live_rate_title
import com.metrolist.shared.generated.resources.dj_live_thumbs_down
import com.metrolist.shared.generated.resources.dj_live_thumbs_up
import com.metrolist.shared.generated.resources.dj_live_up_next
import com.metrolist.shared.generated.resources.dj_mix_progress
import com.metrolist.shared.generated.resources.dj_mixing_into
import com.metrolist.shared.generated.resources.dj_next
import com.metrolist.shared.generated.resources.dj_now_mixing
import com.metrolist.shared.generated.resources.dj_pause
import com.metrolist.shared.generated.resources.dj_paused
import com.metrolist.shared.generated.resources.dj_play
import com.metrolist.shared.generated.resources.dj_queue_mixing_in
import com.metrolist.shared.generated.resources.dj_queue_move_down
import com.metrolist.shared.generated.resources.dj_queue_move_up
import com.metrolist.shared.generated.resources.dj_queue_never_play
import com.metrolist.shared.generated.resources.dj_queue_options
import com.metrolist.shared.generated.resources.dj_queue_remove
import com.metrolist.shared.generated.resources.dj_starting_mix
import com.metrolist.shared.generated.resources.dj_waiting_for_focus
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIconsFilled
import com.metrolist.shared.ui.icons.MetrolistIconsOutlined
import org.jetbrains.compose.resources.stringResource

/** One song still to come. [locked] while the DJ is already mixing into it (it can no longer be moved). */
data class DjQueueItem(
    val videoId: String,
    val title: String,
    val artist: String?,
    val thumbnailUrl: String?,
    val locked: Boolean = false,
)

/** The transition the thumbs rate. [rating]: true up, false down, null not rated. */
data class DjThumbsUi(
    val fromTitle: String,
    val toTitle: String,
    val rating: Boolean?,
)

/** What the live screen shows (brief §4.3). The host maps the engine and feedback state to this. */
data class DjLiveScreenUi(
    val live: DjLiveUi,
    val queue: List<DjQueueItem>,
    val thumbs: DjThumbsUi?,
    /** Song progress and the coming mix (stage 12). */
    val timeline: DjTimelineUi? = null,
)

class DjLiveActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onEnd: () -> Unit,
    val onThumb: (up: Boolean) -> Unit,
    val onRemove: (videoId: String, neverPlay: Boolean) -> Unit,
    val onMove: (videoId: String, by: Int) -> Unit,
    val onNewMix: () -> Unit,
)

/**
 * The live DJ screen (brief §4.3, stage 10): now playing and up next with artwork, thumbs on the transition
 * that just happened, the queue (remove, never play, move up/down) and play/pause, Next and End session.
 * Everything else (BPM, key, waveform) belongs to the v2 "DJ details" page.
 */
@Composable
fun DjLiveScreen(
    ui: DjLiveScreenUi,
    actions: DjLiveActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val live = ui.live
    val enabled = live.status != DjLiveUi.Status.ENDING
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "now") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(Res.string.dj_now_mixing), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Artwork(live.thumbnailUrl, Modifier.fillMaxWidth(0.62f).widthIn(max = 320.dp).aspectRatio(1f), corner = 16.dp)
                Text(
                    live.title ?: stringResource(Res.string.dj_starting_mix),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                live.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                statusLine(live)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (live.songCount > 0 && live.songNumber > 0) {
                    Text(
                        stringResource(Res.string.dj_mix_progress, live.songNumber, live.songCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.timeline?.let { DjTimeline(it, Modifier.padding(top = 4.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = actions.onPlayPause, enabled = enabled, modifier = Modifier.size(56.dp)) {
                        Icon(
                            if (live.isPlaying) MetrolistIconsFilled.Pause else MetrolistIconsFilled.PlayArrow,
                            contentDescription = stringResource(if (live.isPlaying) Res.string.dj_pause else Res.string.dj_play),
                        )
                    }
                    IconButton(onClick = actions.onNext, enabled = enabled && live.nextTitle != null) {
                        Icon(MetrolistIconsFilled.SkipNext, contentDescription = stringResource(Res.string.dj_next))
                    }
                }
                TextButton(onClick = actions.onEnd, enabled = enabled) { Text(stringResource(Res.string.dj_end_session)) }
            }
        }

        ui.thumbs?.let { thumbs ->
            item(key = "thumbs") { ThumbsCard(thumbs, actions.onThumb, Modifier.padding(horizontal = 16.dp)) }
        }

        if (ui.queue.isNotEmpty()) {
            item(key = "queue-title") {
                Text(
                    stringResource(Res.string.dj_live_queue, ui.queue.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            itemsIndexed(ui.queue, key = { _, item -> "q-" + item.videoId }) { index, item ->
                QueueRow(
                    item = item,
                    isFirst = index == 0,
                    canMoveUp = !item.locked && index > 0 && !ui.queue[index - 1].locked,
                    canMoveDown = !item.locked && index < ui.queue.lastIndex,
                    actions = actions,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        item(key = "new-mix") {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = actions.onNewMix) { Text(stringResource(Res.string.dj_live_new_mix)) }
            }
        }
    }
}

@Composable
private fun statusLine(live: DjLiveUi): String? =
    when {
        live.mixingInto != null -> stringResource(Res.string.dj_mixing_into, live.mixingInto)
        live.status == DjLiveUi.Status.WAITING_FOR_FOCUS -> stringResource(Res.string.dj_waiting_for_focus)
        live.status == DjLiveUi.Status.PAUSED -> stringResource(Res.string.dj_paused)
        live.title != null && live.nextTitle == null -> stringResource(Res.string.dj_last_song)
        else -> null
    }

@Composable
private fun ThumbsCard(
    thumbs: DjThumbsUi,
    onThumb: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(if (thumbs.rating == null) Res.string.dj_live_rate_title else Res.string.dj_live_rate_thanks),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    stringResource(Res.string.dj_live_rate_pair, thumbs.fromTitle, thumbs.toTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconToggleButton(checked = thumbs.rating == true, onCheckedChange = { onThumb(true) }) {
                // Metrolist has no thumb-up symbol: thumb-down turned around is the same shape.
                Icon(
                    if (thumbs.rating == true) MetrolistIconsFilled.ThumbDown else MetrolistIconsOutlined.ThumbDown,
                    contentDescription = stringResource(Res.string.dj_live_thumbs_up),
                    modifier = Modifier.rotate(180f),
                )
            }
            FilledTonalIconToggleButton(checked = thumbs.rating == false, onCheckedChange = { onThumb(false) }) {
                Icon(
                    if (thumbs.rating == false) MetrolistIconsFilled.ThumbDown else MetrolistIconsOutlined.ThumbDown,
                    contentDescription = stringResource(Res.string.dj_live_thumbs_down),
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: DjQueueItem,
    isFirst: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    actions: DjLiveActions,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Artwork(item.thumbnailUrl, Modifier.padding(start = 8.dp).size(48.dp), corner = 6.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (isFirst) {
                Text(
                    stringResource(if (item.locked) Res.string.dj_queue_mixing_in else Res.string.dj_live_up_next),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.artist?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { actions.onMove(item.videoId, -1) }, enabled = canMoveUp) {
            Icon(MetrolistIconsOutlined.KeyboardArrowUp, contentDescription = stringResource(Res.string.dj_queue_move_up))
        }
        IconButton(onClick = { actions.onMove(item.videoId, 1) }, enabled = canMoveDown) {
            Icon(MetrolistIconsOutlined.KeyboardArrowDown, contentDescription = stringResource(Res.string.dj_queue_move_down))
        }
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = !item.locked) {
                Icon(MetrolistIconsOutlined.Close, contentDescription = stringResource(Res.string.dj_queue_options))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.dj_queue_remove)) },
                    leadingIcon = { Icon(MetrolistIconsOutlined.Close, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        actions.onRemove(item.videoId, false)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.dj_queue_never_play)) },
                    leadingIcon = { Icon(MetrolistIconsOutlined.Block, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        actions.onRemove(item.videoId, true)
                    },
                )
            }
        }
    }
}

@Composable
internal fun Artwork(
    url: String?,
    modifier: Modifier,
    corner: Dp,
) {
    AsyncImage(url = url, modifier = modifier, shape = RoundedCornerShape(corner))
}
