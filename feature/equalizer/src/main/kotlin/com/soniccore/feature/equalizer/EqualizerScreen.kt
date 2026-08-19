package com.soniccore.feature.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.FilterType
import com.soniccore.core.ui.R
import com.soniccore.core.ui.component.EqBandFader
import com.soniccore.core.ui.component.EqCurveView
import com.soniccore.core.ui.component.InfoChip
import com.soniccore.core.ui.component.LimitationNotice
import com.soniccore.core.ui.component.SectionHeader
import com.soniccore.core.ui.component.formatFrequency
import kotlinx.collections.immutable.toImmutableList
import com.soniccore.core.ui.component.formatGain
import com.soniccore.core.ui.component.formatQ
import com.soniccore.core.ui.theme.MonoNumericStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    viewModel: EqualizerViewModel = hiltViewModel(),
    spectrum: FloatArray? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

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
                title = { Text(stringResource(R.string.equalizer_equalizer)) },
                actions = {
                    Switch(
                        checked = state.settings.enabled,
                        onCheckedChange = viewModel::setEnabled,
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = viewModel::reset) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reset to flat")
                    }
                    IconButton(onClick = { showSaveDialog = true }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save preset")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp),
        ) {
            // Mode selector.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    EqMode.QUICK,
                    EqMode.GRAPHIC_10,
                    EqMode.GRAPHIC_31,
                    EqMode.PARAMETRIC,
                )
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.settings.mode == mode,
                        onClick = { viewModel.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        label = {
                            Text(
                                text = when (mode) {
                                    EqMode.QUICK -> "Quick"
                                    EqMode.GRAPHIC_10 -> "10-band"
                                    EqMode.GRAPHIC_31 -> "31-band"
                                    EqMode.PARAMETRIC -> "Parametric"
                                    else -> mode.displayName
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Response curve with live spectrum behind it.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                ),
            ) {
                EqCurveView(
                    // toImmutableList() here is cheap versus the recompositions it
                    // prevents: these lists are ~10 and ~48 elements.
                    response = state.response.toImmutableList(),
                    bands = state.settings.bands.toImmutableList(),
                    spectrum = spectrum,
                    selectedBandId = state.selectedBandId,
                    editable = state.settings.mode == EqMode.PARAMETRIC,
                    onBandChange = viewModel::updateBand,
                    onBandSelected = viewModel::selectBand,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Preamp / headroom readout.
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoChip(
                    text = stringResource(R.string.format_auto_preamp, formatGain(state.autoPreampDb)),
                    mono = true,
                )
                Spacer(Modifier.width(6.dp))
                if (state.platformBandCount > 0) {
                    InfoChip(text = pluralStringResource(R.plurals.count_system_bands, state.platformBandCount, state.platformBandCount), mono = true)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::stashForComparison) {
                    Icon(Icons.Filled.CompareArrows, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.equalizer_a_b))
                }
                if (state.comparisonSettings != null) {
                    TextButton(onClick = viewModel::toggleComparison) {
                        Text(if (state.isComparing) "B" else "A")
                    }
                }
            }

            state.platformNote?.let { note ->
                LimitationNotice(text = note)
            }

            Spacer(Modifier.height(4.dp))

            when (state.settings.mode) {
                EqMode.PARAMETRIC -> ParametricEditor(state, viewModel)
                EqMode.OFF -> Unit
                else -> GraphicEditor(state, viewModel)
            }

            SectionHeader(title = stringResource(R.string.equalizer_presets), subtitle = stringResource(R.string.format_presets_available, presets.size))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presets, key = { it.id }) { preset ->
                    FilterChip(
                        selected = state.currentPresetName == preset.name,
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(preset.name) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = viewModel::saveToActiveProfile,
                    label = { Text(stringResource(R.string.equalizer_save_to_profile)) },
                    leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showSaveDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text(stringResource(R.string.equalizer_save_preset)) },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text(stringResource(R.string.equalizer_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (presetName.isNotBlank()) viewModel.savePreset(presetName.trim())
                        presetName = ""
                        showSaveDialog = false
                    },
                ) { Text(stringResource(R.string.equalizer_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.equalizer_cancel)) }
            },
        )
    }
}

@Composable
private fun GraphicEditor(state: EqUiState, viewModel: EqualizerViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        state.settings.bands.forEach { band ->
            EqBandFader(
                frequencyHz = band.frequencyHz,
                gainDb = band.gainDb,
                isSelected = band.id == state.selectedBandId,
                onGainChange = { viewModel.setBandGain(band.id, it) },
            )
        }
    }
}

@Composable
private fun ParametricEditor(state: EqUiState, viewModel: EqualizerViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(R.string.equalizer_bands),
            subtitle = stringResource(R.string.format_filters_drag_hint, pluralStringResource(R.plurals.count_filters, state.settings.bands.size, state.settings.bands.size)),
            trailing = {
                IconButton(onClick = { viewModel.addParametricBand() }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add band")
                }
            },
        )

        state.settings.bands.forEach { band ->
            ParametricBandRow(
                band = band,
                isSelected = band.id == state.selectedBandId,
                onChange = viewModel::updateBand,
                onDelete = { viewModel.removeBand(band.id) },
                onSelect = { viewModel.selectBand(band.id) },
            )
        }

        if (state.settings.bands.isEmpty()) {
            Text(
                text = stringResource(R.string.equalizer_no_filters_yet_add_one_to_start_shaping),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParametricBandRow(
    band: EqBand,
    isSelected: Boolean,
    onChange: (EqBand) -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = band.enabled,
                    onCheckedChange = { onChange(band.copy(enabled = it)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatFrequency(band.frequencyHz),
                    style = MonoNumericStyle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                InfoChip(text = band.type.shortLabel)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete band")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterType.entries.forEach { type ->
                    FilterChip(
                        selected = band.type == type,
                        onClick = { onChange(band.copy(type = type)) },
                        label = { Text(type.shortLabel, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            LabeledSlider(
                label = stringResource(R.string.equalizer_frequency),
                valueText = formatFrequency(band.frequencyHz),
                // Log-mapped so low frequencies are actually adjustable.
                value = logNormalize(band.frequencyHz),
                onValueChange = { onChange(band.copy(frequencyHz = logDenormalize(it))) },
            )

            if (band.type.usesGain) {
                LabeledSlider(
                    label = stringResource(R.string.equalizer_gain),
                    valueText = formatGain(band.gainDb),
                    value = (band.gainDb + 24f) / 48f,
                    onValueChange = { onChange(band.copy(gainDb = it * 48f - 24f)) },
                )
            }

            if (band.type.usesQ) {
                LabeledSlider(
                    label = "Q",
                    valueText = formatQ(band.q),
                    value = (band.q - 0.05f) / 39.95f,
                    onValueChange = { onChange(band.copy(q = 0.05f + it * 39.95f)) },
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(text = valueText, style = MonoNumericStyle)
        }
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onValueChange)
    }
}

private const val MIN_FREQ = 20f
private const val MAX_FREQ = 20_000f

private fun logNormalize(hz: Float): Float {
    val logMin = kotlin.math.ln(MIN_FREQ)
    val logMax = kotlin.math.ln(MAX_FREQ)
    return ((kotlin.math.ln(hz.coerceIn(MIN_FREQ, MAX_FREQ)) - logMin) / (logMax - logMin))
}

private fun logDenormalize(normalized: Float): Float {
    val logMin = kotlin.math.ln(MIN_FREQ)
    val logMax = kotlin.math.ln(MAX_FREQ)
    return kotlin.math.exp(logMin + normalized.coerceIn(0f, 1f) * (logMax - logMin))
}
