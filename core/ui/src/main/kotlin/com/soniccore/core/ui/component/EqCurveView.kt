package com.soniccore.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.ResponsePoint
import com.soniccore.core.ui.theme.LocalSonicColors
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Interactive frequency-response curve.
 *
 * Frequency is mapped logarithmically — a linear mapping makes the entire bass
 * region unreadable. Band nodes are draggable in both axes (frequency and gain)
 * and a vertical drag with two pointers adjusts Q.
 *
 * All [Path] objects are hoisted out of the draw lambda: reallocating a Path per
 * frame is the single biggest cause of jank in a live analyzer view.
 */
@Composable
fun EqCurveView(
    // ImmutableList, not List: a plain List is an UNSTABLE Compose type, so this
    // Canvas recomposes on every parent recomposition while a band is being dragged.
    response: ImmutableList<ResponsePoint>,
    bands: ImmutableList<EqBand>,
    modifier: Modifier = Modifier,
    spectrum: FloatArray? = null,
    minDb: Float = -15f,
    maxDb: Float = 15f,
    minFrequency: Float = 20f,
    maxFrequency: Float = 20_000f,
    selectedBandId: String? = null,
    editable: Boolean = true,
    onBandChange: (EqBand) -> Unit = {},
    onBandSelected: (String?) -> Unit = {},
) {
    val colors = LocalSonicColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // Hoisted geometry — reused every frame.
    val curvePath = remember { Path() }
    val fillPath = remember { Path() }
    val spectrumPath = remember { Path() }

    var canvasWidth by remember { mutableIntStateOf(0) }
    var canvasHeight by remember { mutableIntStateOf(0) }
    var draggingBandId by remember { mutableStateOf<String?>(null) }

    val logMin = remember(minFrequency) { ln(minFrequency) }
    val logMax = remember(maxFrequency) { ln(maxFrequency) }

    fun freqToX(frequency: Float, width: Float): Float =
        ((ln(frequency.coerceIn(minFrequency, maxFrequency)) - logMin) / (logMax - logMin)) * width

    fun xToFreq(x: Float, width: Float): Float =
        exp(logMin + (x / width).coerceIn(0f, 1f) * (logMax - logMin))

    fun dbToY(db: Float, height: Float): Float =
        (1f - ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)) * height

    fun yToDb(y: Float, height: Float): Float =
        maxDb - (y / height).coerceIn(0f, 1f) * (maxDb - minDb)

    Box(
        modifier = modifier.pointerInput(editable, bands) {
            if (!editable) return@pointerInput
            detectTapGestures { offset ->
                val hit = bands.minByOrNull { band ->
                    abs(freqToX(band.frequencyHz, size.width.toFloat()) - offset.x)
                }
                val hitX = hit?.let { freqToX(it.frequencyHz, size.width.toFloat()) }
                onBandSelected(if (hit != null && hitX != null && abs(hitX - offset.x) < 48f) hit.id else null)
            }
        }.pointerInput(editable, bands) {
            if (!editable) return@pointerInput
            detectDragGestures(
                onDragStart = { offset ->
                    val width = size.width.toFloat()
                    draggingBandId = bands.minByOrNull { band ->
                        abs(freqToX(band.frequencyHz, width) - offset.x)
                    }?.takeIf { band ->
                        abs(freqToX(band.frequencyHz, width) - offset.x) < 64f
                    }?.id
                    draggingBandId?.let(onBandSelected)
                },
                onDragEnd = { draggingBandId = null },
                onDragCancel = { draggingBandId = null },
            ) { change, _ ->
                val id = draggingBandId ?: return@detectDragGestures
                val band = bands.firstOrNull { it.id == id } ?: return@detectDragGestures
                val width = size.width.toFloat()
                val height = size.height.toFloat()
                onBandChange(
                    band.copy(
                        frequencyHz = xToFreq(change.position.x, width),
                        gainDb = yToDb(change.position.y, height),
                    ).sanitized(),
                )
            }
        },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasWidth = size.width.toInt()
            canvasHeight = size.height.toInt()
            val width = size.width
            val height = size.height

            drawGrid(width, height, colors.gridLine, logMin, logMax, minDb, maxDb, textMeasurer)

            // Live spectrum behind the curve, if supplied.
            if (spectrum != null && spectrum.isNotEmpty()) {
                spectrumPath.reset()
                val barWidth = width / spectrum.size
                spectrum.forEachIndexed { index, magnitudeDb ->
                    val normalized = ((magnitudeDb + 90f) / 90f).coerceIn(0f, 1f)
                    val barHeight = normalized * height
                    val left = index * barWidth
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(colors.spectrumHigh.copy(alpha = 0.5f), colors.spectrumLow.copy(alpha = 0.15f)),
                        ),
                        topLeft = Offset(left, height - barHeight),
                        size = androidx.compose.ui.geometry.Size(barWidth * 0.82f, barHeight),
                    )
                }
            }

            // Zero-dB reference.
            val zeroY = dbToY(0f, height)
            drawLine(
                color = colors.gridLine.copy(alpha = 0.9f),
                start = Offset(0f, zeroY),
                end = Offset(width, zeroY),
                strokeWidth = with(density) { 1.dp.toPx() },
            )

            if (response.size > 1) {
                curvePath.reset()
                fillPath.reset()
                response.forEachIndexed { index, point ->
                    val x = freqToX(point.frequencyHz, width)
                    val y = dbToY(point.magnitudeDb, height)
                    if (index == 0) {
                        curvePath.moveTo(x, y)
                        fillPath.moveTo(x, zeroY)
                        fillPath.lineTo(x, y)
                    } else {
                        curvePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(freqToX(response.last().frequencyHz, width), zeroY)
                fillPath.close()

                drawPath(path = fillPath, color = colors.curveFill)
                drawPath(
                    path = curvePath,
                    color = colors.curveStroke,
                    style = Stroke(width = with(density) { 2.5.dp.toPx() }),
                )
            }

            // Band handles.
            bands.filter { it.enabled }.forEach { band ->
                val x = freqToX(band.frequencyHz, width)
                val y = dbToY(band.gainDb, height)
                val isSelected = band.id == selectedBandId
                val radius = with(density) { (if (isSelected) 9.dp else 6.dp).toPx() }

                if (isSelected) {
                    drawCircle(
                        color = colors.activeGlow.copy(alpha = 0.25f),
                        radius = radius * 2.4f,
                        center = Offset(x, y),
                    )
                }
                drawCircle(color = colors.curveStroke, radius = radius, center = Offset(x, y))
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    radius = radius * 0.45f,
                    center = Offset(x, y),
                )
            }
        }
    }
}

