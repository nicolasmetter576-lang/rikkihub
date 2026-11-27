package me.rerere.rikkihub.data.book.history

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File
import java.io.IOException

private const val TAG = "BookLockTable"

/** 一条锁定提醒（作品页横幅、对话卡片）。[key] 是历史键，[path] 是当时的路径。 */
@Serializable
data class LockNotice(
    val id: String,
    val key: String,
    val path: String,
    val kind: Kind,
    val at: Long,
    val conversationId: String? = null,
    val messageId: String? = null,
    /** 改之前的稿子（放回、比较用）。 */
    val beforeSha: String? = null,
    /** 被改成的样子（“保留这次修改”用）。 */
    val changedSha: String? = null,
    /** 已经在哪个 CLI 回合交代过（交代过就不再放进 `<book_locks>`）。 */
    val delivered: Boolean = false,
    /** 你打开过比较（已放回的提醒之后自动归档）。 */
    val seen: Boolean = false,
    /** 同一文件同一种更早的次数。 */
    val earlier: Int = 0,
) {
    @Serializable
    enum class Kind {
        /** 命令行或 CLI 改了锁定内容，已自动放回。 */
        @SerialName("restored") RESTORED,

        /** 命令行或 CLI 改了锁定内容，没能自动放回（认不出、超出上限）。 */
        @SerialName("not_restored") NOT_RESTORED,

        /** 空间不足没能留底，为了不丢这次修改没有放回。 */
        @SerialName("no_space") NO_SPACE,

        /** 来源不确定的改动（终端、回到前台才发现、作品模式关闭期间），可能是你自己改的。 */
        @SerialName("changed") CHANGED,

        /** 段落锁在稿子里已经找不到了。 */
        @SerialName("lost") LOST,

        /** 锁的底稿丢了，文件也变了。 */
        @SerialName("base_missing") BASE_MISSING,

        /** 网页端或别的 App 删除了锁定的章节（连锁进了回收站）。 */
        @SerialName("deleted") DELETED,
    }

    /** 只提醒、需要你决定的那几类（不会自动归档）。 */
    val needsDecision: Boolean get() = kind != Kind.RESTORED
}

/** 已处理过的状态：这个文件是这个样子时不再提醒。 */
@Serializable
data class HandledLock(val key: String, val sha: String)

/** `history/locks.json` 的全部内容。 */
@Serializable
data class LockTableData(
    val locks: Map<String, FileLocks> = emptyMap(),
    val pinned: Set<String> = emptySet(),
    val notices: List<LockNotice> = emptyList(),
    val handled: List<HandledLock> = emptyList(),
    /** 已经提示过“这一章已锁定”的历史键。 */
    val tips: Set<String> = emptySet(),
)

/** 锁表读坏了（`.bak` 也坏）：这期间不能改锁，历史清理也暂停。 */
class LockTableBrokenException : IOException("The lock table cannot be read")

/**
 * `history/locks.json` 的读写：先写临时文件再改名，写之前把上一份好的留成 `locks.json.bak`。
 * 读坏时退回 `.bak`；两份都坏就进入“损坏”状态：[read] 给空表（检查按没有锁放行），[write] 一律拒绝，免得把旧锁覆盖掉。
 * 调用方持有这本书的历史锁。
 */
internal class BookLockTable(private val dir: File) {
    private val file = File(dir, "locks.json")
    private val backup = File(dir, "locks.json.bak")
    private var cached: LockTableData? = null

    var broken: Boolean = false
        private set

    fun read(): LockTableData = cached ?: load().also { cached = it }

    fun write(data: LockTableData) {
        if (broken) throw LockTableBrokenException()
        if (data == cached) return
        dir.mkdirs()
        if (file.isFile) file.copyTo(backup, overwrite = true)
        val temp = File(dir, ".locks.json.tmp")
        temp.writeText(JsonInstant.encodeToString(data))
        if (!temp.renameTo(file)) throw IOException("Cannot save the lock table")
        cached = data
    }

    /** 重新读一遍（“重试”）。 */
    fun reload(): LockTableData {
        cached = null
        broken = false
        return read()
    }

    private fun load(): LockTableData {
        if (!file.exists() && !backup.exists()) return LockTableData()
        decode(file)?.let { return it }
        Log.w(TAG, "locks.json is damaged, falling back to the backup")
        decode(backup)?.let { return it }
        broken = true
        return LockTableData()
    }

    private fun decode(source: File): LockTableData? =
        runCatching { JsonInstant.decodeFromString<LockTableData>(source.readText()) }.getOrNull()
}
