package me.rerere.rikkihub.data.book.history

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File
import java.io.IOException

private const val TAG = "BookTurnTable"

/** 一轮 = 一条助手回复。 */
data class TurnKey(val conversationId: String, val messageId: String)

/**
 * 一轮改了作品里哪些文件（`history/turns/<会话>/<消息>.json`）。[files] 按历史键（改名不换键）。
 * 状态是算出来的（[status]）：已归档；所有文件都处理过 = 已审阅；否则待审。[finishedAt] 为 null 的还在进行（界面不显示）。
 */
@Serializable
data class TurnRecord(
    val conversationId: String,
    val messageId: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val files: Map<String, TurnFile> = emptyMap(),
    val archivedAt: Long? = null,
    /** 打开过审阅，或在作品页横幅上点过 ×。 */
    val seenAt: Long? = null,
    /** 最后一个文件处理完的时间。 */
    val reviewedAt: Long? = null,
    /** 完成时换回了几处。 */
    val reverted: Int = 0,
    /** 命令改动没能归到这一轮的原因。 */
    val commandsUnattributed: Unattributed? = null,
) {
    val key: TurnKey get() = TurnKey(conversationId, messageId)

    val status: TurnStatus
        get() = when {
            archivedAt != null -> TurnStatus.ARCHIVED
            files.values.all { it.done } -> TurnStatus.REVIEWED
            else -> TurnStatus.PENDING
        }
}

enum class TurnStatus { PENDING, REVIEWED, ARCHIVED }

/** 一轮里的一个文件：[before] 改前、[after] 改后（全文对象的哈希）。 */
@Serializable
data class TurnFile(
    /** 现在的路径（改名时更新）。 */
    val path: String,
    val before: String? = null,
    val after: String? = null,
    /** 这一轮命令收尾时系统放回锁定内容写下的样子。 */
    val restoredSha: String? = null,
    val created: Boolean = false,
    /** 这一轮删掉的：回收站里的名字。 */
    val trashed: String? = null,
    /** 删掉了，但没能放进回收站。 */
    val deleted: Boolean = false,
    val renamedFrom: String? = null,
    val missing: TurnMissing? = null,
    /** 这一轮期间这个文件还有别人的写入。 */
    val mixed: Boolean = false,
    /** 只改了缩进或空行（没有能逐处审阅的块）。 */
    val layoutOnly: Boolean = false,
    /** 这个文件第一次记进这一轮的时间（判断“之后还有别人的写入”）。 */
    val since: Long = 0,
    val done: Boolean = false,
    val undo: TurnUndo? = null,
)

@Serializable
enum class TurnMissing {
    /** 改前那一版没能留下（空间不足）。 */
    @SerialName("before") BEFORE,

    /** 改后那一版没能留下（被清理、空间不足）。 */
    @SerialName("after") AFTER,

    /** 你在历史里删了那一版。 */
    @SerialName("deleted_version") DELETED_VERSION,
}

@Serializable
enum class Unattributed {
    /** 同一本书同时还有别的命令在跑。 */
    @SerialName("overlap") OVERLAP,

    /** 这期间开着终端。 */
    @SerialName("terminal") TERMINAL,
}

/** 完成审阅时做了什么，撤销用。 */
@Serializable
data class TurnUndo(
    val action: Action,
    /** CONTENT：审阅前的样子。 */
    val beforeSha: String? = null,
    /** CONTENT：写下的样子（撤销时按它核对版本）；RESTORED_DELETED：放回时的样子。 */
    val writtenSha: String? = null,
    /** DELETED_CREATED：删进回收站的名字。 */
    val trashName: String? = null,
    /** RESTORED_DELETED：实际放回的路径。 */
    val path: String? = null,
    /** DELETED_CREATED：被删的目录项与它前一项（撤销时接回原位）。 */
    val chapter: UndoChapter? = null,
    val afterChapterId: String? = null,
) {
    @Serializable
    enum class Action {
        @SerialName("content") CONTENT,
        @SerialName("deleted_created") DELETED_CREATED,
        @SerialName("restored_deleted") RESTORED_DELETED,
    }
}

