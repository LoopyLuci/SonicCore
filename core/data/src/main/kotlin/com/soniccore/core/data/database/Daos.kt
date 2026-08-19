package com.soniccore.core.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeById(id: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Upsert
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteById(id: String): Int

    @Query("UPDATE profiles SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE profiles SET isActive = 1, activationCount = activationCount + 1, lastActivatedEpochMs = :now WHERE id = :id")
    suspend fun markActive(id: String, now: Long)

    @Query("SELECT * FROM profiles WHERE autoActivate = 1 AND boundDeviceKeys LIKE '%' || :deviceKey || '%' ORDER BY priority DESC LIMIT 1")
    suspend fun findForDevice(deviceKey: String): ProfileEntity?
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY isFavorite DESC, lastSeenEpochMs DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE stableKey = :key")
    fun observeByKey(key: String): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE stableKey = :key")
    suspend fun getByKey(key: String): DeviceEntity?

    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceEntity>

    @Upsert
    suspend fun upsert(device: DeviceEntity)

    @Query("UPDATE devices SET isFavorite = :favorite WHERE stableKey = :key")
    suspend fun setFavorite(key: String, favorite: Boolean)

    @Query("UPDATE devices SET userLabel = :label WHERE stableKey = :key")
    suspend fun setLabel(key: String, label: String?)

    @Query("UPDATE devices SET notes = :notes WHERE stableKey = :key")
    suspend fun setNotes(key: String, notes: String?)

    @Query("UPDATE devices SET preferredProfileId = :profileId WHERE stableKey = :key")
    suspend fun setPreferredProfile(key: String, profileId: String?)

    @Query("UPDATE devices SET connectionCount = connectionCount + 1, lastSeenEpochMs = :now WHERE stableKey = :key")
    suspend fun recordConnection(key: String, now: Long)

    @Query("DELETE FROM devices WHERE stableKey = :key")
    suspend fun deleteByKey(key: String)
}

@Dao
interface EqPresetDao {
    @Query("SELECT * FROM eq_presets ORDER BY isBuiltIn DESC, name ASC")
    fun observeAll(): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE targetDeviceKey = :deviceKey OR targetDeviceKey IS NULL ORDER BY name ASC")
    fun observeForDevice(deviceKey: String): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE id = :id")
    suspend fun getById(id: String): EqPresetEntity?

    @Query("SELECT * FROM eq_presets")
    suspend fun getAll(): List<EqPresetEntity>

    @Query("SELECT COUNT(*) FROM eq_presets")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(preset: EqPresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(presets: List<EqPresetEntity>)

    @Query("DELETE FROM eq_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteById(id: String): Int
}

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY priority DESC, name ASC")
    fun observeAll(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules WHERE enabled = 1 ORDER BY priority DESC")
    suspend fun getEnabled(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun getById(id: String): AutomationRuleEntity?

    @Query("SELECT * FROM automation_rules")
    suspend fun getAll(): List<AutomationRuleEntity>

    @Upsert
    suspend fun upsert(rule: AutomationRuleEntity)

    @Query("UPDATE automation_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE automation_rules SET lastFiredEpochMs = :now, fireCount = fireCount + 1 WHERE id = :id")
    suspend fun recordFire(id: String, now: Long)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ListeningHistoryDao {
    @Query("SELECT * FROM listening_history ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ListeningSessionEntity>>

    @Query("SELECT SUM(durationMs) FROM listening_history WHERE startedAtEpochMs >= :since")
    suspend fun totalDurationSince(since: Long): Long?

    @Query("SELECT AVG(estimatedDoseDb) FROM listening_history WHERE startedAtEpochMs >= :since")
    suspend fun averageDoseSince(since: Long): Float?

    @Insert
    suspend fun insert(session: ListeningSessionEntity): Long

    @Query("UPDATE listening_history SET endedAtEpochMs = :endedAt, durationMs = :durationMs WHERE id = :id")
    suspend fun close(id: Long, endedAt: Long, durationMs: Long)

    @Query("DELETE FROM listening_history WHERE startedAtEpochMs < :before")
    suspend fun pruneBefore(before: Long)
}
