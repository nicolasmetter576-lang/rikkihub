package me.rerere.rikkihub.data.fonts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On-demand font downloads (mirror fallback, size/digest check, cancel, delete). */
class FontDownloadServiceTest {
    private val root: File = Files.createTempDirectory("fonts").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bytes = ByteArray(300_000) { (it % 251).toByte() }
    private val font = DownloadableFont(
        id = "test_font", displayName = "Test", family = "Test", sizeBytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        urls = listOf("https://a/font.ttf", "https://b/font.ttf"), license = "OFL", licenseUrl = "",
    )

    @After fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun service(fetcher: FontFetcher) =
        FontDownloadService(root, scope, fetcher, listOf(font)) { _, _ -> }

    private fun FontDownloadService.awaitIdle(id: String) = runBlocking {
        withTimeout(10_000) { states.first { it[id]?.downloading != true } }
    }

    @Test
    fun `a good download is installed and served from the downloaded dir`() {
        val opened = mutableListOf<String>()
        val service = service { url -> opened += url; FontDownloadStream(ByteArrayInputStream(bytes), bytes.size.toLong()) }
        assertNull(service.fileOf(font.id))
        service.start(font.id)
        service.awaitIdle(font.id)
        val file = service.fileOf(font.id)!!
        assertEquals(File(root, "$DOWNLOADED_FONTS_DIR/test_font.ttf"), file)
        assertTrue(bytes.contentEquals(file.readBytes()))
        assertEquals(setOf(font.id), service.installed.value)
        assertEquals(listOf("https://a/font.ttf"), opened)
        assertTrue(service.states.value.isEmpty())
        assertFalse(File(root, "$DOWNLOADED_FONTS_DIR/test_font.part").exists())
        // A new service (next process) sees the installed font.
        assertEquals(setOf(font.id), service { error("no fetch") }.installed.value)
    }

    @Test
    fun `a corrupt or failing mirror falls through to the next one`() {
        val service = service { url ->
            if (url.contains("//a/")) {
                val corrupt = bytes.copyOf().also { it[10] = 99 }
                FontDownloadStream(ByteArrayInputStream(corrupt), null)
            } else FontDownloadStream(ByteArrayInputStream(bytes), bytes.size.toLong())
        }
        service.start(font.id)
        service.awaitIdle(font.id)
        assertEquals(setOf(font.id), service.installed.value)
    }

    @Test
    fun `when every mirror fails the state says why and nothing is kept`() {
        val service = service { url ->
            if (url.contains("//a/")) throw IOException("HTTP 404")
            FontDownloadStream(ByteArrayInputStream(bytes.copyOf(1000)), 1000)
        }
        service.start(font.id)
        service.awaitIdle(font.id)
        val state = service.states.value[font.id]!!
        assertEquals(FontDownloadState.STATUS_FAILED, state.status)
        assertTrue(state.error!!, state.error!!.contains("Unexpected size"))
        assertNull(service.fileOf(font.id))
        assertFalse(File(root, "$DOWNLOADED_FONTS_DIR/test_font.part").exists())
        service.dismiss(font.id)
        assertTrue(service.states.value.isEmpty())
    }

    @Test
    fun `a running download refuses a second start and delete, and can be cancelled`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : InputStream() {
            var sent = 0
            override fun read(): Int = error("unused")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (sent == 0) { sent = 1; started.countDown(); b[off] = bytes[0]; return 1 }
                release.await(5, TimeUnit.SECONDS)
                b[off] = bytes[sent]; sent++
                return 1
            }
        }
        val service = service { FontDownloadStream(slow, bytes.size.toLong()) }
        service.start(font.id)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertThrows(FontDownloadAlreadyRunningException::class.java) { service.start(font.id) }
        assertThrows(FontDownloadAlreadyRunningException::class.java) { service.delete(font.id) }
        assertTrue(service.cancel(font.id))
        release.countDown()
        service.awaitIdle(font.id)
        assertTrue(service.states.value.isEmpty())
        assertNull(service.fileOf(font.id))
    }

    @Test
    fun `delete removes an installed font`() {
        val service = service { FontDownloadStream(ByteArrayInputStream(bytes), bytes.size.toLong()) }
        service.start(font.id)
        service.awaitIdle(font.id)
        assertTrue(service.delete(font.id))
        assertNull(service.fileOf(font.id))
        assertTrue(service.installed.value.isEmpty())
        assertThrows(NoSuchElementException::class.java) { service.start("nope") }
    }

    @Test
    fun `the shipped catalog has pinned sizes, digests and https mirrors`() {
        assertEquals(listOf("harmonyos_sans_sc", "lxgw_wenkai_screen"), DownloadableFonts.ALL.map { it.id })
        for (entry in DownloadableFonts.ALL) {
            assertTrue(entry.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(entry.sizeBytes > 1_000_000)
            assertTrue(entry.urls.isNotEmpty() && entry.urls.all { it.startsWith("https://") })
        }
    }
}