/** 删掉的目录项（标题、状态、简介等都要接回来）。 */
@Serializable
data class UndoChapter(
    val id: String,
    val path: String,
    val title: String? = null,
    val status: String,
    val synopsis: String = "",
    val targetWords: Int = 0,
    val extra: JsonObject = JsonObject(emptyMap()),
)

/** 一条回复现在在不在：当前分支、别的分支（重新生成、切换了候选）、会话在回收站里、已经没了。 */
enum class MessagePresence { CURRENT, OTHER_BRANCH, HIDDEN, GONE }

/** `history/turns/state.json`：不再提示的对话，与上次清理时的两种时钟（判断墙钟跳变）。 */
@Serializable
data class TurnState(
    val muted: Set<String> = emptySet(),
    val cleanedWall: Long? = null,
    val cleanedMono: Long? = null,
)

/**
 * `history/turns/` 的读写：每轮一个文件，先写临时文件再改名（不 fsync，被杀最多丢一份，收尾时能补）。
 * 第一次用时把目录读进内存，之后增量更新；解码不了的记录当作没有。调用方持有这本书的历史锁。
 */
internal class BookTurnTable(dir: File) {
    private val root = File(dir, "turns")
    private val stateFile = File(root, "state.json")
    private var records: HashMap<TurnKey, TurnRecord>? = null
    private var state: TurnState? = null

    fun all(): Collection<TurnRecord> = load().values

    fun get(key: TurnKey): TurnRecord? = load()[key]

    fun put(record: TurnRecord) {
        val records = load()
        if (records[record.key] == record) return
        val file = fileOf(record.key) ?: return
        file.parentFile!!.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(JsonInstant.encodeToString(record))
        if (!temp.renameTo(file)) throw IOException("Cannot save a turn record")
        records[record.key] = record
    }

    fun remove(key: TurnKey) {
        load().remove(key)
        fileOf(key)?.let { file ->
            file.delete()
            file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }
    }

    /** 清空历史：记录全删，[state] 留着。 */
    fun clear() {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { it.deleteRecursively() }
        records = HashMap()
    }

    fun state(): TurnState = state ?: (runCatching { JsonInstant.decodeFromString<TurnState>(stateFile.readText()) }.getOrNull() ?: TurnState()).also { state = it }

    fun writeState(next: TurnState) {
        if (next == state()) return
        root.mkdirs()
        val temp = File(root, ".state.json.tmp")
        temp.writeText(JsonInstant.encodeToString(next))
        if (!temp.renameTo(stateFile)) throw IOException("Cannot save the turn state")
        state = next
    }

    private fun load(): HashMap<TurnKey, TurnRecord> = records ?: HashMap<TurnKey, TurnRecord>().also { loaded ->
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach { conversation ->
            conversation.listFiles().orEmpty().filter { it.name.endsWith(".json") && !it.name.startsWith(".") }.forEach { file ->
                val record = runCatching { JsonInstant.decodeFromString<TurnRecord>(file.readText()) }
                    .onFailure { Log.w(TAG, "Ignoring a damaged turn record", it) }.getOrNull()
                if (record != null && fileOf(record.key) == file) loaded[record.key] = record
            }
        }
        records = loaded
    }

    /** 会话、消息 id 只用作文件名里的安全字符；不像 id 的不记（返回 null）。 */
    private fun fileOf(key: TurnKey): File? {
        if (!SAFE.matches(key.conversationId) || !SAFE.matches(key.messageId)) return null
        return File(root, "${key.conversationId}/${key.messageId}.json")
    }

    companion object {
        private val SAFE = Regex("[A-Za-z0-9_-]{1,128}")

        fun accepts(key: TurnKey): Boolean = SAFE.matches(key.conversationId) && SAFE.matches(key.messageId)
    }
}
