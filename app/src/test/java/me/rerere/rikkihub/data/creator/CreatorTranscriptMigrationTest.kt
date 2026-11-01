package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.storage.CreatorTranscriptMigration
import me.rerere.rikkihub.data.creator.storage.CreatorTranscriptMigration.Companion.LEGACY_SESSION_ID
import me.rerere.rikkihub.data.creator.storage.CreatorTranscriptStore
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.uuid.Uuid

/** 2.7.x 创造会话 transcript → 普通会话（会话 ID 沿用旧 sessionId，旧提案随之归属）。 */
class CreatorTranscriptMigrationTest {

    private class Fixture {
        val dir: File = kotlin.io.path.createTempDirectory("creator-migration").toFile()
        val store = CreatorTranscriptStore(dir, JsonInstant)
        val conversations = linkedMapOf<Uuid, Conversation>()
        var failInsert = false
        val migration = CreatorTranscriptMigration(
            transcripts = store,
            conversationExists = { it in conversations },
            insertConversation = {
                check(!failInsert) { "database unavailable" }
                conversations[it.id] = it
            },
        )
    }

    private val messages = listOf(
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("add a greeting quick message\nplease"))),
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Proposed."))),
    )

    @Test
    fun `transcript becomes an ordinary creator conversation with the legacy id`() = runBlocking<Unit> {
        val f = Fixture()
        f.store.save(LEGACY_SESSION_ID, messages)
        assertEquals(CreatorTranscriptMigration.Result.MIGRATED, f.migration.migrate())
        val conversation = f.conversations.getValue(LEGACY_SESSION_ID)
        assertEquals(CreatorAssistantDefinition.ID, conversation.assistantId)
        assertEquals("add a greeting quick message", conversation.title)
        assertEquals(messages, conversation.messageNodes.map { it.currentMessage })
        assertFalse(f.store.exists(LEGACY_SESSION_ID))
        assertEquals(CreatorTranscriptMigration.Result.NOTHING_TO_DO, f.migration.migrate())
    }

    @Test
    fun `failed insert keeps the transcript for the next launch`() = runBlocking<Unit> {
        val f = Fixture()
        f.store.save(LEGACY_SESSION_ID, messages)
        f.failInsert = true
        runCatching { f.migration.migrate() }
        assertTrue(f.store.exists(LEGACY_SESSION_ID))
        f.failInsert = false
        assertEquals(CreatorTranscriptMigration.Result.MIGRATED, f.migration.migrate())
    }

    @Test
    fun `corrupt transcript is kept`() = runBlocking<Unit> {
        val f = Fixture()
        File(f.dir, "transcript_$LEGACY_SESSION_ID.json").writeText("{not json")
        assertTrue(runCatching { f.migration.migrate() }.isFailure)
        assertTrue(f.store.exists(LEGACY_SESSION_ID))
        assertTrue(f.conversations.isEmpty())
    }

    @Test
    fun `already migrated conversation is not overwritten`() = runBlocking<Unit> {
        val f = Fixture()
        f.store.save(LEGACY_SESSION_ID, messages)
        f.conversations[LEGACY_SESSION_ID] = Conversation(id = LEGACY_SESSION_ID,
            assistantId = CreatorAssistantDefinition.ID, title = "kept", messageNodes = emptyList())
        assertEquals(CreatorTranscriptMigration.Result.ALREADY_PRESENT, f.migration.migrate())
        assertEquals("kept", f.conversations.getValue(LEGACY_SESSION_ID).title)
        assertFalse(f.store.exists(LEGACY_SESSION_ID))
    }
}
