package me.rerere.rikkihub.data.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.workspace.WorkspaceFileRevisionConflictException
import java.io.File

data class BookReplaceQuery(val pairs: List<ReplacePair>, val ignoreCase: Boolean, val paths: List<String>)

/** 一个文件里的命中；[revision] 是搜索时的版本，写入时凭它确认文件没被改过。 */
data class BookReplaceFile(val path: String, val revision: String, val hits: List<ReplaceHit>)

/** [unreadable]：没能读的文件（找不到、不是文本、太大）。 */
data class BookReplacePreview(val files: List<BookReplaceFile>, val unreadable: List<String>)

/** 要替换的一个文件；[starts] 为 null = 全部命中。 */
data class BookReplaceTarget(val path: String, val revision: String, val starts: Set<Int>?)

data class BookReplaceResult(
    /** 撤销用；一处都没换时为 null。 */
    val undoId: String?,
    val replaced: Int,
    val changed: List<String>,
    /** 搜索之后又被改过，没动。 */
    val conflicts: List<String>,
    val failed: List<String>,
)

data class BookUndoResult(val restored: List<String>, val changedSince: List<String>, val failed: List<String>)

@Serializable
data class BookUndoFile(val path: String, val backup: String, val writtenRevision: String)

@Serializable
data class BookUndoEntry(val id: String, val time: Long, val summary: String, val replaced: Int, val files: List<BookUndoFile>)

/**
 * 全书查找替换。逐个文件带版本校验写入，一个文件失败不影响其他；原文先存进应用私有目录，
 * 每个作品留最近 [KEEP_UNDO] 次，可撤销。
 */
class BookReplacer(
    private val store: BookStore,
    private val undoDir: File,
) {
    suspend fun preview(workspaceId: String, query: BookReplaceQuery): BookReplacePreview = withContext(Dispatchers.IO) {
        val files = ArrayList<BookReplaceFile>()
        val unreadable = ArrayList<String>()
        for (path in query.paths) {
            val snapshot = attempt { store.read(workspaceId, path) }
            if (snapshot == null) {
                unreadable += path
                continue
            }
            val hits = BookReplace.find(snapshot.content, query.pairs, query.ignoreCase)
            if (hits.isNotEmpty()) files += BookReplaceFile(path, snapshot.revision, hits)
        }
        BookReplacePreview(files, unreadable)
    }

    suspend fun apply(workspaceId: String, query: BookReplaceQuery, targets: List<BookReplaceTarget>): BookReplaceResult =
        withContext(Dispatchers.IO) {
            val id = System.currentTimeMillis().toString()
            val dir = File(bookDir(workspaceId), id)
            val undo = ArrayList<BookUndoFile>()
            val conflicts = ArrayList<String>()
            val failed = ArrayList<String>()
            var replaced = 0
            for (target in targets) {
                val snapshot = attempt { store.read(workspaceId, target.path) }
                if (snapshot == null) {
                    failed += target.path
                    continue
                }
                if (snapshot.revision != target.revision) {
                    conflicts += target.path
                    continue
                }
                val (text, count) = BookReplace.replace(snapshot.content, query.pairs, query.ignoreCase, target.starts)
                if (count == 0 || text == snapshot.content) continue
                val backup = "${undo.size}.txt"
                try {
                    dir.mkdirs()
                    File(dir, backup).writeText(snapshot.content)
                    val revision = store.write(workspaceId, target.path, text, snapshot.revision)
                    undo += BookUndoFile(target.path, backup, revision)
                    replaced += count
                    // 每写完一个文件就记一次：中途被杀掉，已经写的也还能撤销
                    val summary = query.pairs.joinToString("、") { "${it.find} → ${it.replace}" }
                    File(dir, META).writeText(JsonInstant.encodeToString(BookUndoEntry(id, id.toLong(), summary, replaced, undo.toList())))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: WorkspaceFileRevisionConflictException) {
                    conflicts += target.path
                } catch (_: Exception) {
                    failed += target.path
                }
            }
            if (undo.isEmpty()) dir.deleteRecursively()
            prune(workspaceId)
            BookReplaceResult(id.takeIf { undo.isNotEmpty() }, replaced, undo.map { it.path }, conflicts, failed)
        }

    /** 新的在前。 */
    suspend fun history(workspaceId: String): List<BookUndoEntry> = withContext(Dispatchers.IO) {
        entries(workspaceId).sortedByDescending { it.time }
    }

    /** 撤销一次替换；之后又被改过的文件不动。全部恢复后这条记录删掉，没恢复的留着。 */
    suspend fun undo(workspaceId: String, id: String): BookUndoResult = withContext(Dispatchers.IO) {
        val entry = entries(workspaceId).firstOrNull { it.id == id } ?: throw NoSuchElementException("Nothing to undo")
        val dir = File(bookDir(workspaceId), id)
        val restored = ArrayList<String>()
        val changed = ArrayList<String>()
        val failed = ArrayList<String>()
        for (file in entry.files) {
            try {
                store.write(workspaceId, file.path, File(dir, file.backup).readText(), file.writtenRevision)
                restored += file.path
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: WorkspaceFileRevisionConflictException) {
                changed += file.path
            } catch (_: Exception) {
                failed += file.path
            }
        }
        val left = entry.files.filter { it.path !in restored }
        if (left.isEmpty()) dir.deleteRecursively()
        else File(dir, META).writeText(JsonInstant.encodeToString(entry.copy(files = left)))
        BookUndoResult(restored, changed, failed)
    }

    private fun bookDir(workspaceId: String) = File(undoDir, workspaceId)

    private fun entries(workspaceId: String): List<BookUndoEntry> = bookDir(workspaceId).listFiles().orEmpty().mapNotNull { dir ->
        runCatching { JsonInstant.decodeFromString<BookUndoEntry>(File(dir, META).readText()) }.getOrNull()
    }

    private fun prune(workspaceId: String) {
        entries(workspaceId).sortedByDescending { it.time }.drop(KEEP_UNDO).forEach { File(bookDir(workspaceId), it.id).deleteRecursively() }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val KEEP_UNDO = 10
        const val META = "undo.json"
    }
}
