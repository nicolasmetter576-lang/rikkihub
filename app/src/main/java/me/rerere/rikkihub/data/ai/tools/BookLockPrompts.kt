package me.rerere.rikkihub.data.ai.tools

import me.rerere.rikkihub.data.book.history.BookLockedException
import me.rerere.rikkihub.data.book.history.BookLocks
import me.rerere.rikkihub.data.book.history.FileLocks
import me.rerere.rikkihub.data.book.history.LockNotice
import me.rerere.rikkihub.data.book.history.LockReport
import java.util.concurrent.ConcurrentHashMap

/**
 * 定稿锁给模型的说明（中英两套，跟随作品提示词语言）：写入被拒、命令行改动被放回、目录与读章时的标注、
 * 有锁时提示词追加的一句、CLI 回合前的 `<book_locks>`。不带类名和堆栈。
 */
object BookLockPrompts {
    /** 拒绝说明里附上的锁定原文合计上限。 */
    private const val MAX_QUOTED_CHARS = 2000

    /** 同一回合同一章被拒的次数：连续两次之后改口径，请模型停下来问用户。 */
    private val refusals = ConcurrentHashMap<String, Int>()

    fun refusal(language: BookPromptLanguage, error: BookLockedException, guestPath: String, turn: String?): String {
        val zh = language == BookPromptLanguage.ZH
        val count = turn?.let { key -> refusals.merge("$key\u0000${error.path}", 1, Int::plus) } ?: 1
        if (refusals.size > 500) refusals.clear()
        if (count >= 3) {
            return if (zh) "`$guestPath` 有用户定稿锁定的内容，已经连续被拒。请先停下，告诉用户这一章有哪些内容锁定了，问是否需要解锁；不要再尝试写入。"
            else "`$guestPath` contains content the user has locked as final, and writes keep being refused. Stop now, tell the user what is locked in this chapter and ask whether to unlock it; do not try to write again."
        }
        return when (error.reason) {
            BookLockedException.Reason.CHAPTER -> if (zh) {
                "`$guestPath` 已被用户定稿锁定，不能修改。不要换别的方式写入（命令行、改名、新建副本替换都不行）。如果确实需要改，请告诉用户，由用户在 App 里解锁。"
            } else {
                "`$guestPath` has been locked as final by the user and cannot be changed. Do not try another way to write it (shell commands, renaming, or replacing it with a copy). If it really needs changes, tell the user so they can unlock it in the app."
            }
            BookLockedException.Reason.PASSAGES -> passages(zh, error, guestPath)
            BookLockedException.Reason.TRASH, BookLockedException.Reason.MOVE_OUT -> if (zh) {
                "`$guestPath` 有用户定稿锁定的内容，不能这样删除或移走。需要的话请告诉用户，由用户在 App 里操作。"
            } else {
                "`$guestPath` contains content the user has locked as final; it cannot be deleted or moved this way. If needed, ask the user to do it in the app."
            }
        }
    }

    private fun passages(zh: Boolean, error: BookLockedException, guestPath: String): String = buildString {
        val duplicate = error.issues.any { it.kind == BookLocks.IssueKind.DUPLICATE }
        if (zh) {
            append("没有写入：这次写入会改动 `$guestPath` 里用户锁定的段落。锁定的段落要一字不改地保留（不要拆段、合段，不要改标点），其余部分可以改。")
        } else {
            append("Nothing was written: this write would change passages in `$guestPath` that the user has locked. Keep locked passages exactly as they are (do not split or merge them, do not change punctuation); everything else may change.")
        }
        append(if (zh) "\n差在哪：" else "\nWhat differs: ")
        append(error.issues.mapIndexed { i, issue -> issueText(zh, i + 1, issue.kind) }.joinToString(if (zh) "；" else "; "))
        if (duplicate) {
            append(if (zh) "\n本章有几处相同的文字，其中一处是锁定的，请都保留。" else "\nThis chapter has the same text in more than one place and one of them is locked; keep all of them.")
        }
        var budget = MAX_QUOTED_CHARS
        val quoted = error.issues.map { it.passage.lines.joinToString("\n") }.takeWhile { text -> (budget >= text.length).also { budget -= text.length } }
        append(if (zh) "\n锁定的段落原文：" else "\nLocked passages, verbatim:")
        quoted.forEachIndexed { i, text -> append("\n[${i + 1}]\n$text") }
        if (quoted.size < error.issues.size) {
            append(if (zh) "\n（还有 ${error.issues.size - quoted.size} 处，请先用 book_read 读这一章。）" else "\n(${error.issues.size - quoted.size} more; read the chapter with book_read first.)")
        }
        append(if (zh) "\n只改其他部分时，建议用 workspace_edit_file 局部替换。" else "\nTo change only other parts, prefer workspace_edit_file for targeted replacements.")
    }

    private fun issueText(zh: Boolean, n: Int, kind: BookLocks.IssueKind): String = when (kind) {
        BookLocks.IssueKind.CHANGED -> if (zh) "第 $n 处被改动或删除了" else "passage $n was changed or removed"
        BookLocks.IssueKind.SPLIT -> if (zh) "第 $n 处被拆成了几段" else "passage $n was split into several paragraphs"
        BookLocks.IssueKind.MERGED -> if (zh) "第 $n 处和别的段合并了" else "passage $n was merged with another paragraph"
        BookLocks.IssueKind.DUPLICATE -> if (zh) "第 $n 处少了一处相同的文字" else "one copy of passage $n is missing"
    }

