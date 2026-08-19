package com.soniccore.core.data.repository

import com.soniccore.core.data.database.ProfileDao
import com.soniccore.core.data.mapper.toDomain
import com.soniccore.core.data.mapper.toEntity
import com.soniccore.core.model.profile.AudioProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile store. Users may create unlimited profiles; there is no cap anywhere in
 * this class by design.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
) {
    val profiles: Flow<List<AudioProfile>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    val activeProfile: Flow<AudioProfile?> = dao.observeActive().map { it?.toDomain() }

    fun observe(id: String): Flow<AudioProfile?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun get(id: String): AudioProfile? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<AudioProfile> = dao.getAll().map { it.toDomain() }

    suspend fun count(): Int = dao.count()

    suspend fun save(profile: AudioProfile): AudioProfile {
        val now = System.currentTimeMillis()
        val toSave = profile.copy(
            modifiedAtEpochMs = now,
            createdAtEpochMs = if (profile.createdAtEpochMs == 0L) now else profile.createdAtEpochMs,
        )
        dao.upsert(toSave.toEntity())
        return toSave
    }

    suspend fun create(
        name: String,
        template: AudioProfile? = null,
    ): AudioProfile {
        val now = System.currentTimeMillis()
        val profile = (template ?: AudioProfile(id = "", name = name)).copy(
            id = UUID.randomUUID().toString(),
            name = name,
            isBuiltIn = false,
            isActive = false,
            createdAtEpochMs = now,
            modifiedAtEpochMs = now,
            activationCount = 0,
            lastActivatedEpochMs = null,
        )
        dao.upsert(profile.toEntity())
        return profile
    }

    /** Duplicate an existing profile, including every nested setting. */
    suspend fun duplicate(id: String, newName: String? = null): AudioProfile? {
        val source = get(id) ?: return null
        return create(newName ?: "${source.name} copy", template = source)
    }

    suspend fun delete(id: String): Boolean = dao.deleteById(id) > 0

    suspend fun activate(id: String) {
        dao.clearActive()
        dao.markActive(id, System.currentTimeMillis())
    }

    suspend fun deactivateAll() = dao.clearActive()

    /** Highest-priority auto-activating profile bound to a device. */
    suspend fun findForDevice(deviceKey: String): AudioProfile? =
        dao.findForDevice(deviceKey)?.toDomain()

    suspend fun replaceAll(profiles: List<AudioProfile>) {
        dao.upsertAll(profiles.map { it.toEntity() })
    }
}
