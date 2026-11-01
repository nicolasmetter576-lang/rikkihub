package me.rerere.rikkihub.data.db.fts

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FilesRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 消息搜索索引：保存、删除一个会话的耗时不能随整个索引变大而变慢（issue #2231），
 * 增量索引后搜索结果的标题和时间仍要是会话当前的。
 */
@RunWith(AndroidJUnit4::class)
class MessageFtsManagerInstrumentedTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var manager: MessageFtsManager
    private lateinit var repository: ConversationRepository

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "message-fts-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File =
                File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        database = AppDatabaseFactory.create(context)
        manager = MessageFtsManager(database)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), AppScope(),
            database.attachmentRetentionDao())
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, manager)
    }

    @After fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test fun indexingAndDeletingOneConversationDoesNotScaleWithTheWholeIndex() = runBlocking {
        val probe = bigConversation("probe")
        manager.indexConversation(probe)
        val small = measure(probe)

        repeat(FILLER_CONVERSATIONS) { manager.indexConversation(bigConversation("filler$it")) }
        assertTrue(rowCount() > FILLER_CONVERSATIONS * MESSAGES_PER_CONVERSATION)
        val large = measure(probe)

        Log.i(TAG, "reindex ms small=${small.reindexMs} large=${large.reindexMs}; " +
            "delete ms small=${small.deleteMs} large=${large.deleteMs}; rows=${rowCount()}")
        assertTrue(
            "保存一个会话在大索引里要 ${large.reindexMs}ms，小索引里 ${small.reindexMs}ms",
            large.reindexMs <= maxOf(small.reindexMs * 5, FLOOR_MS),
        )
        assertTrue(
            "删除一个会话在大索引里要 ${large.deleteMs}ms，小索引里 ${small.deleteMs}ms",
            large.deleteMs <= maxOf(small.deleteMs * 5, FLOOR_MS),
        )
    }

    @Test fun reindexingKeepsChangedAndDropsRemovedMessages() = runBlocking {
        val keep = UIMessage.user("alpha keeps staying")
        val drop = UIMessage.user("bravo goes away")
        val inserted = conversation("charlie", listOf(keep, drop))
        val conversation = inserted.copy(revision = repository.insertConversation(inserted).revision)
        assertEquals(1, repository.searchMessages("alpha").size)
        assertEquals(1, repository.searchMessages("bravo").size)

        // 去掉一条、改另一条、再新增一条
        val edited = keep.copy(parts = UIMessage.user("alpha was edited to delta").parts)
        val added = UIMessage.user("echo is new")
        repository.updateConversation(
            conversation.copy(messageNodes = listOf(MessageNode.of(edited), MessageNode.of(added)))
        )

        assertEquals(0, repository.searchMessages("bravo").size)
        assertEquals(1, repository.searchMessages("delta").size)
        assertEquals(1, repository.searchMessages("echo").size)
        assertEquals(2, rowCount())
    }

    @Test fun searchShowsTheCurrentTitleAndUpdateTime() = runBlocking {
        val inserted = conversation("foxtrot", listOf(UIMessage.user("golf is searchable")))
        val conversation = inserted.copy(revision = repository.insertConversation(inserted).revision)

        val renamedAt = Instant.ofEpochMilli(1_800_000_000_000)
        repository.updateConversation(conversation.copy(title = "Renamed hotel", updateAt = renamedAt))

        val hit = repository.searchMessages("golf").single()
        assertEquals("Renamed hotel", hit.title)
        assertEquals(renamedAt, hit.updateAt)
    }

    @Test fun deletingAConversationRemovesItsRowsOnly() = runBlocking {
        val first = conversation("india", listOf(UIMessage.user("juliet one")))
        val second = conversation("kilo", listOf(UIMessage.user("juliet two")))
        repository.insertConversation(first)
        repository.insertConversation(second)
        assertEquals(2, repository.searchMessages("juliet").size)

        manager.deleteConversation(first.id.toString())

        val hits = repository.searchMessages("juliet")
        assertEquals(listOf(second.id.toString()), hits.map { it.conversationId })
        assertEquals(1, rowCount())
    }

    private class Timing(val reindexMs: Long, val deleteMs: Long)

    private suspend fun measure(probe: Conversation): Timing {
        val id = probe.id.toString()
        repeat(2) { manager.indexConversation(probe) }
        val reindex = List(REINDEX_RUNS) {
            val t = System.nanoTime()
            manager.indexConversation(probe)
            System.nanoTime() - t
        }
        val delete = List(DELETE_RUNS) {
            val t = System.nanoTime()
            manager.deleteConversation(id)
            val took = System.nanoTime() - t
            manager.indexConversation(probe)
            took
        }
        // 取中位数，免得偶发的 GC 或调度抖动影响判断
        return Timing(reindex.sorted()[reindex.size / 2] / 1_000_000, delete.sorted()[delete.size / 2] / 1_000_000)
    }

    private fun rowCount(): Int = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM message_fts").use { it.moveToFirst(); it.getInt(0) }

    private fun conversation(word: String, messages: List<UIMessage>) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "Conversation $word",
        messageNodes = messages.map { MessageNode.of(it) },
        updateAt = Instant.now(),
    )

    private fun bigConversation(tag: String) = conversation(
        tag,
        List(MESSAGES_PER_CONVERSATION) { i ->
            UIMessage.user("$tag message $i " + "lorem ipsum dolor sit amet consectetur ".repeat(5))
        },
    )

    private companion object {
        const val TAG = "MessageFtsManagerTest"
        const val FILLER_CONVERSATIONS = 1600
        const val MESSAGES_PER_CONVERSATION = 20
        const val REINDEX_RUNS = 7
        const val DELETE_RUNS = 5
        const val FLOOR_MS = 12L
    }
}
