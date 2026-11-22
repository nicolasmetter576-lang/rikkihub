package me.rerere.tavern.card

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * 角色卡 PNG：卡数据放在 `tEXt` 块里，关键字 `chara`（V2）和 `ccv3`（V3），内容是 base64 的 UTF-8 JSON。
 *
 * 读：`ccv3` 优先，其次 `chara`，关键字不分大小写（`ST:src/character-card-parser.js`）。
 * 写：删掉旧的 `chara` / `ccv3`，在 `IEND` 前写入新的一对；其余块（含图像数据）一字节不动。
 * 这里不解码图像，所以任何合法的 PNG 都能当卡面。
 */
object PngCards {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private const val MAX_CARD_TEXT = 64 * 1024 * 1024

    class Chunk(val type: String, val data: ByteArray, val raw: ByteArray)

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }

    /** 读出卡 JSON 文本；没有卡数据抛 [CardFormatException]。 */
    fun read(png: ByteArray): String {
        val texts = chunks(png).filter { it.type == "tEXt" || it.type == "iTXt" }.mapNotNull { textChunk(it) }
        if (texts.isEmpty()) throw CardFormatException("The PNG has no text metadata, so it holds no character card")
        val pick = texts.firstOrNull { it.first.equals("ccv3", ignoreCase = true) }
            ?: texts.firstOrNull { it.first.equals("chara", ignoreCase = true) }
            ?: throw CardFormatException("The PNG has no character data (no chara or ccv3 block)")
        val decoded = try {
            Base64.getDecoder().decode(pick.second.filterNot { it.isWhitespace() })
        } catch (e: IllegalArgumentException) {
            throw CardFormatException("The character data in the PNG is not valid base64", e)
        }
        if (decoded.isEmpty()) throw CardFormatException("The character data in the PNG is empty")
        return String(decoded, Charsets.UTF_8)
    }

    /** 写入卡：`chara` 放 [v2Json]，`ccv3` 放 [v3Json]。 */
    fun write(png: ByteArray, v2Json: String, v3Json: String): ByteArray {
        val all = chunks(png)
        val out = ByteArrayOutputStream(png.size + v2Json.length * 2 + v3Json.length * 2)
        out.write(SIGNATURE)
        for (c in all) {
            if (c.type == "IEND") {
                out.write(textChunk("chara", v2Json))
                out.write(textChunk("ccv3", v3Json))
            }
            if (isCardChunk(c)) continue
            out.write(c.raw)
        }
        return out.toByteArray()
    }

    /** 去掉卡数据，只剩图像（给“只要图片”的场景用）。 */
    fun stripCard(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(png.size)
        out.write(SIGNATURE)
        for (c in chunks(png)) {
            if (isCardChunk(c)) continue
            out.write(c.raw)
        }
        return out.toByteArray()
    }

    private fun isCardChunk(c: Chunk): Boolean {
        if (c.type != "tEXt" && c.type != "iTXt") return false
        val key = textChunk(c)?.first?.lowercase() ?: return false
        return key == "chara" || key == "ccv3"
    }

    /** 逐块切开；块头长度越界、没有 IEND 都算坏文件。 */
    fun chunks(png: ByteArray): List<Chunk> {
        if (!isPng(png)) throw CardFormatException("Not a PNG file")
        val result = ArrayList<Chunk>()
        var pos = 8
        var sawEnd = false
        while (pos + 12 <= png.size) {
            val len = be32(png, pos).toLong() and 0xFFFFFFFFL
            if (len > MAX_CARD_TEXT || pos + 12 + len > png.size) throw CardFormatException("The PNG is truncated or damaged")
            val length = len.toInt()
            val type = String(png, pos + 4, 4, Charsets.ISO_8859_1)
            val data = png.copyOfRange(pos + 8, pos + 8 + length)
            val raw = png.copyOfRange(pos, pos + 12 + length)
            result += Chunk(type, data, raw)
            pos += 12 + length
            if (type == "IEND") { sawEnd = true; break }
        }
        if (!sawEnd) throw CardFormatException("The PNG has no IEND block")
        return result
    }

    /** `tEXt`：关键字 NUL 文本（Latin-1）；`iTXt`（不压缩）：关键字 NUL 标志 方法 语言 NUL 译名 NUL UTF-8 文本。 */
    private fun textChunk(c: Chunk): Pair<String, String>? {
        val nul = c.data.indexOf(0.toByte())
        if (nul <= 0) return null
        val key = String(c.data, 0, nul, Charsets.ISO_8859_1)
        if (c.type == "tEXt") return key to String(c.data, nul + 1, c.data.size - nul - 1, Charsets.ISO_8859_1)
        if (c.data.size < nul + 3 || c.data[nul + 1].toInt() != 0) return null // 压缩的 iTXt 不读
        var p = nul + 3
        repeat(2) {
            val n = c.data.indexOfFrom(0.toByte(), p)
            if (n < 0) return null
            p = n + 1
        }
        return key to String(c.data, p, c.data.size - p, Charsets.UTF_8)
    }

    private fun textChunk(keyword: String, jsonText: String): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(jsonText.toByteArray(Charsets.UTF_8))
        val body = keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + b64.toByteArray(Charsets.ISO_8859_1)
        return buildChunk("tEXt", body)
    }

    private fun buildChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 12)
        out.write(int32(data.size))
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        out.write(int32(crc.value.toInt()))
        return out.toByteArray()
    }

    /**
     * 一张纯色渐变的占位 PNG（卡没有卡面又要导出 PNG 时用）。颜色由 [seed] 决定，同一个名字总是同一张图。
     */
    fun placeholder(seed: String, width: Int = 400, height: Int = 600): ByteArray {
        val h = seed.hashCode()
        val r0 = 60 + (h and 0x7F)
        val g0 = 60 + ((h shr 7) and 0x7F)
        val b0 = 60 + ((h shr 14) and 0x7F)
        val raw = ByteArray((width * 3 + 1) * height)
        var i = 0
        for (y in 0 until height) {
            raw[i++] = 0 // filter: none
            val t = y * 100 / height
            for (x in 0 until width) {
                raw[i++] = (r0 + t * (255 - r0) / 300).toByte()
                raw[i++] = (g0 + t * (255 - g0) / 300).toByte()
                raw[i++] = (b0 + t * (255 - b0) / 300).toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val ihdr = int32(width) + int32(height) + byteArrayOf(8, 2, 0, 0, 0)
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(buildChunk("IHDR", ihdr))
        out.write(buildChunk("IDAT", z.toByteArray()))
        out.write(buildChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun be32(b: ByteArray, p: Int): Int =
        (b[p].toInt() and 0xFF shl 24) or (b[p + 1].toInt() and 0xFF shl 16) or (b[p + 2].toInt() and 0xFF shl 8) or (b[p + 3].toInt() and 0xFF)

    private fun int32(v: Int): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun ByteArray.indexOfFrom(value: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == value) return i
        return -1
    }
}
