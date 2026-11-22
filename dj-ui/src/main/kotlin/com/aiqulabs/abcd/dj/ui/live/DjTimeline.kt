package com.aiqulabs.abcd.dj.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_timeline_mix_in
import com.metrolist.shared.generated.resources.dj_timeline_mix_label
import com.metrolist.shared.generated.resources.dj_timeline_mixing
import com.metrolist.shared.generated.resources.dj_timeline_planning
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min

/**
 * What the timeline draws (stage 12). The waveform, beats and choruses come from the song's analysis; the
 * position and the mix window from the engine, read about 20 times a second.
 */
@Immutable
data class DjTimelineUi(
    val positionMs: Long,
    val durationMs: Long,
    /** The next (or running) transition on this song's clock; null until the next song is ready. */
    val mixStartMs: Long?,
    val mixEndMs: Long?,
    val mixing: Boolean,
    val mixProgress: Float,
    val waveform: List<Int>,
    val beatsMs: List<Double>,
    val beatsPerBar: Int,
    val choruses: List<Pair<Double, Double>>,
    val bpm: Double,
    val camelot: String,
    val nextTitle: String?,
) {
    /** Index of the beat at [positionMs] (-1 before the first beat). */
    val beatIndex: Int
        get() {
            var lo = 0
            var hi = beatsMs.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (beatsMs[mid] <= positionMs) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return found
        }

    /** 0 at a beat, rising to 1 just before the next one. */
    val beatPhase: Float
        get() {
            val i = beatIndex
            if (i < 0 || i + 1 >= beatsMs.size) return 0f
            val span = beatsMs[i + 1] - beatsMs[i]
            return if (span <= 0) 0f else ((positionMs - beatsMs[i]) / span).toFloat().coerceIn(0f, 1f)
        }

    /** Whole bars from now until the mix starts. */
    val barsToMix: Int?
        get() {
            val start = mixStartMs ?: return null
            if (bpm <= 0) return null
            val barMs = 60_000.0 / bpm * beatsPerBar.coerceAtLeast(1)
            return ((start - positionMs) / barMs).toInt().coerceAtLeast(0)
        }
}

/**
 * The live screen's timeline: the song's waveform with the played part lit, its choruses, the window where the
 * DJ will mix into the next song, a glowing playhead, a beat pulse and a countdown to the mix.
 */
@Composable
fun DjTimeline(
    ui: DjTimelineUi,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatPulse(ui, Modifier.weight(1f))
                Chip("${formatBpm(ui.bpm)} BPM")
                Spacer(Modifier.size(6.dp))
                if (ui.camelot.isNotBlank()) Chip(ui.camelot)
            }
            Waveform(ui, Modifier.fillMaxWidth().height(84.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(ui.positionMs), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = colors.onSurfaceVariant)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { MixStatus(ui) }
                Text(
                    "-" + clock((ui.durationMs - ui.positionMs).coerceAtLeast(0)),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant,
                )
            }
            if (ui.mixing) MixBar(ui.mixProgress)
        }
    }
}

@Composable
private fun MixStatus(ui: DjTimelineUi) {
    val colors = MaterialTheme.colorScheme
    val text =
        when {
            ui.mixing -> stringResource(Res.string.dj_timeline_mixing, (ui.mixProgress * 100).toInt())
            ui.mixStartMs != null ->
                stringResource(Res.string.dj_timeline_mix_in, clock((ui.mixStartMs - ui.positionMs).coerceAtLeast(0)), ui.barsToMix ?: 0)
            else -> stringResource(Res.string.dj_timeline_planning)
        }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (ui.mixing) colors.tertiary else colors.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** One dot per beat of the bar; the current beat is lit and pulses, the downbeat is bigger. */
@Composable
private fun BeatPulse(
    ui: DjTimelineUi,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val perBar = ui.beatsPerBar.coerceIn(1, 8)
    val index = ui.beatIndex
    val current = if (index < 0) -1 else index % perBar
    val pulse = 1f + 0.45f * (1f - ui.beatPhase) * (1f - ui.beatPhase)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (beat in 0 until perBar) {
            val lit = beat == current
            val base = if (beat == 0) 12.dp else 9.dp
            Box(
                Modifier
                    .size(base)
                    .scale(if (lit) pulse else 1f)
                    .clip(CircleShape)
                    .background(
                        if (lit) {
                            Brush.radialGradient(listOf(colors.tertiary, colors.primary))
                        } else {
                            Brush.linearGradient(listOf(colors.outlineVariant, colors.outlineVariant))
                        },
                    ),
            )
        }
    }
}

