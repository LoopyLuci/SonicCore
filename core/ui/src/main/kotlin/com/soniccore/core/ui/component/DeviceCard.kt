package com.soniccore.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerGroup
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.soniccore.core.ui.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.ConnectionState
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.ui.theme.LocalSonicColors
import com.soniccore.core.ui.theme.MonoNumericStyle

fun DeviceKind.icon(): ImageVector = when (this) {
    DeviceKind.HEADPHONES -> Icons.Filled.Headphones
    DeviceKind.HEADSET -> Icons.Filled.Headset
    DeviceKind.EARBUDS -> Icons.Filled.Headphones
    DeviceKind.SPEAKER -> Icons.Filled.Speaker
    DeviceKind.SOUNDBAR -> Icons.Filled.SpeakerGroup
    DeviceKind.DAC -> Icons.Filled.Usb
    DeviceKind.AUDIO_INTERFACE -> Icons.Filled.GraphicEq
    DeviceKind.MICROPHONE, DeviceKind.PHONE_MIC -> Icons.Filled.Mic
    DeviceKind.HEARING_AID -> Icons.Filled.Hearing
    DeviceKind.CAR_AUDIO -> Icons.Filled.DirectionsCar
    DeviceKind.TV -> Icons.Filled.Tv
    DeviceKind.PHONE_SPEAKER, DeviceKind.EARPIECE -> Icons.Filled.PhoneAndroid
    DeviceKind.UNKNOWN -> Icons.Filled.Speaker
}

fun DeviceTransport.icon(): ImageVector = when (this) {
    DeviceTransport.BLUETOOTH_CLASSIC, DeviceTransport.BLUETOOTH_LE -> Icons.Filled.Bluetooth
    DeviceTransport.USB -> Icons.Filled.Usb
    DeviceTransport.ANALOG_35MM -> Icons.Filled.Cable
    DeviceTransport.WIFI -> Icons.Filled.Cast
    DeviceTransport.HDMI -> Icons.Filled.Tv
    DeviceTransport.BUILTIN -> Icons.Filled.PhoneAndroid
    DeviceTransport.UNKNOWN -> Icons.Filled.Speaker
}

@Composable
fun DeviceTransport.color(): Color {
    val colors = LocalSonicColors.current
    return when (this) {
        DeviceTransport.BLUETOOTH_CLASSIC, DeviceTransport.BLUETOOTH_LE -> colors.transportBluetooth
        DeviceTransport.USB -> colors.transportUsb
        DeviceTransport.ANALOG_35MM -> colors.transportAnalog
        DeviceTransport.WIFI -> colors.transportWifi
        DeviceTransport.HDMI, DeviceTransport.BUILTIN, DeviceTransport.UNKNOWN -> colors.transportBuiltin
    }
}

fun DeviceTransport.label(): String = when (this) {
    DeviceTransport.ANALOG_35MM -> "3.5 mm"
    DeviceTransport.USB -> "USB"
    DeviceTransport.BLUETOOTH_CLASSIC -> "Bluetooth"
    DeviceTransport.BLUETOOTH_LE -> "LE Audio"
    DeviceTransport.WIFI -> "Wi-Fi"
    DeviceTransport.HDMI -> "HDMI"
    DeviceTransport.BUILTIN -> "Built-in"
    DeviceTransport.UNKNOWN -> "Unknown"
}

/** Small pill showing a codec, sample rate, or any short technical fact. */
@Composable
fun InfoChip(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    mono: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.14f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = if (mono) MonoNumericStyle else MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Battery readout that renders "—" when the platform gives us nothing. */
@Composable
fun BatteryIndicator(
    percent: Int?,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val colors = LocalSonicColors.current
    val tint = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent <= 15 -> colors.batteryCritical
        percent <= 35 -> colors.batteryLow
        else -> colors.batteryGood
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(3.dp))
        }
        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 11.dp)
                .background(tint.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
        ) {
            if (percent != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(percent / 100f)
                        .height(11.dp)
                        .background(tint, RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = percent?.let { "$it%" } ?: "—",
            style = MonoNumericStyle,
            color = tint,
        )
    }
}

/**
 * Primary device card. Shows every fact we actually know and omits — rather than
 * fabricates — the ones the platform withholds.
 */
@Composable
fun DeviceCard(
    device: AudioDevice,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalSonicColors.current
    val transportColor = device.transport.color()
    val borderAlpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(stiffness = 300f),
        label = "activeBorder",
        // NOT localised — animation label key, not user copy.
    )
    val containerColor by animateColorAsState(
        targetValue = if (isActive) {
            transportColor.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        label = "container",
        // NOT localised — animation label key, not user copy.
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 3.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(transportColor.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = device.kind.icon(),
                    contentDescription = null,
                    tint = transportColor,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    InfoChip(text = device.transport.label(), tint = transportColor)
                    device.activeCodec?.let { codec ->
                        InfoChip(text = codec.displayName, tint = colors.spectrumMid)
                    }
                    device.currentSampleRate?.let { rate ->
                        InfoChip(text = stringResource(R.string.format_khz, (rate / 1000).toString()), tint = colors.spectrumLow, mono = true)
                    }
                    device.usbAudioClass?.let { uac ->
                        InfoChip(text = uac.displayName, tint = colors.transportUsb)
                    }
                    device.wifiProtocol?.let { protocol ->
                        InfoChip(text = protocol.displayName, tint = colors.transportWifi)
                    }
                }
                val showsBattery = device.batteryPercent != null ||
                    device.capabilities.supportsBatteryReporting
                if (showsBattery || device.connectionState == ConnectionState.ACTIVE) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Render the indicator whenever the device *can* report battery,
                        // so an unreported level shows "—" rather than vanishing.
                        if (showsBattery) {
                            BatteryIndicator(percent = device.batteryPercent)
                            device.secondaryBatteryPercent?.let {
                                Spacer(Modifier.width(8.dp))
                                BatteryIndicator(percent = it, label = "R")
                            }
                            device.caseBatteryPercent?.let {
                                Spacer(Modifier.width(8.dp))
                                BatteryIndicator(percent = it, label = stringResource(R.string.device_battery_case))
                            }
                        }
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.device_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = transportColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            trailing?.invoke()
        }
    }
}
