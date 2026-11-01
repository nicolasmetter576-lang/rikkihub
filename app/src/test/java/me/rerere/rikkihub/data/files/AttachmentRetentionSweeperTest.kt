package me.rerere.rikkihub.data.files

import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.entity.AttachmentRetentionEntity
import me.rerere.rikkihub.web.routes.StorageProtectionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentRetentionSweeperTest {
    private fun entry(path: String) = AttachmentRetentionEntity(path, "", "file:///x/$path", "released", 0, 10)

    @Test fun deletesOnlyUnreferencedAndRevivesReferencedAndKeepsUncertain() = runBlocking {
        val entries = listOf("upload/free.png", "upload/msg.png", "upload/avatar.png", "upload/leased.png", "upload/err.png")
            .map(::entry)
        val released = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val sweeper = AttachmentRetentionSweeper(
            expired = { entries },
            release = { released += it },
            evaluator = StorageProtectionEvaluator(
                dbHasReference = { _, rel -> if (rel == "upload/err.png") error("db down") else rel == "upload/msg.png" },
                sessionRefUrls = { emptySet() },
                settingsRefUrls = { setOf("file:///data/user/0/x/files/upload/avatar.png") },
            ),
            leaseExpiry = { if (it == "upload/leased.png") Long.MAX_VALUE else null },
            canonicalUrl = { "file:///data/user/0/x/files/$it" },
            createdAt = { 0L },
            deleteFile = { deleted += it; true },
            nowMillis = { 100_000_000L },
        )
        val result = sweeper.sweep()
        assertEquals(listOf("upload/free.png"), deleted)
        assertEquals(setOf("upload/free.png", "upload/msg.png", "upload/avatar.png"), released.toSet())
        assertEquals(AttachmentRetentionSweeper.Result(deleted = 1, revived = 2, kept = 2), result)
    }

    @Test fun failedDeleteKeepsTheEntry() = runBlocking {
        val released = mutableListOf<String>()
        val sweeper = AttachmentRetentionSweeper(
            expired = { listOf(entry("upload/a.png")) },
            release = { released += it },
            evaluator = StorageProtectionEvaluator({ _, _ -> false }, { emptySet() }, { emptySet() }),
            leaseExpiry = { null }, canonicalUrl = { "file:///f/$it" }, createdAt = { 0L },
            deleteFile = { false }, nowMillis = { 100_000_000L },
        )
        assertEquals(1, sweeper.sweep().kept)
        assertEquals(emptyList<String>(), released)
    }
}
