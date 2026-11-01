package me.rerere.rikkihub.ui.components.ai

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import kotlin.coroutines.cancellation.CancellationException

/**
 * Output encoding for cropped images.
 *
 * Policy: JPEG quality 90 when the source is proven opaque; PNG whenever the
 * source may carry transparency or the format cannot be bounded-verified.
 */
internal enum class CropOutputEncoding(
    val extension: String,
    val mimeType: String,
    val quality: Int,
) {
    JPEG("jpg", "image/jpeg", 90),
    PNG("png", "image/png", 100),
}

/**
 * Chooses the crop output encoding by inspecting a bounded prefix of [input].
 *
 * - Never trusts file names or caller-declared MIME types.
 * - Never decodes or samples pixels; recognized JPEG signatures cannot carry
 *   alpha. For PNG only IHDR metadata and a pre-IDAT tRNS
 *   chunk can prove opacity; strict length/CRC parsing is applied and anything
 *   malformed, truncated, over-budget, or unknown falls back to PNG.
 * - Reads at most [maxProbeBytes] bytes, never allocates attacker-declared
 *   chunk sizes beyond that budget, and never reads the whole image.
 * - Does not close [input]; the caller owns its lifecycle.
 * - [IOException] (including short reads / EOF) degrades to PNG, but
 *   [CancellationException] and fatal errors propagate untouched.
 */