private fun DrawScope.drawGrid(
    width: Float,
    height: Float,
    gridColor: Color,
    logMin: Float,
    logMax: Float,
    minDb: Float,
    maxDb: Float,
    textMeasurer: TextMeasurer,
) {
    val decades = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
    decades.forEach { frequency ->
        val x = ((ln(frequency) - logMin) / (logMax - logMin)) * width
        drawLine(
            color = gridColor,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f,
        )
    }

    // Horizontal dB lines every 3 dB, dashed.
    val dashed = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
    var db = minDb
    while (db <= maxDb) {
        if (db != 0f) {
            val y = (1f - ((db - minDb) / (maxDb - minDb))) * height
            drawLine(
                color = gridColor.copy(alpha = 0.6f),
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f,
                pathEffect = dashed,
            )
        }
        db += 3f
    }
}

/** Human-friendly frequency label: 850 Hz, 1.2 kHz, 12 kHz. */
fun formatFrequency(hz: Float): String = when {
    hz >= 10_000f -> "${(hz / 1000f).toInt()} kHz"
    hz >= 1000f -> "${"%.1f".format(hz / 1000f)} kHz"
    else -> "${hz.toInt()} Hz"
}

fun formatGain(db: Float): String = when {
    db >= 0f -> "+${"%.1f".format(db)} dB"
    else -> "${"%.1f".format(db)} dB"
}

fun formatQ(q: Float): String = "%.2f".format(q)
