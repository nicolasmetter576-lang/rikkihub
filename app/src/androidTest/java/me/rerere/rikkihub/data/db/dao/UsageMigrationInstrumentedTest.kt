package me.rerere.rikkihub.data.db.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.db.migrations.Migration_42_43
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class UsageMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext))

    @Test fun historicalBranchesBackfillSafelyAndValidateGeneratedSchema() {
        val name = "usage-migration-test"
        helper.createDatabase(name, 42).use { db ->
            db.insert("conversationentity", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                put("id", "usage-old-chat")
                put("assistant_id", "0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
                put("title", "usage migration")
                put("nodes", "[]")
                put("create_at", 700L)
                put("update_at", 700L)
            })
            val messages = """[null,1,"primitive",false,[],
              {"role":"user","usage":{"promptTokens":999}},
              {"role":"assistant","createdAt":"2026-11-03T10:20:30","modelId":"old-model",
                "modelSnapshot":{"modelId":"former-model"},"usage":{"promptTokens":100,"completionTokens":20,"cachedTokens":40,"totalTokens":0}},
              {"role":"assistant","createdAt":"bad-date","usage":{"promptTokens":10,"completionTokens":5,"totalTokens":99}},
              {"role":"assistant","usage":null}]"""
            for ((index, body) in listOf(messages, "broken JSON").withIndex()) {
                db.insert("message_node", SQLiteDatabase.CONFLICT_ABORT, ContentValues().apply {
                    put("id", "usage-old-node-$index")
                    put("conversation_id", "usage-old-chat")
                    put("node_index", index)
                    put("messages", body)
                    put("select_index", 0)
                })
            }
        }
        helper.runMigrationsAndValidate(name, 43, true, Migration_42_43).use { db ->
            db.query("SELECT * FROM usage_record ORDER BY created_at").use { c ->
                assertEquals(2, c.count)
                assertTrue(c.moveToFirst())
                assertEquals(700L, c.getLong(c.getColumnIndexOrThrow("created_at")))
                assertEquals(99L, c.getLong(c.getColumnIndexOrThrow("total_tokens")))
                assertTrue(c.moveToNext())
                val expected = LocalDateTime.parse("2026-11-03T10:20:30")
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                assertEquals(expected, c.getLong(c.getColumnIndexOrThrow("created_at")))
                assertEquals(120L, c.getLong(c.getColumnIndexOrThrow("total_tokens")))
                assertEquals(40L, c.getLong(c.getColumnIndexOrThrow("cached_tokens")))
                assertEquals("former-model", c.getString(c.getColumnIndexOrThrow("model_name")))
                assertEquals(0, c.getInt(c.getColumnIndexOrThrow("usage_complete")))
                assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("started_at")))
                assertTrue(c.isNull(c.getColumnIndexOrThrow("account_key")))
            }
            // Ledger rows have no conversation FK and survive removal of the original data.
            db.execSQL("DELETE FROM message_node")
            db.execSQL("DELETE FROM conversationentity")
            db.query("SELECT COUNT(*) FROM usage_record").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        }
    }
}
