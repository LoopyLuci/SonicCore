package com.soniccore.core.data.mapper

import com.soniccore.core.data.database.AutomationRuleEntity
import com.soniccore.core.data.database.DeviceEntity
import com.soniccore.core.data.database.EqPresetEntity
import com.soniccore.core.data.database.ProfileEntity
import com.soniccore.core.model.automation.AutomationRule
import com.soniccore.core.model.automation.ConditionLogic
import com.soniccore.core.model.automation.RuleAction
import com.soniccore.core.model.automation.RuleCondition
import com.soniccore.core.model.automation.RuleTrigger
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.device.BluetoothCodec
import com.soniccore.core.model.device.DeviceCapabilities
import com.soniccore.core.model.device.DeviceDirection
import com.soniccore.core.model.device.DeviceKind
import com.soniccore.core.model.device.DeviceTransport
import com.soniccore.core.model.effects.EffectsSettings
import com.soniccore.core.model.eq.EqPreset
import com.soniccore.core.model.eq.EqSettings
import com.soniccore.core.model.profile.AppOverride
import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.profile.InputSettings
import com.soniccore.core.model.profile.OutputSettings
import com.soniccore.core.model.profile.ProfileIcon
import com.soniccore.core.model.profile.VolumeSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * JSON codec for the composite settings blobs.
 *
 * Nested settings are stored as JSON columns rather than a wide relational schema:
 * the settings tree is deep, versioned, and always read/written as a unit, so
 * serialization keeps migrations to a single concern.
 */
object SettingsJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
    }

    inline fun <reified T> encode(value: T): String =
        json.encodeToString(serializer = json.serializersModule.serializer(), value = value)

    inline fun <reified T> decode(raw: String, fallback: T): T =
        runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
}

fun AudioProfile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    name = name,
    icon = icon.name,
    colorArgb = colorArgb,
    description = description,
    boundDeviceKeys = SettingsJson.encode(boundDeviceKeys.toList()),
    volumeJson = SettingsJson.encode(volume),
    outputJson = SettingsJson.encode(output),
    inputJson = SettingsJson.encode(input),
    eqJson = SettingsJson.encode(eq),
    effectsJson = SettingsJson.encode(effects),
    appOverridesJson = SettingsJson.encode(appOverrides),
    autoActivate = autoActivate,
    priority = priority,
    isBuiltIn = isBuiltIn,
    isActive = isActive,
    createdAtEpochMs = createdAtEpochMs,
    modifiedAtEpochMs = modifiedAtEpochMs,
    activationCount = activationCount,
    lastActivatedEpochMs = lastActivatedEpochMs,
)

fun ProfileEntity.toDomain(): AudioProfile = AudioProfile(
    id = id,
    name = name,
    icon = runCatching { ProfileIcon.valueOf(icon) }.getOrDefault(ProfileIcon.CUSTOM),
    colorArgb = colorArgb,
    description = description,
    boundDeviceKeys = SettingsJson.decode<List<String>>(boundDeviceKeys, emptyList()).toSet(),
    volume = SettingsJson.decode(volumeJson, VolumeSettings()),
    output = SettingsJson.decode(outputJson, OutputSettings()),
    input = SettingsJson.decode(inputJson, InputSettings()),
    eq = SettingsJson.decode(eqJson, EqSettings()),
    effects = SettingsJson.decode(effectsJson, EffectsSettings()),
    appOverrides = SettingsJson.decode<List<AppOverride>>(appOverridesJson, emptyList()),
    autoActivate = autoActivate,
    priority = priority,
    isBuiltIn = isBuiltIn,
    isActive = isActive,
    createdAtEpochMs = createdAtEpochMs,
    modifiedAtEpochMs = modifiedAtEpochMs,
    activationCount = activationCount,
    lastActivatedEpochMs = lastActivatedEpochMs,
)

fun AudioDevice.toEntity(connectionCount: Int = 0, notes: String? = null, preferredProfileId: String? = null): DeviceEntity =
    DeviceEntity(
        stableKey = stableKey,
        displayName = displayName,
        productName = productName,
        address = address,
        transport = transport.name,
        kind = kind.name,
        direction = direction.name,
        userLabel = userLabel,
        isFavorite = isFavorite,
        notes = notes,
        lastSeenEpochMs = lastSeenEpochMs,
        connectionCount = connectionCount,
        lastBatteryPercent = batteryPercent,
        lastCodec = activeCodec?.name,
        preferredProfileId = preferredProfileId,
        capabilitiesJson = SettingsJson.encode(capabilities),
    )

/** Remembered device metadata, hydrated without a live connection. */
fun DeviceEntity.toDomain(): AudioDevice = AudioDevice(
    stableKey = stableKey,
    systemId = null,
    displayName = displayName,
    productName = productName,
    address = address,
    transport = runCatching { DeviceTransport.valueOf(transport) }.getOrDefault(DeviceTransport.UNKNOWN),
    kind = runCatching { DeviceKind.valueOf(kind) }.getOrDefault(DeviceKind.UNKNOWN),
    direction = runCatching { DeviceDirection.valueOf(direction) }.getOrDefault(DeviceDirection.OUTPUT),
    capabilities = SettingsJson.decode(capabilitiesJson, DeviceCapabilities()),
    batteryPercent = lastBatteryPercent,
    activeCodec = lastCodec?.let { name -> runCatching { BluetoothCodec.valueOf(name) }.getOrNull() },
    userLabel = userLabel,
    isFavorite = isFavorite,
    lastSeenEpochMs = lastSeenEpochMs,
)

fun EqPreset.toEntity(): EqPresetEntity = EqPresetEntity(
    id = id,
    name = name,
    settingsJson = SettingsJson.encode(settings),
    isBuiltIn = isBuiltIn,
    targetDeviceKey = targetDeviceKey,
    author = author,
    description = description,
    createdAtEpochMs = createdAtEpochMs,
)

fun EqPresetEntity.toDomain(): EqPreset = EqPreset(
    id = id,
    name = name,
    settings = SettingsJson.decode(settingsJson, EqSettings()),
    isBuiltIn = isBuiltIn,
    targetDeviceKey = targetDeviceKey,
    author = author,
    description = description,
    createdAtEpochMs = createdAtEpochMs,
)

fun AutomationRule.toEntity(): AutomationRuleEntity = AutomationRuleEntity(
    id = id,
    name = name,
    enabled = enabled,
    triggerJson = SettingsJson.encode(trigger),
    conditionsJson = SettingsJson.encode(conditions),
    conditionLogic = conditionLogic.name,
    actionsJson = SettingsJson.encode(actions),
    priority = priority,
    cooldownMs = cooldownMs,
    lastFiredEpochMs = lastFiredEpochMs,
    fireCount = fireCount,
    isBuiltIn = isBuiltIn,
)

fun AutomationRuleEntity.toDomain(): AutomationRule = AutomationRule(
    id = id,
    name = name,
    enabled = enabled,
    trigger = SettingsJson.decode(triggerJson, RuleTrigger.Manual()),
    conditions = SettingsJson.decode<List<RuleCondition>>(conditionsJson, emptyList()),
    conditionLogic = runCatching { ConditionLogic.valueOf(conditionLogic) }.getOrDefault(ConditionLogic.ALL),
    actions = SettingsJson.decode<List<RuleAction>>(actionsJson, emptyList()),
    priority = priority,
    cooldownMs = cooldownMs,
    lastFiredEpochMs = lastFiredEpochMs,
    fireCount = fireCount,
    isBuiltIn = isBuiltIn,
)
