package me.rerere.rikkihub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure-JVM tests for [chooseCropOutputEncoding].
 * PNG fixtures are built with real CRC-32 chunk checksums.
 */
class CropEncodingTest {

    // ---------- fixture builders ----------

    private fun chunk(type: String, data: ByteArray, corruptCrc: Boolean = false): ByteArray {
        require(type.length == 4)
        val out = ByteArray(12 + data.size)
        out[0] = (data.size ushr 24).toByte()
        out[1] = (data.size ushr 16).toByte()
        out[2] = (data.size ushr 8).toByte()
        out[3] = data.size.toByte()
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(typeBytes, 0, out, 4, 4)
        System.arraycopy(data, 0, out, 8, data.size)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcVal = if (corruptCrc) crc.value.toInt() xor 0x1 else crc.value.toInt()
        out[8 + data.size] = (crcVal ushr 24).toByte()
        out[9 + data.size] = (crcVal ushr 16).toByte()
        out[10 + data.size] = (crcVal ushr 8).toByte()
        out[11 + data.size] = crcVal.toByte()
        return out
    }

    private fun ihdr(colorType: Int, bitDepth: Int = 8): ByteArray {
        val d = ByteArray(13)
        d[3] = 1 // width = 1
        d[7] = 1 // height = 1
        d[8] = bitDepth.toByte()
        d[9] = colorType.toByte()
        return chunk("IHDR", d)
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val sig = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        return sig + chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    private fun idat() = chunk("IDAT", byteArrayOf(0x78, 0x01, 0x02, 0x03))
    private fun iend() = chunk("IEND", ByteArray(0))
    private fun trns() = chunk("tRNS", byteArrayOf(0x00))
    private fun bkgd() = chunk("bKGD", ByteArray(6))

    private fun jpeg(vararg tail: Byte): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + tail

    private fun choose(bytes: ByteArray, budget: Int = 64 * 1024) =
        chooseCropOutputEncoding(ByteArrayInputStream(bytes), budget)

    // ---------- enum contract ----------

    @Test
    fun `enum contract extension mime quality`() {
        assertEquals("jpg", CropOutputEncoding.JPEG.extension)
        assertEquals("image/jpeg", CropOutputEncoding.JPEG.mimeType)
        assertEquals(90, CropOutputEncoding.JPEG.quality)
        assertEquals("png", CropOutputEncoding.PNG.extension)
        assertEquals("image/png", CropOutputEncoding.PNG.mimeType)
        assertEquals(100, CropOutputEncoding.PNG.quality)
    }

    // ---------- jpeg ----------

    @Test
    fun `jpeg soi selects jpeg`() {
        assertEquals(CropOutputEncoding.JPEG, choose(jpeg()))
    }

    @Test
    fun `jpeg with alpha-looking payload still jpeg`() {
        // JPEG has no alpha channel; payload contents are irrelevant.
        assertEquals(CropOutputEncoding.JPEG, choose(jpeg(0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 0x4A)))
    }

    // ---------- png opaque proofs ----------

    @Test
    fun `opaque grayscale png selects jpeg`() {
        assertEquals(CropOutputEncoding.JPEG, choose(png(ihdr(0), idat(), iend())))
    }

    @Test
    fun `opaque truecolor rgb png selects jpeg`() {
        assertEquals(CropOutputEncoding.JPEG, choose(png(ihdr(2), idat())))
    }

    @Test
    fun `palette png without trns selects jpeg`() {
        val plte = chunk("PLTE", ByteArray(6))
        assertEquals(CropOutputEncoding.JPEG, choose(png(ihdr(3), plte, idat())))
    }

    @Test
    fun `verified ancillary chunk before idat still opaque`() {
        assertEquals(CropOutputEncoding.JPEG, choose(png(ihdr(2), bkgd(), idat())))
    }

    // ---------- png transparency / alpha ----------

    @Test
    fun `rgba color type 6 keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(6), idat())))
    }

    @Test
    fun `grayscale alpha color type 4 keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(4), idat())))
    }

    @Test
    fun `trns before idat keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(2), trns(), idat())))
    }

    @Test
    fun `palette with trns keeps png`() {
        val plte = chunk("PLTE", ByteArray(6))
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(3), plte, trns(), idat())))
    }

    @Test
    fun `iend without idat keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(2), iend())))
    }

    // ---------- unknown / invalid signatures ----------

    @Test
    fun `empty input keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(ByteArray(0)))
    }

    @Test
    fun `single byte keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(byteArrayOf(0x89.toByte())))
    }

    @Test
    fun `webp riff signature keeps png`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertEquals(CropOutputEncoding.PNG, choose(webp))
    }

    @Test
    fun `gif signature keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose("GIF89a".toByteArray()))
    }

    @Test
    fun `random garbage keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test
    fun `png magic prefix but corrupt signature tail keeps png`() {
        val bad = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x00)
        assertEquals(CropOutputEncoding.PNG, choose(bad + ihdr(2) + idat()))
    }

    // ---------- structural strictness ----------

    @Test
    fun `bad ihdr crc keeps png`() {
        val d = ByteArray(13).also { it[3] = 1; it[7] = 1; it[8] = 8; it[9] = 2 }
        assertEquals(CropOutputEncoding.PNG, choose(png(chunk("IHDR", d, corruptCrc = true), idat())))
    }

    @Test
    fun `bad crc on skipped ancillary chunk keeps png`() {
        val bad = chunk("bKGD", byteArrayOf(1), corruptCrc = true)
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(2), bad, idat())))
    }

    @Test
    fun `ihdr wrong length keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(chunk("IHDR", ByteArray(12)), idat())))
    }

    @Test
    fun `first chunk not ihdr keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(bkgd(), ihdr(2), idat())))
    }

    @Test
    fun `reserved invalid color type keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(1), idat())))
    }

    @Test
    fun `negative chunk length keeps png`() {
        val bytes = png(ihdr(2)) + byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "tRNS".toByteArray()
        assertEquals(CropOutputEncoding.PNG, choose(bytes))
    }

    @Test
    fun `attacker huge chunk length keeps png`() {
        // Declares ~2GB chunk with almost no real payload.
        val bytes = png(ihdr(2)) + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            "teXt".toByteArray()
        assertEquals(CropOutputEncoding.PNG, choose(bytes))
    }

    @Test
    fun `truncated inside ihdr payload keeps png`() {
        val full = png(ihdr(2), idat())
        assertEquals(CropOutputEncoding.PNG, choose(full.copyOf(8 + 4)))
    }

    @Test
    fun `truncated before ihdr crc keeps png`() {
        val full = png(ihdr(2), idat())
        assertEquals(CropOutputEncoding.PNG, choose(full.copyOf(8 + 8 + 13 + 2)))
    }

    // ---------- budget ----------

    @Test
    fun `budget too small for signature keeps png`() {
        assertEquals(CropOutputEncoding.PNG, choose(jpeg(), budget = 1))
        assertEquals(CropOutputEncoding.PNG, choose(jpeg(), budget = 0))
    }

    @Test
    fun `budget cut before idat header keeps png`() {
        val bytes = png(ihdr(2), bkgd(), idat())
        // signature(8) + IHDR(25) + bKGD(18) = 51; cut inside IDAT header.
        assertEquals(CropOutputEncoding.PNG, choose(bytes, budget = 53))
        // A budget that exactly covers the IDAT header is enough to decide.
        assertEquals(CropOutputEncoding.JPEG, choose(bytes, budget = 59))
    }

    // ---------- stream behaviour ----------

    @Test
    fun `short reads handled correctly`() {
        val bytes = png(ihdr(2), idat())
        val dribble = object : InputStream() {
            private var i = 0
            override fun read(): Int = if (i < bytes.size) bytes[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= bytes.size) return -1
                val n = minOf(3, len, bytes.size - i)
                System.arraycopy(bytes, i, b, off, n)
                i += n
                return n
            }
        }
        assertEquals(CropOutputEncoding.JPEG, chooseCropOutputEncoding(dribble))
    }

    @Test
    fun `eof inside stream keeps png`() {
        val earlyEof = object : InputStream() {
            private var sent = 0
            override fun read(): Int = if (sent++ < 5) 0x50 else -1
        }
        assertEquals(CropOutputEncoding.PNG, chooseCropOutputEncoding(earlyEof))
    }

    @Test
    fun `input stream is not closed`() {
        var closed = false
        val bytes = png(ihdr(2), idat())
        val tracking = object : InputStream() {
            private val inner = ByteArrayInputStream(bytes)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, len)
            override fun close() {
                closed = true
                inner.close()
            }
        }
        chooseCropOutputEncoding(tracking)
        assertFalse(closed)
    }

    @Test
    fun `does not read whole image after deciding`() {
        // 1 MB payload after IDAT header must not be consumed.
        var reads = 0
        val bytes = png(ihdr(2), idat()) + ByteArray(1024 * 1024)
        val counting = object : InputStream() {
            private var i = 0
            override fun read(): Int {
                if (i >= bytes.size) return -1
                reads++
                return bytes[i++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= bytes.size) return -1
                val n = minOf(len, bytes.size - i)
                System.arraycopy(bytes, i, b, off, n)
                i += n
                reads += n
                return n
            }
        }
        assertEquals(CropOutputEncoding.JPEG, chooseCropOutputEncoding(counting))
        assertTrue("read $reads bytes", reads < 64 * 1024)
    }

    @Test
    fun `io exception mid stream keeps png`() {
        val failing = object : InputStream() {
            private var sent = 0
            override fun read(): Int {
                if (sent++ == 10) throw IOException("boom")
                return 0x50
            }
        }
        assertEquals(CropOutputEncoding.PNG, chooseCropOutputEncoding(failing))
    }

    @Test fun `truncated jpeg soi and invalid following marker stay png`() {
        assertEquals(CropOutputEncoding.PNG, choose(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        assertEquals(CropOutputEncoding.PNG, choose(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1)))
    }

    @Test fun `invalid png dimensions depth and methods stay png`() {
        val valid = ByteArray(13).also { it[3] = 1; it[7] = 1; it[8] = 8; it[9] = 2 }
        for (change in listOf<Pair<Int, Byte>>(3 to 0, 8 to 1, 10 to 1, 11 to 1, 12 to 2)) {
            val bad = valid.copyOf().also { it[change.first] = change.second }
            assertEquals(CropOutputEncoding.PNG, choose(png(chunk("IHDR", bad), idat())))
        }
    }

    @Test fun `missing invalid and duplicate palette stay png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(3), idat())))
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(3), chunk("PLTE", ByteArray(4)), idat())))
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(3, 1), chunk("PLTE", ByteArray(9)), idat())))
        val plte = chunk("PLTE", ByteArray(6))
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(3), plte, plte, idat())))
    }

    @Test fun `duplicate headers unknown critical chunks and animation stay png`() {
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(2), ihdr(6), idat())))
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(2), chunk("ABCD", ByteArray(0)), idat())))
        assertEquals(CropOutputEncoding.PNG, choose(png(ihdr(2), chunk("acTL", ByteArray(8)), idat())))
    }

    @Test fun `negative idat length is not opacity proof`() {
        val bad = png(ihdr(2)) + byteArrayOf(-1, -1, -1, -1) + "IDAT".toByteArray()
        assertEquals(CropOutputEncoding.PNG, choose(bad))
    }

    @Test
    fun `cancellation exception propagates`() {
        val cancelling = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")
        }
        assertThrows(CancellationException::class.java) {
            chooseCropOutputEncoding(cancelling)
        }
    }
}