    /** `workspace_shell` 结果里的 `book_locks`：命令改了锁定内容，放回了还是没放回。 */
    fun restored(language: BookPromptLanguage, reports: List<LockReport>, guestPath: (String) -> String): String {
        val zh = language == BookPromptLanguage.ZH
        return reports.joinToString("\n") { report ->
            val path = guestPath(report.path)
            val what = when {
                report.chapter -> if (zh) "是用户定稿锁定的章节" else "is a chapter the user locked as final"
                else -> if (zh) "里有用户锁定的段落（${report.passages.joinToString("、") { "“$it…”" }}）" else "contains passages the user locked (${report.passages.joinToString(", ") { "\"$it…\"" }})"
            }
            when {
                report.noSpace -> if (zh) "`$path` $what，刚才的命令改了它。手机空间不足，没能留底，所以没有放回；请告诉用户。不要再改这些内容。"
                else "`$path` $what and the command just changed it. The phone is low on space, so it was not put back; tell the user. Do not change this content again."
                report.restored -> if (zh) "`$path` $what，刚才的命令改了它，已自动放回原样。不要再改，也不要换方式绕过；需要改请告诉用户。"
                else "`$path` $what. The command just changed it and it has been put back automatically. Do not change it again or work around this; if it needs changes, tell the user."
                else -> if (zh) "`$path` $what，刚才的命令改了它，没能自动放回，已提醒用户。不要再改这些内容。"
                else "`$path` $what. The command just changed it and it could not be put back automatically; the user has been notified. Do not change this content again."
            }
        }
    }

    /** `book_contents` 章节行后面的标注。 */
    fun contentsMark(locks: FileLocks?): String = when {
        locks == null -> ""
        locks.chapter != null -> ", locked"
        else -> locks.passages.count { it.dormantSince == null }.takeIf { it > 0 }?.let { ", $it locked passage${if (it > 1) "s" else ""}" }.orEmpty()
    }

    /** `book_read` 输出末尾（正文以外）列出本章锁定的内容。 */
    fun readFooter(language: BookPromptLanguage, locks: FileLocks?): String {
        if (locks == null || locks.isEmpty) return ""
        val zh = language == BookPromptLanguage.ZH
        if (locks.chapter != null) return if (zh) "\n\n[本章已由用户定稿锁定，不能修改]" else "\n\n[This chapter is locked as final by the user and cannot be changed]"
        val passages = locks.passages.filter { it.dormantSince == null }
        if (passages.isEmpty()) return ""
        val list = passages.joinToString("\n") { "- “${it.lines.firstOrNull().orEmpty().trim().take(30)}…”" }
        return if (zh) "\n\n[本章锁定的段落（一字不改地保留）]\n$list" else "\n\n[Locked passages in this chapter (keep them verbatim)]\n$list"
    }

    /** 有锁时追加在作品提示词模板之外的一句。 */
    fun reminderLine(language: BookPromptLanguage): String = if (language == BookPromptLanguage.ZH) {
        "- 目录里标着 locked 的章节、段落已由用户定稿锁定：不要改动，也不要换方式绕过；需要改先告诉用户。"
    } else {
        "- Chapters and passages marked locked in the contents have been locked as final by the user: do not change them or work around this; ask the user first if they need changes."
    }

    /** CLI 回合前的 `<book_locks>`：锁定的章节与段落开头（最多 20 条），以及还没交代的放回说明。 */
    fun cliBlock(language: BookPromptLanguage, locks: Map<String, FileLocks>, notices: List<LockNotice>, guestPath: (String) -> String): String? {
        if (locks.isEmpty() && notices.isEmpty()) return null
        val zh = language == BookPromptLanguage.ZH
        val lines = locks.entries.sortedBy { it.key }.flatMap { (path, file) ->
            if (file.chapter != null) listOf(if (zh) "- `${guestPath(path)}`：整章锁定" else "- `${guestPath(path)}`: whole chapter locked")
            else file.passages.filter { it.dormantSince == null }.map { "- `${guestPath(path)}`: “${it.lines.firstOrNull().orEmpty().trim().take(20)}…”" }
        }
        val shown = lines.take(20)
        return buildString {
            append("<book_locks>\n")
            if (shown.isNotEmpty()) {
                append(if (zh) "下面这些内容已由用户定稿锁定：不要改动，也不要换方式绕过；需要改先告诉用户。\n" else "The user has locked the following as final: do not change it or work around this; ask the user first if it needs changes.\n")
                shown.forEach { append(it).append('\n') }
                if (lines.size > shown.size) append(if (zh) "（等 ${lines.size - shown.size} 处）\n" else "(and ${lines.size - shown.size} more)\n")
            }
            notices.forEach { notice ->
                val path = guestPath(notice.path)
                append(
                    when (notice.kind) {
                        LockNotice.Kind.RESTORED -> if (zh) "上一回合改动了锁定的 `$path`，已自动放回原样。\n" else "Last turn changed the locked `$path`; it has been put back.\n"
                        LockNotice.Kind.NO_SPACE -> if (zh) "上一回合改动了锁定的 `$path`，空间不足没能放回，已提醒用户。\n" else "Last turn changed the locked `$path`; it could not be put back (low space) and the user was notified.\n"
                        else -> if (zh) "上一回合改动了锁定的 `$path`，没能自动放回，已提醒用户。\n" else "Last turn changed the locked `$path`; it could not be put back and the user was notified.\n"
                    },
                )
            }
            append("</book_locks>")
        }
    }
}
