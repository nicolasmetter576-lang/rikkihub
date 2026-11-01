package me.rerere.rikkihub.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.fts.MessageFtsManager
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.uuid.Uuid

/** Delete → undo, re-import over a deleted id, and expiry purge on the real Room schema. */
@RunWith(AndroidJUnit4::class)
class ConversationDeleteLifecycleInstrumentedTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "conversation-delete-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File =
                File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        database = AppDatabaseFactory.create(context)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), AppScope(),
            database.attachmentRetentionDao())
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
    }

    @After fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test fun undoDeleteRestoresTheConversationUnchangedInItsOldPlace() = runBlocking {
        val updatedAt = Instant.ofEpochMilli(1_700_000_000_000)
        val original = conversation("zebra", updatedAt)
        repository.insertConversation(original)

        repository.deleteConversation(original)
        assertNull(repository.getConversationById(original.id))
        assertTrue(repository.searchMessages("zebra").isEmpty())

        repository.restoreConversation(original.id)
        val restored = repository.getConversationById(original.id)!!
        assertEquals(original.title, restored.title)
        assertEquals(updatedAt, restored.updateAt)
        assertEquals(original.messageNodes.map { it.id }, restored.messageNodes.map { it.id })
        assertFalse(repository.searchMessages("zebra").isEmpty())
    }

    @Test fun importReplacesADeletedConversationWithTheSameId() = runBlocking {
        val original = conversation("first")
        repository.insertConversation(original)
        repository.deleteConversation(original)

        repository.importConversation(original.copy(title = "imported again"))

        assertEquals("imported again", repository.getConversationById(original.id)?.title)
    }

    @Test fun deletedConversationsArePurgedOnlyAfterTheUndoWindow() = runBlocking {
        val deleted = conversation("old")
        val live = conversation("live")
        repository.insertConversation(deleted)
        repository.insertConversation(live)
        repository.deleteConversation(deleted)

        assertEquals(0, repository.purgeExpiredDeletedConversations())
        assertEquals(1, nodeCount(deleted.id))

        val afterWindow = System.currentTimeMillis() + ConversationRepository.RETENTION_TTL_MILLIS + 1_000
        assertEquals(1, repository.purgeExpiredDeletedConversations(now = afterWindow))
        assertNull(repository.currentVersion(deleted.id))
        assertEquals(0, nodeCount(deleted.id))
        assertNotNull(repository.getConversationById(live.id))
    }

    private fun conversation(word: String, updatedAt: Instant = Instant.now()) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "Conversation $word",
        messageNodes = listOf(MessageNode.of(UIMessage.user("message about $word"))),
        updateAt = updatedAt,
    )

    private fun nodeCount(id: Uuid): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM message_node WHERE conversation_id = ?", arrayOf(id.toString()))
        .use { it.moveToFirst(); it.getInt(0) }
}
