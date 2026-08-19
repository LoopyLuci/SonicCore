package com.soniccore.core.data.repository

import com.soniccore.core.data.database.AutomationRuleDao
import com.soniccore.core.data.mapper.toDomain
import com.soniccore.core.data.mapper.toEntity
import com.soniccore.core.model.automation.AutomationRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationRepository @Inject constructor(
    private val dao: AutomationRuleDao,
) {
    val rules: Flow<List<AutomationRule>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun enabledRules(): List<AutomationRule> = dao.getEnabled().map { it.toDomain() }

    suspend fun get(id: String): AutomationRule? = dao.getById(id)?.toDomain()

    suspend fun getAll(): List<AutomationRule> = dao.getAll().map { it.toDomain() }

    suspend fun save(rule: AutomationRule): AutomationRule {
        val toSave = if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule
        dao.upsert(toSave.toEntity())
        return toSave
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun recordFire(id: String) = dao.recordFire(id, System.currentTimeMillis())

    suspend fun delete(id: String) = dao.deleteById(id)

    /** Cooldown check so a chatty trigger cannot thrash the audio state. */
    fun isInCooldown(rule: AutomationRule, now: Long = System.currentTimeMillis()): Boolean {
        val last = rule.lastFiredEpochMs ?: return false
        return now - last < rule.cooldownMs
    }
}
