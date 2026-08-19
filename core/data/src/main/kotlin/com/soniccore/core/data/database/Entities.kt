package com.soniccore.core.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String,
    val colorArgb: Int,
    val description: String?,
    val boundDeviceKeys: String,
    val volumeJson: String,
    val outputJson: String,
    val inputJson: String,
    val eqJson: String,
    val effectsJson: String,
    val appOverridesJson: String,
    val autoActivate: Boolean,
    val priority: Int,
    val isBuiltIn: Boolean,
    val isActive: Boolean,
    val createdAtEpochMs: Long,
    val modifiedAtEpochMs: Long,
    val activationCount: Int,
    val lastActivatedEpochMs: Long?,
)

@Entity(
    tableName = "devices",
    indices = [Index(value = ["stableKey"], unique = true)],
)
data class DeviceEntity(
    @PrimaryKey val stableKey: String,
    val displayName: String,
    val productName: String?,
    val address: String?,
    val transport: String,
    val kind: String,
    val direction: String,
    val userLabel: String?,
    val isFavorite: Boolean,
    val notes: String?,
    val lastSeenEpochMs: Long,
    val connectionCount: Int,
    val lastBatteryPercent: Int?,
    val lastCodec: String?,
    val preferredProfileId: String?,
    val capabilitiesJson: String,
)

@Entity(tableName = "eq_presets")
data class EqPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val settingsJson: String,
    val isBuiltIn: Boolean,
    val targetDeviceKey: String?,
    val author: String?,
    val description: String?,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val triggerJson: String,
    val conditionsJson: String,
    val conditionLogic: String,
    val actionsJson: String,
    val priority: Int,
    val cooldownMs: Long,
    val lastFiredEpochMs: Long?,
    val fireCount: Int,
    val isBuiltIn: Boolean,
)

@Entity(tableName = "listening_history")
data class ListeningSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceKey: String,
    val profileId: String?,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val averageVolumePercent: Float,
    val peakVolumePercent: Float,
    val estimatedDoseDb: Float,
    val durationMs: Long,
)
