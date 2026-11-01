package me.rerere.rikkihub.data.creator

import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.definition.ReservedAssistantIdException
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import org.junit.Assert.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 管理链视角：创造助手是保留身份，不能被提案读取或修改。 */
class AssistantRegistryTest {

    private val registry = AssistantRegistry()
    private val creatorId = CreatorAssistantDefinition.ID

    @Test
    fun `creator id is reserved`() {
        assertTrue(AssistantRegistry.isReservedAssistantId(creatorId))
        assertTrue(AssistantRegistry.isReservedAssistantId(creatorId.toString()))
    }

    @Test
    fun `ordinary id is not reserved`() {
        assertFalse(AssistantRegistry.isReservedAssistantId(Uuid.random()))
    }

    @Test
    fun `malformed id string is not treated as reserved`() {
        assertFalse(AssistantRegistry.isReservedAssistantId("not-a-uuid"))
        assertFalse(AssistantRegistry.isReservedAssistantId(""))
    }

    @Test
    fun `management chain rejects reserved id`() {
        assertFailsWith<ReservedAssistantIdException> {
            registry.requireOrdinaryAssistantId(creatorId)
        }
        // 普通 id 不抛
        registry.requireOrdinaryAssistantId(Uuid.random())
    }

    @Test
    fun `sanitize strips creator id from management view`() {
        val fakeCreatorEntry = Assistant(id = creatorId, name = "spoofed creator")
        val ordinary = Assistant(id = Uuid.random(), name = "A")
        val cleaned = registry.sanitizeOrdinaryAssistants(listOf(fakeCreatorEntry, ordinary))
        assertEquals(listOf(ordinary.id), cleaned.map { it.id })
        // 幂等
        assertEquals(cleaned, registry.sanitizeOrdinaryAssistants(cleaned))
    }

    @Test
    fun `resolveOrdinaryAssistant never returns creator`() {
        val settings = Settings(assistants = listOf(Assistant(id = creatorId)))
        assertNull(registry.resolveOrdinaryAssistant(settings, creatorId))
    }

}
