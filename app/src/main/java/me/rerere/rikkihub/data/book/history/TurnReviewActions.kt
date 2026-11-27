package me.rerere.rikkihub.data.book.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.book.BookChapter
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.ChapterStatus
import me.rerere.workspace.WorkspaceFileRevisionConflictException
import me.rerere.workspace.WorkspaceLockedException

/** 一轮改动的审阅页（App 与 Web 共用）。[files] 里只有界面要显示的文件。 */
@Serializable
data class TurnReviewDto(
    val conversationId: String,
    val messageId: String,
    val finishedAt: Long,
    val status: TurnStatus,
    val reverted: Int,
    val unattributed: Unattributed? = null,
    val files: List<TurnFileDto>,
)

@Serializable
data class TurnFileDto(
    /** 历史键（改名不换），完成、撤销时用它指文件。 */
    val key: String,
    val path: String,
    val kind: Kind,
    val renamedFrom: String? = null,
    /** 现在稿子的版本号（文件不在了为 null）。 */
    val revision: String? = null,
    /** 改前、改后的哈希：完成时核对，记录变了就要重新加载。 */
    val before: String? = null,
    val after: String? = null,
    /** 整章锁定（解锁只在 App）。 */
    val chapterLocked: Boolean = false,
    /** 只能整章还原（差异太大，或整章改成了一块）。 */
    val whole: Boolean = false,
    /** 只改了缩进或空行，没有能逐处审阅的块。 */
    val layoutOnly: Boolean = false,
    /** 另有几处只改了缩进或空行。 */
    val layout: Int = 0,
    val missing: TurnMissing? = null,
    val mixed: Boolean = false,
    val done: Boolean = false,
    /** 整章还原会丢掉的、之后的改动处数（算不出时为 null）。 */
    val changesSince: Int? = null,
    /** 文件已经不在了（被删、被移出作品）。 */
    val gone: Boolean = false,
    /** 删掉时没能放进回收站（不能放回）。 */
    val unrecoverable: Boolean = false,
    val canUndo: Boolean = false,
    val blocks: List<TurnBlockDto> = emptyList(),
) {
    @Serializable
    enum class Kind {
        @SerialName("changed") CHANGED,
        @SerialName("created") CREATED,
        @SerialName("deleted") DELETED,
        @SerialName("renamed") RENAMED,
    }
}

@Serializable
data class TurnBlockDto(
    val index: Int,
    val old: List<String>,
    val new: List<String>,
    /** 两边段数相同时的逐段字级高亮。 */
    val spans: List<List<ChapterDiff.Span>>? = null,
    /** 块在现在稿里的位置碰到了段落锁。 */
    val locked: Boolean = false,
    val state: BlockState,
    /** 移动绑定的另一块：两块只能一起拒绝。 */
    val partner: Int? = null,
) {
    @Serializable
    enum class BlockState {
        @SerialName("pending") PENDING,
        @SerialName("reverted") REVERTED,
        @SerialName("gone") GONE,
        @SerialName("changed_later") CHANGED_LATER,
    }
}

/** 完成时一个文件的选择：[reject] 要拒绝的块；[whole] 拒绝整个文件（新建的删掉、删掉的放回、改了的整章还原）。 */
@Serializable
data class TurnFilePick(
    val key: String,
    val revision: String? = null,
    val before: String? = null,
    val after: String? = null,
    val reject: Set<Int> = emptySet(),
    val whole: Boolean = false,
)

@Serializable
data class TurnFileOutcome(
    val key: String,
    val path: String,
    val outcome: Outcome,
    /** 没能换回的块。 */
    val failed: Set<Int> = emptySet(),
    /** 换回了几处。 */
    val reverted: Int = 0,
) {
    @Serializable
    enum class Outcome {
        @SerialName("ok") OK,

        /** 稿子刚刚又改了，或记录变了：重新加载。 */
        @SerialName("conflict") CONFLICT,

        /** 整章锁定：到 App 里解锁后再审阅。 */
        @SerialName("locked") LOCKED,

        /** 自检不过、块之后又改过、空间不足没能先存一版：没写（或只写了一部分，见 [failed]）。 */
        @SerialName("failed") FAILED,

        /** 文件或需要的版本已经不在了。 */
        @SerialName("missing") MISSING,
    }
}

/**
 * 逐处审阅的读、完成、撤销。每个文件独立处理、各自返回结果；写回照“读快照 → 锁外计算 → 带版本号写”，
 * 写之前先把现在的稿子强制存一版（“审阅前”），记成“还原”。选择只在界面内存里，不存中间状态。
 */
