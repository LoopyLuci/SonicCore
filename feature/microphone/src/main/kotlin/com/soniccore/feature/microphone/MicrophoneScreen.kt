package com.soniccore.feature.microphone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.audio.MicSource
import com.soniccore.core.model.audio.NoiseSuppressionMode
import com.soniccore.core.model.settings.VisualizationStyle
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LevelMeterBar
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.core.ui.component.SpectrumVisualizer
import com.soniccore.core.ui.theme.MonoNumericStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicrophoneScreen(
    viewModel: MicrophoneViewModel = hiltViewModel(),
    onRequestPermission: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.microphone_microphone)) },
                actions = {
                    IconButton(onClick = viewModel::saveToActiveProfile) {
                        Icon(Icons.Filled.Save, contentDescription = "Save to profile")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            // Live level + spectrum.
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.microphone_input_level),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.microphone_1f_dbfs).format(state.levels.peakDbfs),
                                style = MonoNumericStyle,
                            )
                            if (state.levels.isClipping) {
                                Spacer(Modifier.width(6.dp))
                                InfoChip(text = stringResource(R.string.microphone_clip), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LevelMeterBar(
                            levelDb = state.levels.rmsDbfs,
                            peakDb = state.levels.heldPeakDbfs,
                            isClipping = state.levels.isClipping,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        if (state.spectrum.isNotEmpty()) {
                            SpectrumVisualizer(
                                magnitudesDb = state.spectrum,
                                style = VisualizationStyle.BARS,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(90.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (state.isMonitoring) {
                                    viewModel.stopMonitoring()
                                } else if (!state.hasPermission) {
                                    onRequestPermission()
                                } else {
                                    viewModel.startMonitoring()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = if (state.isMonitoring) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    state.isMonitoring -> "Stop monitoring"
                                    !state.hasPermission -> "Grant microphone permission"
                                    else -> "Start monitoring"
                                },
                            )
                        }
                    }
                }
            }

            item { SectionHeader(title = stringResource(R.string.microphone_source), subtitle = stringResource(R.string.microphone_controls_platform_pre_processing)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    MicSource.entries.forEach { source ->
                        val enabled = source != MicSource.UNPROCESSED || state.support.unprocessedSource
                        FilterChip(
                            selected = state.settings.micSource == source,
                            onClick = { viewModel.setSource(source) },
                            enabled = enabled,
                            label = { Text(source.displayName) },
                        )
                    }
                }
            }
            item {
                Text(
                    text = state.settings.micSource.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            if (!state.support.unprocessedSource) {
                item {
                    LimitationNotice(
                        text = stringResource(R.string.microphone_this_device_does_not_report_support_for),
                    )
                }
            }

            item { SectionHeader(title = stringResource(R.string.microphone_gain), subtitle = stringResource(R.string.microphone_software_gain_android_has_no_hardware_mi)) }
            item {
                SliderRow(
                    label = stringResource(R.string.microphone_input_gain),
                    valueText = "%+.1f dB".format(state.settings.gainDb),
                    value = (state.settings.gainDb + 24f) / 60f,
                    onValueChange = { viewModel.setGainDb(it * 60f - 24f) },
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_automatic_gain_control),
                    checked = state.settings.autoGainControl,
                    enabled = state.support.automaticGainControl,
                    subtitle = if (state.support.automaticGainControl) {
                        "Platform AGC keeps levels consistent"
                    } else {
                        "Not available on this device"
                    },
                    onCheckedChange = viewModel::setAutoGainControl,
                )
            }

            item { SectionHeader(title = stringResource(R.string.microphone_noise_control)) }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    NoiseSuppressionMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.settings.noiseSuppression == mode,
                            onClick = { viewModel.setNoiseSuppression(mode) },
                            enabled = mode == NoiseSuppressionMode.OFF || state.support.noiseSuppression,
                            label = { Text(mode.displayName) },
                        )
                    }
                }
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_echo_cancellation),
                    checked = state.settings.echoCancellation,
                    enabled = state.support.echoCancellation,
                    subtitle = if (state.support.echoCancellation) {
                        "Removes speaker bleed during calls"
                    } else {
                        "Not available on this device"
                    },
                    onCheckedChange = viewModel::setEchoCancellation,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_wind_noise_reduction),
                    checked = state.settings.windNoiseReduction,
                    onCheckedChange = viewModel::setWindNoiseReduction,
                )
            }

            item { SectionHeader(title = stringResource(R.string.microphone_noise_gate), subtitle = stringResource(R.string.microphone_mutes_the_mic_below_a_threshold)) }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_enable_gate),
                    checked = state.settings.noiseGateEnabled,
                    subtitle = if (state.levels.gateOpen) "Gate is open" else "Gate is closed",
                    onCheckedChange = viewModel::setNoiseGate,
                )
            }
            if (state.settings.noiseGateEnabled) {
                item {
                    SliderRow(
                        label = stringResource(R.string.microphone_threshold),
                        valueText = "%.0f dB".format(state.settings.noiseGateThresholdDb),
                        value = (state.settings.noiseGateThresholdDb + 80f) / 70f,
                        onValueChange = { viewModel.setNoiseGateThreshold(it * 70f - 80f) },
                    )
                }
                item {
                    SliderRow(
                        label = stringResource(R.string.microphone_attack),
                        valueText = "%.1f ms".format(state.settings.noiseGateAttackMs),
                        value = state.settings.noiseGateAttackMs / 100f,
                        onValueChange = { viewModel.setNoiseGateAttack(it * 100f) },
                    )
                }
                item {
                    SliderRow(
                        label = stringResource(R.string.microphone_release),
                        valueText = "%.0f ms".format(state.settings.noiseGateReleaseMs),
                        value = state.settings.noiseGateReleaseMs / 1000f,
                        onValueChange = { viewModel.setNoiseGateRelease(it * 1000f) },
                    )
                }
            }

            item { SectionHeader(title = stringResource(R.string.microphone_monitoring), subtitle = stringResource(R.string.microphone_hear_yourself_while_you_speak)) }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_sidetone),
                    checked = state.settings.sidetoneEnabled,
                    onCheckedChange = viewModel::setSidetone,
                )
            }
            if (state.settings.sidetoneEnabled) {
                item {
                    SliderRow(
                        label = stringResource(R.string.microphone_sidetone_level),
                        valueText = "${(state.settings.sidetoneLevel * 100).toInt()}%",
                        value = state.settings.sidetoneLevel,
                        onValueChange = viewModel::setSidetoneLevel,
                    )
                }
            }

            item { SectionHeader(title = stringResource(R.string.microphone_voice_processing)) }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_compressor),
                    checked = state.settings.compressorEnabled,
                    subtitle = stringResource(R.string.microphone_evens_out_loud_and_quiet_passages),
                    onCheckedChange = viewModel::setCompressor,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_de_esser),
                    checked = state.settings.deEsserEnabled,
                    subtitle = stringResource(R.string.microphone_tames_harsh_s_sounds_around_7_khz),
                    onCheckedChange = viewModel::setDeEsser,
                )
            }
            item {
                ToggleRow(
                    label = stringResource(R.string.microphone_beamforming),
                    checked = state.settings.beamforming,
                    subtitle = stringResource(R.string.microphone_directional_pickup_hint_device_dependent),
                    onCheckedChange = viewModel::setBeamforming,
                )
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MonoNumericStyle)
        }
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
