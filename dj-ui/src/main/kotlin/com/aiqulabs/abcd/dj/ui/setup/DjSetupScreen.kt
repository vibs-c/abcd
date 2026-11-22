package com.aiqulabs.abcd.dj.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.aiqulabs.abcd.dj.ui.live.DjLiveUi
import com.aiqulabs.abcd.dj.ui.live.DjNowMixingCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiqulabs.abcd.dj.core.model.DjLanguage
import com.aiqulabs.abcd.dj.core.model.DjMood
import com.aiqulabs.abcd.dj.core.model.DjSessionSettings
import com.aiqulabs.abcd.dj.core.model.DjSetupDraft
import com.aiqulabs.abcd.dj.core.model.MixStyle
import com.aiqulabs.abcd.dj.core.prep.PreparationProgress
import com.aiqulabs.abcd.dj.ui.library.LibraryAnalysisCard
import com.aiqulabs.abcd.dj.ui.library.LibraryAnalysisState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_building_message
import com.metrolist.shared.generated.resources.dj_building_title
import com.metrolist.shared.generated.resources.dj_cancel
import com.metrolist.shared.generated.resources.dj_failed_error
import com.metrolist.shared.generated.resources.dj_failed_not_enough
import com.metrolist.shared.generated.resources.dj_failed_storage
import com.metrolist.shared.generated.resources.dj_failure_detail
import com.metrolist.shared.generated.resources.dj_failed_title
import com.metrolist.shared.generated.resources.dj_hint_choose_language
import com.metrolist.shared.generated.resources.dj_hint_choose_mood
import com.metrolist.shared.generated.resources.dj_lab_open
import com.metrolist.shared.generated.resources.dj_lab_toggle_desc
import com.metrolist.shared.generated.resources.dj_lab_toggle_title
import com.metrolist.shared.generated.resources.dj_length_hours
import com.metrolist.shared.generated.resources.dj_length_hours_minutes
import com.metrolist.shared.generated.resources.dj_length_minutes
import com.metrolist.shared.generated.resources.dj_preparing_hint
import com.metrolist.shared.generated.resources.dj_preparing_known
import com.metrolist.shared.generated.resources.dj_preparing_progress
import com.metrolist.shared.generated.resources.dj_preparing_skipped
import com.metrolist.shared.generated.resources.dj_preparing_title
import com.metrolist.shared.generated.resources.dj_section_languages
import com.metrolist.shared.generated.resources.dj_section_length
import com.metrolist.shared.generated.resources.dj_section_mix_style
import com.metrolist.shared.generated.resources.dj_section_mood
import com.metrolist.shared.generated.resources.dj_setup_subtitle
import com.metrolist.shared.generated.resources.dj_setup_title
import com.metrolist.shared.generated.resources.dj_start
import com.metrolist.shared.generated.resources.dj_started_message
import com.metrolist.shared.generated.resources.dj_started_title
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val MOODS_PER_ROW = 3
private const val LENGTH_STEP_MINUTES = 15

