package me.rerere.rikkihub.data.db.fts

import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class MemoryDictAssets(
    private val files: Map<String, ByteArray>,
    private val openDelayMillis: Long = 0L,
) : DictAssetSource {
    override fun children(path: String): List<String> {
        val prefix = "$path/"
        return files.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .sorted()
            .toList()
    }

    override fun open(path: String): ByteArrayInputStream {
        if (openDelayMillis > 0) Thread.sleep(openDelayMillis)
        return ByteArrayInputStream(files.getValue(path))
    }
}

/**
 * 回归：DB onOpen 可能并发触发 extractDict；旧实现先 deleteRecursively 再拷，
 * 并发窗口里一方会删掉另一方刚建好的目录 → copyAssetDir ENOENT → fresh install 崩。
 * 现实现串行 + 临时目录 + 最后写 version，并发必须收敛。
 */
class SimpleDictManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun assets(
        openDelayMillis: Long = 0L,
        files: Map<String, ByteArray> = mapOf(
            "simple_dict/dict.txt" to "中文字典内容".toByteArray(),
            "simple_dict/sub/nested.bin" to byteArrayOf(1, 2, 3),
        ),
    ) = MemoryDictAssets(files, openDelayMillis)

    @Test
    fun `fresh extraction writes content then the version marker`() {
        val files = tmp.newFolder("files")
        val dest = SimpleDictManager.extractTo(files, "simple_dict", 1, assets())

        assertEquals("中文字典内容", dest.resolve("dict.txt").readText())
        assertEquals("1", dest.resolve("version.txt").readText())
        assertTrue(dest.resolve("sub/nested.bin").isFile)
        assertFalse(files.resolve(".simple_dict.tmp").exists())
    }

    @Test
    fun `current version short circuits without touching assets`() {
        val files = tmp.newFolder("files")
        val dest = SimpleDictManager.extractTo(files, "simple_dict", 1, assets())

        val empty = MemoryDictAssets(emptyMap())
        val again = SimpleDictManager.extractTo(files, "simple_dict", 1, empty)
        assertEquals(dest, again)
        assertEquals("中文字典内容", dest.resolve("dict.txt").readText())
    }

    @Test
    fun `stale or missing version reextracts and cleans tmp`() {
        val files = tmp.newFolder("files")
        val dest = SimpleDictManager.extractTo(files, "simple_dict", 1, assets())
        dest.resolve("version.txt").writeText("0")

        val upgraded = assets(files = mapOf("simple_dict/dict.txt" to "v2".toByteArray()))
        SimpleDictManager.extractTo(files, "simple_dict", 2, upgraded)

        assertEquals("v2", dest.resolve("dict.txt").readText())
        assertEquals("2", dest.resolve("version.txt").readText())
        assertFalse(files.resolve(".simple_dict.tmp").exists())
    }

    @Test
    fun `failed extraction leaves no version marker and tmp is cleaned`() {
        val files = tmp.newFolder("files")
        val failing = object : DictAssetSource {
            override fun children(path: String) = if (path == "simple_dict") listOf("dict.txt") else emptyList()
            override fun open(path: String): ByteArrayInputStream { throw java.io.IOException("boom") }
        }
        try {
            SimpleDictManager.extractTo(files, "simple_dict", 1, failing)
            org.junit.Assert.fail("expected IOException")
        } catch (e: java.io.IOException) {
            // expected
        }
        assertFalse(files.resolve("simple_dict/version.txt").exists())
        assertFalse(files.resolve(".simple_dict.tmp").exists())
        // 失败后重试必须能成功
        val dest = SimpleDictManager.extractTo(files, "simple_dict", 1, assets())
        assertEquals("中文字典内容", dest.resolve("dict.txt").readText())
    }

    @Test
    fun `concurrent extractions converge to a complete dict`() {
        val files = tmp.newFolder("files")
        val source = assets(openDelayMillis = 3) // 慢 IO 放大竞态窗口
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val jobs = (1..threads).map {
                pool.submit<File> {
                    start.await()
                    SimpleDictManager.extractTo(files, "simple_dict", 1, source)
                }
            }
            start.countDown()
            jobs.forEach { job ->
                val dir = job.get(20, TimeUnit.SECONDS)
                // 每个调用拿到的都必须是完整目录
                assertEquals("中文字典内容", dir.resolve("dict.txt").readText())
                assertTrue(dir.resolve("sub/nested.bin").isFile)
                assertEquals("1", dir.resolve("version.txt").readText())
            }
            assertFalse(files.resolve(".simple_dict.tmp").exists())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `existing dest dir is preserved when new extraction fails`() {
        val files = tmp.newFolder("files")
        val dest = SimpleDictManager.extractTo(files, "simple_dict", 1, assets())
        dest.resolve("version.txt").writeText("0") // 标记过期，但内容还是好的

        val failing = object : DictAssetSource {
            override fun children(path: String) = if (path == "simple_dict") listOf("dict.txt") else emptyList()
            override fun open(path: String): ByteArrayInputStream { throw java.io.IOException("boom") }
        }
        try {
            SimpleDictManager.extractTo(files, "simple_dict", 2, failing)
            org.junit.Assert.fail("expected IOException")
        } catch (e: java.io.IOException) {
            // expected
        }
        // 旧目录在解压完成前不能被删掉（区别于旧实现先 deleteRecursively）
        assertEquals("中文字典内容", dest.resolve("dict.txt").readText())
        assertFalse(files.resolve(".simple_dict.tmp").exists())
    }
}
