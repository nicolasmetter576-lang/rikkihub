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
import me.rerere.rikkihub.data.repository.TranslationHistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/**
 * 2.23.0：`translation_history`（v37 → v38 AutoMigration）与「只留最近 100 条」。
 */
@RunWith(AndroidJUnit4::class)
class TranslationHistoryInstrumentedTest {
    private val testDbName = "translation-history-migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SQLiteConfiguration.openHelperFactory(InstrumentationRegistry.getInstrumentation().targetContext),
    )

    @Test
    fun migrationAddsAnEmptyHistoryTable() {
        helper.createDatabase(testDbName, 37).close()
        helper.runMigrationsAndValidate(testDbName, 38, true).use { db ->
            db.query("SELECT COUNT(*) FROM translation_history").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun repositoryKeepsTheNewestHundredAndSkipsBlankResults() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(app.cacheDir.toPath(), "translation-history-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File =
                File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        val database = AppDatabaseFactory.create(context)
        try {
            var clock = 1_000L
            val repository = TranslationHistoryRepository(database.translationHistoryDao()) { clock++ }
            val model = Uuid.random()
            repeat(105) { index ->
                repository.add("src $index", "dst $index", "en", model)
            }
            assertNull(repository.add("src", "   ", "en", model))
            assertEquals(100, repository.count())

            val firstPage = repository.page(0, 20)
            assertEquals("src 104", firstPage.first().sourceText)
            val all = repository.page(0, 100)
            assertEquals("src 5", all.last().sourceText)
            assertEquals(all.map { it.id }.distinct(), all.map { it.id })

            assertTrue(repository.deleteById(firstPage.first().id))
            assertFalse(repository.deleteById(firstPage.first().id))
            assertEquals(99, repository.count())
            repository.deleteAll()
            assertEquals(0, repository.count())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
