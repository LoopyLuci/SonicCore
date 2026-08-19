package com.soniccore.feature.effects

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.audio.ChannelMode
import com.soniccore.core.model.audio.ReplayGainMode
import com.soniccore.core.model.effects.DitheringMode
import com.soniccore.core.model.effects.HrtfProfile
import com.soniccore.core.model.effects.ReverbPreset
import com.soniccore.core.model.effects.VirtualizerMode
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.core.ui.theme.MonoNumericStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsScreen(viewModel: EffectsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val s = state.settings

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
                title = { Text(stringResource(R.string.effects_effects)) },
                actions = {
                    IconButton(onClick = viewModel::resetAll) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset")
                    }
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
            state.platformNote?.let { note ->
                item { LimitationNotice(text = note) }
            }

            item { SectionHeader(title = stringResource(R.string.effects_bass), subtitle = stringResource(R.string.effects_platform_bass_boost)) }
            item {
                Toggle("Bass boost", s.bassBoost.enabled, viewModel::setBassBoost)
            }
            if (s.bassBoost.enabled) {
                item {
                    SliderRow("Strength", "${(s.bassBoost.strength * 100).toInt()}%", s.bassBoost.strength) {
                        viewModel.setBassStrength(it)
                    }
                }
                item {
                    SliderRow("Cutoff", "%.0f Hz".format(s.bassBoost.cutoffHz), (s.bassBoost.cutoffHz - 40f) / 260f) {
                        viewModel.setBassCutoff(40f + it * 260f)
                    }
                }
            }

            item { SectionHeader(title = stringResource(R.string.effects_virtual_surround)) }
            item { Toggle("Virtualizer", s.virtualizer.enabled, viewModel::setVirtualizer) }
            if (s.virtualizer.enabled) {
                item {
                    SliderRow(
                        "Strength",
                        "${(s.virtualizer.strength * 100).toInt()}%",
                        s.virtualizer.strength,
                    ) { viewModel.setVirtualizerStrength(it) }
                }
                item {
                    ChipRow(
                        options = VirtualizerMode.entries.map { it to it.displayName },
                        selected = s.virtualizer.mode,
                        onSelect = viewModel::setVirtualizerMode,
                    )
                }
            }

            item { SectionHeader(title = stringResource(R.string.effects_spatial_audio), subtitle = stringResource(R.string.effects_binaural_rendering_with_head_tracking)) }
            item { Toggle("Spatial audio", s.spatial.enabled, viewModel::setSpatial) }
            if (s.spatial.enabled) {
                item { Toggle("Head tracking", s.spatial.headTracking, viewModel::setHeadTracking) }
                item { Toggle("Dolby Atmos passthrough", s.spatial.passthroughAtmos, viewModel::setAtmosPassthrough) }
                item {
                    SliderRow("Room size", "${(s.spatial.roomSize * 100).toInt()}%", s.spatial.roomSize) {
                        viewModel.setRoomSize(it)
                    }
                }
                item {
                    ChipRow(
                        options = HrtfProfile.entries.map { it to it.displayName },
                        selected = s.spatial.hrtfProfile,
                        onSelect = viewModel::setHrtf,
                    )
                }
            }

            item { SectionHeader(title = stringResource(R.string.effects_reverb)) }
            item { Toggle("Reverb", s.reverb.enabled, viewModel::setReverb) }
            if (s.reverb.enabled) {
                item {
                    ChipRow(
                        options = ReverbPreset.entries.map { it to it.displayName },
                        selected = s.reverb.preset,
                        onSelect = viewModel::setReverbPreset,
                    )
                }
                item {
                    SliderRow("Wet mix", "${(s.reverb.wetMix * 100).toInt()}%", s.reverb.wetMix) {
                        viewModel.setReverbWet(it)
                    }
                }
                item {
                    SliderRow("Decay", "%.1f s".format(s.reverb.decaySeconds), s.reverb.decaySeconds / 10f) {
                        viewModel.setReverbDecay(it * 10f)
                    }
                }
            }

            item {
                SectionHeader(
                    title = stringResource(R.string.effects_crossfeed),
                    subtitle = stringResource(R.string.effects_reduces_headphone_fatigue_by_blending_ch),
                )
            }
            item { Toggle("Crossfeed", s.crossfeed.enabled, viewModel::setCrossfeed) }
            if (s.crossfeed.enabled) {
                item {
                    SliderRow("Amount", "${(s.crossfeed.amount * 100).toInt()}%", s.crossfeed.amount) {
                        viewModel.setCrossfeedAmount(it)
                    }
                }
                item {
                    SliderRow(
                        "Cutoff",
                        "%.0f Hz".format(s.crossfeed.cutoffHz),
                        (s.crossfeed.cutoffHz - 200f) / 1800f,
                    ) { viewModel.setCrossfeedCutoff(200f + it * 1800f) }
                }
                item {
                    SliderRow(
                        "Delay",
                        "%.0f µs".format(s.crossfeed.delayMicros),
                        s.crossfeed.delayMicros / 1000f,
                    ) { viewModel.setCrossfeedDelay(it * 1000f) }
                }
            }

            item { SectionHeader(title = stringResource(R.string.effects_dynamics), subtitle = stringResource(R.string.effects_compressor_and_limiter)) }
            item { Toggle("Compressor", s.dynamics.compressorEnabled, viewModel::setCompressor) }
            if (s.dynamics.compressorEnabled) {
                item {
                    SliderRow(
                        "Threshold",
                        "%.0f dB".format(s.dynamics.thresholdDb),
                        (s.dynamics.thresholdDb + 60f) / 60f,
                    ) { viewModel.setThreshold(it * 60f - 60f) }
                }
                item {
                    SliderRow("Ratio", "%.1f:1".format(s.dynamics.ratio), (s.dynamics.ratio - 1f) / 19f) {
                        viewModel.setRatio(1f + it * 19f)
                    }
                }
                item {
                    SliderRow("Attack", "%.1f ms".format(s.dynamics.attackMs), s.dynamics.attackMs / 200f) {
                        viewModel.setAttack(it * 200f)
                    }
                }
                item {
                    SliderRow("Release", "%.0f ms".format(s.dynamics.releaseMs), s.dynamics.releaseMs / 2000f) {
                        viewModel.setRelease(it * 2000f)
                    }
                }
                item {
                    SliderRow(
                        "Makeup gain",
                        "%+.1f dB".format(s.dynamics.makeupGainDb),
                        (s.dynamics.makeupGainDb + 12f) / 36f,
                    ) { viewModel.setMakeupGain(it * 36f - 12f) }
                }
            }
            item { Toggle("Limiter", s.dynamics.limiterEnabled, viewModel::setLimiter) }
            item { Toggle("Night mode", s.dynamics.nightMode, viewModel::setNightMode) }
            item { Toggle("Speech enhancement", s.dynamics.speechEnhancement, viewModel::setSpeechEnhancement) }

            item { SectionHeader(title = stringResource(R.string.effects_loudness)) }
            item { Toggle("Loudness enhancer", s.loudness.enabled, viewModel::setLoudness) }
            if (s.loudness.enabled) {
                item {
                    SliderRow(
                        "Target gain",
                        "%.1f dB".format(s.loudness.targetGainMb / 100f),
                        s.loudness.targetGainMb / 2000f,
                    ) { viewModel.setLoudnessGain((it * 2000f).toInt()) }
                }
            }
            item {
                ChipRow(
                    options = ReplayGainMode.entries.map { it to it.displayName },
                    selected = s.loudness.replayGainMode,
                    onSelect = viewModel::setReplayGain,
                )
            }

            item { SectionHeader(title = stringResource(R.string.effects_channels), subtitle = stringResource(R.string.effects_balance_width_phase)) }
            item {
                ChipRow(
                    options = ChannelMode.entries.map { it to it.displayName },
                    selected = s.channelMode,
                    onSelect = viewModel::setChannelMode,
                )
            }
            item {
                SliderRow(
                    "Balance",
                    when {
                        s.balance < -0.01f -> "L ${(-s.balance * 100).toInt()}%"
                        s.balance > 0.01f -> "R ${(s.balance * 100).toInt()}%"
                        else -> "Centre"
                    },
                    (s.balance + 1f) / 2f,
                ) { viewModel.setBalance(it * 2f - 1f) }
            }
            item {
                SliderRow("Stereo width", "%.2f×".format(s.stereoWidth), s.stereoWidth / 2f) {
                    viewModel.setStereoWidth(it * 2f)
                }
            }
            item { Toggle("Invert left phase", s.phaseInvertLeft, viewModel::setPhaseInvertLeft) }
            item { Toggle("Invert right phase", s.phaseInvertRight, viewModel::setPhaseInvertRight) }
            item {
                ChipRow(
                    options = DitheringMode.entries.map { it to it.displayName },
                    selected = s.dithering,
                    onSelect = viewModel::setDithering,
                )
            }

            item {
                LimitationNotice(
                    text = stringResource(R.string.effects_platform_effects_bass_virtualizer_reverb),
                )
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MonoNumericStyle)
        }
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
    }
}

@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}
