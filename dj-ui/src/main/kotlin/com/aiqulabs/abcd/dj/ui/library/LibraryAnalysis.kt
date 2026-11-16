package com.aiqulabs.abcd.dj.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_library_cancel
import com.metrolist.shared.generated.resources.dj_library_collecting
import com.metrolist.shared.generated.resources.dj_library_desc
import com.metrolist.shared.generated.resources.dj_library_done
import com.metrolist.shared.generated.resources.dj_library_failed
import com.metrolist.shared.generated.resources.dj_library_nothing
import com.metrolist.shared.generated.resources.dj_library_remaining
import com.metrolist.shared.generated.resources.dj_library_running
import com.metrolist.shared.generated.resources.dj_library_start
import com.metrolist.shared.generated.resources.dj_library_stopped
import com.metrolist.shared.generated.resources.dj_library_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** State of the "Analyse my library" batch (brief §5.1, second point). */
sealed interface LibraryAnalysisState {
    /** Nothing has run since the app started. */
    data object Idle : LibraryAnalysisState

    /** Looking through liked, library and downloaded songs for ones without an analysis. */
    data object Collecting : LibraryAnalysisState

    /** [done] of [total] songs are finished; [currentTitle] is the one being worked on. */
    data class Running(
        val done: Int,
        val total: Int,
        val currentTitle: String?,
    ) : LibraryAnalysisState

    /** Every song in the library already had a current analysis. */
    data object NothingToDo : LibraryAnalysisState

    /** The batch ended. [remaining] songs were left over because a batch is limited in size or was stopped. */
    data class Finished(
        val analysed: Int,
        val failed: Int,
        val remaining: Int,
        val stopped: Boolean,
    ) : LibraryAnalysisState

    val isBusy: Boolean get() = this is Collecting || this is Running
}

/**
 * The "Analyse my library" card: a button, and while the batch runs a progress bar and a stop button.
 * Used in the DJ settings (setup screen) and in the Lab.
 */
@Composable
fun LibraryAnalysisCard(
    state: LibraryAnalysisState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.dj_library_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.dj_library_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state) {
                LibraryAnalysisState.Collecting -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.dj_library_collecting), style = MaterialTheme.typography.bodyMedium)
                    StopButton(onStop)
                }

                is LibraryAnalysisState.Running -> {
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(Res.string.dj_library_running, (state.done + 1).coerceAtMost(state.total), state.total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    state.currentTitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StopButton(onStop)
                }

                LibraryAnalysisState.NothingToDo -> {
                    Text(stringResource(Res.string.dj_library_nothing), style = MaterialTheme.typography.bodyMedium)
                    StartButton(onStart)
                }

                is LibraryAnalysisState.Finished -> {
                    if (state.stopped) Text(stringResource(Res.string.dj_library_stopped), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        pluralStringResource(Res.plurals.dj_library_done, state.analysed, state.analysed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.failed > 0) {
                        Text(
                            pluralStringResource(Res.plurals.dj_library_failed, state.failed, state.failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.remaining > 0) {
                        Text(
                            pluralStringResource(Res.plurals.dj_library_remaining, state.remaining, state.remaining),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StartButton(onStart)
                }

                LibraryAnalysisState.Idle -> {
                    StartButton(onStart)
                }
            }
        }
    }
}

@Composable
private fun StartButton(onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.dj_library_start)) }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.dj_library_cancel)) }
}
