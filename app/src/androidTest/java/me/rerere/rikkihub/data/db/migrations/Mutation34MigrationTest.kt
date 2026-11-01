package me.rerere.rikkihub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate fixture database, never the user's live conversation database. */
@RunWith(AndroidJUnit4::class)
class Mutation34MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "mutation-33-34-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(context),
    )

    @After
    fun removeFixture() {
        context.deleteDatabase(name)
    }

    @Test
    fun migration33to34PreservesConversationAndInitializesMutationColumns() {
        context.deleteDatabase(name)
        val title = "migration canary 迁移验证"
        val nodes = "  [ { \"legacy\": true } ]  "
        helper.createDatabase(name, 33).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity " +
                    "(id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned) " +
                    "VALUES (?, ?, ?, ?, 11, 22, '[]', 1)",
                arrayOf("fixture-conversation", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", title, nodes),
            )
        }
        helper.runMigrationsAndValidate(name, 34, true).use { db ->
            db.query("SELECT title, nodes, create_at, update_at, is_pinned, revision, generation, deleted_at " +
                "FROM ConversationEntity WHERE id = 'fixture-conversation'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(title, cursor.getString(0))
                assertEquals(nodes, cursor.getString(1))
                assertEquals(11L, cursor.getLong(2))
                assertEquals(22L, cursor.getLong(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(0L, cursor.getLong(5))
                assertEquals(0L, cursor.getLong(6))
                assertTrue(cursor.isNull(7))
            }
            for (table in listOf("operation_receipts", "attachment_retention")) {
                db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        }
    }
}
