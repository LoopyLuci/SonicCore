package com.soniccore.core.ui.component

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soniccore.core.model.audio.StreamVolume
import com.soniccore.core.ui.theme.LocalSonicColors
import com.soniccore.core.ui.theme.MonoNumericStyle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import com.soniccore.core.ui.R
import kotlin.math.roundToInt

/**
 * Volume row with a mute toggle, honest step count, and haptic ticks on each
 * hardware step boundary.
 *
 * When the hardware exposes fewer steps than the UI implies, [showStepHint] surfaces
 * the real granularity instead of pretending the slider is continuous.
 */
@Composable
fun VolumeSliderRow(
    volume: StreamVolume,
    modifier: Modifier = Modifier,
    label: String = volume.stream.displayName,
    enabled: Boolean = true,
    showStepHint: Boolean = true,
    onPercentChange: (Float) -> Unit,
    onToggleMute: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val colors = LocalSonicColors.current
    var localPercent by remember(volume.index, volume.maxIndex) {
        mutableFloatStateOf(volume.percent)
    }
    var lastStep by remember { mutableStateOf(volume.index) }

    LaunchedEffect(volume.index) { localPercent = volume.percent }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.format_percent, (localPercent * 100).roundToInt()),
                style = MonoNumericStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showStepHint) {
                Spacer(Modifier.width(6.dp))
                InfoChip(
                    text = pluralStringResource(R.plurals.count_steps, volume.stepCount, volume.stepCount),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    mono = true,
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleMute, enabled = enabled) {
                Icon(
                    imageVector = when {
                        volume.isMuted -> Icons.AutoMirrored.Filled.VolumeOff
                        localPercent < 0.02f -> Icons.AutoMirrored.Filled.VolumeMute
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    contentDescription = if (volume.isMuted) "Unmute" else "Mute",
                    tint = if (volume.isMuted) {
                        colors.levelDanger
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Slider(
                value = localPercent,
                onValueChange = { value ->
                    localPercent = value
                    val step = volume.indexForPercent(value)
                    if (step != lastStep) {
                        lastStep = step
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onPercentChange(value)
                },
                enabled = enabled && !volume.isFixed,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }

        if (volume.isFixed) {
            Text(
                text = stringResource(R.string.volume_fixed_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/**
 * Vertical fader for one EQ band. Drag anywhere on the column; the gain readout
 * follows the thumb. Used by the 10/15/31-band graphic views.
 */
@Composable
fun EqBandFader(
    frequencyHz: Float,
    gainDb: Float,
    modifier: Modifier = Modifier,
    minDb: Float = -12f,
    maxDb: Float = 12f,
    isSelected: Boolean = false,
    onGainChange: (Float) -> Unit,
) {
    val colors = LocalSonicColors.current
    val haptics = LocalHapticFeedback.current
    var trackHeight by remember { mutableFloatStateOf(1f) }

    Column(
        modifier = modifier.width(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (gainDb == 0f) "0" else "%+.1f".format(gainDb),
            style = MonoNumericStyle,
            color = if (gainDb == 0f) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                colors.curveStroke
            },
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .width(30.dp)
                .height(150.dp)
                .pointerInput(minDb, maxDb) {
                    trackHeight = size.height.toFloat()
                    detectDragGestures { change, _ ->
                        val ratio = 1f - (change.position.y / trackHeight).coerceIn(0f, 1f)
                        val newGain = minDb + ratio * (maxDb - minDb)
                        onGainChange(newGain)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
                .drawBehind {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f
                    val trackWidth = 5.dp.toPx()

                    // Track.
                    drawRoundRect(
                        color = colors.gridLine,
                        topLeft = Offset((width - trackWidth) / 2f, 0f),
                        size = Size(trackWidth, height),
                        cornerRadius = CornerRadius(trackWidth / 2f),
                    )
                    // Zero marker.
                    drawLine(
                        color = colors.gridLine.copy(alpha = 0.85f),
                        start = Offset(width * 0.15f, centerY),
                        end = Offset(width * 0.85f, centerY),
                        strokeWidth = 1.dp.toPx(),
                    )

                    val ratio = ((gainDb - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
                    val thumbY = height - ratio * height

                    // Fill from zero to the thumb.
                    val fillTop = minOf(centerY, thumbY)
                    val fillHeight = kotlin.math.abs(centerY - thumbY)
                    if (fillHeight > 1f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(colors.spectrumHigh, colors.spectrumLow),
                            ),
                            topLeft = Offset((width - trackWidth) / 2f, fillTop),
                            size = Size(trackWidth, fillHeight),
                            cornerRadius = CornerRadius(trackWidth / 2f),
                        )
                    }

                    // Thumb.
                    val thumbRadius = if (isSelected) 8.dp.toPx() else 6.5.dp.toPx()
                    if (isSelected) {
                        drawCircle(
                            color = colors.activeGlow.copy(alpha = 0.22f),
                            radius = thumbRadius * 2f,
                            center = Offset(width / 2f, thumbY),
                        )
                    }
                    drawCircle(
                        color = colors.curveStroke,
                        radius = thumbRadius,
                        center = Offset(width / 2f, thumbY),
                    )
                },
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = shortFrequencyLabel(frequencyHz),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun shortFrequencyLabel(hz: Float): String = when {
    hz >= 1000f -> {
        val k = hz / 1000f
        if (k % 1f == 0f) "${k.toInt()}k" else "%.1fk".format(k)
    }
    hz % 1f == 0f -> hz.toInt().toString()
    else -> "%.1f".format(hz)
}

/** Section header used across every settings surface. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Explains a platform limitation instead of silently doing nothing. */
@Composable
fun LimitationNotice(
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val colors = LocalSonicColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .drawBehind {
                    drawCircle(color = colors.levelCaution)
                },
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action?.invoke()
        }
    }
}
