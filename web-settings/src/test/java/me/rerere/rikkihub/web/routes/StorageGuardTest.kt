package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.DisplaySetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.files.AttachmentLeaseRegistry
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 10_000_000_000L
private val NEW_FILE_GUARD = AttachmentLeaseRegistry.NEW_FILE_GUARD_MILLIS

class StorageGuardTest {

    private fun facts(
        relativePath: String = "upload/a.png",
        canonicalFileUrl: String = "file:///data/user/0/me.rerere.rikkihub/files/upload/a.png",
        createdAt: Long = NOW - NEW_FILE_GUARD - 1, // 默认超过 24h guard
        leaseExpiresAt: Long? = null,
        nowMillis: Long = NOW,
    ) = StorageProtectionFacts(relativePath, canonicalFileUrl, createdAt, leaseExpiresAt, nowMillis)

    private fun evaluator(
        db: suspend (String, String) -> Boolean = { _, _ -> false },
        session: () -> Set<String> = { emptySet() },
        settings: () -> Set<String> = { emptySet() },
    ) = StorageProtectionEvaluator(db, session, settings)

    @Test
    fun `db reference hit protects`() = runBlocking {
        val reasons = evaluator(db = { _, _ -> true }).evaluateProtection(facts())
        assertTrue(StorageProtectionReason.REFERENCED_DB in reasons)
    }

    @Test
    fun `db query failure is fail closed`() = runBlocking {
        val reasons = evaluator(db = { _, _ -> throw IllegalStateException("db down") }).evaluateProtection(facts())
        assertTrue(StorageProtectionReason.CHECK_FAILED in reasons)
        assertFalse(reasons.isEmpty()) // fail-closed：异常≠无引用
    }

    @Test
    fun `session snapshot matched by normalized relative path`() = runBlocking {
        // session 里是 file:// 绝对路径，条目是 upload/a.png 相对路径 —— 归一化后必须命中
        val reasons = evaluator(
            session = { setOf("file:///data/user/0/me.rerere.rikkihub/files/upload/a.png") },
        ).evaluateProtection(facts())
        assertTrue(StorageProtectionReason.REFERENCED_SESSION in reasons)
    }

    @Test
    fun `session snapshot matched by canonical url`() = runBlocking {
        val reasons = evaluator(
            session = { setOf("file:///data/user/0/me.rerere.rikkihub/files/upload/a.png") },
        ).evaluateProtection(
            facts(relativePath = "upload/a.png"),
        )
        assertTrue(StorageProtectionReason.REFERENCED_SESSION in reasons)
    }

    @Test
    fun `session snapshot failure is fail closed`() = runBlocking {
        val reasons = evaluator(
            session = { throw RuntimeException("snapshot boom") },
        ).evaluateProtection(facts())
        assertTrue(StorageProtectionReason.CHECK_FAILED in reasons)
    }

    @Test
    fun `settings reference protects`() = runBlocking {
        val reasons = evaluator(
            settings = { setOf("upload/a.png") }, // settings 里存的也可能是相对路径形态
        ).evaluateProtection(facts())
        assertTrue(StorageProtectionReason.REFERENCED_SETTINGS in reasons)
    }

    @Test
    fun `settings failure is fail closed`() = runBlocking {
        val reasons = evaluator(
            settings = { throw IllegalArgumentException("settings boom") },
        ).evaluateProtection(facts())
        assertTrue(StorageProtectionReason.CHECK_FAILED in reasons)
    }

    @Test
    fun `active lease protects`() = runBlocking {
        val reasons = evaluator().evaluateProtection(facts(leaseExpiresAt = NOW + 60_000))
        assertTrue(StorageProtectionReason.LEASED in reasons)
        assertFalse(StorageProtectionReason.REFERENCED_DB in reasons)
    }

    @Test
    fun `expired lease does not protect`() = runBlocking {
        val reasons = evaluator().evaluateProtection(facts(leaseExpiresAt = NOW - 1))
        assertFalse(StorageProtectionReason.LEASED in reasons)
    }

    @Test
    fun `fresh file guarded for 24h`() = runBlocking {
        val fresh = evaluator().evaluateProtection(facts(createdAt = NOW - 1_000))
        assertTrue(StorageProtectionReason.NEW_FILE in fresh)

        val boundary = evaluator().evaluateProtection(facts(createdAt = NOW - NEW_FILE_GUARD))
        assertFalse(StorageProtectionReason.NEW_FILE in boundary)
    }