class TurnReviewActions(private val store: BookStore, private val history: BookHistory) {
    suspend fun load(workspaceId: String, conversationId: String, messageId: String, markSeen: Boolean = true): TurnReviewDto? {
        val record = history.turn(workspaceId, conversationId, messageId) ?: return null
        if (markSeen && record.seenAt == null) history.updateTurn(workspaceId, conversationId, messageId) { it.copy(seenAt = System.currentTimeMillis()) }
        val files = record.files.entries.sortedBy { it.value.path }.map { (key, file) -> fileDto(workspaceId, key, file) }
        return TurnReviewDto(conversationId, messageId, record.finishedAt!!, record.status, record.reverted, record.commandsUnattributed, files)
    }

    private suspend fun fileDto(workspaceId: String, key: String, file: TurnFile): TurnFileDto {
        val path = history.pathOf(workspaceId, key)
        val kind = when {
            file.trashed != null || file.deleted -> TurnFileDto.Kind.DELETED
            file.created -> TurnFileDto.Kind.CREATED
            file.renamedFrom != null -> TurnFileDto.Kind.RENAMED
            else -> TurnFileDto.Kind.CHANGED
        }
        val base = TurnFileDto(
            key = key, path = path ?: file.path, kind = kind, renamedFrom = file.renamedFrom, before = file.before, after = file.after,
            missing = file.missing, mixed = file.mixed, done = file.done, layoutOnly = file.layoutOnly, canUndo = file.undo != null,
            unrecoverable = kind == TurnFileDto.Kind.DELETED && file.trashed == null,
        )
        if (kind == TurnFileDto.Kind.DELETED) {
            val text = file.before?.let { history.read(workspaceId, it) }
            return base.copy(gone = path == null, blocks = listOfNotNull(text?.let { wholeBlock(BookLocks.normalize(it), "") }), changesSince = 0)
        }
        if (path == null) return base.copy(gone = true)
        val snapshot = runCatching { store.read(workspaceId, path) }.getOrElse { if (it is CancellationException) throw it; return base.copy(gone = true) }
        val locks = history.locksOf(workspaceId, path)
        val after = file.after?.let { history.read(workspaceId, it) }
        val common = base.copy(revision = snapshot.revision, chapterLocked = locks?.chapter != null)
        if (after == null) return common
        if (kind == TurnFileDto.Kind.CREATED) {
            val changed = BookLocks.normalize(after) != snapshot.content
            return common.copy(blocks = listOf(wholeBlock("", snapshot.content).copy(state = if (changed) TurnBlockDto.BlockState.CHANGED_LATER else TurnBlockDto.BlockState.PENDING)))
        }
        val before = file.before?.let { history.read(workspaceId, it) } ?: return common
        val review = withContext(Dispatchers.Default) { TurnReview.review(before, after, snapshot.content) }
        val ranges = locks?.passages?.filter { it.dormantSince == null }?.let { BookLocks.rangesIn(snapshot.content, it).values.toList() }.orEmpty()
        val blocks = review.pieces.mapIndexed { index, piece ->
            val rows = piece.rows
            TurnBlockDto(
                index = index,
                old = piece.old,
                new = piece.new,
                spans = spansOf(piece.old, piece.new),
                locked = rows != null && ranges.any { lock -> if (rows.isEmpty()) rows.first in lock.first + 1..lock.last else rows.first <= lock.last && lock.first <= rows.last },
                state = TurnBlockDto.BlockState.valueOf(piece.state.name),
                partner = piece.partner,
            )
        }
        return common.copy(whole = review.whole, layout = review.layout, changesSince = review.changesSince, blocks = blocks)
    }

    private fun wholeBlock(old: String, new: String) =
        TurnBlockDto(0, old.split('\n').takeIf { old.isNotEmpty() }.orEmpty(), new.split('\n').takeIf { new.isNotEmpty() }.orEmpty(), state = TurnBlockDto.BlockState.PENDING)

    private fun spansOf(old: List<String>, new: List<String>): List<List<ChapterDiff.Span>>? {
        if (old.isEmpty() || new.isEmpty() || old.size != new.size) return null
        if (old.zip(new).any { (a, b) -> a.length > ChapterDiff.MAX_WORD_DIFF_CHARS || b.length > ChapterDiff.MAX_WORD_DIFF_CHARS }) return null
        return old.zip(new).map { (a, b) -> ChapterDiff.words(a, b) }
    }

