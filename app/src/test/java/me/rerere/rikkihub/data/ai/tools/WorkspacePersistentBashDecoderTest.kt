package me.rerere.rikkihub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.workspace.WorkspacePty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class WorkspacePersistentBashDecoderTest {
    private class ChunkedFakePty(chunks: List<ByteArray>) : WorkspacePty {
        private val queue = ArrayBlockingQueue<ByteArray>(chunks.size + 2).apply {
            chunks.forEach { add(it) }
            add(EOF)
        }

        @Volatile
        private var alive = true

        override val exitCode: Int?
            get() = if (alive) null else 0

        override fun available(): Int = queue.peek()?.size ?: 0

        override fun read(buffer: ByteArray): Int {
            val next = queue.poll(5, TimeUnit.SECONDS) ?: return -1
            if (next === EOF) {
                alive = false
                return -1
            }
            val count = minOf(next.size, buffer.size)
            System.arraycopy(next, 0, buffer, 0, count)
            return count
        }

        override suspend fun write(bytes: ByteArray) = Unit
        override fun resize(rows: Int, columns: Int) = Unit
        override suspend fun awaitExit(): Int = 0
        override suspend fun awaitClosed(timeoutMillis: Long): Boolean = true
        override fun close() {
            alive = false
            queue.offer(EOF)
        }

        private companion object {
            val EOF = ByteArray(0)
        }
    }

    private fun splitByStep(bytes: ByteArray, step: Int): List<ByteArray> =
        (0 until bytes.size step step).map { start ->
            bytes.copyOfRange(start, minOf(start + step, bytes.size))
        }

    @Test
    fun `LiveSession preserves multibyte Chinese and emoji split across PTY read boundaries`() = runBlocking {
        val marker = persistentBashMarkers("utf8-split")
        val body = "中文测试内容：你好世界！😀🚀👨‍👩‍👧‍👦 café naïve 混合ASCII\n第二行汉字🎉"
        val full = "${marker.start}\n$body\n${marker.end}0\n".toByteArray(Charsets.UTF_8)

        for (step in listOf(1, 2, 3, 5)) {
            val pty = ChunkedFakePty(splitByStep(full, step))
            val session = WorkspacePersistentBashSessionManager.LiveSession("ws-1", "ws-1", pty)
            try {
                val waiter = session.beginCapture(marker.end)
                withTimeout(5_000) { waiter.await() }
                // Wait for EOF so the full exit status line has arrived.
                val deadline = System.currentTimeMillis() + 5_000
                while (!session.isEof && System.currentTimeMillis() < deadline) Thread.sleep(5)
                val captured = commandOutputFromScrollback(session.snapshot().text, marker)
                assertNotNull("step=$step: marker should be parsed", captured)
                assertEquals("step=$step: exit code", 0, captured!!.exitCode)
                assertEquals("step=$step: decoded body must not corrupt UTF-8", body, captured.text)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `LiveSession flushes trailing incomplete UTF-8 bytes as replacement char before EOF`() = runBlocking {
        // Complete Chinese char + truncated 2-byte prefix of a 3-byte Chinese char right before EOF
        val good = "你好".toByteArray(Charsets.UTF_8)
        val truncatedTail = byteArrayOf(0xE4.toByte(), 0xB8.toByte())
        val pty = ChunkedFakePty(listOf(good, truncatedTail))
        val session = WorkspacePersistentBashSessionManager.LiveSession("ws-eof", "ws-eof", pty)
        try {
            val waiter = session.beginCapture("__NEVER_APPEARS__")
            withTimeout(5_000) { waiter.await() }
            val text = session.snapshot().text
            assertEquals("你好\uFFFD", text)
        } finally {
            session.close()
        }
    }

    @Test
    fun `LiveSession preserves end marker immediately after a stray malformed lead byte`() = runBlocking {
        val marker = persistentBashMarkers("bad-lead")
        val payload = "${marker.start}\n前文".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0xE4.toByte()) +
            "${marker.end}0\n".toByteArray(Charsets.UTF_8)
        val pty = ChunkedFakePty(splitByStep(payload, 2))
        val session = WorkspacePersistentBashSessionManager.LiveSession("ws-bad", "ws-bad", pty)
        try {
            val waiter = session.beginCapture(marker.end)
            withTimeout(5_000) { waiter.await() }
            val deadline = System.currentTimeMillis() + 5_000
            while (!session.isEof && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val captured = commandOutputFromScrollback(session.snapshot().text, marker)
            assertNotNull(captured)
            assertEquals(0, captured!!.exitCode)
            assertEquals("前文\uFFFD", captured.text)
        } finally {
            session.close()
        }
    }

    @Test
    fun `LiveSession scrollback head trim does not leave an orphan low surrogate`() = runBlocking {
        // Place 'a' at index 0 followed by 32_000 emoji pairs (high at odd, low at even) and 'b' at index 64_001.
        // Total length = 64_002 > 64_000, so raw drop = 64_002 - 32_000 = 32_002 lands directly on a low surrogate.
        val chunks = mutableListOf("a".toByteArray(Charsets.UTF_8))
        val emojiBlock = "😀".repeat(1_000).toByteArray(Charsets.UTF_8)
        repeat(32) { chunks += emojiBlock }
        chunks += "b".toByteArray(Charsets.UTF_8)
        val pty = ChunkedFakePty(chunks)
        val session = WorkspacePersistentBashSessionManager.LiveSession("ws-trim", "ws-trim", pty)
        try {
            val waiter = session.beginCapture("__NEVER_APPEARS__")
            withTimeout(5_000) { waiter.await() }
            val snap = session.snapshot()
            assertTrue("scrollback should have trimmed", snap.truncated)
            assertTrue(snap.text.isNotEmpty())
            assertFalse(
                "trimmed scrollback must not start with an orphan low surrogate",
                snap.text.first().isLowSurrogate(),
            )
            assertFalse(
                "trimmed scrollback must not end with an orphan high surrogate",
                snap.text.last().isHighSurrogate(),
            )
            assertTrue("trailing marker char must survive trim", snap.text.endsWith("b"))
        } finally {
            session.close()
        }
    }

    @Test
    fun `IncrementalUtf8Decoder handles CharBuffer OVERFLOW, large inputs, residual lead bytes, and idempotent finish`() {
        val sample = "中文🎉混合ASCII😀" + "x".repeat(PERSISTENT_BASH_READ_BUFFER_BYTES)
        val bytes = sample.toByteArray(Charsets.UTF_8)

        // Tiny output buffer forces repeated CoderResult.OVERFLOW draining including 2-char surrogate pairs.
        val tiny = IncrementalUtf8Decoder(byteBufferCapacity = 4, charBufferCapacity = 2)
        val tinyOut = StringBuilder()
        splitByStep(bytes, 3).forEach { chunk -> tinyOut.append(tiny.decode(chunk)) }
        tinyOut.append(tiny.finish())
        assertEquals(sample, tinyOut.toString())
        assertEquals("finish must be idempotent", "", tiny.finish())

        // Residual 2-byte UTF-8 prefix + full 8192-byte ASCII chunk + oversized 20KB chunk.
        val defaultDecoder = IncrementalUtf8Decoder()
        val firstPart = byteArrayOf(0xE4.toByte(), 0xB8.toByte()) // first 2 bytes of '中' (E4 B8 AD)
        assertEquals("", defaultDecoder.decode(firstPart))
        val bigTail = byteArrayOf(0xAD.toByte()) + "A".repeat(20_000).toByteArray(Charsets.UTF_8)
        assertEquals("中" + "A".repeat(20_000), defaultDecoder.decode(bigTail) + defaultDecoder.finish())
    }

    @Test
    fun `LiveSession continues reading past zero-length PTY reads`() = runBlocking {
        val marker = persistentBashMarkers("zero-read")
        val full = "${marker.start}\n你好\n${marker.end}0\n".toByteArray(Charsets.UTF_8)
        val chunks = listOf(
            full.copyOfRange(0, marker.start.length + 2),
            ByteArray(0), // zero-length read (distinct from EOF sentinel)
            full.copyOfRange(marker.start.length + 2, full.size),
        )
        val pty = ChunkedFakePty(chunks)
        val session = WorkspacePersistentBashSessionManager.LiveSession("ws-zero", "ws-zero", pty)
        try {
            val waiter = session.beginCapture(marker.end)
            withTimeout(5_000) { waiter.await() }
            val deadline = System.currentTimeMillis() + 5_000
            while (!session.isEof && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val captured = commandOutputFromScrollback(session.snapshot().text, marker)
            assertNotNull(captured)
            assertEquals("你好", captured!!.text)
        } finally {
            session.close()
        }
    }

    @Test
    fun `maybeTruncatePersistentBash does not split a UTF-16 surrogate pair`() {
        val clipped = maybeTruncatePersistentBash("a😀b", maxOutputChars = 2)
        val prefix = clipped.substringBefore("<response clipped>")
        assertEquals("a", prefix)
        assertFalse(prefix.last().isHighSurrogate())

        val singleLimit = maybeTruncatePersistentBash("😀b", maxOutputChars = 1)
        val singlePrefix = singleLimit.substringBefore("<response clipped>")
        assertEquals("", singlePrefix)
    }
}