/**
 * "Start a mix": the four required inputs (languages, mood, mix style, session length) and the
 * Start DJ button (brief §4.2). Stateless: the caller owns [draft] and [startState].
 *
 * @param draft the form as filled in so far.
 * @param startState progress of the last "Start DJ" tap, shown as a card above the form.
 * @param onDraftChange called with the updated form after every input change.
 * @param onStart called when the user taps Start DJ with a complete form.
 * @param onCancel called when the user cancels while the mix is being built.
 * @param onDismissResult called when the user dismisses a finished or failed status card.
 * @param libraryState progress of the "Analyse my library" batch.
 * @param onAnalyseLibrary called when the user starts the library batch.
 * @param onStopLibrary called when the user stops the library batch.
 * @param labEnabled whether the developer Lab is switched on (brief §4.5).
 * @param onLabEnabledChange called when the Lab switch is toggled.
 * @param onOpenLab called when the user opens the Lab.
 * @param contentPadding space to keep clear for the app's top bar, mini player and navigation bar.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DjSetupScreen(
    draft: DjSetupDraft,
    startState: DjStartState,
    onDraftChange: (DjSetupDraft) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
    libraryState: LibraryAnalysisState,
    onAnalyseLibrary: () -> Unit,
    onStopLibrary: () -> Unit,
    labEnabled: Boolean,
    onLabEnabledChange: (Boolean) -> Unit,
    onOpenLab: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    live: DjLiveUi? = null,
    onLivePlayPause: () -> Unit = {},
    onLiveNext: () -> Unit = {},
    onLiveEnd: () -> Unit = {},
    onLiveOpen: (() -> Unit)? = null,
) {
    val building = startState is DjStartState.Building || startState is DjStartState.Preparing
    val scroll = rememberScrollState()
    // The status card sits at the top, but "Start DJ" is near the bottom: bring the card into view when
    // a mix starts or finishes, otherwise preparation looks frozen (only the greyed-out button is visible).
    val statusShown = startState !is DjStartState.Idle || live != null
    LaunchedEffect(startState::class, live != null) {
        if (statusShown) scroll.animateScrollTo(0)
    }
    Column(
        modifier =
            modifier
                .verticalScroll(scroll)
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.dj_setup_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(Res.string.dj_setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        live?.let { DjNowMixingCard(it, onPlayPause = onLivePlayPause, onNext = onLiveNext, onEnd = onLiveEnd, onOpen = onLiveOpen) }
        // While a mix plays, its card replaces the "Your mix is playing" result.
        if (live == null || startState !is DjStartState.Started) StatusCard(startState, onCancel, onDismissResult)

        Section(stringResource(Res.string.dj_section_languages)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DjLanguage.selectable.forEach { language ->
                    FilterChip(
                        selected = language in draft.languages,
                        enabled = !building,
                        onClick = { onDraftChange(draft.toggleLanguage(language)) },
                        label = { Text(stringResource(language.labelRes())) },
                        leadingIcon =
                            if (language in draft.languages) {
                                { Icon(MetrolistIcons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                    )
                }
            }
        }

        Section(stringResource(Res.string.dj_section_mood)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = MOODS_PER_ROW,
            ) {
                DjMood.entries.forEach { mood ->
                    MoodCard(
                        mood = mood,
                        selected = draft.mood == mood,
                        enabled = !building,
                        onClick = { onDraftChange(draft.copy(mood = mood)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Section(stringResource(Res.string.dj_section_mix_style)) {
            val styles = MixStyle.v1Styles
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                styles.forEachIndexed { index, style ->
                    SegmentedButton(
                        selected = draft.mixStyle == style,
                        enabled = !building,
                        onClick = { onDraftChange(draft.copy(mixStyle = style)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size),
                    ) {
                        style.labelRes()?.let { Text(stringResource(it), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            draft.mixStyle.descriptionRes()?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Section(stringResource(Res.string.dj_section_length)) {
            Text(sessionLengthText(draft.sessionLengthMinutes), style = MaterialTheme.typography.titleMedium)
            Slider(
                value = draft.sessionLengthMinutes.toFloat(),
                onValueChange = { onDraftChange(draft.copy(sessionLengthMinutes = it.roundToInt())) },
                enabled = !building,
                valueRange = DjSessionSettings.MIN_LENGTH_MINUTES.toFloat()..DjSessionSettings.MAX_LENGTH_MINUTES.toFloat(),
                steps = (DjSessionSettings.MAX_LENGTH_MINUTES - DjSessionSettings.MIN_LENGTH_MINUTES) / LENGTH_STEP_MINUTES - 1,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = draft.isComplete && !building,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Text(stringResource(Res.string.dj_start), style = MaterialTheme.typography.titleMedium)
            }
            when (draft.missing) {
                DjSetupDraft.Missing.LANGUAGE -> Hint(stringResource(Res.string.dj_hint_choose_language))
                DjSetupDraft.Missing.MOOD -> Hint(stringResource(Res.string.dj_hint_choose_mood))
                null -> Unit
            }
        }
        LibraryAnalysisCard(libraryState, onStart = onAnalyseLibrary, onStop = onStopLibrary)
        LabSwitch(labEnabled, onLabEnabledChange, onOpenLab)
        Spacer(Modifier.height(24.dp))
    }
}

/** Developer-only switch (brief §4.5). Off by default; when on, an "Open Lab" button appears. */
@Composable
private fun LabSwitch(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(Res.string.dj_lab_toggle_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.dj_lab_toggle_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.dj_lab_open)) }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MoodCard(
    mood: DjMood,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier =
            modifier.heightIn(min = 88.dp).semantics {
                role = Role.RadioButton
                this.selected = selected
            },
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(mood.icon(), contentDescription = null, modifier = Modifier.size(28.dp))
            Text(
                stringResource(mood.labelRes()),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusCard(
    state: DjStartState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        DjStartState.Idle -> {
            Unit
        }

        DjStartState.Building -> {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.dj_building_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(Res.string.dj_building_message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(Res.string.dj_cancel)) }
                }
            }
        }

        is DjStartState.Preparing -> {
            PreparingCard(state.progress, onCancel)
        }

        is DjStartState.Started -> {
            ResultCard(
                title = stringResource(Res.string.dj_started_title),
                message = pluralStringResource(Res.plurals.dj_started_message, state.trackCount, state.trackCount),
                error = false,
                onDismiss = onDismiss,
            )
        }

        is DjStartState.Failed -> {
            ResultCard(
                title = stringResource(Res.string.dj_failed_title),
                message =
                    when (state.reason) {
                        DjStartState.Reason.NOT_ENOUGH_SONGS -> {
                            pluralStringResource(Res.plurals.dj_failed_not_enough, state.foundCount, state.foundCount)
                        }

                        DjStartState.Reason.ERROR -> {
                            stringResource(Res.string.dj_failed_error)
                        }

                        DjStartState.Reason.NOT_ENOUGH_STORAGE -> {
                            stringResource(Res.string.dj_failed_storage)
                        }
                    },
                error = true,
                onDismiss = onDismiss,
                detail = state.detail?.let { stringResource(Res.string.dj_failure_detail, it) },
            )
        }
    }
}