internal fun chooseCropOutputEncoding(
    input: InputStream,
    maxProbeBytes: Int = 64 * 1024,
): CropOutputEncoding {
    if (maxProbeBytes < 3) return CropOutputEncoding.PNG
    return try {
        val probe = Probe(input, maxProbeBytes.coerceAtMost(64 * 1024))
        val b0 = probe.readByte()
        val b1 = probe.readByte()
        when {
            // Require a following marker too; a two-byte/truncated SOI is uncertain.
            b0 == 0xFF && b1 == 0xD8 ->
                if (probe.readByte() == 0xFF) CropOutputEncoding.JPEG else CropOutputEncoding.PNG
            // PNG signature start (89 50 'P'); full signature checked in probePng.
            b0 == 0x89 && b1 == 0x50 -> probePng(probe)
            // Unknown / unsupported format (WebP, GIF, HEIC, ...): cannot prove
            // opacity without a full parser -> conservative PNG.
            else -> CropOutputEncoding.PNG
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        CropOutputEncoding.PNG
    }
}

/**
 * Bounded big-endian reader over [input]. Throws [EOFException] on premature
 * EOF (handled upstream as conservative PNG) and refuses to advance past the
 * byte budget via [BudgetExceededException].
 */
private class Probe(
    private val input: InputStream,
    private val maxBytes: Int,
) {
    var consumed = 0
        private set

    class BudgetExceededException : IOException("probe byte budget exceeded")

    fun readByte(): Int {
        if (consumed + 1 > maxBytes) throw BudgetExceededException()
        val b = input.read()
        if (b < 0) throw EOFException()
        consumed += 1
        return b
    }

    /** Fills [dst] completely, tolerating short reads; throws on EOF/budget. */
    fun readFully(dst: ByteArray) {
        var offset = 0
        while (offset < dst.size) {
            if (consumed + (dst.size - offset) > maxBytes) throw BudgetExceededException()
            val n = input.read(dst, offset, dst.size - offset)
            if (n < 0) throw EOFException()
            if (n == 0) {
                // Progress-stall guard: fall back to single-byte reads.
                dst[offset] = readByte().toByte()
                offset += 1
            } else {
                consumed += n
                offset += n
            }
        }
    }

    fun readInt(): Int {
        val buf = ByteArray(4)
        readFully(buf)
        return (buf[0].toInt() and 0xFF shl 24) or
            (buf[1].toInt() and 0xFF shl 16) or
            (buf[2].toInt() and 0xFF shl 8) or
            (buf[3].toInt() and 0xFF)
    }

    /** Remaining budget after reserving [extra] bytes (e.g. a CRC trailer). */
    fun remainingAfter(extra: Int): Long = maxBytes.toLong() - consumed - extra
}

private class MalformedPngException : IOException("malformed png")

private val PNG_SIGNATURE_TAIL = byteArrayOf(
    0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // "NG\r\n\x1a\n" after 89 50
)

private const val CHUNK_IHDR = 0x49484452 // "IHDR"
private const val CHUNK_IDAT = 0x49444154 // "IDAT"
private const val CHUNK_TRNS = 0x74524E53 // "tRNS"
private const val CHUNK_IEND = 0x49454E44 // "IEND"
private const val CHUNK_PLTE = 0x504C5445 // "PLTE"
private const val CHUNK_ACTL = 0x6163544C // "acTL" (animation is not proven opaque by still-image metadata)

// PNG colour types that can be proven opaque without a tRNS chunk.
private const val COLOR_GRAYSCALE = 0
private const val COLOR_RGB = 2
private const val COLOR_PALETTE = 3

// Colour types with a mandatory alpha channel.
private const val COLOR_GRAYSCALE_ALPHA = 4
private const val COLOR_RGBA = 6

private fun probePng(probe: Probe): CropOutputEncoding {
    val tail = ByteArray(PNG_SIGNATURE_TAIL.size)
    probe.readFully(tail)
    if (!tail.contentEquals(PNG_SIGNATURE_TAIL)) throw MalformedPngException()

    var colorType = -1
    var bitDepth = 0
    var seenPalette = false
    while (true) {
        val length = probe.readInt()
        val type = probe.readInt()
        if (length < 0 || !validChunkType(type)) return CropOutputEncoding.PNG

        if (colorType < 0) {
            if (type != CHUNK_IHDR || length != 13 || length.toLong() > probe.remainingAfter(4)) {
                return CropOutputEncoding.PNG
            }
            val data = ByteArray(length)
            probe.readFully(data)
            if (crc32(type, data) != probe.readInt()) return CropOutputEncoding.PNG
            colorType = data[9].toInt() and 0xFF
            bitDepth = data[8].toInt() and 0xFF
            val depths = when (colorType) {
                COLOR_GRAYSCALE -> setOf(1, 2, 4, 8, 16)
                COLOR_RGB, COLOR_GRAYSCALE_ALPHA, COLOR_RGBA -> setOf(8, 16)
                COLOR_PALETTE -> setOf(1, 2, 4, 8)
                else -> emptySet()
            }
            if (readPositiveDimension(data, 0) <= 0 || readPositiveDimension(data, 4) <= 0 ||
                bitDepth !in depths || data[10].toInt() != 0 || data[11].toInt() != 0 ||
                data[12].toInt() !in 0..1
            ) return CropOutputEncoding.PNG
            if (colorType == COLOR_GRAYSCALE_ALPHA || colorType == COLOR_RGBA) return CropOutputEncoding.PNG
            continue
        }

        // A standard PNG's tRNS must precede IDAT. We verify its metadata prefix,
        // not image pixels/compressed data. Invalid length or absent required palette
        // cannot be taken as opacity proof, even though no payload allocation is needed.
        if (type == CHUNK_IDAT) return if (colorType == COLOR_PALETTE && !seenPalette) {
            CropOutputEncoding.PNG
        } else CropOutputEncoding.JPEG
        if (type == CHUNK_TRNS || type == CHUNK_IEND || type == CHUNK_ACTL || type == CHUNK_IHDR) {
            return CropOutputEncoding.PNG
        }
        if (type == CHUNK_PLTE) {
            if (seenPalette || colorType == COLOR_GRAYSCALE || length !in 3..768 || length % 3 != 0 ||
                (colorType == COLOR_PALETTE && length / 3 > (1 shl bitDepth))
            ) return CropOutputEncoding.PNG
        } else if ((type ushr 24) and 0x20 == 0) {
            return CropOutputEncoding.PNG // unknown critical chunk
        }
        if (length.toLong() > probe.remainingAfter(4)) return CropOutputEncoding.PNG
        val data = ByteArray(length)
        probe.readFully(data)
        if (crc32(type, data) != probe.readInt()) return CropOutputEncoding.PNG
        if (type == CHUNK_PLTE) seenPalette = true
    }
}

private fun readPositiveDimension(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 255) shl 24) or ((data[offset + 1].toInt() and 255) shl 16) or
        ((data[offset + 2].toInt() and 255) shl 8) or (data[offset + 3].toInt() and 255)

private fun validChunkType(type: Int): Boolean {
    val chars = listOf(type ushr 24, type ushr 16 and 255, type ushr 8 and 255, type and 255)
    return chars.all { it in 65..90 || it in 97..122 } && chars[2] in 65..90
}

private fun crc32(type: Int, data: ByteArray): Int {
    val crc = CRC32()
    crc.update(
        byteArrayOf(
            (type ushr 24).toByte(),
            (type ushr 16).toByte(),
            (type ushr 8).toByte(),
            type.toByte(),
        )
    )
    crc.update(data)
    return crc.value.toInt()
}
