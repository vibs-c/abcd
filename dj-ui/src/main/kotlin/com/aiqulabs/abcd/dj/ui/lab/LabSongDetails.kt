package com.aiqulabs.abcd.dj.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aiqulabs.abcd.dj.core.model.DjGenre
import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.tagging.AnalysisTagger
import com.aiqulabs.abcd.dj.core.tagging.TagSource
import com.aiqulabs.abcd.dj.core.tagging.TagType
import com.aiqulabs.abcd.dj.core.tagging.TrackTagValue
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_lab_details_auto
import com.metrolist.shared.generated.resources.dj_lab_details_automatic
import com.metrolist.shared.generated.resources.dj_lab_details_cancel
import com.metrolist.shared.generated.resources.dj_lab_details_edit
import com.metrolist.shared.generated.resources.dj_lab_details_genre
import com.metrolist.shared.generated.resources.dj_lab_details_hint
import com.metrolist.shared.generated.resources.dj_lab_details_language
import com.metrolist.shared.generated.resources.dj_lab_details_mood
import com.metrolist.shared.generated.resources.dj_lab_details_none
import com.metrolist.shared.generated.resources.dj_lab_details_save
import com.metrolist.shared.generated.resources.dj_lab_details_title
import com.metrolist.shared.generated.resources.dj_lab_details_user
import com.metrolist.shared.generated.resources.dj_lab_details_vibe
import com.metrolist.shared.generated.resources.dj_lab_saved_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The four details the user can correct (stage 12): what they are called, the choices, and single or multi choice. */
private enum class EditableDetail(
    val type: TagType,
    val label: StringResource,
    val options: List<String>,
    val multiple: Boolean,
) {
    LANGUAGE(TagType.LANGUAGE, Res.string.dj_lab_details_language, DjLanguage.entries.filter { it != DjLanguage.ANY }.map { it.name }, true),
    GENRE(TagType.GENRE, Res.string.dj_lab_details_genre, DjGenre.entries.map { it.name }, true),
    MOOD(TagType.MOOD, Res.string.dj_lab_details_mood, DjMood.entries.map { it.name }, true),
    VIBE(TagType.VIBE, Res.string.dj_lab_details_vibe, AnalysisTagger.VIBES, false),
}

/** "FEEL_GOOD" → "Feel good". */
internal fun prettyName(value: String): String = if (value.length <= 3) value else value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * Language, type (genre), mood and vibe of a song, with where each came from. "Edit" lets the user set their own;
 * those are stored with source USER and always win over the automatic ones (brief §6.2), including for which
 * mixes the song may join.
 */
@Composable
internal fun SongDetailsCard(
    tags: List<TrackTagValue>,
    onSet: (TagType, List<String>) -> Unit,
) {
    var editing by remember { mutableStateOf<EditableDetail?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.dj_lab_details_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.dj_lab_details_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EditableDetail.entries.forEach { detail ->
                val ofType = tags.filter { it.type == detail.type }
                val user = ofType.filter { it.source == TagSource.USER }
                val shown = (user.ifEmpty { ofType.filter { it.confidence >= SHOWN_CONFIDENCE } }).sortedByDescending { it.confidence }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(detail.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (shown.isEmpty()) stringResource(Res.string.dj_lab_details_none) else shown.distinctBy { it.value }.joinToString(", ") { prettyName(it.value) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            when {
                                user.isNotEmpty() -> stringResource(Res.string.dj_lab_details_user)
                                shown.isNotEmpty() -> stringResource(Res.string.dj_lab_details_auto, shown.map { prettyName(it.source.name) }.distinct().joinToString(", "))
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = { editing = detail }) { Text(stringResource(Res.string.dj_lab_details_edit)) }
                }
            }
        }
    }
    editing?.let { detail ->
        val current = tags.filter { it.type == detail.type && it.source == TagSource.USER }.map { it.value }
        val initial = current.ifEmpty { tags.filter { it.type == detail.type && it.confidence >= SHOWN_CONFIDENCE }.map { it.value } }
        EditDialog(
            detail = detail,
            initial = initial.distinct().filter { it in detail.options },
            onDismiss = { editing = null },
            onSave = {
                onSet(detail.type, it)
                editing = null
            },
            onAutomatic = {
                onSet(detail.type, emptyList())
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(
    detail: EditableDetail,
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
    onAutomatic: () -> Unit,
) {
    var chosen by remember(detail) { mutableStateOf(initial.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(detail.label)) },
        text = {
            FlowRow(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                detail.options.forEach { option ->
                    FilterChip(
                        selected = option in chosen,
                        onClick = {
                            chosen =
                                when {
                                    option in chosen -> chosen - option
                                    detail.multiple -> chosen + option
                                    else -> setOf(option)
                                }
                        },
                        label = { Text(prettyName(option)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(chosen.toList()) }, enabled = chosen.isNotEmpty()) { Text(stringResource(Res.string.dj_lab_details_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAutomatic) { Text(stringResource(Res.string.dj_lab_details_automatic)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.dj_lab_details_cancel)) }
            }
        },
    )
}

/** Everything stored for the song, table by table (stage 12: "let me see all data saved for that song"). */
@Composable
internal fun SavedDataSection(groups: List<LabSavedGroup>) {
    if (groups.isEmpty()) return
    Text(stringResource(Res.string.dj_lab_saved_title), style = MaterialTheme.typography.titleMedium)
    groups.forEach { group ->
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                group.rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
                        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.58f))
                    }
                }
            }
        }
    }
}

private const val SHOWN_CONFIDENCE = 0.3f
