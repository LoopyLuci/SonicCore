package com.soniccore.feature.equalizer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soniccore.core.audio.effects.PlatformEffectsController
import com.soniccore.core.data.engine.ProfileEngine
import com.soniccore.core.data.presets.AutoEqImporter
import com.soniccore.core.data.repository.EqPresetRepository
import com.soniccore.core.data.repository.ProfileRepository
import com.soniccore.core.data.settings.SettingsStore
import com.soniccore.core.dsp.EqualizerEngine
import com.soniccore.core.model.eq.EqBand
import com.soniccore.core.model.eq.EqMode
import com.soniccore.core.model.eq.EqPreset
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.eq.FilterType
import com.soniccore.core.model.eq.ResponsePoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class EqUiState(
    val settings: EqSettings = EqSettings.flat(EqMode.GRAPHIC_10),
    val response: List<ResponsePoint> = emptyList(),
    val selectedBandId: String? = null,
    val autoPreampDb: Float = 0f,
    val platformBandCount: Int = 0,
    val platformNote: String? = null,
    val comparisonSettings: EqSettings? = null,
    val isComparing: Boolean = false,
    val isDirty: Boolean = false,
    val currentPresetName: String? = null,
    val message: String? = null,
)

@HiltViewModel
class EqualizerViewModel @Inject constructor(
    private val profileEngine: ProfileEngine,
    private val profileRepository: ProfileRepository,
    private val presetRepository: EqPresetRepository,
    private val platformEffects: PlatformEffectsController,
    private val autoEqImporter: AutoEqImporter,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = _uiState.asStateFlow()

    val presets: StateFlow<List<EqPreset>> = presetRepository.presets.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    init {
        viewModelScope.launch {
            // Seed defensively: HiltTestApplication replaces SonicCoreApplication in
            // instrumented tests, so Application.onCreate seeding does not run there.
            presetRepository.seedBuiltIns(com.soniccore.core.data.presets.BuiltInEqPresets.all)
            profileRepository.getAll().firstOrNull { it.isActive }?.let { profile ->
                updateSettings(profile.eq, markDirty = false)
            } ?: recomputeResponse()
        }
    }

    fun setMode(mode: EqMode) {
        val current = _uiState.value.settings
        val next = when (mode) {
            EqMode.PARAMETRIC -> current.copy(mode = mode)
            EqMode.OFF -> current.copy(mode = mode, enabled = false)
            else -> {
                // Rebuild graphic bands at the new resolution, preserving the
                // existing curve by sampling it at each new centre frequency.
                val engine = EqualizerEngine().apply { configure(current.copy(enabled = true)) }
                val response = engine.frequencyResponse(pointCount = 256)
                val frequencies = EqSettings.frequenciesFor(mode)
                val q = EqSettings.graphicQFor(mode)
                current.copy(
                    mode = mode,
                    bands = frequencies.mapIndexed { index, frequency ->
                        val sampled = response.minByOrNull {
                            kotlin.math.abs(it.frequencyHz - frequency)
                        }?.magnitudeDb ?: 0f
                        EqBand(
                            id = "band_$index",
                            type = if (mode == EqMode.QUICK && index == 0) {
                                FilterType.LOW_SHELF
                            } else if (mode == EqMode.QUICK) {
                                FilterType.HIGH_SHELF
                            } else {
                                FilterType.PEAK
                            },
                            frequencyHz = frequency,
                            gainDb = sampled.coerceIn(-12f, 12f),
                            q = q,
                        )
                    },
                )
            }
        }
        updateSettings(next)
    }

    fun setEnabled(enabled: Boolean) {
        updateSettings(_uiState.value.settings.copy(enabled = enabled))
    }

    fun updateBand(band: EqBand) {
        val settings = _uiState.value.settings
        updateSettings(
            settings.copy(
                bands = settings.bands.map { if (it.id == band.id) band.sanitized() else it },
            ),
        )
    }

    fun setBandGain(bandId: String, gainDb: Float) {
        val settings = _uiState.value.settings
        updateSettings(
            settings.copy(
                bands = settings.bands.map {
                    if (it.id == bandId) it.copy(gainDb = gainDb.coerceIn(-24f, 24f)).sanitized() else it
                },
            ),
        )
    }

    fun addParametricBand(frequencyHz: Float = 1000f) {
        val settings = _uiState.value.settings
        val band = EqBand(
            id = UUID.randomUUID().toString(),
            type = FilterType.PEAK,
            frequencyHz = frequencyHz,
            gainDb = 0f,
            q = EqBand.DEFAULT_Q,
        )
        updateSettings(
            settings.copy(
                mode = EqMode.PARAMETRIC,
                bands = (settings.bands + band).sortedBy { it.frequencyHz },
            ),
        )
        _uiState.value = _uiState.value.copy(selectedBandId = band.id)
    }

    fun removeBand(bandId: String) {
        val settings = _uiState.value.settings
        updateSettings(settings.copy(bands = settings.bands.filterNot { it.id == bandId }))
        if (_uiState.value.selectedBandId == bandId) {
            _uiState.value = _uiState.value.copy(selectedBandId = null)
        }
    }

    fun selectBand(bandId: String?) {
        _uiState.value = _uiState.value.copy(selectedBandId = bandId)
    }

    fun setPreamp(db: Float) {
        updateSettings(_uiState.value.settings.copy(preampDb = db.coerceIn(-24f, 12f), autoPreamp = false))
    }

    fun setAutoPreamp(enabled: Boolean) {
        updateSettings(_uiState.value.settings.copy(autoPreamp = enabled))
    }

    fun reset() {
        updateSettings(EqSettings.flat(_uiState.value.settings.mode).copy(enabled = _uiState.value.settings.enabled))
        _uiState.value = _uiState.value.copy(currentPresetName = "Flat")
    }

    /** A/B compare: stash the current curve, then toggle between the two. */
    fun stashForComparison() {
        _uiState.value = _uiState.value.copy(comparisonSettings = _uiState.value.settings)
    }

    fun toggleComparison() {
        val state = _uiState.value
        val other = state.comparisonSettings ?: return
        _uiState.value = state.copy(
            settings = other,
            comparisonSettings = state.settings,
            isComparing = !state.isComparing,
        )
        applyAndRecompute(other)
    }

    fun applyPreset(preset: EqPreset) {
        updateSettings(preset.settings.copy(enabled = true))
        _uiState.value = _uiState.value.copy(currentPresetName = preset.name, isDirty = false)
    }

    fun savePreset(name: String) {
        viewModelScope.launch {
            val saved = presetRepository.save(
                EqPreset(
                    id = "",
                    name = name,
                    settings = _uiState.value.settings,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            _uiState.value = _uiState.value.copy(
                currentPresetName = saved.name,
                isDirty = false,
                message = "Saved “${saved.name}”",
            )
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            val deleted = presetRepository.delete(id)
            _uiState.value = _uiState.value.copy(
                message = if (deleted) "Preset deleted" else "Built-in presets can't be deleted",
            )
        }
    }

    /** Import an AutoEQ / Equalizer APO ParametricEQ text block. */
    fun importAutoEq(text: String, name: String? = null) {
        viewModelScope.launch {
            when (val result = autoEqImporter.parse(text, name)) {
                is AutoEqImporter.ImportResult.Success -> {
                    val saved = presetRepository.save(result.preset)
                    applyPreset(saved)
                    _uiState.value = _uiState.value.copy(
                        message = buildString {
                            append("Imported ${saved.settings.bands.size} bands")
                            if (result.warnings.isNotEmpty()) {
                                append(" (${result.warnings.size} warnings)")
                            }
                        },
                    )
                }
                is AutoEqImporter.ImportResult.Failure ->
                    _uiState.value = _uiState.value.copy(message = result.reason)
            }
        }
    }

    fun exportCurrentPreset(): String = autoEqImporter.export(
        EqPreset(
            id = "",
            name = _uiState.value.currentPresetName ?: "SonicCore preset",
            settings = _uiState.value.settings,
        ),
    )

    /** Persist the current curve into the active profile. */
    fun saveToActiveProfile() {
        viewModelScope.launch {
            val active = profileRepository.getAll().firstOrNull { it.isActive }
            if (active == null) {
                _uiState.value = _uiState.value.copy(message = "No active profile to save into")
                return@launch
            }
            profileRepository.save(active.copy(eq = _uiState.value.settings))
            _uiState.value = _uiState.value.copy(
                isDirty = false,
                message = "Saved to “${active.name}”",
            )
        }
    }

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun updateSettings(settings: EqSettings, markDirty: Boolean = true) {
        _uiState.value = _uiState.value.copy(settings = settings, isDirty = markDirty)
        applyAndRecompute(settings)
    }

    private fun applyAndRecompute(settings: EqSettings) {
        viewModelScope.launch {
            profileEngine.applyEqualizerOnly(settings)
            recomputeResponse(settings)
        }
    }

    private suspend fun recomputeResponse(settings: EqSettings = _uiState.value.settings) {
        val computed = withContext(Dispatchers.Default) {
            val engine = EqualizerEngine().apply { configure(settings.copy(enabled = true)) }
            engine.frequencyResponse(pointCount = 220) to EqualizerEngine.autoPreampDb(settings)
        }
        _uiState.value = _uiState.value.copy(
            response = computed.first,
            autoPreampDb = computed.second,
            platformBandCount = platformEffects.platformBandCount,
            platformNote = platformEffects.lastError ?: platformNoteFor(platformEffects.platformBandCount),
        )
    }

    private fun platformNoteFor(bandCount: Int): String? = when {
        bandCount == 0 -> null
        bandCount <= 5 ->
            "System-wide EQ maps your curve onto $bandCount hardware bands. " +
                "Audio played inside SonicCore uses the full-resolution curve."
        else -> null
    }
}
