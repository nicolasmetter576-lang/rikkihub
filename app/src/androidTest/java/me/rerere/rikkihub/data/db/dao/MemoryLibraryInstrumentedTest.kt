package me.rerere.rikkihub.data.db.dao

import android.content.Context
import android.content.ContextWrapper
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkihub.data.db.migrations.Migration_39_40
import me.rerere.rikkihub.data.model.MemoryFile
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.MemoryWriteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * 2.23.0：条目式记忆 → Markdown 记忆库（v39 → v40 手写迁移）与做梦进度表。
 */
@RunWith(AndroidJUnit4::class)
class MemoryLibraryInstrumentedTest {
    private val testDbName = "memory-library-migration-test"
    private val assistantA = "11111111-1111-1111-1111-111111111111"
    private val assistantB = "22222222-2222-2222-2222-222222222222"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrationTurnsEntriesIntoOneProfilePerStoreAndMarksExistingChatsAsConsolidated() {
        helper.createDatabase(testDbName, 39).use { db ->
            listOf(
                assistantA to "Name is Lin",
                assistantA to "Likes noodles\n  and tea",
                MemoryRepository.GLOBAL_MEMORY_ID to "Lives in Hangzhou",
                assistantB to "   ",
            ).forEach { (owner, content) ->
                db.execSQL("INSERT INTO MemoryEntity (assistant_id, content) VALUES (?, ?)", arrayOf(owner, content))
            }
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("c1", assistantA, "chat", "[]", 100L, 500L),
            )
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, deleted_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("c2", assistantA, "trashed", "[]", 100L, 700L, 800L),
            )
        }

        helper.runMigrationsAndValidate(testDbName, 40, true, Migration_39_40).use { db ->
            val files = mutableMapOf<String, String>()
            db.query("SELECT assistant_id, path, content FROM memory_file").use { c ->
                while (c.moveToNext()) {
                    assertEquals(MemoryFile.PROFILE_PATH, c.getString(1))
                    files[c.getString(0)] = c.getString(2)
                }
            }
            // 只有空白条目的记忆库不生成文件
            assertEquals(setOf(assistantA, MemoryRepository.GLOBAL_MEMORY_ID), files.keys)
            val profile = files.getValue(assistantA)
            assertTrue(profile.startsWith("---\nname: profile\n"))
            assertTrue(profile.contains("- [stated] Name is Lin\n- [stated] Likes noodles and tea\n"))
            assertEquals("Who the user is", MemoryFile(MemoryFile.PROFILE_PATH, profile, 0).description)

            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'MemoryEntity'").use { c ->
                assertFalse(c.moveToFirst())
            }
            val progress = mutableMapOf<String, Long>()
            db.query("SELECT conversation_id, consolidated_at FROM memory_consolidation").use { c ->
                while (c.moveToNext()) progress[c.getString(0)] = c.getLong(1)
            }
            assertEquals(mapOf("c1" to 500L, "c2" to 700L), progress)
        }
    }

    @Test
    fun pendingQueriesSkipConsolidatedAndTrashedChatsAndProgressFollowsDeletes() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "memory-library-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File =
                File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val database = AppDatabaseFactory.create(context)
        try {
            val sql = database.openHelper.writableDatabase
            fun conversation(id: String, assistant: String, updatedAt: Long, deletedAt: Long? = null) = sql.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, deleted_at) " +
                    "VALUES (?, ?, '', '[]', 0, ?, ?)",
                arrayOf<Any?>(id, assistant, updatedAt, deletedAt),
            )
            conversation("fresh", assistantA, 2_000)
            conversation("done", assistantA, 1_500)
            conversation("updated", assistantA, 3_000)
            conversation("trashed", assistantA, 4_000, deletedAt = 4_100)
            conversation("old", assistantA, 10)
            conversation("other", assistantB, 5_000)

            val dao = database.memoryConsolidationDao()
            dao.upsert(MemoryConsolidationEntity("done", 1_500))
            dao.upsert(MemoryConsolidationEntity("updated", 2_500))

            assertEquals(
                listOf("updated", "fresh"),
                dao.getUpdatedConversationIds(listOf(assistantA), since = 1_000, limit = 10),
            )
            assertEquals(listOf("updated"), dao.getUpdatedConversationIds(listOf(assistantA), since = 1_000, limit = 1))
            assertEquals(
                setOf(assistantA, assistantB),
                dao.getAssistantIdsWithUpdatedConversations(listOf(assistantA, assistantB), since = 1_000).toSet(),
            )
            assertEquals(2_500L, dao.getConsolidatedAt("updated"))

            // 对话被彻底删掉时进度一起删掉
            sql.execSQL("DELETE FROM ConversationEntity WHERE id = 'updated'")
            assertNull(dao.getConsolidatedAt("updated"))

            // 记忆库的乐观锁在真库上也成立
            val repository = MemoryRepository(database.memoryDao())
            val created = repository.writeFile(assistantA, "/topics/food.md", "- tea\n", MemoryRepository.NEW_FILE_VERSION)
                as MemoryWriteResult.Success
            assertTrue(repository.writeFile(assistantA, "/topics/food.md", "x\n", "stale") is MemoryWriteResult.Conflict)
            assertTrue(repository.appendToFile(assistantA, "topics/food.md", "- noodles", created.file!!.version) is MemoryWriteResult.Success)
            assertEquals("- tea\n- noodles\n", repository.getFile(assistantA, "/topics/food.md")?.content)
            assertNull(repository.getFile(assistantB, "/topics/food.md"))
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