    @Test
    fun `unprotected file yields empty reasons`() = runBlocking {
        val reasons = evaluator().evaluateProtection(facts())
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `multiple independent failures all recorded`() = runBlocking {
        val reasons = evaluator(
            db = { _, _ -> throw IllegalStateException("x") },
            session = { throw RuntimeException("y") },
            settings = { throw RuntimeException("z") },
        ).evaluateProtection(facts())
        assertEquals(setOf(StorageProtectionReason.CHECK_FAILED), reasons)
    }

    // ---- normalizeFileRefKeys ----

    @Test
    fun `normalize keys maps file url and relative path to same key`() {
        val keys = normalizeFileRefKeys(
            listOf(
                "file:///data/user/0/me.rerere.rikkihub/files/upload/a.png",
                "upload/b.png",
                "https://example.com/remote.png",
                "   ",
            )
        )
        assertTrue("upload/a.png" in keys)
        assertTrue("upload/b.png" in keys)
        assertTrue("file:///data/user/0/me.rerere.rikkihub/files/upload/a.png" in keys)
        assertTrue("https://example.com/remote.png" in keys) // 无法归一保留原值
        assertFalse("" in keys)
    }

    // ---- collectSettingsFileRefs ----

    @Test
    fun `settings refs cover avatar background preset and user avatar`() {
        val settings = Settings(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image("file:///f/files/upload/av.png"),
                    background = "file:///f/files/upload/bg.png",
                    presetMessages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(
                                UIMessagePart.Image(url = "file:///f/files/upload/preset.png"),
                                UIMessagePart.Tool(
                                    toolCallId = "t1",
                                    toolName = "x",
                                    input = "{}",
                                    output = listOf(
                                        UIMessagePart.Document(
                                            url = "file:///f/files/upload/nested.pdf",
                                            fileName = "n.pdf",
                                        )
                                    ),
                                ),
                                UIMessagePart.Text("plain"),
                            ),
                        )
                    ),
                ),
                Assistant(), // 无引用助手
            ),
            displaySetting = DisplaySetting(
                userAvatar = Avatar.Image("file:///f/files/upload/user.png"),
            ),
        )
        val refs = collectSettingsFileRefs(settings)
        assertTrue("file:///f/files/upload/av.png" in refs)
        assertTrue("file:///f/files/upload/bg.png" in refs)
        assertTrue("file:///f/files/upload/preset.png" in refs)
        assertTrue("file:///f/files/upload/nested.pdf" in refs) // 工具 output 嵌套
        assertTrue("file:///f/files/upload/user.png" in refs)
        assertEquals(5, refs.size)
    }

    @Test
    fun `settings refs ignore non file urls`() {
        val settings = Settings(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image("https://example.com/a.png"),
                    background = "https://example.com/bg.png",
                    presetMessages = listOf(
                        UIMessage(
                            role = MessageRole.USER,
                            parts = listOf(UIMessagePart.Image(url = "https://example.com/x.png")),
                        )
                    ),
                )
            ),
        )
        assertTrue(collectSettingsFileRefs(settings).isEmpty())
    }

    // ---- sanitizeStoragePageParams / filterStorageFiles ----

    @Test
    fun `page params clamped`() {
        assertEquals(0 to 50, sanitizeStoragePageParams(null, null))
        assertEquals(0 to 1, sanitizeStoragePageParams(-5, 0))
        assertEquals(10 to 100, sanitizeStoragePageParams(10, 500))
        assertEquals(3 to 7, sanitizeStoragePageParams(3, 7))
    }

    @Test
    fun `filter by query and age`() {
        fun ent(name: String, mime: String, created: Long) = ManagedFileEntity(
            folder = "upload",
            relativePath = "upload/$name",
            displayName = name,
            mimeType = mime,
            sizeBytes = 1,
            createdAt = created,
            updatedAt = created,
        )
        val items = listOf(
            ent("photo.png", "image/png", NOW - 100),
            ent("doc.pdf", "application/pdf", NOW - 8L * 24 * 60 * 60 * 1000),
            ent("Old.JPG", "image/jpeg", NOW - 40L * 24 * 60 * 60 * 1000),
        )
        assertEquals(3, filterStorageFiles(items, null, null, NOW).size)
        assertEquals(listOf("photo.png"), filterStorageFiles(items, "photo", null, NOW).map { it.displayName })
        assertEquals(listOf("Old.JPG"), filterStorageFiles(items, "JPEG", null, NOW).map { it.displayName })
        assertEquals(
            listOf("doc.pdf", "Old.JPG"),
            filterStorageFiles(items, null, 7, NOW).map { it.displayName },
        )
        assertEquals(listOf("Old.JPG"), filterStorageFiles(items, null, 30, NOW).map { it.displayName })
        // olderThanDays=0 → 全量（cutoff=now，等于 now 的文件也不算老）
        assertEquals(3, filterStorageFiles(items, null, 0, NOW).size)
    }
}
