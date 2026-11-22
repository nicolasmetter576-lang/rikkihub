package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.adapters.conversation.ConversationCollections
import me.rerere.rikkihub.data.management.common.VersionToken
import me.rerere.rikkihub.data.management.ports.ManagementReadPort
import me.rerere.rikkihub.data.management.ports.ManagementSettingsSnapshot
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CreatorReadDomainCliTest {

    private fun domainWith(
        settings: Settings,
        conversationViews: Map<String, JsonObject> = emptyMap(),
    ): CreatorReadDomain {
        val fakePort = object : ManagementReadPort {
            override suspend fun snapshotIds(collectionId: String): List<String> = emptyList()

            override suspend fun readObjectJson(collectionId: String, id: String): JsonObject? =
                if (collectionId == ConversationCollections.TREE) conversationViews[id] else null

            override suspend fun atomicSettingsSnapshot(): ManagementSettingsSnapshot =
                ManagementSettingsSnapshot(settings, 0L, emptyMap(), emptyMap())

            override suspend fun settingsSnapshot(): Settings = settings

            override suspend fun objectToken(collectionId: String, id: String): VersionToken? = null

            override suspend fun collectionToken(collectionId: String): VersionToken =
                VersionToken(0L, 0L, 0L)
        }
        return CreatorReadDomain(
            json = JsonInstant,
            settingsProvider = { settings },
            assistantRegistry = AssistantRegistry(),
            managementRead = { fakePort },
        )
    }

    @Test
    fun `read assistant with defaultCliAgentId exposes cliActive true only in readOnly`() = runBlocking {
        val assistant = Assistant(
            id = Uuid.random(),
            name = "cli-assistant",
            promptPreset = AssistantPresets.withSystemPrompt("prompt"),
            defaultCliAgentId = "claude-code",
        )
        val domain = domainWith(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val out = domain.read(CreatorObjectKind.ASSISTANT, assistant.id.toString()) as CreatorReadDomain.DetailResult.Ok

        val readOnly = out.output.readOnly!!
        assertEquals(true, readOnly["cliActive"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("claude-code", readOnly["defaultCliAgentId"]?.jsonPrimitive?.contentOrNull)

        val content = out.output.contentJson.jsonObject
        assertFalse(content.containsKey("defaultCliAgentId"))
        assertFalse(content.containsKey("cliActive"))
    }

    @Test
    fun `read assistant without defaultCliAgentId exposes cliActive false and omits defaultCliAgentId`() = runBlocking {
        val assistant = Assistant(
            id = Uuid.random(),
            name = "plain-assistant",
            promptPreset = AssistantPresets.withSystemPrompt("prompt"),
            defaultCliAgentId = null,
        )
        val domain = domainWith(Settings(assistants = listOf(assistant), assistantId = assistant.id))
        val out = domain.read(CreatorObjectKind.ASSISTANT, assistant.id.toString()) as CreatorReadDomain.DetailResult.Ok

        val readOnly = out.output.readOnly!!
        assertEquals(false, readOnly["cliActive"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(readOnly.containsKey("defaultCliAgentId"))
    }

    @Test
    fun `read conversation with cliSession sets cliActive true without mutating contentJson`() = runBlocking {
        val assistant = Assistant(id = Uuid.random(), name = "a1")
        val convId = Uuid.random().toString()
        val view = JsonObject(mapOf(
            "assistantId" to JsonPrimitive(assistant.id.toString()),
            "title" to JsonPrimitive("CLI chat"),
            "cliSession" to JsonObject(mapOf("agentId" to JsonPrimitive("claude-code"))),
            "nodes" to JsonArray(emptyList()),
        ))
        val domain = domainWith(
            settings = Settings(assistants = listOf(assistant), assistantId = assistant.id),
            conversationViews = mapOf(convId to view),
        )
        val out = domain.read(CreatorObjectKind.CONVERSATION, convId) as CreatorReadDomain.DetailResult.Ok

        assertEquals(true, out.output.readOnly?.get("cliActive")?.jsonPrimitive?.booleanOrNull)
        val content = out.output.contentJson.jsonObject
        assertEquals("CLI chat", content["title"]?.jsonPrimitive?.contentOrNull)
        assertFalse(content.containsKey("cliSession"))
        assertFalse(content.containsKey("cliActive"))
    }

    @Test
    fun `read conversation without cliSession or with JsonNull sets cliActive false`() = runBlocking {
        val assistant = Assistant(id = Uuid.random(), name = "a1")
        val missingId = Uuid.random().toString()
        val nullId = Uuid.random().toString()
        val domain = domainWith(
            settings = Settings(assistants = listOf(assistant), assistantId = assistant.id),
            conversationViews = mapOf(
                missingId to JsonObject(mapOf(
                    "assistantId" to JsonPrimitive(assistant.id.toString()),
                    "title" to JsonPrimitive("No CLI"),
                    "nodes" to JsonArray(emptyList()),
                )),
                nullId to JsonObject(mapOf(
                    "assistantId" to JsonPrimitive(assistant.id.toString()),
                    "title" to JsonPrimitive("Null CLI"),
                    "cliSession" to JsonNull,
                    "nodes" to JsonArray(emptyList()),
                )),
            ),
        )

        val missingOut = domain.read(CreatorObjectKind.CONVERSATION, missingId) as CreatorReadDomain.DetailResult.Ok
        assertEquals(false, missingOut.output.readOnly?.get("cliActive")?.jsonPrimitive?.booleanOrNull)
        assertTrue(missingOut.output.contentJson.jsonObject.containsKey("title"))

        val nullOut = domain.read(CreatorObjectKind.CONVERSATION, nullId) as CreatorReadDomain.DetailResult.Ok
        assertEquals(false, nullOut.output.readOnly?.get("cliActive")?.jsonPrimitive?.booleanOrNull)
    }
}