@Composable
private fun Waveform(
    ui: DjTimelineUi,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val played = Brush.horizontalGradient(listOf(colors.primary, colors.tertiary))
    val unplayed = colors.onSurface.copy(alpha = 0.22f)
    val chorusTint = colors.secondary.copy(alpha = 0.12f)
    val mixFill = colors.tertiary.copy(alpha = 0.18f)
    val mixEdge = colors.tertiary
    val playhead = colors.onSurface
    val glow = colors.primary.copy(alpha = 0.35f)
    val mixLabel = stringResource(Res.string.dj_timeline_mix_label)
    val labelColor = colors.tertiary
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val duration = ui.durationMs.coerceAtLeast(1).toFloat()
            fun xOf(ms: Double): Float = (ms.toFloat() / duration * size.width).coerceIn(0f, size.width)
            val playX = xOf(ui.positionMs.toDouble())

            ui.choruses.forEach { (start, end) ->
                drawRoundRect(chorusTint, Offset(xOf(start), 0f), Size(xOf(end) - xOf(start), size.height), CornerRadius(8f))
            }
            val mixStart = ui.mixStartMs
            val mixEnd = ui.mixEndMs
            if (mixStart != null && mixEnd != null) {
                val left = xOf(mixStart.toDouble())
                val right = max(xOf(mixEnd.toDouble()), left + 6f)
                drawRoundRect(mixFill, Offset(left, 0f), Size(right - left, size.height), CornerRadius(10f))
                drawRoundRect(
                    mixEdge,
                    Offset(left, 0f),
                    Size(right - left, size.height),
                    CornerRadius(10f),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }

            // Waveform bars, mirrored around the middle.
            val barWidth = 3.dp.toPx()
            val gap = 1.5.dp.toPx()
            val count = max(1, (size.width / (barWidth + gap)).toInt())
            val peaks = ui.waveform
            val mid = size.height / 2
            for (i in 0 until count) {
                val x = i * (barWidth + gap)
                val value =
                    if (peaks.isEmpty()) {
                        0.25f
                    } else {
                        val from = i * peaks.size / count
                        val to = max(from + 1, (i + 1) * peaks.size / count)
                        var peak = 0
                        for (k in from until min(to, peaks.size)) peak = max(peak, peaks[k])
                        (peak / 255f).coerceIn(0.04f, 1f)
                    }
                val half = value * mid * 0.92f
                val top = Offset(x, mid - half)
                val barSize = Size(barWidth, half * 2)
                if (x + barWidth <= playX) {
                    drawRoundRect(played, top, barSize, CornerRadius(barWidth / 2))
                } else {
                    drawRoundRect(unplayed, top, barSize, CornerRadius(barWidth / 2))
                }
            }

            // Playhead with a soft glow.
            drawLine(glow, Offset(playX, 0f), Offset(playX, size.height), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
            drawLine(playhead, Offset(playX, 0f), Offset(playX, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(playhead, radius = 4.dp.toPx(), center = Offset(playX, 3.dp.toPx()))
        }
        val start = ui.mixStartMs
        if (start != null && !ui.mixing) {
            Text(
                mixLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp).offsetFraction(start.toFloat() / ui.durationMs.coerceAtLeast(1)),
            )
        }
    }
}

/** Places a label at [fraction] of the parent's width. */
private fun Modifier.offsetFraction(fraction: Float): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0))
            val parentWidth = constraints.maxWidth
            val x = (fraction.coerceIn(0f, 1f) * parentWidth).toInt().coerceAtMost((parentWidth - placeable.width).coerceAtLeast(0))
            layout(parentWidth, placeable.height) { placeable.place(x, 0) }
        },
    )

/** The blend's progress, from the outgoing song's colour to the incoming one's. */
@Composable
private fun MixBar(progress: Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(colors.primary, colors.tertiary))),
        )
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun formatBpm(bpm: Double): String = if (bpm <= 0) "-" else (kotlin.math.round(bpm * 10) / 10).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }

/** For the mini bar: a thin line with the played part and the mix window. */
@Composable
fun DjMiniProgress(
    ui: DjTimelineUi,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val played = Brush.horizontalGradient(listOf(colors.primary, colors.tertiary))
    val track = colors.onPrimaryContainer.copy(alpha = 0.18f)
    val mix = colors.tertiary.copy(alpha = 0.55f)
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val duration = ui.durationMs.coerceAtLeast(1).toFloat()
        drawRect(track)
        val start = ui.mixStartMs
        val end = ui.mixEndMs
        if (start != null && end != null) {
            val left = start / duration * size.width
            drawRect(mix, Offset(left, 0f), Size(max(end / duration * size.width - left, 3f), size.height))
        }
        drawRect(played, Offset.Zero, Size(ui.positionMs / duration * size.width, size.height))
    }
}
