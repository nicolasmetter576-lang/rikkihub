package me.rerere.rikkihub.data.book.transfer

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 认 TXT 的编码。严格解码，不把坏字节替换成问号。 */
internal object TextDecoding {
    val CHARSETS = listOf("UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE")

    data class Decoded(val text: String, val charset: String)

    /** 按 [charset] 解；为 null 时自动认：BOM、UTF-8，再从 GB18030 / Big5 里挑常用字标点最多的。都不合格返回 null。 */
    fun decode(bytes: ByteArray, charset: String? = null): Decoded? {
        val bom = bomOf(bytes)
        if (charset != null) {
            val skip = if (bom != null && bom.first == charset) bom.second else 0
            return strict(bytes, charset, skip)?.let { Decoded(it, charset) }
        }
        if (bom != null) return strict(bytes, bom.first, bom.second)?.let { Decoded(it, bom.first) }
        strict(bytes, "UTF-8", 0)?.let { return Decoded(it, "UTF-8") }
        return listOf("GB18030", "Big5").mapNotNull { name -> strict(bytes, name, 0)?.let { Decoded(it, name) } }
            .maxByOrNull { candidate -> candidate.text.count { it in COMMON } }
    }

    private fun bomOf(bytes: ByteArray): Pair<String, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8" to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE" to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE" to 2
        else -> null
    }

    private fun strict(bytes: ByteArray, name: String, skip: Int): String? = runCatching {
        Charset.forName(name).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip))
            .toString()
    }.getOrNull()

    // 认错编码时这些字几乎不会大量出现
    private val COMMON = "，。？！、：；「」『』“”…的了一是不在他我這这個个們们來来說说".toSet()
}
