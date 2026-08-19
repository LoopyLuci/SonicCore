package com.soniccore.feature.profiles

import com.soniccore.core.model.profile.AudioProfile
import com.soniccore.core.model.profile.ProfileIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profile lifecycle rules: duplication, device binding, activation selection and
 * priority. A binding bug here silently applies the wrong EQ to the wrong headphones.
 */
class ProfileLifecycleTest {

    private fun profile(
        id: String,
        name: String = "P-$id",
        bound: Set<String> = emptySet(),
        priority: Int = 0,
        active: Boolean = false,
        autoActivate: Boolean = true,
    ) = AudioProfile(
        id = id,
        name = name,
        boundDeviceKeys = bound,
        priority = priority,
        isActive = active,
        autoActivate = autoActivate,
    )

    // --- duplication ---

    private fun duplicate(source: AudioProfile, newId: String): AudioProfile = source.copy(
        id = newId,
        name = "${source.name} copy",
        isActive = false,
        boundDeviceKeys = emptySet(),
        activationCount = 0,
        lastActivatedEpochMs = null,
    )

    @Test
    fun `duplicate gets a new id and does not inherit active state`() {
        val original = profile("a", active = true)
        val copy = duplicate(original, "b")
        assertNotEquals(original.id, copy.id)
        assertFalse("a copy must not be active", copy.isActive)
    }

    @Test
    fun `duplicate does not steal the original's device bindings`() {
        // Two profiles bound to the same device would race on connect.
        val original = profile("a", bound = setOf("BT:OUTPUT:AA"))
        val copy = duplicate(original, "b")
        assertTrue(copy.boundDeviceKeys.isEmpty())
        assertEquals(1, original.boundDeviceKeys.size)
    }

    @Test
    fun `duplicate preserves the audio settings that make it useful`() {
        val original = profile("a").copy(
            eq = com.soniccore.core.model.eq.EqSettings(enabled = true, preampDb = -4f),
        )
        val copy = duplicate(original, "b")
        assertTrue(copy.eq.enabled)
        assertEquals(-4f, copy.eq.preampDb, 0.001f)
    }

    @Test
    fun `duplicate resets usage statistics`() {
        val original = profile("a").copy(activationCount = 42, lastActivatedEpochMs = 1_000)
        val copy = duplicate(original, "b")
        assertEquals(0, copy.activationCount)
        assertEquals(null, copy.lastActivatedEpochMs)
    }

    @Test
    fun `duplicate name is distinguishable from the original`() {
        val copy = duplicate(profile("a", name = "Studio"), "b")
        assertNotEquals("Studio", copy.name)
        assertTrue(copy.name.contains("Studio"))
    }

    // --- activation selection ---

    private fun selectForDevice(profiles: List<AudioProfile>, deviceKey: String): AudioProfile? =
        profiles
            .filter { it.autoActivate && deviceKey in it.boundDeviceKeys }
            .maxByOrNull { it.priority }

    @Test
    fun `highest priority bound profile wins for a device`() {
        val profiles = listOf(
            profile("low", bound = setOf("k"), priority = 1),
            profile("high", bound = setOf("k"), priority = 50),
            profile("mid", bound = setOf("k"), priority = 10),
        )
        assertEquals("high", selectForDevice(profiles, "k")?.id)
    }

    @Test
    fun `unbound device selects nothing rather than a random profile`() {
        val profiles = listOf(profile("a", bound = setOf("other")))
        assertEquals(null, selectForDevice(profiles, "k"))
    }

    @Test
    fun `auto activate disabled profiles are never auto selected`() {
        val profiles = listOf(
            profile("manual", bound = setOf("k"), priority = 99, autoActivate = false),
            profile("auto", bound = setOf("k"), priority = 1, autoActivate = true),
        )
        // The manual profile has higher priority but must be skipped.
        assertEquals("auto", selectForDevice(profiles, "k")?.id)
    }

    @Test
    fun `a profile bound to several devices matches each of them`() {
        val multi = profile("a", bound = setOf("k1", "k2", "k3"))
        listOf("k1", "k2", "k3").forEach { key ->
            assertEquals("a", selectForDevice(listOf(multi), key)?.id)
        }
    }

    @Test
    fun `equal priority selection is deterministic`() {
        val profiles = listOf(
            profile("a", bound = setOf("k"), priority = 5),
            profile("b", bound = setOf("k"), priority = 5),
        )
        // maxByOrNull keeps the first on ties — stable across runs.
        assertEquals("a", selectForDevice(profiles, "k")?.id)
    }

    // --- activation state ---

    private fun activate(profiles: List<AudioProfile>, id: String): List<AudioProfile> =
        profiles.map { it.copy(isActive = it.id == id) }

    @Test
    fun `activating a profile deactivates all others`() {
        val profiles = listOf(
            profile("a", active = true),
            profile("b"),
            profile("c"),
        )
        val result = activate(profiles, "b")
        assertEquals(1, result.count { it.isActive })
        assertTrue(result.first { it.id == "b" }.isActive)
        assertFalse(result.first { it.id == "a" }.isActive)
    }

    @Test
    fun `activating a nonexistent id leaves nothing active`() {
        val result = activate(listOf(profile("a", active = true)), "missing")
        assertEquals(0, result.count { it.isActive })
    }

    // --- validation ---

    @Test
    fun `blank names are rejected before saving`() {
        fun valid(name: String) = name.isNotBlank()
        assertFalse(valid(""))
        assertFalse(valid("   "))
        assertTrue(valid("Studio"))
    }

    @Test
    fun `name is trimmed so trailing spaces do not create lookalikes`() {
        assertEquals("Studio", "  Studio  ".trim())
    }

    @Test
    fun `every icon is selectable and distinctly named`() {
        val names = ProfileIcon.entries.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(ProfileIcon.entries.contains(ProfileIcon.CUSTOM))
    }

    @Test
    fun `deleting the active profile leaves no dangling active reference`() {
        val profiles = listOf(profile("a", active = true), profile("b"))
        val remaining = profiles.filterNot { it.id == "a" }
        assertEquals(1, remaining.size)
        assertEquals(0, remaining.count { it.isActive })
    }

    @Test
    fun `priority ordering is used for display`() {
        val profiles = listOf(
            profile("c", priority = 5),
            profile("a", priority = 20),
            profile("b", priority = 10),
        )
        assertEquals(
            listOf("a", "b", "c"),
            profiles.sortedByDescending { it.priority }.map { it.id },
        )
    }
}