/** "Preparing your mix": "Analysing 7 of 18", a progress bar, the song being worked on and Cancel (brief §5.1). */
@Composable
private fun PreparingCard(
    progress: PreparationProgress,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.dj_preparing_title), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(Res.string.dj_preparing_progress, progress.current, progress.total),
                style = MaterialTheme.typography.titleSmall,
            )
            progress.currentTitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress.alreadyKnown > 0) {
                Text(
                    pluralStringResource(Res.plurals.dj_preparing_known, progress.alreadyKnown, progress.alreadyKnown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress.failed > 0) {
                Text(
                    pluralStringResource(Res.plurals.dj_preparing_skipped, progress.failed, progress.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress.lastFailureReason?.let {
                    Text(
                        stringResource(Res.string.dj_failure_detail, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                stringResource(Res.string.dj_preparing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringResource(Res.string.dj_cancel)) }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    message: String,
    error: Boolean,
    onDismiss: () -> Unit,
    detail: String? = null,
) {
    val colors =
        if (error) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors, onClick = onDismiss) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message, style = MaterialTheme.typography.bodyMedium)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** "45 min", "2 h" or "1 h 30 min". */
@Composable
private fun sessionLengthText(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> stringResource(Res.string.dj_length_minutes, rest)
        rest == 0 -> stringResource(Res.string.dj_length_hours, hours)
        else -> stringResource(Res.string.dj_length_hours_minutes, hours, rest)
    }
}
