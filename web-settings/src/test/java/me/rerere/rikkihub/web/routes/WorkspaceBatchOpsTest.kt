package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import io.ktor.http.HttpStatusCode
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.dto.WorkspaceBatchDeleteFailureDto
import me.rerere.rikkihub.web.dto.WorkspaceBatchDeleteResponse
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

class WorkspaceBatchOpsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- 严格 schema ----------

    private fun bodyOf(vararg paths: String): JsonObject =
        buildJsonObject {
            put("paths", JsonArray(paths.map { JsonPrimitive(it) }))
        }

    @Test
    fun `schema accepts a plain paths array`() {
        val paths = extractWorkspaceSelectionPaths(bodyOf("a", "dir/b.txt"))
        assertEquals(listOf("a", "dir/b.txt"), paths)
    }

    @Test
    fun `schema rejects missing paths key, non-array and non-string elements`() {
        assertThrows(BadRequestException::class.java) {
            extractWorkspaceSelectionPaths(buildJsonObject { put("x", JsonPrimitive("a")) })
        }
        assertThrows(BadRequestException::class.java) {
            extractWorkspaceSelectionPaths(buildJsonObject { put("paths", JsonPrimitive("a")) })
        }
        assertThrows(BadRequestException::class.java) {
            extractWorkspaceSelectionPaths(
                buildJsonObject { put("paths", buildJsonArray { add(JsonPrimitive(1)) }) }
            )
        }
        assertThrows(BadRequestException::class.java) {
            extractWorkspaceSelectionPaths(bodyOf())
        }
    }

    @Test
    fun `schema rejects unknown extra keys`() {
        assertThrows(BadRequestException::class.java) {
            extractWorkspaceSelectionPaths(
                buildJsonObject {
                    put("paths", buildJsonArray { add(JsonPrimitive("a")) })
                    put("extra", JsonPrimitive(true))
                }
            )
        }
    }

    @Test
    fun `schema enforces path count and single path length limits`() {
        val tooMany = buildJsonObject {
            put("paths", buildJsonArray { repeat(MAX_BATCH_PATH_COUNT + 1) { add(JsonPrimitive("p$it")) } })
        }
        assertThrows(WorkspaceSelectionLimitException::class.java) {
            extractWorkspaceSelectionPaths(tooMany)
        }
        assertThrows(WorkspaceSelectionLimitException::class.java) {
            extractWorkspaceSelectionPaths(bodyOf("a".repeat(MAX_BATCH_PATH_LENGTH + 1)))
        }
        // 边界值放行
        assertEquals(1, extractWorkspaceSelectionPaths(bodyOf("a".repeat(MAX_BATCH_PATH_LENGTH))).size)
    }

    // ---------- 词法归一化与祖先去重 ----------

    @Test
    fun `normalize folds separators, dot segments and backslashes`() {
        assertEquals(
            listOf("a/b", "c.txt"),
            normalizeWorkspaceSelectionPaths(listOf("/a//b/", ".\\c.txt"))
        )
        // 中间 .. 折叠且未逃出根
        assertEquals(listOf("b"), normalizeWorkspaceSelectionPaths(listOf("a/../b")))
        // 绝对词法路径剥前导斜杠 → 按存储区内相对路径处理 (与模块 resolve 口径一致)
        assertEquals(listOf("etc/hosts"), normalizeWorkspaceSelectionPaths(listOf("/etc/hosts")))
    }

    @Test
    fun `normalize rejects escape, root selection and NUL`() {
        for (bad in listOf("..", "../x", "a/../../x", "", ".", "/", "a/..", "./.")) {
            assertThrows("expected reject: '$bad'", BadRequestException::class.java) {
                normalizeWorkspaceSelectionPaths(listOf(bad))
            }
        }
        assertThrows(BadRequestException::class.java) {
            normalizeWorkspaceSelectionPaths(listOf("a\u0000b"))
        }
    }

    @Test
    fun `normalize dedupes exact and ancestor-covered descendants in first-seen order`() {
        assertEquals(
            listOf("a", "x"),
            normalizeWorkspaceSelectionPaths(listOf("a/b", "a", "a/b/c", "a", "x"))
        )
        // 被祖先覆盖的后代在模块校验前就折叠掉 (契约: 祖先折叠优先)
        assertEquals(listOf("dir"), normalizeWorkspaceSelectionPaths(listOf("dir", "dir/nope")))
    }

    // ---------- 宿主路径脱敏 ----------

    @Test
    fun `sanitize strips host absolute paths under workspace areas`() {
        val msg = "Failed to list directory: /data/user/0/me.rerere/files/workspaces/ws-1/files/dir"
        assertEquals(
            "Failed to list directory: <workspace>/files/dir",
            sanitizeWorkspaceHostPaths(msg, "ws-1")
        )
        // 不属于该工作区的路径不改写, null → ""
        assertEquals(msg, sanitizeWorkspaceHostPaths(msg, "other"))
        assertEquals("", sanitizeWorkspaceHostPaths(null, "ws-1"))
    }

    // ---------- 响应 DTO 序列化 ----------

    // ---------- 错误映射: 400/413 保具体提示, 未知异常不落宿主路径 ----------

    @Test
    fun `batch error mapping keeps safe details and hides host paths on unknown errors`() {
        val root = "ws-1"
        // 未知 IO 异常 → 500 通用文案, 宿主路径绝不回给用户
        val io = workspaceBatchError(
            IOException("open failed: /data/user/0/x/files/workspaces/ws-1/files/dir"), root
        )
        assertEquals(HttpStatusCode.InternalServerError, io.status)
        assertFalse(io.message.contains("workspaces"))
        assertFalse(io.message.contains("/data"))
        // 非 not-found 的 IllegalStateException → 500 通用文案
        val ise = workspaceBatchError(IllegalStateException("Failed to list directory: /x"), root)
        assertEquals(HttpStatusCode.InternalServerError, ise.status)
        assertFalse(ise.message.contains("/x"))
        // workspace 中途被删 → 404
        assertEquals(
            HttpStatusCode.NotFound,
            workspaceBatchError(IllegalStateException("Workspace not found: ws-1"), root).status
        )
        // 路径安全错 → 400 且保留 (脱敏后的) 具体提示
        val bad = workspaceBatchError(IllegalArgumentException("Path does not exist: nope"), root)
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertEquals("Path does not exist: nope", bad.message)
        // 限额类 → 413
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            workspaceBatchError(WorkspaceSelectionLimitException("Too many entries"), root).status
        )
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            workspaceBatchError(WorkspaceArchiveOutputLimitException("cap"), root).status
        )
        // ApiException 保留原状态码与消息
        val api = workspaceBatchError(BadRequestException("x must be boolean"), root)
        assertEquals(HttpStatusCode.BadRequest, api.status)
        assertEquals("x must be boolean", api.message)
        // Cancellation 永远传播
        assertThrows(CancellationException::class.java) {
            workspaceBatchError(CancellationException("gone"), root)
        }
    }

    @Test
    fun `archive output byte cap matches the 200MiB web client limit`() {
        // MAX_ARCHIVE_OUTPUT_BYTES 是 writeArchiveToTempFile 的默认硬上限,
        // 必须等于前端 MAX_WORKSPACE_ARCHIVE_BYTES (200 MiB)
        assertEquals(200L * 1024 * 1024, MAX_ARCHIVE_OUTPUT_BYTES)
    }

    @Test
    fun `batch delete response serializes deletedPaths and failures`() {
        val dto = WorkspaceBatchDeleteResponse(
            deletedPaths = listOf("a", "dir"),
            failures = listOf(WorkspaceBatchDeleteFailureDto("x", "boom")),
        )
        val parsed = Json.parseToJsonElement(Json.encodeToString(dto)).jsonObject
        assertEquals(
            listOf("a", "dir"),
            (parsed["deletedPaths"] as JsonArray).map { (it as JsonPrimitive).content }
        )
        val failure = (parsed["failures"] as JsonArray).single() as JsonObject
        assertEquals("x", (failure["path"] as JsonPrimitive).content)
        assertEquals("boom", (failure["error"] as JsonPrimitive).content)
    }

    // ---------- 真实 WorkspaceManager 接线 ----------

    private fun newManager(): Pair<WorkspaceManager, File> {
        val manager = WorkspaceManager(baseDir = tmp.newFolder("workspaces"))
        manager.ensureWorkspace("ws")
        return manager to manager.filesDir("ws")
    }

    private suspend fun measure(manager: WorkspaceManager, rels: List<String>) =
        measureWorkspaceSelection(
            relPaths = rels,
            listDir = { rel -> manager.listFiles("ws", rel, WorkspaceStorageArea.FILES) },
            fileSize = { rel -> manager.fileSize("ws", rel, WorkspaceStorageArea.FILES) },
        )

    @Test
    fun `measure counts files and directories and sums bytes skipping l2s`() = runBlocking {
        val (manager, files) = newManager()
        File(files, "dir/sub").mkdirs()
        File(files, "dir/a.txt").writeText("AA")        // 2B
        File(files, "dir/sub/b.txt").writeText("BBB")   // 3B
        File(files, "dir/.l2s.cache").writeText("X")    // 被跳过
        File(files, "top.txt").writeText("T")           // 1B

        val est = measure(manager, listOf("dir", "top.txt"))
        // dir + dir/a.txt + dir/sub + dir/sub/b.txt + top.txt = 5 项, 2+3+1 = 6B
        assertEquals(5L, est.entries)
        assertEquals(6L, est.inputBytes)
    }

    @Test
    fun `measure propagates invalid selection as IllegalArgumentException`() = runBlocking {
        val (manager, _) = newManager()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { measure(manager, listOf("missing.txt")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { measure(manager, listOf("../escape")) }
        }
        Unit
    }

    @Test
    fun `measure enforces entry and input size limits`() = runBlocking {
        val (manager, files) = newManager()
        File(files, "a.txt").writeText("AAAA")
        File(files, "b.txt").writeText("BBBB")
        assertThrows(WorkspaceSelectionLimitException::class.java) {
            runBlocking {
                measureWorkspaceSelection(
                    relPaths = listOf("a.txt", "b.txt"),
                    listDir = { rel -> manager.listFiles("ws", rel, WorkspaceStorageArea.FILES) },
                    fileSize = { rel -> manager.fileSize("ws", rel, WorkspaceStorageArea.FILES) },
                    maxEntries = 1,
                )
            }
        }
        assertThrows(WorkspaceSelectionLimitException::class.java) {
            runBlocking {
                measureWorkspaceSelection(
                    relPaths = listOf("a.txt", "b.txt"),
                    listDir = { rel -> manager.listFiles("ws", rel, WorkspaceStorageArea.FILES) },
                    fileSize = { rel -> manager.fileSize("ws", rel, WorkspaceStorageArea.FILES) },
                    maxInputBytes = 5,
                )
            }
        }
        Unit
    }

    @Test
    fun `measure honors cancellation`() {
        val (manager, files) = newManager()
        File(files, "a.txt").writeText("A")
        assertThrows(CancellationException::class.java) {
            runBlocking {
                coroutineContext.cancel()
                measure(manager, listOf("a.txt"))
            }
        }
        Unit
    }

    // ---------- 批量删除: 真实仓库路径安全 ----------

    @Test
    fun `batch delete removes mixed selection but one invalid path vetoes the whole batch`() = runBlocking {
        val (manager, files) = newManager()
        File(files, "dir").mkdirs()
        File(files, "dir/a.txt").writeText("A")
        File(files, "top.txt").writeText("T")
        File(files, "keep.txt").writeText("K")

        val result = manager.deleteFiles(
            "ws",
            normalizeWorkspaceSelectionPaths(listOf("dir", "top.txt")),
            WorkspaceStorageArea.FILES,
        )
        assertEquals(listOf("dir", "top.txt"), result.deletedPaths)
        assertTrue(result.failures.isEmpty())
        assertFalse(File(files, "dir").exists())
        assertTrue(File(files, "keep.txt").exists())

        // mixed invalid: 合法项绝不能先被删
        File(files, "ok.txt").writeText("O")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager.deleteFiles("ws", listOf("ok.txt", "../escape"), WorkspaceStorageArea.FILES)
            }
        }
        assertTrue(File(files, "ok.txt").exists())
        // 不存在项同样整体否决
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager.deleteFiles("ws", listOf("ok.txt", "nope"), WorkspaceStorageArea.FILES)
            }
        }
        assertTrue(File(files, "ok.txt").exists())
    }

    @Test
    fun `batch delete rejects symlink selection without following it`() = runBlocking {
        val (manager, files) = newManager()
        val target = File(files, "real.txt").apply { writeText("R") }
        File(files, "dir").mkdirs()
        val link = File(files, "link.txt")
        val midLink = File(files, "dir-link")
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(link.toPath(), target.toPath())
                Files.createSymbolicLink(midLink.toPath(), files.toPath())
            }.isSuccess
        )
        File(files, "dir/inner.txt").writeText("I")
        // 让 dir-link/inner.txt 经由链接真实存在, 命中中间组件 NOFOLLOW 校验
        File(files, "inner.txt").writeText("I")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager.deleteFiles("ws", listOf("dir", "link.txt"), WorkspaceStorageArea.FILES)
            }
        }
        // 链接与目标都还在, dir 未删
        assertTrue(target.exists())
        assertTrue(File(files, "dir").exists())
        // 中间组件是链接同样拒绝
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                manager.deleteFiles("ws", listOf("dir-link/inner.txt"), WorkspaceStorageArea.FILES)
            }
        }
        Unit
    }

    @Test
    fun `batch delete through normalized ancestors deletes covered tree once`() = runBlocking {
        val (manager, files) = newManager()
        File(files, "a/b").mkdirs()
        File(files, "a/b/c.txt").writeText("C")
        val result = manager.deleteFiles(
            "ws",
            normalizeWorkspaceSelectionPaths(listOf("a/b", "a")),
            WorkspaceStorageArea.FILES,
        )
        assertEquals(listOf("a"), result.deletedPaths)
        assertFalse(File(files, "a").exists())
    }

    @Test
    fun `batch delete works in LINUX area too`() = runBlocking {
        val (manager, _) = newManager()
        val linux = manager.linuxDir("ws")
        File(linux, "etc").mkdirs()
        File(linux, "etc/hostname").writeText("h")
        val result = manager.deleteFiles("ws", listOf("etc"), WorkspaceStorageArea.LINUX)
        assertEquals(listOf("etc"), result.deletedPaths)
        assertFalse(File(linux, "etc").exists())
    }

    // ---------- 归档: 临时文件、ZIP 正确性、限额与回收 ----------

    @Test
    fun `archive writes a valid zip then temp file can be served and deleted`() = runBlocking {
        val (manager, files) = newManager()
        File(files, "dir/sub").mkdirs()
        File(files, "dir/empty").mkdirs()
        File(files, "dir/a.txt").writeText("AA")
        File(files, "dir/sub/b.txt").writeText("")
        File(files, "dir/.l2s.cache").writeText("X")

        val tempDir = tmp.newFolder("tmp-archives")
        val zip = writeArchiveToTempFile(tempDir) { out ->
            manager.exportArchive("ws", listOf("dir"), WorkspaceStorageArea.FILES, out)
        }
        assertTrue(zip.isFile)
        ZipFile(zip).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            // base = 选中项公共父 → 顶层即 dir/
            assertTrue("dir/" in names)
            assertTrue("dir/empty/" in names)
            assertTrue("dir/a.txt" in names)
            assertTrue("dir/sub/b.txt" in names)
            assertFalse(names.any { it.contains(".l2s.") })
            assertEquals("AA", zf.getInputStream(zf.getEntry("dir/a.txt")).readBytes().decodeToString())
            assertEquals(0, zf.getInputStream(zf.getEntry("dir/sub/b.txt")).readBytes().size)
        }
        zip.delete()
        Unit
    }

    @Test
    fun `archive failure and cancellation both reclaim the temp file`() = runBlocking {
        val tempDir = tmp.newFolder("tmp-archives")
        // 导出中途抛错: 临时文件被删, 异常透传
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                writeArchiveToTempFile(tempDir) { out ->
                    out.write(byteArrayOf(1, 2, 3))
                    throw IllegalStateException("disk exploded")
                }
            }
        }
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
        // 取消同样回收
        assertThrows(CancellationException::class.java) {
            runBlocking {
                writeArchiveToTempFile(tempDir) {
                    throw CancellationException("client gone")
                }
            }
        }
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `archive output limit aborts export and removes partial zip`() = runBlocking {
        val (manager, files) = newManager()
        // 随机不可压缩数据, 确保压缩后的 ZIP 输出也必然超过限额
        File(files, "big.bin").writeBytes(
            ByteArray(256 * 1024).also { java.util.Random(42).nextBytes(it) }
        )
        val tempDir = tmp.newFolder("tmp-archives")
        assertThrows(WorkspaceArchiveOutputLimitException::class.java) {
            runBlocking {
                writeArchiveToTempFile(tempDir, maxOutputBytes = 1024) { out ->
                    manager.exportArchive("ws", listOf("big.bin"), WorkspaceStorageArea.FILES, out)
                }
            }
        }
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `archive rejects symlink selection and leaves no temp file`() = runBlocking {
        val (manager, files) = newManager()
        val target = File(files, "real.txt").apply { writeText("R") }
        val link = File(files, "link.txt")
        assumeTrue(
            runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        )
        val tempDir = tmp.newFolder("tmp-archives")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                writeArchiveToTempFile(tempDir) { out ->
                    manager.exportArchive(
                        "ws",
                        listOf("real.txt", "link.txt"),
                        WorkspaceStorageArea.FILES,
                        out,
                    )
                }
            }
        }
        assertTrue(tempDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `bounded output stream enforces the byte cap exactly`() {
        val sink = ByteArrayOutputStream()
        val bounded = WorkspaceBoundedOutputStream(sink, 8)
        bounded.write(ByteArray(8))           // 恰好写满
        assertThrows(WorkspaceArchiveOutputLimitException::class.java) {
            bounded.write(1)
        }
        bounded.close()
        assertEquals(8, sink.size())
    }

    // ---------- 并发 ----------

    @Test
    fun `operation locks serialize same workspace-area and parallelize different keys`() = runBlocking {
        val locks = WorkspaceFileOperationLocks()
        // 同 key 串行: 临界区内 overlap 计数不得 >1
        val inSection = AtomicInteger()
        val violations = AtomicInteger()
        val sameKey = List(4) {
            async(Dispatchers.Default) {
                locks.withLock("ws1", "FILES") {
                    if (inSection.incrementAndGet() > 1) violations.incrementAndGet()
                    try {
                        Thread.sleep(30)
                    } finally {
                        inSection.decrementAndGet()
                    }
                }
            }
        }
        sameKey.forEach { it.await() }
        assertEquals(0, violations.get())

        // 不同 key 并行: 两个都进临界区后才放行, 串行实现会超时失败
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val jobs = listOf("ws1|FILES", "ws2|FILES").map { key ->
            async(Dispatchers.Default) {
                val (id, area) = key.split("|")
                locks.withLock(id, area) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }
        assertTrue("different keys must not serialize", entered.await(5, TimeUnit.SECONDS))
        release.countDown()
        withTimeout(10_000) { jobs.forEach { it.await() } }
    }

    @Test
    fun `archive permits cap concurrency and release`() {
        val permits = WorkspaceArchivePermits(maxConcurrent = 1)
        assertTrue(permits.tryAcquire())
        assertFalse(permits.tryAcquire())
        permits.release()
        assertTrue(permits.tryAcquire())
        permits.release()
    }
}
