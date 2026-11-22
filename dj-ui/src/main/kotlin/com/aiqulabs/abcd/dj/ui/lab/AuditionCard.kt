package com.aiqulabs.abcd.dj.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiqulabs.abcd.dj.core.analysis.TrackSummary
import com.aiqulabs.abcd.dj.core.mix.TransitionTechnique
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_audition_auto
import com.metrolist.shared.generated.resources.dj_audition_beatmatch
import com.metrolist.shared.generated.resources.dj_audition_classic
import com.metrolist.shared.generated.resources.dj_audition_desc
import com.metrolist.shared.generated.resources.dj_audition_echo
import com.metrolist.shared.generated.resources.dj_audition_from
import com.metrolist.shared.generated.resources.dj_audition_pick
import com.metrolist.shared.generated.resources.dj_audition_play
import com.metrolist.shared.generated.resources.dj_audition_stop
import com.metrolist.shared.generated.resources.dj_audition_title
import com.metrolist.shared.generated.resources.dj_audition_to
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Audition a transition" (brief §4.5): pick two analysed songs and a technique, and hear the transition the DJ
 * would play, starting just before the blend.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuditionCard(
    analysed: List<TrackSummary>,
    onPlay: (fromId: String, toId: String, technique: TransitionTechnique?) -> Unit,
    onStop: () -> Unit,
) {
    var fromId by rememberSaveable { mutableStateOf<String?>(null) }
    var toId by rememberSaveable { mutableStateOf<String?>(null) }
    var technique by rememberSaveable { mutableStateOf<String?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.dj_audition_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.dj_audition_desc), style = MaterialTheme.typography.bodySmall)
            SongPicker(Res.string.dj_audition_from, analysed, fromId) { fromId = it }
            SongPicker(Res.string.dj_audition_to, analysed, toId) { toId = it }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TECHNIQUES.forEach { (value, label) ->
                    FilterChip(selected = technique == value, onClick = { technique = value }, label = { Text(stringResource(label)) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val from = fromId
                val to = toId
                Button(
                    onClick = { if (from != null && to != null) onPlay(from, to, technique?.let(TransitionTechnique::valueOf)) },
                    enabled = from != null && to != null && from != to,
                ) { Text(stringResource(Res.string.dj_audition_play)) }
                OutlinedButton(onClick = onStop) { Text(stringResource(Res.string.dj_audition_stop)) }
            }
        }
    }
}

@Composable
private fun SongPicker(
    label: StringResource,
    analysed: List<TrackSummary>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = analysed.firstOrNull { it.videoId == selectedId }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(label) + ": " + (selected?.let { it.title ?: it.videoId } ?: stringResource(Res.string.dj_audition_pick)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            analysed.forEach { song ->
                DropdownMenuItem(
                    text = { Text((song.title ?: song.videoId) + " · " + song.bpm.toInt() + " BPM", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(song.videoId)
                        open = false
                    },
                )
            }
        }
    }
}

/** Technique chips: null = the DJ's automatic choice. */
private val TECHNIQUES: List<Pair<String?, StringResource>> =
    listOf(
        null to Res.string.dj_audition_auto,
        TransitionTechnique.BEATMATCHED_BLEND.name to Res.string.dj_audition_beatmatch,
        TransitionTechnique.ECHO_OUT.name to Res.string.dj_audition_echo,
        TransitionTechnique.CLASSIC_FADE.name to Res.string.dj_audition_classic,
    )
