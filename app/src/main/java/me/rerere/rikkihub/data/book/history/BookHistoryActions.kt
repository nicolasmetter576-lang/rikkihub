package me.rerere.rikkihub.data.book.history

import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.workspace.WorkspaceFileRevisionConflictException
import me.rerere.workspace.WorkspaceTextSnapshot

/**
 * 历史与定稿锁界面的写操作（App 与 Web 共用）：都经 [BookStore] 带版本校验写回，记成“还原”；
 * 写之前现在的稿子由写入钩子自动留一版。加锁对着磁盘上指定版本的稿子做。
 */
class BookHistoryActions(private val store: BookStore, private val history: BookHistory) {
    /** 某一版与现在的稿子比较：[current] 带版本号，取回、还原都按它校验。 */
    data class Comparison(val old: String, val current: WorkspaceTextSnapshot, val diff: ChapterDiff)

    /** 版本的全文丢了或坏了。 */
    class VersionMissingException : Exception("This version is missing or damaged")

    /** 历史里的一版按原字节存（可能带 BOM、CRLF）；比较、写回都用归一后的文字，快照层写回时照现在的文件补回 BOM 与换行。 */
    private suspend fun version(workspaceId: String, sha: String): String =
        BookLocks.normalize(history.read(workspaceId, sha) ?: throw VersionMissingException())

    suspend fun compare(workspaceId: String, path: String, sha: String): Comparison {
        val old = version(workspaceId, sha)
        val current = store.read(workspaceId, path)
        return Comparison(old, current, ChapterDiff.of(old, current.content))
    }

    /** 整章换回这一版；返回新版本号。 */
    suspend fun restore(workspaceId: String, path: String, sha: String, expectedRevision: String): String {
        val old = version(workspaceId, sha)
        return withContext(BookHistory.sourceElement(HistorySource.Restore)) { store.write(workspaceId, path, old, expectedRevision) }
    }

    /** 把第 [index] 处改动换回这一版的样子；稿子在比较之后又变了就抛版本冲突。 */
    suspend fun takeBack(workspaceId: String, path: String, sha: String, index: Int, expectedRevision: String): String {
        val old = version(workspaceId, sha)
        val current = store.read(workspaceId, path)
        if (current.revision != expectedRevision) throw WorkspaceFileRevisionConflictException()
        val text = ChapterDiff.takeBack(old, current.content, index) ?: throw WorkspaceFileRevisionConflictException()
        return withContext(BookHistory.sourceElement(HistorySource.Restore)) { store.write(workspaceId, path, text, expectedRevision) }
    }

    /**
     * 从回收站放回，返回放回的路径。目录里还留着这个路径的章节（显示“找不到”）就接回去；
     * 删章节时目录项已经删了的，放回后在“未编入”里，由用户决定编到哪。
     */
    suspend fun restoreTrashed(workspaceId: String, key: String, name: String, originalPath: String): String? {
        val path = history.restoreTrashed(workspaceId, key, name) ?: return null
        val book = store.open(workspaceId)
        val waiting = book.entries.firstOrNull { it.missing && it.path == originalPath }
        if (waiting != null && book.entryByPath(path) == null) store.relink(workspaceId, waiting.id, path)
        return path
    }

    /** 给整章加锁（对着版本号 [revision] 的稿子）；返回加锁前的锁（撤销用）。 */
    suspend fun lockChapter(workspaceId: String, path: String, revision: String, web: Boolean = false): FileLocks? =
        store.withRevision(workspaceId, path, revision) { bytes -> history.lockChapter(workspaceId, path, bytes, web) }

    /** 给 [start]..[end]（字符位置，按归一后的文字算）所在的整段加锁。 */
    suspend fun lockPassage(workspaceId: String, path: String, revision: String, start: Int, end: Int, web: Boolean = false): Pair<PassageLock, FileLocks?> =
        store.withRevision(workspaceId, path, revision) { bytes ->
            val rows = BookLocks.rowsOf(BookLocks.normalize(bytes.decodeToString()), start, end)
            history.lockPassage(workspaceId, path, bytes, rows, web)
        }
}
