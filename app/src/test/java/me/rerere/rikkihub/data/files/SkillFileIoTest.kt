package me.rerere.rikkihub.data.files

import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 回归：单技能文件原子写（原件保护 + 独占临时文件 + 取消传播）
 * 与隐藏目录排除。
 */
class SkillFileIoTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `atomic write replaces existing file and removes temp file`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("SKILL.md").apply { writeText("old content") }

        assertTrue(writeSkillFileTextAtomically(target, "new content"))

        assertEquals("new content", target.readText())
        assertEquals(listOf("SKILL.md"), dir.list()!!.toList())
    }

    @Test
    fun `atomic write creates missing parent directories`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("docs/notes.md")

        assertTrue(writeSkillFileTextAtomically(target, "body"))

        assertEquals("body", target.readText())
        assertEquals(listOf("notes.md"), target.parentFile.list()!!.toList())
    }

    @Test
    fun `write failure keeps original file and cleans temp`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("SKILL.md").apply { writeText("original") }

        val success = runAtomicFileReplace(target) {
            throw IOException("disk full")
        }

        assertFalse(success)
        assertEquals("original", target.readText())
        assertEquals(listOf("SKILL.md"), dir.list()!!.toList())
    }

    @Test
    fun `rename failure keeps original file and cleans temp`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        // renameTo 目标是已存在目录时替换失败，原件必须原样保留
        val target = dir.resolve("docs").apply { mkdirs() }
        target.resolve("keep.md").writeText("inner")

        val success = writeSkillFileTextAtomically(target, "new")

        assertFalse(success)
        assertTrue(target.isDirectory)
        assertEquals("inner", target.resolve("keep.md").readText())
        assertEquals(listOf("keep.md"), target.list()!!.toList())
    }

    @Test
    fun `binary content is preserved byte for byte`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("icon.png")
        // 含非法 UTF-8 序列，按文本解码/重编码必然损坏
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x01)

        assertTrue(writeSkillFileBytesAtomically(target, bytes))

        assertTrue(bytes.contentEquals(target.readBytes()))
    }

    @Test
    fun `concurrent saves use independent temp files without truncation`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("SKILL.md").apply { writeText("seed") }
        val bodyA = "a".repeat(8192)
        val bodyB = "b".repeat(8192)
        val temps = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val bothWritten = CountDownLatch(2)
        // Dedicated threads isolate this blocking filesystem/barrier test from shared IO workers.
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            fun writer(body: String) = async(dispatcher) {
                runAtomicFileReplace(target) { temp ->
                    try {
                        temps.add(temp.name)
                        temp.writeText(body)
                        bothWritten.countDown()
                        check(bothWritten.await(5, TimeUnit.SECONDS))
                    } catch (failure: Exception) {
                        errors.add(failure)
                        throw failure
                    }
                }
            }
            val a = writer(bodyA)
            val b = writer(bodyB)
            assertTrue("writer A failed: $errors", a.await())
            assertTrue("writer B failed: $errors", b.await())
        }
        assertEquals(2, temps.size)
        assertNotEquals(temps[0], temps[1])
        val final = target.readText()
        assertTrue("final content must be one complete body", final == bodyA || final == bodyB)
        assertEquals(listOf("SKILL.md"), dir.list()!!.toList())
    }

    @Test
    fun `preexisting fixed-name temp file is not touched`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("SKILL.md").apply { writeText("old") }
        // 其他任务/历史遗留的固定名临时文件必须原样保留
        val foreign = dir.resolve(".SKILL.md.tmp").apply { writeText("not mine") }

        assertTrue(writeSkillFileTextAtomically(target, "new"))

        assertEquals("new", target.readText())
        assertTrue(foreign.exists())
        assertEquals("not mine", foreign.readText())
    }

    @Test
    fun `preexisting fixed-name temp symlink is not followed or deleted`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val victim = dir.resolve("victim.txt").apply { writeText("victim") }
        val target = dir.resolve("SKILL.md").apply { writeText("old") }
        // 预留同名 .tmp 软链：独占 createTempFile 不得写入链接目标或删除链接
        val link = dir.resolve(".SKILL.md.tmp")
        Files.createSymbolicLink(link.toPath(), victim.toPath())

        assertTrue(writeSkillFileTextAtomically(target, "new"))

        assertEquals("new", target.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("victim", victim.readText())
    }

    @Test
    fun `cancellation propagates and does not corrupt original`() = runBlocking {
        val dir = tempFolder.newFolder("skill")
        val target = dir.resolve("SKILL.md").apply { writeText("original") }

        // 独立子协程内写完后才取消：ensureActive 必须让取消正常传播而不是返回失败
        val child = async {
            val job = currentCoroutineContext()[Job]!!
            runAtomicFileReplace(target) { temp ->
                temp.writeText("new content")
                job.cancel()
            }
        }
        try {
            child.await()
            fail("cancellation should propagate")
        } catch (expected: CancellationException) {
            // expected: 取消必须原样传播
        }

        assertEquals("original", target.readText())
        assertEquals(listOf("SKILL.md"), dir.list()!!.toList())
    }

    @Test
    fun `listSkillDirs excludes hidden staging and backup leftovers`() {
        val root = tempFolder.newFolder("skills")
        root.resolve("my-skill").mkdirs()
        root.resolve(".my-skill.staging.0.tmp").apply {
            mkdirs()
            resolve("SKILL.md").writeText("---\nname: ghost\ndescription: x\n---\nbody")
        }
        root.resolve(".my-skill.backup.1.tmp").mkdirs()
        root.resolve("not-a-dir.md").writeText("file entry")

        val dirs = listSkillDirs(root)

        assertEquals(listOf("my-skill"), dirs.map { it.name })
    }
}
