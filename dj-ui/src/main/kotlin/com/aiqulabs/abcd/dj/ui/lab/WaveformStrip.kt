package com.aiqulabs.abcd.dj.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiqulabs.abcd.dj.core.analysis.AnalysisResult
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dj_lab_waveform_desc
import org.jetbrains.compose.resources.stringResource

private const val SECTION_ALPHA = 0.25f
private const val MIN_BAR_FRACTION = 0.03f

/**
 * The song at a glance: a coloured strip of sections, then the waveform, with the chorus candidates
 * outlined (the main one with a thicker line) and a tick at every phrase start.
 */
@Composable
fun WaveformStrip(
    result: AnalysisResult,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.dj_lab_waveform_desc)
    val waveColor = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outline
    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = description },
    ) {
        val duration = result.durationMs.coerceAtLeast(1.0)

        fun x(ms: Double) = (ms / duration * size.width).toFloat().coerceIn(0f, size.width)

        val stripHeight = size.height * 0.18f
        result.sections.forEach { section ->
            drawRect(
                color = section.type.color().copy(alpha = 0.9f),
                topLeft = Offset(x(section.startMs), 0f),
                size = Size(x(section.endMs) - x(section.startMs), stripHeight),
            )
            drawRect(
                color = section.type.color().copy(alpha = SECTION_ALPHA),
                topLeft = Offset(x(section.startMs), stripHeight),
                size = Size(x(section.endMs) - x(section.startMs), size.height - stripHeight),
            )
        }

        val peaks = result.waveformPeaks
        if (peaks.isNotEmpty()) {
            val middle = stripHeight + (size.height - stripHeight) / 2
            val half = (size.height - stripHeight) / 2 - 2f
            val step = size.width / peaks.size
            peaks.forEachIndexed { index, peak ->
                val h = half * (peak / 255f).coerceAtLeast(MIN_BAR_FRACTION)
                val px = index * step + step / 2
                drawLine(
                    waveColor.copy(alpha = 0.7f),
                    Offset(px, middle - h),
                    Offset(px, middle + h),
                    strokeWidth = (step * 0.8f).coerceAtLeast(1f),
                )
            }
        }

        result.phraseStartsMs.forEach { ms ->
            drawLine(tick, Offset(x(ms), stripHeight), Offset(x(ms), size.height), strokeWidth = 1f)
        }

        result.chorusCandidates.forEach { chorus ->
            drawRect(
                color = outline,
                topLeft = Offset(x(chorus.startMs), stripHeight),
                size = Size(x(chorus.endMs) - x(chorus.startMs), size.height - stripHeight),
                style = Stroke(width = if (chorus.isMain) 5f else 2f),
            )
        }
    }
}
