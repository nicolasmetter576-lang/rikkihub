package me.rerere.rikkihub.data.ai

import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderSpendLimitTest {
    @Test
    fun `limits keyed by provider id survive the settings JSON round trip`() {
        val id = Uuid.random()
        val limits = mapOf(id to ProviderSpendLimit(limit = 12.5, since = 1_700_000_000_000, blocked = true))
        val json = JsonInstant.encodeToString(Settings(providerSpendLimits = limits))
        assertTrue(json.contains(id.toString()))
        assertEquals(limits, JsonInstant.decodeFromString<Settings>(json).providerSpendLimits)
    }

    @Test
    fun `settings written before the limits existed decode with none`() {
        assertEquals(emptyMap<Uuid, ProviderSpendLimit>(), JsonInstant.decodeFromString<Settings>("{}").providerSpendLimits)
    }

    @Test
    fun `limits are shown as short dollar amounts`() {
        assertEquals("$5", formatSpendLimit(5.0))
        assertEquals("$0.5", formatSpendLimit(0.5))
        assertEquals("$12.34", formatSpendLimit(12.34))
    }
}