    /** 完成：逐个处理 [picks] 里的文件；所有文件都处理过即已审阅。 */
    suspend fun complete(workspaceId: String, conversationId: String, messageId: String, picks: List<TurnFilePick>): List<TurnFileOutcome> {
        val record = history.turn(workspaceId, conversationId, messageId) ?: return picks.map { TurnFileOutcome(it.key, "", TurnFileOutcome.Outcome.MISSING) }
        val outcomes = picks.map { pick ->
            val file = record.files[pick.key] ?: return@map TurnFileOutcome(pick.key, "", TurnFileOutcome.Outcome.MISSING)
            try {
                completeFile(workspaceId, record, pick, file)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val outcome = when (error) {
                    is WorkspaceFileRevisionConflictException -> TurnFileOutcome.Outcome.CONFLICT
                    is WorkspaceLockedException -> TurnFileOutcome.Outcome.LOCKED
                    else -> TurnFileOutcome.Outcome.FAILED
                }
                TurnFileOutcome(pick.key, file.path, outcome, failed = pick.reject)
            }
        }
        // 界面“完成”时带上所有没处理过的文件（没拒绝的也带，标为处理过）；只带一个文件的（整章还原）不动别的文件
        val reverted = outcomes.sumOf { it.reverted }
        history.updateTurn(workspaceId, conversationId, messageId) { current ->
            val next = current.copy(reverted = current.reverted + reverted)
            if (next.files.values.all { it.done }) next.copy(reviewedAt = System.currentTimeMillis()) else next
        }
        return outcomes
    }

    private suspend fun completeFile(workspaceId: String, record: TurnRecord, pick: TurnFilePick, file: TurnFile): TurnFileOutcome {
        val key = pick.key
        fun outcome(outcome: TurnFileOutcome.Outcome, path: String = file.path, failed: Set<Int> = emptySet(), reverted: Int = 0) =
            TurnFileOutcome(key, path, outcome, failed, reverted)
        if (pick.before != file.before || pick.after != file.after) return outcome(TurnFileOutcome.Outcome.CONFLICT)
        if (pick.reject.isEmpty() && !pick.whole) {
            markDone(workspaceId, record, key, null)
            return outcome(TurnFileOutcome.Outcome.OK)
        }
        if (file.trashed != null || file.deleted) {
            // 拒绝删除：从回收站放回
            val name = file.trashed ?: return outcome(TurnFileOutcome.Outcome.MISSING)
            val path = history.restoreTrashed(workspaceId, key, name) ?: return outcome(TurnFileOutcome.Outcome.MISSING)
            val book = store.open(workspaceId)
            book.entries.firstOrNull { it.missing && it.path == file.path }?.takeIf { book.entryByPath(path) == null }?.let { store.relink(workspaceId, it.id, path) }
            val sha = store.read(workspaceId, path).revision
            markDone(workspaceId, record, key, TurnUndo(TurnUndo.Action.RESTORED_DELETED, writtenSha = sha, path = path))
            return outcome(TurnFileOutcome.Outcome.OK, path, reverted = 1)
        }
        val path = history.pathOf(workspaceId, key) ?: return outcome(TurnFileOutcome.Outcome.MISSING)
        val revision = pick.revision ?: return outcome(TurnFileOutcome.Outcome.CONFLICT, path)
        if (history.locksOf(workspaceId, path)?.chapter != null) return outcome(TurnFileOutcome.Outcome.LOCKED, path)
        if (file.created) {
            // 拒绝新建：删掉（进回收站），目录项记下来撤销时接回原位
            val current = store.read(workspaceId, path)
            if (current.revision != revision) return outcome(TurnFileOutcome.Outcome.CONFLICT, path)
            val book = store.open(workspaceId)
            val entry = book.entryByPath(path)
            val undoChapter = entry?.chapter?.let { chapter ->
                val items = book.manifest.items
                val previous = items.getOrNull(items.indexOfFirst { it.id == chapter.id } - 1)?.id ?: BookManifest.START
                UndoChapter(chapter.id, chapter.path, chapter.title, chapter.status.id, chapter.synopsis, chapter.targetWords, chapter.extra) to previous
            }
            if (entry != null) store.deleteChapters(workspaceId, setOf(entry.id)) else store.deleteFile(workspaceId, path)
            val trashName = history.trashNameOf(workspaceId, key)
            markDone(workspaceId, record, key, TurnUndo(TurnUndo.Action.DELETED_CREATED, trashName = trashName, chapter = undoChapter?.first, afterChapterId = undoChapter?.second))
            return outcome(TurnFileOutcome.Outcome.OK, path, reverted = 1)
        }
        val before = file.before?.let { history.read(workspaceId, it) } ?: return outcome(TurnFileOutcome.Outcome.MISSING, path)
        val after = file.after?.let { history.read(workspaceId, it) } ?: return outcome(TurnFileOutcome.Outcome.MISSING, path)
        val bytes = store.withRevision(workspaceId, path, revision) { it }
        val current = bytes.decodeToString()
        val (text, failed) = if (pick.whole) {
            BookLocks.normalize(before) to emptySet()
        } else {
            val result = withContext(Dispatchers.Default) { TurnReview.revert(before, after, current, pick.reject) }
            (result.text ?: return outcome(TurnFileOutcome.Outcome.FAILED, path, failed = result.failed)) to result.failed
        }
        // 先强制存一版“审阅前”，存不下就不写
        val kept = history.keepForReview(workspaceId, path, bytes) ?: return outcome(TurnFileOutcome.Outcome.FAILED, path, failed = pick.reject)
        val written = withContext(BookHistory.sourceElement(HistorySource.Restore)) { store.write(workspaceId, path, text, revision) }
        val count = if (pick.whole) 1 else (pick.reject - failed).size
        markDone(workspaceId, record, key, TurnUndo(TurnUndo.Action.CONTENT, beforeSha = kept, writtenSha = written), done = failed.isEmpty())
        return outcome(if (failed.isEmpty()) TurnFileOutcome.Outcome.OK else TurnFileOutcome.Outcome.FAILED, path, failed = failed, reverted = count)
    }

