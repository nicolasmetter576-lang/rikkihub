package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.session.CreatorListObjectsInput
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 白名单读取面断言：摘要分页、预算截断、
 * 秘密字段不投影、保留 ID 不可读。
 */
class CreatorReadDomainTest {

    private fun domainWith(settings: Settings) = CreatorReadDomain(
        json = JsonInstant,
        settingsProvider = { settings },
        assistantRegistry = AssistantRegistry(),
    )

    @Test
    fun `list is paged with cursor and omitted count`() = runBlocking<Unit> {
        val items = (1..95).map {
            QuickMessage(id = Uuid.random(), title = "q$it", content = "c$it")
        }
        val domain = domainWith(Settings(quickMessages = items))
        val page1 = domain.list(CreatorListObjectsInput(kind = "QUICK_MESSAGE", pageSize = 40))
        val ok1 = page1 as CreatorReadDomain.ReadResult.Ok
        assertEquals(40, ok1.output.items.size)
        assertNotNull(ok1.output.nextPageToken)
        assertEquals(55, ok1.output.omittedCount)
        val page2 = domain.list(
            CreatorListObjectsInput(kind = "QUICK_MESSAGE", pageSize = 40, pageToken = ok1.output.nextPageToken),
        ) as CreatorReadDomain.ReadResult.Ok
        assertEquals(40, page2.output.items.size)
        val page3 = domain.list(
            CreatorListObjectsInput(kind = "QUICK_MESSAGE", pageSize = 40, pageToken = page2.output.nextPageToken),
        ) as CreatorReadDomain.ReadResult.Ok
        assertEquals(15, page3.output.items.size)
        assertNull(page3.output.nextPageToken)
        assertEquals(0, page3.output.omittedCount)
    }

    @Test
    fun `page size is capped at 100`() = runBlocking<Unit> {
        val items = (1..150).map { QuickMessage(id = Uuid.random(), title = "q$it") }
        val domain = domainWith(Settings(quickMessages = items))
        val ok = domain.list(CreatorListObjectsInput(kind = "QUICK_MESSAGE", pageSize = 1000))
            as CreatorReadDomain.ReadResult.Ok
        assertEquals(100, ok.output.items.size)
        assertEquals(50, ok.output.omittedCount)
    }

    @Test
    fun `unknown kind is denied`() = runBlocking<Unit> {
        val domain = domainWith(Settings())
        val result = domain.list(CreatorListObjectsInput(kind = "EVERYTHING"))
        assertTrue(result is CreatorReadDomain.ReadResult.Denied)
    }

    @Test
    fun `assistant projection never leaks secret fields`() = runBlocking<Unit> {
        val assistant = Assistant(
            id = Uuid.random(),
            name = "with-secrets",
            customHeaders = listOf(CustomHeader("Authorization", "Bearer topsecret")),
            customBodies = listOf(CustomBody("key", JsonPrimitive("v"))),
            systemPrompt = "prompt",
        )
        val domain = domainWith(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val out = domain.read(CreatorObjectKind.ASSISTANT, assistant.id.toString()) as CreatorReadDomain.DetailResult.Ok
        val json = out.output.toString()
        assertFalse("projection must not contain credentials", json.contains("topsecret"))
        assertFalse(json.contains("customHeaders"))
        assertFalse(json.contains("customBodies"))
        assertFalse(json.contains("providerOverwrite"))
    }

    @Test
    fun `reserved assistant id is not readable`() = runBlocking<Unit> {
        val fake = Assistant(id = CreatorAssistantDefinition.ID, name = "spoof")
        val domain = domainWith(Settings(assistants = listOf(fake)))
        // 保留 ID 不在普通读面（读不到，而非读到）
        assertTrue(domain.read(CreatorObjectKind.ASSISTANT, CreatorAssistantDefinition.ID.toString()) is CreatorReadDomain.DetailResult.Missing)
        // 列表也不含
        val list = domain.list(CreatorListObjectsInput(kind = "ASSISTANT"))
            as CreatorReadDomain.ReadResult.Ok
        assertTrue(list.output.items.none { it.id == CreatorAssistantDefinition.ID.toString() })
    }

    @Test
    fun `empty assistant list lists empty`() = runBlocking<Unit> {
        val domain = domainWith(Settings(assistants = emptyList()))
        val ok = domain.list(CreatorListObjectsInput(kind = "ASSISTANT"))
            as CreatorReadDomain.ReadResult.Ok
        assertTrue(ok.output.items.isEmpty())
    }
}
