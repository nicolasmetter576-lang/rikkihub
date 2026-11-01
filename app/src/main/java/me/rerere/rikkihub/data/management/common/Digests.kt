package me.rerere.rikkihub.data.management.common

import java.security.MessageDigest

/** canonicalDigest / readSet 指纹共用的 sha256 helper（hex 小写）。 */
internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/**
 * 提案 canonical digest 输入：逐字段拼接（长度前缀防歧义），不依赖序列化
 * 库的输出顺序。任何参与批准的字段改动都会改变 digest —— 这正是
 * “批准 exact digest，改变 patch/基线/影响即失效”的实现。
 */
internal fun canonicalDigestOf(fields: List<String>): String {
    val sb = StringBuilder()
    for (f in fields) {
        sb.append(f.length).append(':').append(f).append('\n')
    }
    return sha256Hex(sb.toString())
}
