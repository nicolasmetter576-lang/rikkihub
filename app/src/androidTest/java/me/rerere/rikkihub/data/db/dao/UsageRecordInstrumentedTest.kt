package me.rerere.rikkihub.data.db.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.entity.UsageRecordEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class UsageRecordInstrumentedTest {
    @Test fun quotaIntervalsInvalidateCrossingRequestsRatherThanSilentlySubtractingTheirTokens() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "usage-dao-").toFile()
        val room = AppDatabaseFactory.create(context, directory.resolve("test.db").path)
        try {
            val dao = room.usageRecordDao()
            for ((start, finish) in listOf(5L to 15L, 11L to 20L, 21L to 30L)) {
                dao.insert(UsageRecordEntity(createdAt = finish, startedAt = start, usageComplete = true,
                    source = "CHAT", accountKey = "devin:offline", promptTokens = 100,
                    completionTokens = 20, cachedTokens = 50, totalTokens = 999))
            }
            val interval = dao.getAccountUsage("devin:offline", 10, 20).single()
            assertEquals(2L, interval.requests)
            assertEquals(1L, interval.incompleteRequests)
            assertEquals(240L, interval.promptTokens + interval.completionTokens)
            assertEquals(0L, dao.getAccountUsage("devin:offline", 10, 20, false).single().incompleteRequests)
            assertEquals(1L, dao.getAccountUsage("devin:offline", 20, 30).single().requests)
            assertEquals(3L, dao.getGroupedUsage("%Y-%m-%d", 0).single().requests)
        } finally {
            room.close()
            directory.deleteRecursively()
        }
    }
}