    private suspend fun markDone(workspaceId: String, record: TurnRecord, key: String, undo: TurnUndo?, done: Boolean = true) {
        history.updateTurn(workspaceId, record.conversationId, record.messageId) { current ->
            val file = current.files[key] ?: return@updateTurn current
            current.copy(files = current.files + (key to file.copy(done = done, undo = undo ?: file.undo)))
        }
    }

    /** 撤销完成时做的写入：只在稿子之后没再变过时生效；撤销了的文件回到待审。 */
    suspend fun undo(workspaceId: String, conversationId: String, messageId: String): List<TurnFileOutcome> {
        val record = history.turn(workspaceId, conversationId, messageId) ?: return emptyList()
        return record.files.filterValues { it.undo != null }.map { (key, file) ->
            val undo = file.undo!!
            val outcome = try {
                undoFile(workspaceId, key, file, undo)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                when (error) {
                    is WorkspaceFileRevisionConflictException -> TurnFileOutcome.Outcome.CONFLICT
                    is WorkspaceLockedException -> TurnFileOutcome.Outcome.LOCKED
                    else -> TurnFileOutcome.Outcome.FAILED
                }
            }
            if (outcome == TurnFileOutcome.Outcome.OK) {
                history.updateTurn(workspaceId, conversationId, messageId) { current ->
                    val now = current.files[key] ?: return@updateTurn current
                    current.copy(files = current.files + (key to now.copy(done = false, undo = null)), reviewedAt = null, reverted = 0)
                }
            }
            TurnFileOutcome(key, history.pathOf(workspaceId, key) ?: file.path, outcome)
        }
    }

    private suspend fun undoFile(workspaceId: String, key: String, file: TurnFile, undo: TurnUndo): TurnFileOutcome.Outcome = when (undo.action) {
        TurnUndo.Action.CONTENT -> {
            val path = history.pathOf(workspaceId, key) ?: return TurnFileOutcome.Outcome.MISSING
            val text = undo.beforeSha?.let { history.read(workspaceId, it) } ?: return TurnFileOutcome.Outcome.MISSING
            withContext(BookHistory.sourceElement(HistorySource.Restore)) { store.write(workspaceId, path, BookLocks.normalize(text), undo.writtenSha!!) }
            TurnFileOutcome.Outcome.OK
        }
        TurnUndo.Action.DELETED_CREATED -> {
            val name = undo.trashName ?: return TurnFileOutcome.Outcome.MISSING
            val path = history.restoreTrashed(workspaceId, key, name) ?: return TurnFileOutcome.Outcome.MISSING
            val chapter = undo.chapter
            if (chapter != null) {
                store.edit(workspaceId) { book ->
                    val manifest = book.manifest
                    // 你手动接回过（同 id 或同路径）就不重复接
                    if (manifest.chapter(chapter.id) != null || manifest.chapters.any { it.path == path }) return@edit manifest
                    val after = undo.afterChapterId?.takeIf { it == BookManifest.START || manifest.items.any { item -> item.id == it } }
                    manifest.addChapters(
                        listOf(BookChapter(chapter.id, path, chapter.title, ChapterStatus.of(chapter.status) ?: ChapterStatus.DRAFT, chapter.synopsis, chapter.targetWords, chapter.extra)),
                        after,
                    )
                }
            }
            TurnFileOutcome.Outcome.OK
        }
        TurnUndo.Action.RESTORED_DELETED -> {
            val path = undo.path ?: return TurnFileOutcome.Outcome.MISSING
            if (store.read(workspaceId, path).revision != undo.writtenSha) return TurnFileOutcome.Outcome.CONFLICT
            val entry = store.open(workspaceId).entryByPath(path)
            if (entry != null) store.deleteChapters(workspaceId, setOf(entry.id)) else store.deleteFile(workspaceId, path)
            TurnFileOutcome.Outcome.OK
        }
    }
}
