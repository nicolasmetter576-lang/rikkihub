package me.rerere.ai.util

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64

class ImageEncodeCacheTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        resetCacheDir()
    }

    @After
    fun tearDown() {
        resetCacheDir()
    }

    private fun resetCacheDir() {
        val field = ImageEncodeCache::class.java.getDeclaredField("dir")
        field.isAccessible = true
        field.set(ImageEncodeCache, null)
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `uninitialized cache safely no ops get and put`() {
        assertNull(ImageEncodeCache.get("uninitialized-key"))
        ImageEncodeCache.put("uninitialized-key", byteArrayOf(1, 2, 3))
        assertNull(ImageEncodeCache.get("uninitialized-key"))
    }

    @Test
    fun `put and get store sha256 of version and key without leftover temp files`() {
        val cacheRoot = tempFolder.newFolder("cache-root")
        ImageEncodeCache.init(cacheRoot)

        val key = "/tmp/sample.png|1000|2048|10000|16000000|85"
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)
        ImageEncodeCache.put(key, payload)

        val expectedFile = cacheRoot
            .resolve("encoded_images")
            .resolve("${sha256Hex("1|$key")}.jpg")
        assertTrue(expectedFile.isFile)
        assertArrayEquals(payload, expectedFile.readBytes())

        val cached = ImageEncodeCache.get(key)
        assertNotNull(cached)
        assertEquals(expectedFile.absolutePath, cached!!.absolutePath)
        assertArrayEquals(payload, cached.readBytes())

        assertNull(ImageEncodeCache.get("missing-key"))

        val leftoverParts = cacheRoot.resolve("encoded_images")
            .listFiles { _, name -> name.endsWith(".part") }
            .orEmpty()
        assertTrue(leftoverParts.isEmpty())
    }

    @Test
    fun `zero byte entry is treated as miss and overwrite replaces atomically`() {
        val cacheRoot = tempFolder.newFolder("cache-atomic")
        ImageEncodeCache.init(cacheRoot)

        val key = "/tmp/photo.jpg|1234|5678|10000|16000000|85"
        val cacheDir = cacheRoot.resolve("encoded_images").apply { mkdirs() }
        val target = cacheDir.resolve("${sha256Hex("1|$key")}.jpg")
        target.writeBytes(ByteArray(0))
        assertTrue(target.isFile)
        assertEquals(0L, target.length())

        assertNull(ImageEncodeCache.get(key))

        val firstBytes = byteArrayOf(10, 20, 30, 40)
        ImageEncodeCache.put(key, firstBytes)
        assertArrayEquals(firstBytes, ImageEncodeCache.get(key)?.readBytes())

        val secondBytes = byteArrayOf(50, 60, 70, 80, 90)
        ImageEncodeCache.put(key, secondBytes)
        assertArrayEquals(secondBytes, ImageEncodeCache.get(key)?.readBytes())

        val leftoverParts = cacheDir.listFiles { _, name -> name.endsWith(".part") }.orEmpty()
        assertTrue(leftoverParts.isEmpty())
    }

    @Test
    fun `get refreshes mtime and put trims least recently used jpg entries above 256MB`() {
        val cacheRoot = tempFolder.newFolder("cache-lru")
        ImageEncodeCache.init(cacheRoot)

        val k1 = "key-1"
        val k2 = "key-2"
        val k3 = "key-3"
        val k4 = "key-4"

        ImageEncodeCache.put(k1, byteArrayOf(1))
        ImageEncodeCache.put(k2, byteArrayOf(2))
        ImageEncodeCache.put(k3, byteArrayOf(3))

        val cacheDir = cacheRoot.resolve("encoded_images")
        val f1 = cacheDir.resolve("${sha256Hex("1|$k1")}.jpg")
        val f2 = cacheDir.resolve("${sha256Hex("1|$k2")}.jpg")
        val f3 = cacheDir.resolve("${sha256Hex("1|$k3")}.jpg")
        val sentinel = cacheDir.resolve("keep.png").apply {
            writeBytes(byteArrayOf(7, 8, 9))
            setLastModified(500_000L)
        }

        val hundredMb = 100L * 1024 * 1024
        listOf(f1 to 1_000_000L, f2 to 2_000_000L, f3 to 3_000_000L).forEach { (file, mtime) ->
            RandomAccessFile(file, "rw").use { it.setLength(hundredMb) }
            assertTrue(file.setLastModified(mtime))
        }

        // 命中 f1 会刷新其 mtime，使 f2 成为最久未使用的条目
        val hit = ImageEncodeCache.get(k1)
        assertNotNull(hit)
        assertTrue(f1.lastModified() > f3.lastModified())

        ImageEncodeCache.put(k4, byteArrayOf(4, 5, 6))
        val f4 = cacheDir.resolve("${sha256Hex("1|$k4")}.jpg")

        assertFalse("LRU oldest entry f2 should be evicted", f2.exists())
        assertTrue("Refreshed entry f1 should be preserved", f1.exists())
        assertTrue("Newer entry f3 should be preserved", f3.exists())
        assertTrue("Newly written entry f4 should exist", f4.exists())
        assertTrue("Non-jpg file should not be touched by trim", sentinel.exists())
    }

    @Test
    fun `trim keeps the size bound while readers refresh entries concurrently`() {
        val cacheRoot = tempFolder.newFolder("cache-concurrent")
        ImageEncodeCache.init(cacheRoot)
        val cacheDir = cacheRoot.resolve("encoded_images").apply { mkdirs() }

        // 1500 个 256KiB 稀疏文件 = 375MiB，超过上限；读线程不停 get() 刷新修改时间
        val keys = (0 until 1500).map { "entry-$it" }
        keys.forEachIndexed { i, key ->
            val file = cacheDir.resolve("${sha256Hex("1|$key")}.jpg")
            RandomAccessFile(file, "rw").use { it.setLength(256L * 1024) }
            file.setLastModified(1_000_000L + i)
        }

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val readers = (0 until 8).map { t ->
            Thread {
                var i = t
                while (!stop.get()) {
                    ImageEncodeCache.get(keys[i % keys.size])
                    i += 7
                }
            }.apply { start() }
        }
        val oversized = mutableListOf<String>()
        try {
            repeat(20) { iter ->
                // 每轮再塞 32MiB 旧条目，逼 trim() 在读线程刷新时间时排序
                repeat(128) { j ->
                    val file = cacheDir.resolve("filler-$iter-$j.jpg")
                    RandomAccessFile(file, "rw").use { it.setLength(256L * 1024) }
                    file.setLastModified(1_000L + j)
                }
                ImageEncodeCache.put("probe-$iter", ByteArray(1024))
                val total = cacheDir.listFiles()!!.filter { it.extension == "jpg" }.sumOf { it.length() }
                if (total > 256L * 1024 * 1024) oversized += "iter=$iter total=$total"
            }
        } finally {
            stop.set(true)
            readers.forEach { it.join() }
        }
        assertTrue("trim() gave up while readers refreshed entries: $oversized", oversized.isEmpty())
    }

    @Test
    fun `put failure does not throw when cache root is a regular file`() {
        val regularFile = tempFolder.newFile("not-a-directory")
        ImageEncodeCache.init(regularFile)

        ImageEncodeCache.put("any-key", byteArrayOf(1, 2, 3))
        assertNull(ImageEncodeCache.get("any-key"))
    }

    @Test
    fun `file encoder compressAndEncode caches hit and invalidates on metadata or quality change`() {
        val cacheRoot = tempFolder.newFolder("cache-encoder")
        ImageEncodeCache.init(cacheRoot)

        val sourceImage = tempFolder.newFile("sample.png").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            setLastModified(1_700_000_000_000L)
        }

        var compressCalls = 0
        val compressor: File.(Int, Long, Int) -> ByteArray = { _, _, q ->
            compressCalls++
            "jpeg-q$q-len${length()}-mtime${lastModified()}".toByteArray()
        }

        val (firstEncoded, firstMime) = sourceImage.compressAndEncode(
            mimeType = "image/png",
            quality = 85,
            compressJpeg = compressor,
        )
        assertEquals("image/jpeg", firstMime)
        assertEquals(1, compressCalls)

        val (secondEncoded, secondMime) = sourceImage.compressAndEncode(
            mimeType = "image/png",
            quality = 85,
            compressJpeg = compressor,
        )
        assertEquals("image/jpeg", secondMime)
        assertEquals(firstEncoded, secondEncoded)
        assertEquals(1, compressCalls)

        // 修改文件 mtime 后 cache key 变化，重新触发压缩
        assertTrue(sourceImage.setLastModified(1_700_000_100_000L))
        val (thirdEncoded, _) = sourceImage.compressAndEncode(
            mimeType = "image/png",
            quality = 85,
            compressJpeg = compressor,
        )
        assertEquals(2, compressCalls)
        assertFalse(firstEncoded == thirdEncoded)

        // 修改 quality 后 cache key 变化
        val (fourthEncoded, _) = sourceImage.compressAndEncode(
            mimeType = "image/png",
            quality = 70,
            compressJpeg = compressor,
        )
        assertEquals(3, compressCalls)
        assertFalse(thirdEncoded == fourthEncoded)

        // 修改文件长度后 cache key 变化
        val prevMtime = sourceImage.lastModified()
        sourceImage.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        sourceImage.setLastModified(prevMtime)
        val (fifthEncoded, _) = sourceImage.compressAndEncode(
            mimeType = "image/png",
            quality = 70,
            compressJpeg = compressor,
        )
        assertEquals(4, compressCalls)
        assertFalse(fourthEncoded == fifthEncoded)
    }

    @Test
    fun `file encoder compressAndEncode recovers after cache file deletion and skips cache for gif`() {
        val cacheRoot = tempFolder.newFolder("cache-recovery")
        ImageEncodeCache.init(cacheRoot)

        val pngFile = tempFolder.newFile("photo.png").apply {
            writeBytes(byteArrayOf(9, 8, 7, 6))
            setLastModified(1_700_000_000_000L)
        }

        var compressCalls = 0
        val expectedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x42, 0x43)
        val compressor: File.(Int, Long, Int) -> ByteArray = { _, _, _ ->
            compressCalls++
            expectedJpeg
        }

        val (encoded1, _) = pngFile.compressAndEncode("image/png", compressJpeg = compressor)
        assertEquals(1, compressCalls)
        assertEquals(Base64.getEncoder().encodeToString(expectedJpeg), encoded1)

        val cacheDir = cacheRoot.resolve("encoded_images")
        val cachedJpgs = cacheDir.listFiles { _, name -> name.endsWith(".jpg") }.orEmpty()
        assertEquals(1, cachedJpgs.size)
        assertTrue(cachedJpgs.single().delete())

        // 缓存文件被删后应重新压缩并写回缓存
        val (encoded2, _) = pngFile.compressAndEncode("image/png", compressJpeg = compressor)
        assertEquals(2, compressCalls)
        assertEquals(encoded1, encoded2)
        assertEquals(1, cacheDir.listFiles { _, name -> name.endsWith(".jpg") }.orEmpty().size)

        // GIF 直接流式编码原始字节，不走压缩和缓存
        val gifBytes = "GIF89a_animated_payload".toByteArray()
        val gifFile = tempFolder.newFile("anim.gif").apply { writeBytes(gifBytes) }
        val (gifEncoded, gifMime) = gifFile.compressAndEncode("image/gif", compressJpeg = compressor)
        assertEquals("image/gif", gifMime)
        assertEquals(Base64.getEncoder().encodeToString(gifBytes), gifEncoded)
        assertEquals(2, compressCalls)
        assertEquals(1, cacheDir.listFiles { _, name -> name.endsWith(".jpg") }.orEmpty().size)
    }
}
