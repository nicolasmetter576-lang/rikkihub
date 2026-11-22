package me.rerere.rikkihub.data.files

import me.rerere.workspace.WorkspaceFileEntry
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Everything-style file name index of one file manager root: the tree is walked once, then each
 * keystroke only filters names in memory. Entry paths are file manager paths.
 */
class FileNameIndex(
    private val entries: List<WorkspaceFileEntry>,
    val builtAt: Long,
    /** The walk stopped at [MAX_ENTRIES]: results may be missing. */
    val truncated: Boolean,
) {
    private val lowerNames = Array(entries.size) { entries[it].name.lowercase() }
    private val hidden = BooleanArray(entries.size) { index ->
        entries[index].path.split('/').any { it.startsWith('.') }
    }

    val size: Int get() = entries.size

    /** Matches and how many there were in total (only the first [limit] are returned). */
    data class Result(val hits: List<WorkspaceFileEntry>, val total: Int)

    /**
     * Every space-separated term must occur in the name (case-insensitive); a term with `*` or `?`
     * must match the whole name as a wildcard pattern. Exact names come first, then names starting
     * with the first term, then shorter paths.
     */
    fun search(query: String, showHidden: Boolean, limit: Int = DEFAULT_LIMIT): Result {
        val terms = query.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return Result(emptyList(), 0)
        val matchers = terms.map { term ->
            if ('*' in term || '?' in term) wildcard(term).let { regex -> { name: String -> regex.matches(name) } }
            else { name: String -> term in name }
        }
        val whole = terms.joinToString(" ")
        val first = terms.first()
        val matched = ArrayList<Int>()
        for (index in entries.indices) {
            if (!showHidden && hidden[index]) continue
            val name = lowerNames[index]
            if (matchers.all { it(name) }) matched += index
        }
        val ranked = matched.sortedWith(
            compareBy<Int> { index ->
                val name = lowerNames[index]
                when {
                    name == whole -> 0
                    name.startsWith(first) -> 1
                    else -> 2
                }
            }.thenBy { entries[it].path.length }.thenBy { entries[it].path }
        )
        return Result(ranked.take(limit).map { entries[it] }, matched.size)
    }

    companion object {
        const val MAX_ENTRIES = 500_000
        const val DEFAULT_LIMIT = 500
        private val WHITESPACE = Regex("\\s+")

        private fun wildcard(term: String): Regex = Regex(
            term.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }
        )
    }
}

/**
 * Collects entries for a [FileNameIndex]: [add] walks a real folder (links are listed, never
 * followed; unreadable folders are skipped) and files its entries under [prefix].
 */
internal class FileNameIndexBuilder(
    private val onProgress: (Int) -> Unit = {},
    /** Folder names (relative to a walked root) to leave out, e.g. Android/data on a phone volume. */
    private val skip: (relativePath: String) -> Boolean = { false },
) {
    private val entries = ArrayList<WorkspaceFileEntry>()
    var truncated = false
        private set

    fun addVirtualFolder(path: String, name: String, updatedAt: Long) {
        entries += WorkspaceFileEntry(path, name, isDirectory = true, sizeBytes = 0, updatedAt = updatedAt)
    }

    fun add(dir: File, prefix: String) {
        if (!dir.isDirectory || truncated) return
        val rootPath = dir.toPath()
        Files.walkFileTree(rootPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (path == rootPath) return FileVisitResult.CONTINUE
                val relative = rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                if (skip(relative)) return FileVisitResult.SKIP_SUBTREE
                record(path, relative, attrs, prefix)
                return if (truncated) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                record(path, rootPath.relativize(path).toString().replace(File.separatorChar, '/'), attrs, prefix)
                return if (truncated) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
    }

    private fun record(path: Path, relative: String, attrs: BasicFileAttributes, prefix: String) {
        if (entries.size >= FileNameIndex.MAX_ENTRIES) {
            truncated = true
            return
        }
        entries += WorkspaceFileEntry(
            path = if (prefix.isEmpty()) relative else "$prefix/$relative",
            name = path.fileName.toString(),
            isDirectory = attrs.isDirectory,
            sizeBytes = if (attrs.isDirectory) 0 else attrs.size(),
            updatedAt = attrs.lastModifiedTime().toMillis(),
        )
        if (entries.size % PROGRESS_STEP == 0) onProgress(entries.size)
    }

    fun build(builtAt: Long): FileNameIndex = FileNameIndex(entries.toList(), builtAt, truncated)

    private companion object {
        const val PROGRESS_STEP = 2_000
    }
}
