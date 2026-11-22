package com.aiqulabs.abcd.dj.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_end_session
import com.metrolist.shared.generated.resources.dj_last_song
import com.metrolist.shared.generated.resources.dj_live_show_mix
import com.metrolist.shared.generated.resources.dj_mix_progress
import com.metrolist.shared.generated.resources.dj_mixing_into
import com.metrolist.shared.generated.resources.dj_next
import com.metrolist.shared.generated.resources.dj_now_mixing
import com.metrolist.shared.generated.resources.dj_pause
import com.metrolist.shared.generated.resources.dj_paused
import com.metrolist.shared.generated.resources.dj_play
import com.metrolist.shared.generated.resources.dj_starting_mix
import com.metrolist.shared.generated.resources.dj_up_next
import com.metrolist.shared.generated.resources.dj_waiting_for_focus
import org.jetbrains.compose.resources.stringResource

/** What the "Now mixing" card shows. The host maps the engine's state to this; dj-ui never sees the engine. */
data class DjLiveUi(
    val title: String?,
    val artist: String?,
    val nextTitle: String?,
    val songNumber: Int,
    val songCount: Int,
    val isPlaying: Boolean,
    val status: Status,
    /** Title of the incoming song while a transition runs. */
    val mixingInto: String? = null,
    /** Artwork of the song playing (stage 10). */
    val thumbnailUrl: String? = null,
) {
    enum class Status { STARTING, PLAYING, PAUSED, WAITING_FOR_FOCUS, ENDING }
}

/**
 * The running mix as a card on the setup screen (stage 8): now playing, up next, play/pause, next and End
 * session. Shown while the user sets up a new mix; [onOpen] goes back to the full [DjLiveScreen] (stage 10).
 */
@Composable
fun DjNowMixingCard(
    live: DjLiveUi,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the live screen (stage 10); no button when null. */
    onOpen: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(Res.string.dj_now_mixing),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                live.title ?: stringResource(Res.string.dj_starting_mix),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            live.artist?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val statusLine =
                when {
                    live.mixingInto != null -> stringResource(Res.string.dj_mixing_into, live.mixingInto)
                    live.status == DjLiveUi.Status.WAITING_FOR_FOCUS -> stringResource(Res.string.dj_waiting_for_focus)
                    live.status == DjLiveUi.Status.PAUSED -> stringResource(Res.string.dj_paused)
                    live.nextTitle != null -> stringResource(Res.string.dj_up_next, live.nextTitle)
                    live.title != null -> stringResource(Res.string.dj_last_song)
                    else -> null
                }
            statusLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (live.songCount > 0 && live.songNumber > 0) {
                Text(
                    stringResource(Res.string.dj_mix_progress, live.songNumber, live.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val enabled = live.status != DjLiveUi.Status.ENDING
                Button(onClick = onPlayPause, enabled = enabled) {
                    Text(stringResource(if (live.isPlaying) Res.string.dj_pause else Res.string.dj_play))
                }
                OutlinedButton(onClick = onNext, enabled = enabled && live.nextTitle != null) { Text(stringResource(Res.string.dj_next)) }
                TextButton(onClick = onEnd, enabled = enabled, modifier = Modifier.weight(1f, fill = false)) {
                    Text(stringResource(Res.string.dj_end_session))
                }
                onOpen?.let { open -> TextButton(onClick = open) { Text(stringResource(Res.string.dj_live_show_mix)) } }
            }
        }
    }
}
