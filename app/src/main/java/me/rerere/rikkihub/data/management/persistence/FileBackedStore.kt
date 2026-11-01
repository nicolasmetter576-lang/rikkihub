package me.rerere.rikkihub.data.management.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * creator_management 文件后端的原子写原语：
 * tmp 文件（同目录）→ FileChannel.force → rename。读路径对尾部截断宽容
 * （append-only 日志到首个坏行止）。该目录整体排除普通备份
 * （CreatorBackupScope 排除清单 + mutation 请求）。
 */
internal class AtomicFileIO(private val json: Json = JsonInstant) {

    suspend fun <T> writeAtomic(file: File, serializer: KSerializer<T>, value: T) {
        val text = json.encodeToString(serializer, value)
        writeAtomicText(file, text)
    }

    suspend fun writeAtomicText(file: File, text: String) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).channel.use { ch ->
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                var written = 0
                while (written < bytes.size) {
                    written += ch.write(java.nio.ByteBuffer.wrap(bytes, written, bytes.size - written))
                }
                ch.force(true)
            }
            Files.move(
                tmp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            tmp.delete()
        }
    }

    suspend fun <T> readJson(file: File, serializer: KSerializer<T>): T? =
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext null
            // Existing corrupt metadata is NOT equivalent to absence. Losing a
            // participation/fence must keep startup closed, not hide an operation.
            json.decodeFromString(serializer, file.readText(StandardCharsets.UTF_8))
        }

    /**
     * append-only 行追加：写行 + force。返回写入前的文件长度（诊断用）。
     * 追加前修复尾部截断：若文件末字节不是 '\n'，先按行边界截掉残尾。
     */
    suspend fun appendLine(file: File, line: String): Unit = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        if (file.exists() && file.length() > 0) {
            // 修复截断残尾（进程死在 append 中途的情况）
            repairTruncatedTail(file)
        }
        FileOutputStream(file, true).channel.use { ch ->
            val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
            var written = 0
            while (written < bytes.size) {
                written += ch.write(java.nio.ByteBuffer.wrap(bytes, written, bytes.size - written))
            }
            ch.force(true)
        }
    }

    /** 逐行解析 NDJSON，容忍尾部坏行（截断安全）。 */
    suspend fun readLines(file: File): List<String> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        val text = file.readText(StandardCharsets.UTF_8)
        // Only a non-newline-terminated LAST fragment may be ignored. Complete
        // corrupt records still throw when decoded, never vanish from recovery.
        val lines = text.split('\n')
        (if (text.endsWith('\n')) lines else lines.dropLast(1)).filter { it.isNotBlank() }
    }

    /** 把文件截断到最后一个完整行边界（'\n' 之后）。 */
    private fun repairTruncatedTail(file: File) {
        val bytes = file.readBytes()
        if (bytes.isEmpty() || bytes.last() == '\n'.code.toByte()) return
        var lastNl = -1
        for (i in bytes.indices.reversed()) {
            if (bytes[i] == '\n'.code.toByte()) {
                lastNl = i
                break
            }
        }
        val keep = if (lastNl < 0) 0 else lastNl + 1
        java.io.RandomAccessFile(file, "rw").use { it.setLength(keep.toLong()) }
    }
}

/**
 * 通用 append-only NDJSON 账本（journal/audit 共用实现）。
 * 单文件 + 全局 mutex（管理面低并发，正确性优先）。
 */
internal class NdjsonLedger<T : Any>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val io: AtomicFileIO = AtomicFileIO(),
) {
    protected val mutex = Mutex()

    suspend fun appendEntry(entry: T) {
        mutex.withLock {
            io.appendLine(file, JsonInstant.encodeToString(serializer, entry))
        }
    }

    suspend fun readAll(): List<T> = mutex.withLock {
        io.readLines(file).map { line -> JsonInstant.decodeFromString(serializer, line) }
    }

    suspend fun readTail(limit: Int): List<T> = readAll().takeLast(limit)

    /** 整账重写（保留裁剪用）：先整体读，原子写回保留部分。 */
    suspend fun rewrite(keep: (T) -> Boolean) {
        mutex.withLock {
            val kept = io.readLines(file).map { line ->
                JsonInstant.decodeFromString(serializer, line)
            }.filter(keep)
            val text = kept.joinToString("") { JsonInstant.encodeToString(serializer, it) + "\n" }
            io.writeAtomicText(file, text)
        }
    }
}
