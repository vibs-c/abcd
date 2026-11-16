package com.aiqulabs.abcd.dj.ui.lab

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.aiqulabs.abcd.dj.core.analysis.ChorusCandidate
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_lab_back
import com.metrolist.shared.generated.resources.dj_lab_bpm_row
import com.metrolist.shared.generated.resources.dj_lab_chorus_main
import com.metrolist.shared.generated.resources.dj_lab_chorus_row
import com.metrolist.shared.generated.resources.dj_lab_delete
import com.metrolist.shared.generated.resources.dj_lab_grid_row
import com.metrolist.shared.generated.resources.dj_lab_info_row
import com.metrolist.shared.generated.resources.dj_lab_key_row
import com.metrolist.shared.generated.resources.dj_lab_label_choruses
import com.metrolist.shared.generated.resources.dj_lab_label_grid
import com.metrolist.shared.generated.resources.dj_lab_label_info
import com.metrolist.shared.generated.resources.dj_lab_label_key
import com.metrolist.shared.generated.resources.dj_lab_label_loudness
import com.metrolist.shared.generated.resources.dj_lab_label_mixable
import com.metrolist.shared.generated.resources.dj_lab_label_sections
import com.metrolist.shared.generated.resources.dj_lab_label_tempo
import com.metrolist.shared.generated.resources.dj_lab_loudness_row
import com.metrolist.shared.generated.resources.dj_lab_mixable_row
import com.metrolist.shared.generated.resources.dj_lab_no_choruses
import com.metrolist.shared.generated.resources.dj_lab_reanalyse
import com.metrolist.shared.generated.resources.dj_lab_user_chorus_kept
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

/** The full analysis of one song (brief §4.5): tempo, key, loudness, sections, chorus candidates. */
@Composable
internal fun LabDetailScreen(
    detail: LabDetail,
    busy: Boolean,
    actions: LabActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val result = detail.stored.result
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onCloseDetail) {
                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.dj_lab_back))
            }
            Column {
                Text(detail.title, style = MaterialTheme.typography.titleLarge)
                Text(detail.artists, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        WaveformStrip(result)

        Fact(
            stringResource(Res.string.dj_lab_label_tempo),
            stringResource(Res.string.dj_lab_bpm_row, formatDecimal(result.bpm, 2), formatPercent(result.bpmConfidence)),
        )
        Fact(
            stringResource(Res.string.dj_lab_label_key),
            stringResource(
                Res.string.dj_lab_key_row,
                result.key.root,
                stringResource(result.key.mode.labelRes()),
                result.key.camelot,
                formatPercent(result.key.confidence),
            ),
        )
        Fact(stringResource(Res.string.dj_lab_label_loudness), stringResource(Res.string.dj_lab_loudness_row, formatDecimal(result.lufs)))
        Fact(
            stringResource(Res.string.dj_lab_label_grid),
            stringResource(
                Res.string.dj_lab_grid_row,
                result.beatsMs.size,
                result.barStartsMs.size,
                formatPercent(result.downbeatConfidence),
            ),
        )
        Fact(
            stringResource(Res.string.dj_lab_label_mixable),
            stringResource(Res.string.dj_lab_mixable_row, formatClock(result.introMixableEndMs), formatClock(result.outroMixableStartMs)),
        )

        Text(stringResource(Res.string.dj_lab_label_sections), style = MaterialTheme.typography.titleMedium)
        result.sections.forEach { section ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.size(12.dp).clip(CircleShape).background(section.type.color())) {}
                Text(stringResource(section.type.labelRes()), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(formatClock(section.startMs) + " - " + formatClock(section.endMs), style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text(stringResource(Res.string.dj_lab_label_choruses), style = MaterialTheme.typography.titleMedium)
        if (result.chorusCandidates.isEmpty()) {
            Text(stringResource(Res.string.dj_lab_no_choruses), style = MaterialTheme.typography.bodyMedium)
        }
        result.chorusCandidates.forEach { ChorusRow(it) }
        if (detail.stored.hasUserChorus) {
            Text(
                stringResource(Res.string.dj_lab_user_chorus_kept),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Fact(stringResource(Res.string.dj_lab_label_info), infoLine(result, detail.stored.analyzedAt))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { actions.onAnalyse(detail.videoId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.dj_lab_reanalyse))
            }
            OutlinedButton(onClick = { actions.onDelete(detail.videoId) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.dj_lab_delete))
            }
        }
    }
}

@Composable
private fun infoLine(
    result: AnalysisResult,
    analyzedAt: Long,
): String {
    val ago = DateUtils.getRelativeTimeSpanString(analyzedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    return stringResource(Res.string.dj_lab_info_row, result.analysisVersion, ago)
}

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChorusRow(chorus: ChorusCandidate) {
    Column {
        Text(
            stringResource(
                Res.string.dj_lab_chorus_row,
                formatClock(chorus.startMs),
                formatClock(chorus.endMs),
                formatPercent(chorus.score),
            ) +
                if (chorus.isMain) "  ·  " + stringResource(Res.string.dj_lab_chorus_main) else "",
            style = MaterialTheme.typography.bodyLarge,
        )
        // stringResource is composable, so resolve each name in a plain loop, not inside a lambda.
        val signalNames = chorus.signals.map { stringResource(it.labelRes()) }
        val signals = signalNames.joinToString(", ")
        Text(
            stringResource(chorus.source.labelRes()) + if (signals.isNotEmpty()) " · $signals" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
