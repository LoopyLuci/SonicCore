package com.soniccore.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.soniccore.core.model.settings.VisualizationStyle
import com.soniccore.core.ui.theme.LocalSonicColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Real-time spectrum visualizer.
 *
 * Performance rules baked in:
 *  - `Path` and `Brush` hoisted via `remember` / `drawWithCache`, never per-frame.
 *  - No per-bin `animate*AsState` — 48 concurrent animations would jank; the
 *    smoothing already happens in the DSP layer.
 *  - Values arrive pre-smoothed as dBFS in the range roughly -120..0.
 */
@Composable
fun SpectrumVisualizer(
    magnitudesDb: FloatArray,
    modifier: Modifier = Modifier,
    peaksDb: FloatArray? = null,
    style: VisualizationStyle = VisualizationStyle.BARS,
    floorDb: Float = -90f,
    showPeakHold: Boolean = true,
) {
    val colors = LocalSonicColors.current
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

    fun normalize(db: Float): Float = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)

    Box(
        modifier = modifier.drawWithCache {
            val gradient = Brush.verticalGradient(
                colors = listOf(colors.spectrumHigh, colors.spectrumMid, colors.spectrumLow),
            )
            onDrawBehind {
                if (magnitudesDb.isEmpty()) return@onDrawBehind
                val width = size.width
                val height = size.height

                when (style) {
                    VisualizationStyle.BARS -> {
                        val slot = width / magnitudesDb.size
                        val barWidth = slot * 0.72f
                        magnitudesDb.forEachIndexed { index, db ->
                            val magnitude = normalize(db)
                            val barHeight = magnitude * height
                            val left = index * slot + (slot - barWidth) / 2f
                            drawRoundRect(
                                brush = gradient,
                                topLeft = Offset(left, height - barHeight),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth * 0.28f),
                            )
                            if (showPeakHold && peaksDb != null && index < peaksDb.size) {
                                val peakY = height - normalize(peaksDb[index]) * height
                                drawRect(
                                    color = colors.spectrumPeak,
                                    topLeft = Offset(left, peakY),
                                    size = Size(barWidth, 2.dp.toPx()),
                                )
                            }
                        }
                    }

                    VisualizationStyle.MIRROR -> {
                        val slot = width / magnitudesDb.size
                        val barWidth = slot * 0.72f
                        val centerY = height / 2f
                        magnitudesDb.forEachIndexed { index, db ->
                            val half = normalize(db) * centerY
                            val left = index * slot + (slot - barWidth) / 2f
                            drawRoundRect(
                                brush = gradient,
                                topLeft = Offset(left, centerY - half),
                                size = Size(barWidth, half * 2f),
                                cornerRadius = CornerRadius(barWidth * 0.3f),
                            )
                        }
                    }

                    VisualizationStyle.LINE, VisualizationStyle.FILLED -> {
                        linePath.reset()
                        fillPath.reset()
                        val step = width / (magnitudesDb.size - 1).coerceAtLeast(1)
                        magnitudesDb.forEachIndexed { index, db ->
                            val x = index * step
                            val y = height - normalize(db) * height
                            if (index == 0) {
                                linePath.moveTo(x, y)
                                fillPath.moveTo(x, height)
                                fillPath.lineTo(x, y)
                            } else {
                                linePath.lineTo(x, y)
                                fillPath.lineTo(x, y)
                            }
                        }
                        fillPath.lineTo(width, height)
                        fillPath.close()

                        if (style == VisualizationStyle.FILLED) {
                            drawPath(
                                path = fillPath,
                                brush = Brush.verticalGradient(
                                    listOf(
                                        colors.spectrumMid.copy(alpha = 0.55f),
                                        colors.spectrumLow.copy(alpha = 0.05f),
                                    ),
                                ),
                            )
                        }
                        drawPath(
                            path = linePath,
                            color = colors.curveStroke,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }

                    VisualizationStyle.CIRCULAR -> {
                        val centerX = width / 2f
                        val centerY = height / 2f
                        val innerRadius = min(width, height) * 0.22f
                        val maxLength = min(width, height) * 0.26f
                        val angleStep = (2 * Math.PI / magnitudesDb.size).toFloat()
                        magnitudesDb.forEachIndexed { index, db ->
                            val magnitude = normalize(db)
                            val angle = index * angleStep - (Math.PI / 2).toFloat()
                            val startX = centerX + cos(angle) * innerRadius
                            val startY = centerY + sin(angle) * innerRadius
                            val endX = centerX + cos(angle) * (innerRadius + magnitude * maxLength)
                            val endY = centerY + sin(angle) * (innerRadius + magnitude * maxLength)
                            drawLine(
                                brush = gradient,
                                start = Offset(startX, startY),
                                end = Offset(endX, endY),
                                strokeWidth = 3.dp.toPx(),
                            )
                        }
                    }

                    VisualizationStyle.WAVEFORM -> {
                        linePath.reset()
                        val step = width / (magnitudesDb.size - 1).coerceAtLeast(1)
                        val centerY = height / 2f
                        magnitudesDb.forEachIndexed { index, db ->
                            val amplitude = normalize(db) * centerY
                            val x = index * step
                            val y = if (index % 2 == 0) centerY - amplitude else centerY + amplitude
                            if (index == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                        }
                        drawPath(
                            path = linePath,
                            color = colors.curveStroke,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }

                    VisualizationStyle.SPECTROGRAM -> {
                        // Column-per-frame heat strip; the caller supplies history.
                        val slot = width / magnitudesDb.size
                        magnitudesDb.forEachIndexed { index, db ->
                            val magnitude = normalize(db)
                            drawRect(
                                color = heatColor(magnitude, colors.spectrumLow, colors.spectrumHigh, colors.spectrumPeak),
                                topLeft = Offset(index * slot, 0f),
                                size = Size(slot, height),
                            )
                        }
                    }

                    VisualizationStyle.OFF -> Unit
                }
            }
        },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) { }
    }
}

private fun heatColor(t: Float, low: Color, mid: Color, high: Color): Color = when {
    t < 0.5f -> lerpColor(low, mid, t * 2f)
    else -> lerpColor(mid, high, (t - 0.5f) * 2f)
}

private fun lerpColor(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha + (to.alpha - from.alpha) * t,
)

/** Horizontal level meter with peak hold and colour-coded zones. */
@Composable
fun LevelMeterBar(
    levelDb: Float,
    modifier: Modifier = Modifier,
    peakDb: Float? = null,
    floorDb: Float = -60f,
    isClipping: Boolean = false,
) {
    val colors = LocalSonicColors.current
    val animated by animateFloatAsState(
        targetValue = ((levelDb - floorDb) / -floorDb).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 900f),
        label = "level"
        // NOT localised — semantics tag, not user copy.,
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val radius = CornerRadius(height / 2f)

        drawRoundRect(
            color = colors.gridLine,
            size = Size(width, height),
            cornerRadius = radius,
        )

        val barColor = when {
            isClipping || levelDb > -1f -> colors.levelDanger
            levelDb > -6f -> colors.levelCaution
            else -> colors.levelSafe
        }

        if (animated > 0f) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(colors.levelSafe, colors.levelCaution, barColor),
                    startX = 0f,
                    endX = width,
                ),
                size = Size(width * animated, height),
                cornerRadius = radius,
            )
        }

        peakDb?.let { peak ->
            val peakX = ((peak - floorDb) / -floorDb).coerceIn(0f, 1f) * width
            drawLine(
                color = colors.spectrumPeak,
                start = Offset(peakX, 0f),
                end = Offset(peakX, height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
