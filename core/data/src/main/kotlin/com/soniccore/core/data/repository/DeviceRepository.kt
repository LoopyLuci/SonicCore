package com.soniccore.core.data.repository

import com.soniccore.core.data.database.DeviceDao
import com.soniccore.core.data.database.EqPresetDao
import com.soniccore.core.data.mapper.toDomain
import com.soniccore.core.data.mapper.toEntity
import com.soniccore.core.model.device.AudioDevice
import com.soniccore.core.model.eq.EqPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Remembered device metadata: labels, favourites, notes, preferred profile. */
@Singleton
class DeviceRepository @Inject constructor(
    private val dao: DeviceDao,
) {
    val knownDevices: Flow<List<AudioDevice>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observe(stableKey: String): Flow<AudioDevice?> =
        dao.observeByKey(stableKey).map { it?.toDomain() }

    suspend fun get(stableKey: String): AudioDevice? = dao.getByKey(stableKey)?.toDomain()

    suspend fun getAll(): List<AudioDevice> = dao.getAll().map { it.toDomain() }

    /** Merge a live device into the store, preserving user-authored fields. */
    suspend fun remember(device: AudioDevice) {
        val existing = dao.getByKey(device.stableKey)
        dao.upsert(
            device.toEntity(
                connectionCount = existing?.connectionCount ?: 0,
                notes = existing?.notes,
                preferredProfileId = existing?.preferredProfileId,
            ).copy(
                userLabel = existing?.userLabel ?: device.userLabel,
                isFavorite = existing?.isFavorite ?: device.isFavorite,
            ),
        )
    }

    suspend fun rememberAll(devices: List<AudioDevice>) = devices.forEach { remember(it) }

    suspend fun setFavorite(stableKey: String, favorite: Boolean) = dao.setFavorite(stableKey, favorite)
    suspend fun setLabel(stableKey: String, label: String?) = dao.setLabel(stableKey, label)
    suspend fun setNotes(stableKey: String, notes: String?) = dao.setNotes(stableKey, notes)
    suspend fun setPreferredProfile(stableKey: String, profileId: String?) =
        dao.setPreferredProfile(stableKey, profileId)

    suspend fun recordConnection(stableKey: String) =
        dao.recordConnection(stableKey, System.currentTimeMillis())

    suspend fun forget(stableKey: String) = dao.deleteByKey(stableKey)
}

/** EQ preset store, including import/export of AutoEQ-style text. */
@Singleton
class EqPresetRepository @Inject constructor(
    private val dao: EqPresetDao,
) {
    val presets: Flow<List<EqPreset>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeForDevice(deviceKey: String): Flow<List<EqPreset>> =
        dao.observeForDevice(deviceKey).map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): EqPreset? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<EqPreset> = dao.getAll().map { it.toDomain() }

    suspend fun count(): Int = dao.count()

    suspend fun save(preset: EqPreset): EqPreset {
        val toSave = if (preset.id.isBlank()) {
            preset.copy(id = UUID.randomUUID().toString(), createdAtEpochMs = System.currentTimeMillis())
        } else {
            preset
        }
        dao.upsert(toSave.toEntity())
        return toSave
    }

    suspend fun delete(id: String): Boolean = dao.deleteById(id) > 0

    suspend fun seedBuiltIns(presets: List<EqPreset>) {
        if (dao.count() == 0) dao.insertAll(presets.map { it.toEntity() })
    }
}
