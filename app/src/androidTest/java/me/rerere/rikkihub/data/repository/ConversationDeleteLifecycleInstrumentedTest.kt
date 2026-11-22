package me.rerere.rikkihub.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.fts.MessageFtsManager
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.FavoriteType
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.NodeFavoriteTarget
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

    @Test fun favoritesAreHiddenWhileDeletedBackOnUndoAndGoneAfterPurge() = runBlocking {
        val kept = conversation("kept")
        val deleted = conversation("gone")
        repository.insertConversation(kept)
        repository.insertConversation(deleted)
        val keptKey = favorite(kept, kept.messageNodes[0])
        val deletedKey = favorite(deleted, deleted.messageNodes[0])
        assertEquals(setOf(keptKey, deletedKey), listedFavorites())

        repository.deleteConversation(deleted)
        assertEquals("hidden while deleted", setOf(keptKey), listedFavorites())

        repository.restoreConversation(deleted.id)
        assertEquals("back after undo", setOf(keptKey, deletedKey), listedFavorites())

        repository.deleteConversation(deleted)
        val afterWindow = System.currentTimeMillis() + ConversationRepository.RETENTION_TTL_MILLIS + 1_000
        repository.purgeExpiredDeletedConversations(now = afterWindow)
        assertNull("purged with the conversation", database.favoriteDao().getByRefKey(deletedKey))
        assertNotNull(database.favoriteDao().getByRefKey(keptKey))
    }

    @Test fun removingANodeDeletesOnlyItsFavorite() = runBlocking {
        val first = MessageNode.of(UIMessage.user("first"))
        val second = MessageNode.of(UIMessage.user("second"))
        val original = conversation("nodes").copy(messageNodes = listOf(first, second))
        repository.insertConversation(original)
        val firstKey = favorite(original, first)
        val secondKey = favorite(original, second)

        repository.updateConversation(original.copy(revision = 1, messageNodes = listOf(first)))

        assertNotNull(database.favoriteDao().getByRefKey(firstKey))
        assertNull(database.favoriteDao().getByRefKey(secondKey))
    }

    @Test fun purgeSweepsFavoritesOfMissingNodesButKeepsDeletedConversations() = runBlocking {
        val deleted = conversation("tombstone")
        repository.insertConversation(deleted)
        val deletedKey = favorite(deleted, deleted.messageNodes[0])
        repository.deleteConversation(deleted)
        // 更早版本删会话/节点时留下的收藏
        val orphan = conversation("orphan")
        val orphanKey = favorite(orphan, orphan.messageNodes[0])

        repository.purgeExpiredDeletedConversations()

        assertNull(database.favoriteDao().getByRefKey(orphanKey))
        assertNotNull("restorable conversation keeps its favorite", database.favoriteDao().getByRefKey(deletedKey))
    }

    private suspend fun favorite(conversation: Conversation, node: MessageNode): String =
        FavoriteRepository(database.favoriteDao()).addNodeFavorite(
            NodeFavoriteTarget(conversation.id, conversation.title, node.id, node)
        ).refKey

    private suspend fun listedFavorites(): Set<String> =
        database.favoriteDao().listByType(FavoriteType.NODE.value).first().mapTo(HashSet()) { it.refKey }

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
