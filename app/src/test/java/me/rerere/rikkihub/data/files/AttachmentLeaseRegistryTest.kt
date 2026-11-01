package me.rerere.rikkihub.data.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AttachmentLeaseRegistryTest {

    private fun registry(start: Long = 1_000L): Pair<AttachmentLeaseRegistry, () -> Long> {
        var now = start
        return AttachmentLeaseRegistry(nowMillis = { now }, defaultTtlMillis = 10_000L) to { now }
    }

    @Test
    fun `track registers lease with default ttl`() {
        val clock = 5_000L
        var now = clock
        val registry = AttachmentLeaseRegistry(nowMillis = { now }, defaultTtlMillis = 1_000L)

        assertTrue(registry.track("upload/a.png"))
        assertTrue(registry.isLeased("upload/a.png"))
        assertEquals(clock + 1_000L, registry.leaseExpiry("upload/a.png"))
    }

    @Test
    fun `renew extends expiry`() {
        var now = 0L
        val registry = AttachmentLeaseRegistry(nowMillis = { now }, defaultTtlMillis = 1_000L)

        registry.track("upload/a.png")
        now = 900L
        registry.renew("upload/a.png")
        assertEquals(1_900L, registry.leaseExpiry("upload/a.png"))

        now = 1_800L
        assertTrue(registry.isLeased("upload/a.png"))
    }

    @Test
    fun `lease expires and prune removes it`() {
        var now = 0L
        val registry = AttachmentLeaseRegistry(nowMillis = { now }, defaultTtlMillis = 100L)

        registry.track("upload/a.png")
        now = 101L
        assertFalse(registry.isLeased("upload/a.png"))
        assertNull(registry.leaseExpiry("upload/a.png"))

        registry.track("upload/b.png")
        now = 300L
        assertEquals(1, registry.pruneExpired())
        assertTrue(registry.activeLeases().isEmpty())
    }

    @Test
    fun `stillValid false rejects registration`() {
        val (registry, _) = registry()
        assertFalse(registry.track("upload/gone.png", stillValid = { false }))
        assertFalse(registry.isLeased("upload/gone.png"))
        assertFalse(registry.renew("upload/gone.png", stillValid = { false }))
    }

    @Test
    fun `release drops the lease`() {
        val (registry, _) = registry()
        registry.track("upload/a.png")
        assertTrue(registry.release("upload/a.png"))
        assertFalse(registry.release("upload/a.png"))
        assertFalse(registry.isLeased("upload/a.png"))
    }

    @Test
    fun `guarded delete refuses leased file`() {
        val (registry, _) = registry()
        val dir = Files.createTempDirectory("lease").toFile()
        val file = File(dir, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        registry.track("upload/a.png")
        val result = registry.guardedDeleteIfUnleased("upload/a.png", file)
        assertTrue(result is LeaseDeleteResult.Leased)
        assertTrue(file.exists())
    }

    @Test
    fun `guarded delete removes unleased file and clears lease`() {
        var now = 0L
        val registry = AttachmentLeaseRegistry(nowMillis = { now }, defaultTtlMillis = 100L)
        val dir = Files.createTempDirectory("lease").toFile()
        val file = File(dir, "a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        registry.track("upload/a.png")
        now = 200L // 租约已过期
        val result = registry.guardedDeleteIfUnleased("upload/a.png", file)
        assertEquals(LeaseDeleteResult.Deleted(bytesFreed = 3, alreadyMissing = false), result)
        assertFalse(file.exists())
        assertTrue(registry.activeLeases().isEmpty())

        // 删除后带存在性校验的续租不得复活该文件
        assertFalse(registry.renew("upload/a.png", stillValid = { file.exists() }))
    }

    @Test
    fun `guarded delete treats missing file as deleted`() {
        val (registry, _) = registry()
        val file = File(Files.createTempDirectory("lease").toFile(), "missing.png")
        val result = registry.guardedDeleteIfUnleased("upload/missing.png", file)
        assertEquals(LeaseDeleteResult.Deleted(bytesFreed = 0, alreadyMissing = true), result)
    }

    @Test
    fun `guarded delete reports io failure`() {
        val (registry, _) = registry()
        // 非空目录 delete() 返回 false → DeleteFailed
        val dir = Files.createTempDirectory("lease").toFile()
        val nonEmpty = File(dir, "d").apply { mkdirs(); File(this, "x").writeText("x") }
        val result = registry.guardedDeleteIfUnleased("upload/d", nonEmpty)
        assertEquals(LeaseDeleteResult.DeleteFailed, result)
        assertTrue(nonEmpty.exists())
    }

    @Test
    fun `renew racing guarded delete cannot resurrect deleted file`() {
        var now = 0L
        val registry = AttachmentLeaseRegistry(nowMillis = { now }, defaultTtlMillis = 50L)
        val dir = Files.createTempDirectory("lease").toFile()
        val file = File(dir, "a.png").apply { writeBytes(byteArrayOf(1)) }
        val key = "upload/a.png"

        registry.track(key)
        now = 100L // 过期

        val renewAttempts = AtomicInteger(0)
        val startGate = CountDownLatch(1)
        val renewer = Thread {
            startGate.await()
            repeat(200) {
                registry.renew(key, stillValid = { file.exists() })
                renewAttempts.incrementAndGet()
            }
        }
        renewer.start()
        startGate.countDown()
        val result = registry.guardedDeleteIfUnleased(key, file)
        renewer.join(5_000)

        // 无论续租与删除谁赢：只要文件被删，后续续租（带 exists 校验）必须全部失败，
        // 且租约表不得残留已删文件的条目。
        if (result is LeaseDeleteResult.Deleted) {
            assertFalse(file.exists())
            assertNull(registry.leaseExpiry(key))
            assertFalse(registry.renew(key, stillValid = { file.exists() }))
        } else {
            assertTrue(file.exists())
            assertTrue(registry.isLeased(key))
        }
    }

    @Test
    fun `withLeaseLock composes atomically`() {
        val (registry, _) = registry()
        // 锁内嵌套调用是重入安全的（synchronized 同线程重入）
        val result = registry.withLeaseLock {
            registry.track("upload/a.png")
            registry.isLeased("upload/a.png")
        }
        assertTrue(result)
    }

    @Test
    fun `cancelled caller leaves no lock or lease residue`() {
        val (registry, _) = registry()
        val latch = CountDownLatch(1)
        val t = Thread {
            try {
                latch.await(10, TimeUnit.MILLISECONDS) // 模拟“取消不释放”场景前的停顿
                registry.track("upload/b.png")
            } catch (_: InterruptedException) {
                // 被打断：不应持有锁，也不得登记半截租约
            }
        }
        t.start()
        t.interrupt()
        t.join(5_000)

        // 锁仍可用、状态一致
        assertTrue(registry.withLeaseLock { true })
        assertFalse(registry.isLeased("upload/b.png"))
        assertTrue(registry.track("upload/c.png"))
    }

    // ---- extractUploadRelativePath（FilesManager.kt 内的纯函数）----

    @Test
    fun `extract upload relative path accepts canonical forms`() {
        assertEquals(
            "upload/abc.png",
            extractUploadRelativePath("file:///data/user/0/me.rerere.rikkihub/files/upload/abc.png")
        )
        assertEquals("upload/abc.png", extractUploadRelativePath("upload/abc.png"))
        assertEquals("upload/abc.png", extractUploadRelativePath("/api/files/path/upload/abc.png"))
        assertEquals("upload/a-b_c.D.PNG", extractUploadRelativePath("upload/a-b_c.D.PNG"))
    }

    @Test
    fun `extract upload relative path rejects foreign folders and traversal`() {
        assertNull(extractUploadRelativePath("file:///data/user/0/x/files/skills/s.md"))
        assertNull(extractUploadRelativePath("file:///data/user/0/x/files/images/a.png"))
        assertNull(extractUploadRelativePath("file:///data/user/0/x/files/tool_outputs/t.json"))
        assertNull(extractUploadRelativePath("skills/s.md"))
        assertNull(extractUploadRelativePath("upload/../skills/s.md"))
        assertNull(extractUploadRelativePath("upload/nested/deep.png"))
        assertNull(extractUploadRelativePath("upload/"))
        assertNull(extractUploadRelativePath("upload/a b.png"))
        assertNull(extractUploadRelativePath("upload/a%2epng"))
        assertNull(extractUploadRelativePath("https://evil.example/upload/x.png"))
        assertNull(extractUploadRelativePath("data:image/png;base64,AAA"))
        assertNull(extractUploadRelativePath(""))
    }
}
