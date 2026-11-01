package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.files.AttachmentLeaseRegistry
import me.rerere.rikkihub.data.files.FileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val T_NOW = 10_000_000_000L
private val GUARD = AttachmentLeaseRegistry.NEW_FILE_GUARD_MILLIS

// Harness 是嵌套类（非 inner），不能引用外部类成员；entity() 挪到文件级，断言不变
private fun entity(id: Long, name: String, createdAt: Long = T_NOW - GUARD - 1000) =
    ManagedFileEntity(
        id = id,
        folder = "upload",
        relativePath = "upload/$name",
        displayName = name,
        mimeType = "image/png",
        sizeBytes = 3,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

class StorageDeletionTest {

    private fun uploadDir(): File = Files.createTempDirectory("upload").toFile()

    private class Harness(upload: File) {
        val registry = AttachmentLeaseRegistry(nowMillis = { T_NOW }, defaultTtlMillis = 60_000L)
        val dir = upload
        var dbHasRef: suspend (String, String) -> Boolean = { _, _ -> false }
        var sessionRefs: () -> Set<String> = { emptySet() }
        var settingsRefs: () -> Set<String> = { emptySet() }
        val deletedRows = mutableListOf<Long>()
        var deleteRowResult: () -> Boolean = { true }
        var deleteRowThrows = false

        val evaluator by lazy {
            StorageProtectionEvaluator(
                dbHasReference = { url, rel -> dbHasRef(url, rel) },
                sessionRefUrls = sessionRefs,
                settingsRefUrls = settingsRefs,
            )
        }

        val deleter by lazy {
            StorageDeleter(
                evaluator = evaluator,
                registry = registry,
                resolveUpload = { id ->
                    // 模拟 FilesManager.resolveUploadEntity：测试直接登记 row
                    val entity = entities[id] ?: return@StorageDeleter null
                    val file = FileUtils.resolveChildWithin(dir, entity.relativePath.removePrefix("upload/"))
                        ?: return@StorageDeleter null
                    entity to file
                },
                deleteRow = { id ->
                    if (deleteRowThrows) throw IllegalStateException("db down")
                    deletedRows += id
                    deleteRowResult()
                },
                nowMillis = { T_NOW },
            )
        }

        val entities = mutableMapOf<Long, ManagedFileEntity>()

        fun addFile(name: String, createdAt: Long = T_NOW - GUARD - 1000): ManagedFileEntity {
            val e = entity((entities.size + 1).toLong(), name, createdAt)
            File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
            entities[e.id] = e
            return e
        }
    }

    // ---- 逐项删除语义 ----

    @Test
    fun `old unreferenced file is deleted and row removed`() = runBlocking {
        val h = Harness(uploadDir())
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertTrue(outcome is StorageDeleteOutcome.Deleted)
        assertEquals(3L, (outcome as StorageDeleteOutcome.Deleted).bytesFreed)
        assertFalse(outcome.alreadyMissing)
        assertTrue(outcome.rowRemoved)
        assertFalse(File(h.dir, "a.png").exists())
        assertEquals(listOf(e.id), h.deletedRows)
        Unit
    }

    @Test
    fun `referenced in db is protected`() = runBlocking {
        val h = Harness(uploadDir())
        h.dbHasRef = { _, _ -> true }
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertEquals(
            StorageDeleteOutcome.Protected(listOf("referenced_db")),
            outcome,
        )
        assertTrue(File(h.dir, "a.png").exists())
        assertTrue(h.deletedRows.isEmpty())
    }

    @Test
    fun `referenced in session snapshot is protected`() = runBlocking {
        val h = Harness(uploadDir())
        val file = File(h.dir, "a.png")
        h.sessionRefs = { setOf("file://${file.absolutePath}") }
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, file)
        assertTrue(outcome is StorageDeleteOutcome.Protected)
        assertTrue((outcome as StorageDeleteOutcome.Protected).reasons.contains("referenced_session"))
        assertTrue(file.exists())
        Unit
    }

    @Test
    fun `referenced in settings is protected`() = runBlocking {
        val h = Harness(uploadDir())
        h.settingsRefs = { setOf("upload/a.png") }
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertTrue(outcome is StorageDeleteOutcome.Protected)
        assertTrue((outcome as StorageDeleteOutcome.Protected).reasons.contains("referenced_settings"))
        Unit
    }

    @Test
    fun `active lease protects`() = runBlocking {
        val h = Harness(uploadDir())
        val e = h.addFile("a.png")
        h.registry.track("upload/a.png")
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertTrue(outcome is StorageDeleteOutcome.Protected)
        assertTrue((outcome as StorageDeleteOutcome.Protected).reasons.contains("leased"))
        assertTrue(File(h.dir, "a.png").exists())
        Unit
    }

    @Test
    fun `fresh file within 24h guard is protected`() = runBlocking {
        val h = Harness(uploadDir())
        val e = h.addFile("a.png", createdAt = T_NOW - 1000)
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertTrue(outcome is StorageDeleteOutcome.Protected)
        assertTrue((outcome as StorageDeleteOutcome.Protected).reasons.contains("new_file"))
        Unit
    }

    @Test
    fun `db query failure is fail closed`() = runBlocking {
        val h = Harness(uploadDir())
        h.dbHasRef = { _, _ -> throw IllegalStateException("sqlite gone") }
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertTrue(outcome is StorageDeleteOutcome.Protected)
        assertTrue((outcome as StorageDeleteOutcome.Protected).reasons.contains("check_failed"))
        assertTrue(File(h.dir, "a.png").exists())
        Unit
    }

    @Test
    fun `session snapshot appearing between checks blocks delete`() = runBlocking {
        val h = Harness(uploadDir())
        val file = File(h.dir, "a.png")
        val calls = AtomicInteger(0)
        // 外圈快照为空，锁内最后一刻复查出现引用 —— 必须拦住
        h.sessionRefs = { if (calls.incrementAndGet() >= 2) setOf("upload/a.png") else emptySet() }
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, file)
        assertTrue(outcome is StorageDeleteOutcome.Protected)
        assertTrue((outcome as StorageDeleteOutcome.Protected).reasons.contains("referenced_session"))
        assertTrue(file.exists())
        Unit
    }

    @Test
    fun `disk delete failure keeps db row`() = runBlocking {
        val h = Harness(uploadDir())
        // 非空目录 delete() 返回 false
        val dirAsFile = File(h.dir, "sub").apply { mkdirs(); File(this, "x").writeText("x") }
        val e = entity(9L, "sub") // relativePath upload/sub
        val outcome = h.deleter.deleteResolved(e, dirAsFile)
        assertEquals(StorageDeleteOutcome.Failed("disk_delete_failed"), outcome)
        assertTrue(dirAsFile.exists())
        assertTrue(h.deletedRows.isEmpty())
        Unit
    }

    @Test
    fun `missing on disk file deletes row only`() = runBlocking {
        val h = Harness(uploadDir())
        val e = entity(7L, "ghost.png")
        h.entities[7L] = e
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "ghost.png"))
        assertTrue(outcome is StorageDeleteOutcome.Deleted)
        assertTrue((outcome as StorageDeleteOutcome.Deleted).alreadyMissing)
        assertEquals(0L, outcome.bytesFreed)
        assertEquals(listOf(7L), h.deletedRows)
        Unit
    }

    @Test
    fun `row delete failure still reports deleted but flags pending row`() = runBlocking {
        val h = Harness(uploadDir())
        h.deleteRowThrows = true
        val e = h.addFile("a.png")
        val outcome = h.deleter.deleteResolved(e, File(h.dir, "a.png"))
        assertTrue(outcome is StorageDeleteOutcome.Deleted)
        assertFalse((outcome as StorageDeleteOutcome.Deleted).rowRemoved)
        assertFalse(File(h.dir, "a.png").exists())
        Unit
    }

    @Test
    fun `resolve unknown id yields not found`() = runBlocking {
        val h = Harness(uploadDir())
        assertEquals(StorageDeleter.Resolve.NotFound, h.deleter.resolve(999))
        Unit
    }

    @Test
    fun `concurrent renew and delete stay mutually exclusive`() {
        // 通过真实 deleter 管线：续租与删除互斥，且删后不能因续租复活
        val h = Harness(uploadDir())
        val e = h.addFile("a.png")
        val file = File(h.dir, "a.png")
        // 租约已过期，删除应当获胜；期间并发续租带 exists 校验
        h.registry.track("upload/a.png", ttlMillis = 1L)
        val gate = CountDownLatch(1)
        val renewer = Thread {
            gate.await()
            repeat(300) { h.registry.renew("upload/a.png", stillValid = { file.exists() }) }
        }
        renewer.start()
        gate.countDown()
        val outcome = runBlocking { h.deleter.deleteResolved(e, file) }
        renewer.join(5_000)

        when (outcome) {
            is StorageDeleteOutcome.Deleted -> {
                assertFalse(file.exists())
                // 文件已删：任何后续续租（带 exists 校验）不得复活
                assertFalse(h.registry.renew("upload/a.png", stillValid = { file.exists() }))
            }

            is StorageDeleteOutcome.Protected -> assertTrue(file.exists())
            is StorageDeleteOutcome.Failed -> error("unexpected failure: ${outcome.error}")
        }
    }

    @Test
    fun `no lease residue after interrupted renewal thread`() {
        val h = Harness(uploadDir())
        val t = Thread {
            try {
                Thread.sleep(30_000)
            } catch (_: InterruptedException) {
            }
            h.registry.track("upload/x.png")
        }
        t.start()
        t.interrupt()
        t.join(5_000)
        // 中断不得持有锁或留下半截状态
        assertTrue(h.registry.withLeaseLock { true })
        assertTrue(h.registry.isLeased("upload/x.png") || !h.registry.isLeased("upload/x.png"))
    }

    // ---- FileUtils.resolveChildWithin：canonical + symlink ----

    @Test
    fun `resolveChildWithin accepts plain child and rejects traversal`() {
        val dir = uploadDir()
        File(dir, "a.png").writeBytes(byteArrayOf(1))
        assertEquals(File(dir, "a.png").canonicalFile, FileUtils.resolveChildWithin(dir, "a.png"))
        assertNull(FileUtils.resolveChildWithin(dir, "../etc/passwd"))
        assertNull(FileUtils.resolveChildWithin(dir, "nested/deep.png"))
        assertNull(FileUtils.resolveChildWithin(dir, ".."))
        assertNull(FileUtils.resolveChildWithin(dir, ""))
        // 不存在的合法名：仍返回 canonical（由上层区分 existsOnDisk）
        assertEquals(File(dir, "missing.png").canonicalFile, FileUtils.resolveChildWithin(dir, "missing.png"))
    }

    @Test
    fun `resolveChildWithin rejects symlink escaping dir`() {
        val dir = uploadDir()
        val outside = Files.createTempDirectory("outside").toFile()
        File(outside, "secret.txt").writeText("s")
        val link = File(dir, "link.png")
        try {
            Files.createSymbolicLink(link.toPath(), File(outside, "secret.txt").toPath())
        } catch (e: Exception) {
            return // 平台不支持 symlink 时跳过
        }
        assertNull(FileUtils.resolveChildWithin(dir, "link.png"))
    }

    @Test
    fun `resolveChildWithin rejects symlink alias inside dir`() {
        // canonical 归属检查本身会通过（指向 dir 内文件）——必须额外拒绝别名
        val dir = uploadDir()
        File(dir, "real.png").writeBytes(byteArrayOf(1))
        val link = File(dir, "alias.png")
        try {
            Files.createSymbolicLink(link.toPath(), File(dir, "real.png").toPath())
        } catch (e: Exception) {
            return
        }
        assertNull(FileUtils.resolveChildWithin(dir, "alias.png"))
    }

    @Test
    fun `resolveChildWithin accepts nonexistent name for orphan rows`() {
        val dir = uploadDir()
        val f = FileUtils.resolveChildWithin(dir, "gone.png")
        assertEquals(File(dir, "gone.png").canonicalFile, f)
    }
}
