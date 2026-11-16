package com.aiqulabs.abcd.dj.ui.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiqulabs.abcd.dj.core.analysis.TrackSummary
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_lab_analyse
import com.metrolist.shared.generated.resources.dj_lab_analysed_badge
import com.metrolist.shared.generated.resources.dj_lab_back
import com.metrolist.shared.generated.resources.dj_lab_bpm_row
import com.metrolist.shared.generated.resources.dj_lab_db_counts_analysis
import com.metrolist.shared.generated.resources.dj_lab_db_counts_feedback
import com.metrolist.shared.generated.resources.dj_lab_dismiss
import com.metrolist.shared.generated.resources.dj_lab_error_failed
import com.metrolist.shared.generated.resources.dj_lab_error_no_file
import com.metrolist.shared.generated.resources.dj_lab_error_unsupported
import com.metrolist.shared.generated.resources.dj_lab_local_hint
import com.metrolist.shared.generated.resources.dj_lab_local_none
import com.metrolist.shared.generated.resources.dj_lab_none_analysed
import com.metrolist.shared.generated.resources.dj_lab_pick_file
import com.metrolist.shared.generated.resources.dj_lab_section_analysed
import com.metrolist.shared.generated.resources.dj_lab_section_database
import com.metrolist.shared.generated.resources.dj_lab_section_local
import com.metrolist.shared.generated.resources.dj_lab_step_analysing
import com.metrolist.shared.generated.resources.dj_lab_step_decoding
import com.metrolist.shared.generated.resources.dj_lab_title
import com.metrolist.shared.generated.resources.dj_lab_unknown_title
import com.metrolist.shared.generated.resources.dj_lab_working
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The Lab (brief §4.5): developer-only tools to analyse songs on the phone and see what the DJ knows
 * about them. Stateless: the caller owns [state] and handles [actions].
 */
@Composable
fun LabScreen(
    state: LabState,
    actions: LabActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val detail = state.detail
    if (detail != null) {
        LabDetailScreen(detail, state.busy != null, actions, modifier, contentPadding)
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom =
                    contentPadding.calculateBottomPadding() + 24.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) {
                    Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.dj_lab_back))
                }
                Text(stringResource(Res.string.dj_lab_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { StatusCards(state, actions) }
        item {
            OutlinedButton(onClick = actions.onPickFile, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.dj_lab_pick_file))
            }
        }

        item { SectionTitle(stringResource(Res.string.dj_lab_section_analysed)) }
        if (state.analysed.isEmpty()) {
            item { Hint(stringResource(Res.string.dj_lab_none_analysed)) }
        } else {
            items(state.analysed, key = { "a-" + it.videoId }) { track -> AnalysedRow(track) { actions.onOpen(track.videoId) } }
        }

        item { SectionTitle(stringResource(Res.string.dj_lab_section_local)) }
        item { Hint(stringResource(Res.string.dj_lab_local_hint)) }
        if (state.local.isEmpty()) {
            item { Hint(stringResource(Res.string.dj_lab_local_none)) }
        } else {
            items(state.local, key = { "l-" + it.videoId }) { song ->
                LocalRow(song, enabled = state.busy == null) {
                    if (song.analysed) actions.onOpen(song.videoId) else actions.onAnalyse(song.videoId)
                }
            }
        }

        item { SectionTitle(stringResource(Res.string.dj_lab_section_database)) }
        item {
            val c = state.counts
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.dj_lab_db_counts_analysis, c.analyses, c.sections, c.autoChoruses, c.userChoruses),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.dj_lab_db_counts_feedback, c.tags, c.transitionFeedback, c.trackFeedback, c.sessions),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusCards(
    state: LabState,
    actions: LabActions,
) {
    state.busy?.let { busy ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Column {
                    Text(
                        stringResource(Res.string.dj_lab_working, busy.title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val step =
                        when (busy.step) {
                            LabBusy.Step.DECODING -> Res.string.dj_lab_step_decoding
                            LabBusy.Step.ANALYSING -> Res.string.dj_lab_step_analysing
                        }
                    Text(
                        stringResource(step),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    state.message?.let { message ->
        val text =
            when (message) {
                LabMessage.NoAudioFile -> stringResource(Res.string.dj_lab_error_no_file)
                LabMessage.UnsupportedFile -> stringResource(Res.string.dj_lab_error_unsupported)
                is LabMessage.Failed -> stringResource(Res.string.dj_lab_error_failed, message.reason)
            }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onDismissMessage) { Text(stringResource(Res.string.dj_lab_dismiss)) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AnalysedRow(
    track: TrackSummary,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(
            track.title ?: stringResource(Res.string.dj_lab_unknown_title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artists.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(Res.string.dj_lab_bpm_row, formatDecimal(track.bpm), formatPercent(track.bpmConfidence)) + "  ·  " +
                track.camelot,
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun LocalRow(
    song: LabLocalSong,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                song.artists,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onClick, enabled = enabled || song.analysed) {
            Text(stringResource(if (song.analysed) Res.string.dj_lab_analysed_badge else Res.string.dj_lab_analyse))
        }
    }
}
