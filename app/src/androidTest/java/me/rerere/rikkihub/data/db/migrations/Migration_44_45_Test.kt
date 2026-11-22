package me.rerere.rikkihub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.DatabaseMigrationTracker
import me.rerere.rikkihub.data.db.MigrationState
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate fixture database, never the user's live conversation database. */
@RunWith(AndroidJUnit4::class)
class Migration_44_45_Test {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDb = "migration-44-45-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(context),
    )

    @Before
    @After
    fun removeFixture() {
        context.deleteDatabase(testDb)
    }

    @Test
    fun loadsPagedNodesInOrderWithoutTempBTreeAndDropsOldIndex() {
        helper.createDatabase(testDb, 44).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Test', '[]', 1000, 1000), " +
                    "('other', 'assistant', 'Other', '[]', 1000, 1000)"
            )
            // 故意乱序插入，并混入另一会话节点验证前缀过滤
            listOf(3, 0, 2, 1).forEach { index ->
                db.execSQL(
                    "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                        "VALUES (?, 'conv', ?, '[]', 0)",
                    arrayOf<Any?>("node-$index", index),
                )
            }
            db.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES ('other-0', 'other', 0, '[]', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 45, true, Migration_44_45)
        try {
            assertEquals(MigrationState.Idle, DatabaseMigrationTracker.state.value)

            val indexNames = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='message_node'"
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue(indexNames.toString(), "index_message_node_conversation_id_node_index" in indexNames)
            assertFalse(indexNames.toString(), "index_message_node_conversation_id" in indexNames)

            val query =
                "SELECT * FROM message_node WHERE conversation_id = 'conv' ORDER BY node_index ASC LIMIT 2 OFFSET 1"
            val ids = db.query(query).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("id"))) }
            }
            assertEquals(listOf("node-1", "node-2"), ids)

            val selectPlan = db.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
            assertTrue(selectPlan.toString(), selectPlan.any { "index_message_node_conversation_id_node_index" in it })
            assertFalse(selectPlan.toString(), selectPlan.any { "TEMP B-TREE" in it })

            val deletePlan = db.query(
                "EXPLAIN QUERY PLAN DELETE FROM message_node WHERE conversation_id = 'conv'"
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
            assertTrue(deletePlan.toString(), deletePlan.any { "index_message_node_conversation_id_node_index" in it })
        } finally {
            db.close()
        }
    }

    // 复合索引不是唯一索引：跨页存在重复 node_index 时迁移不得失败，按 pageSize=64 分页读取不能丢行或重复
    @Test
    fun migratesAndPagesAcrossBoundariesWithDuplicateNodeIndices() {
        val totalTargetNodes = 150
        val expectedByIndex = (0 until totalTargetNodes).map { i -> "node-${i.toString().padStart(3, '0')}" to (i / 3) }

        helper.createDatabase(testDb, 44).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Large', '[]', 1000, 1000), " +
                    "('noise', 'assistant', 'Noise', '[]', 1000, 1000)"
            )
            expectedByIndex.reversed().forEachIndexed { n, (id, nodeIndex) ->
                db.execSQL(
                    "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                        "VALUES (?, 'conv', ?, '[]', 0)",
                    arrayOf<Any?>(id, nodeIndex),
                )
                if (n % 5 == 0) {
                    db.execSQL(
                        "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                            "VALUES (?, 'noise', ?, '[]', 0)",
                        arrayOf<Any?>("noise-$n", nodeIndex),
                    )
                }
            }
        }

        val db = helper.runMigrationsAndValidate(testDb, 45, true, Migration_44_45)
        try {
            val loaded = mutableListOf<Pair<String, Int>>()
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = db.query(
                    "SELECT id, node_index FROM message_node WHERE conversation_id = 'conv' " +
                        "ORDER BY node_index ASC LIMIT $pageSize OFFSET $offset"
                ).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getInt(1)) }
                }
                if (page.isEmpty()) break
                loaded += page
                offset += page.size
            }

            assertEquals(totalTargetNodes, loaded.size)
            assertEquals(expectedByIndex.map { it.first }.toSet(), loaded.map { it.first }.toSet())
            assertEquals(expectedByIndex.map { it.second }, loaded.map { it.second })
            assertTrue(loaded.none { it.first.startsWith("noise-") })
        } finally {
            db.close()
        }
    }

    @Test
    fun migratesContinuouslyFrom43ThroughAutoMigration44To45() {
        helper.createDatabase(testDb, 43).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv', 'assistant', 'Chain', '[]', 1000, 1000)"
            )
            db.execSQL(
                "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                    "VALUES ('n1', 'conv', 1, '[]', 0), ('n0', 'conv', 0, '[]', 0)"
            )
        }

        val db = helper.runMigrationsAndValidate(testDb, 45, true, Migration_44_45)
        try {
            val ids = db.query(
                "SELECT id FROM message_node WHERE conversation_id = 'conv' ORDER BY node_index ASC"
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(listOf("n0", "n1"), ids)
        } finally {
            db.close()
        }
    }
}
