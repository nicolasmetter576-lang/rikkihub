package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * 编辑器未保存草稿落盘器: Bundle 只存随机 UUID token (几十字节), 全文+选区
 * 在 save 回调里同步写进 noBackupFilesDir/workspace_editor_drafts/<token>.draft
 * (同目录 <token>.tmp + rename 原子替换), 不写工作区原件、不进系统备份.
 * token 严格 UUID 形态防目录穿越; 结构损坏/IO 失败返回 null, 由页面回退磁盘加载.
 *
 * 格式 (DataStreams):
 *   int MAGIC | int VERSION | int keyLen + UTF-8 fileKey |
 *   long textLen + UTF-8 全文 |
 *   [v2+] bool hasBaseline (+ long len + UTF-8 原文 | int len + revision) |
 *   [v3+] bool hasOpened (+ long len + UTF-8 打开时的磁盘文本, 「还原」目标) |
 *   int selStart | int selEnd (UTF-16 字符偏移选区)
 */
internal class WorkspaceEditorDraftStore(
    private val directory: File,
    private val onError: (Throwable) -> Unit = {},
) {
    class RestoredDraft(val fileKey: String, val text: String, val selStart: Int, val selEnd: Int,
        val originalText: String? = null, val revision: String? = null, val openedText: String? = null)

    fun newToken(): String = UUID.randomUUID().toString()

    /** 严格 UUID 形态; token 仅作文件名成分, 借此防 `../` 目录穿越. */
    fun isValidToken(token: String?): Boolean =
        token != null && TOKEN_PATTERN.matches(token)

    fun hasDraft(token: String?): Boolean =
        token != null && isValidToken(token) && draftFile(token).isFile

    /**
     * 同步写全文草稿 (在 save 回调返回前完成, 无 async 竞态); 主线程 O(全文) 磁盘写
     * 是 Bundle 体积安全的代价. IO 失败记录并返回 null, 不抛异常毁 Activity.
     */
    fun save(
        fileKey: String,
        text: CharSequence,
        selStart: Int,
        selEnd: Int,
        existingToken: String? = null,
        originalText: String? = null,
        revision: String? = null,
        openedText: String? = null,
    ): String? {
        if (existingToken != null && !isValidToken(existingToken)) return null
        val token = existingToken ?: newToken()
        var tmp: File? = null
        val target = draftFile(token)
        try {
            // A stable token lets even an older Android saved Bundle recover the newest snapshot.
            // Never overwrite another file's draft when a caller supplies an existing token.
            if (target.isFile) {
                val previous = restore(token)
                if (previous == null && target.exists()) return null
                if (previous != null && previous.fileKey != fileKey) return null
            }
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("draft dir unavailable: $directory")
            }
            val created = File.createTempFile(".$token-", ".tmp", directory)
            tmp = created
            FileOutputStream(created).use { fos ->
                val out = DataOutputStream(BufferedOutputStream(fos))
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                writeString(out, fileKey)
                val textBytes = text.toString().toByteArray(Charsets.UTF_8)
                out.writeLong(textBytes.size.toLong())
                out.write(textBytes)
                val validBaseline = originalText != null && revision?.matches(REVISION_PATTERN) == true
                out.writeBoolean(validBaseline)
                if (validBaseline) {
                    val originalBytes = originalText!!.toByteArray(Charsets.UTF_8)
                    out.writeLong(originalBytes.size.toLong())
                    out.write(originalBytes)
                    writeString(out, revision!!)
                }
                out.writeBoolean(openedText != null)
                if (openedText != null) {
                    val openedBytes = openedText.toByteArray(Charsets.UTF_8)
                    out.writeLong(openedBytes.size.toLong())
                    out.write(openedBytes)
                }
                out.writeInt(selStart)
                out.writeInt(selEnd)
                out.flush()
                fos.fd.sync()
            }
            Files.move(created.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            onError(e)
            return null
        } finally {
            tmp?.delete()
        }
        return token
    }

    /**
     * 按 token 读回草稿. token 非法/无文件/结构损坏/读异常 → null;
     * 判定为损坏的文件顺手清掉. fileKey 归属校验由调用方做 (不匹配只拒绝, 不删).
     */
    fun restore(token: String): RestoredDraft? {
        if (!isValidToken(token)) return null
        val file = draftFile(token)
        if (!file.isFile) return null
        return try {
            FileInputStream(file).use { fis ->
                val inp = DataInputStream(BufferedInputStream(fis))
                if (inp.readInt() != MAGIC) return discard(file)
                val version = inp.readInt()
                if (version !in 1..VERSION) return discard(file)
                val keyBytes = readBytes(inp, MAX_KEY_BYTES) ?: return discard(file)
                val textLen = inp.readLong()
                if (textLen < 0 || textLen > MAX_TEXT_BYTES || textLen > file.length()) {
                    return discard(file)
                }
                val textBytes = ByteArray(textLen.toInt())
                inp.readFully(textBytes)
                var originalText: String? = null
                var revision: String? = null
                if (version >= 2 && inp.readBoolean()) {
                    val originalLength = inp.readLong()
                    if (originalLength < 0 || originalLength > MAX_TEXT_BYTES || originalLength > file.length()) return discard(file)
                    val originalBytes = ByteArray(originalLength.toInt())
                    inp.readFully(originalBytes)
                    originalText = String(originalBytes, Charsets.UTF_8)
                    val revisionBytes = readBytes(inp, 64) ?: return discard(file)
                    revision = String(revisionBytes, Charsets.UTF_8)
                    if (!REVISION_PATTERN.matches(revision)) return discard(file)
                }
                var openedText: String? = null
                if (version >= 3 && inp.readBoolean()) {
                    val openedLength = inp.readLong()
                    if (openedLength < 0 || openedLength > MAX_TEXT_BYTES || openedLength > file.length()) return discard(file)
                    val openedBytes = ByteArray(openedLength.toInt())
                    inp.readFully(openedBytes)
                    openedText = String(openedBytes, Charsets.UTF_8)
                }
                val selStart = inp.readInt()
                val selEnd = inp.readInt()
                if (inp.read() != -1) return discard(file)
                val text = String(textBytes, Charsets.UTF_8)
                // 选区是 UTF-16 字符偏移, 必须落在全文范围内, 越界视为损坏
                // Reversed TextRange is valid (selection dragged right-to-left).
                if (selStart !in 0..text.length || selEnd !in 0..text.length) {
                    return discard(file)
                }
                RestoredDraft(String(keyBytes, Charsets.UTF_8), text, selStart, selEnd, originalText, revision, openedText)
            }
        } catch (e: EOFException) {
            onError(e)
            discard(file)
        } catch (e: Exception) {
            // Temporary read failure is not proof of corrupt data; retain it for later recovery.
            onError(e)
            null
        }
    }

    /** 只删 token 绑定的草稿/临时文件; 非法 token 一律不碰盘. 失败仅记录. */
    fun delete(token: String?) {
        if (token == null || !isValidToken(token)) return
        try {
            draftFile(token).delete()
            tmpFile(token).delete()
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun discard(file: File): RestoredDraft? {
        try {
            file.delete()
        } catch (e: Exception) {
            onError(e)
        }
        return null
    }

    private fun draftFile(token: String) = File(directory, "$token.draft")
    private fun tmpFile(token: String) = File(directory, "$token.tmp")

    private fun writeString(out: DataOutputStream, value: CharSequence) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readBytes(inp: DataInputStream, maxLen: Int): ByteArray? {
        val len = inp.readInt()
        if (len < 0 || len > maxLen) return null
        val bytes = ByteArray(len)
        inp.readFully(bytes)
        return bytes
    }

    companion object {
        private const val MAGIC = 0x524B4431 // "RKD1"
        private const val VERSION = 3
        private val REVISION_PATTERN = Regex("[0-9a-f]{64}")
        private const val MAX_KEY_BYTES = 1 shl 20
        private const val MAX_TEXT_BYTES = 256L * 1024 * 1024

        private val TOKEN_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
    }
}

/**
 * 草稿内容指纹 (SHA-256 hex, 与落盘的 UTF-8 字节同源): save 去重判定用,
 * 不能再用 32bit 滚动 hash —— "Aa"/"BB" 这类碰撞会让已修改草稿错误复用旧 token.
 */
internal fun workspaceEditorDraftFingerprint(text: CharSequence): String {
    val bytes = MessageDigest.getInstance("SHA-256")
        .digest(text.toString().toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    val hex = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        hex[i * 2] = digits[(bytes[i].toInt() shr 4) and 0xF]
        hex[i * 2 + 1] = digits[bytes[i].toInt() and 0xF]
    }
    return String(hex)
}
