package me.rerere.ai.provider.providers.devin

import java.util.Base64

/** 测试共享工具: golden 文件加载 + hex/base64 编解码 */
internal object DevinTestGolden {
    private val raw: Map<String, String> by lazy {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/devin/golden.txt")
        ) { "missing test resource /devin/golden.txt" }
            .bufferedReader().readText()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { line ->
                line.substringBefore('=') to line.substringAfter('=')
            }
    }

    /** hex 编码值 → 字节 */
    operator fun get(key: String): ByteArray =
        checkNotNull(raw[key]) { "no golden value for $key" }.hexToBytes()

    /** 原样字符串值 (例如本身就是 hex 的 fingerprint) */
    fun string(key: String): String =
        checkNotNull(raw[key]) { "no golden value for $key" }

    fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { i ->
            ((Character.digit(this[2 * i], 16) shl 4) or Character.digit(this[2 * i + 1], 16)).toByte()
        }
    }

    fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0xF])
        }
        return sb.toString()
    }

    fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun b64decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
