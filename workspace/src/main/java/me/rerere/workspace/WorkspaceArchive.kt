package me.rerere.workspace

import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** 一次批量导出的结果统计 */
@kotlinx.serialization.Serializable
data class WorkspaceArchiveResult(
    val fileCount: Int,
    val directoryCount: Int,
)

/**
 * 把工作区里选中的若干文件/目录打包成单个 ZIP, 流式写出.
 *
 * 契约与安全边界:
 * - paths 必须是相对存储区根目录的路径; 空选择、根目录本身 ("", ".", "/")
 *   和逃逸出存储区根的路径一律拒绝
 * - 多个选中项若存在祖先目录覆盖后代的情况, 后代条目去重, 不重复写
 * - archive base = 所有选中项的最长公共父路径 (UI 在同一个目录里选同级项时
 *   就是该目录); ZIP entry 以选中项自身为顶层, 目录递归展开并保留空目录
 * - entry 名绝不可能是绝对路径或含 ".." 段 (生成后仍逐条二次校验)
 * - 符号链接一律拒绝并给出可理解的错误, 不跟随 (既防导出宿主敏感文件, 也防链接循环);
 *   目录遍历时另按 canonical 路径去重兜底
 * - 分块拷贝支持取消; [outputStream] 由本函数负责关闭 (与 exportFile 一致),
 *   上层拿到的是 content:// 流, 失败时由上层删除半成品 SAF 文档
 */
class WorkspaceArchiveExporter(
    private val fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
) {
    suspend fun exportZip(
        root: File,
        paths: List<String>,
        outputStream: OutputStream,
    ): WorkspaceArchiveResult {
        // 注意顺序: ensureActive 必须在 use 之内. 若调用方在已取消的协程里
        // 直接调用本函数, ensureActive 先行抛出时 use 的 try/finally 还没装上,
        // 接管的流会泄漏; 放进 use 之后, 任何早退路径 (取消/参数校验) 都保证关流.
        outputStream.use { raw ->
            coroutineContext.ensureActive()
            require(root.isDirectory) { "Storage root is not a directory: $root" }
            val rootCanonical = root.canonicalFile

            val items = resolveWorkspaceSelection(fileSystem, rootCanonical, paths, "archive")
                .map { Selection(it.file, it.rel) }
            // archive base: 所有选中项的最长公共父路径 ("" 表示存储区根)
            val base = items.map { it.rel.substringBeforeLast('/', "") }
                .reduce { acc, parent -> commonParentPath(acc, parent) }

            val stats = Stats()
            val visitedDirs = HashSet<String>()
            ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                for (item in items) {
                    writeTree(item.file, item.rel, base, zip, visitedDirs, stats)
                }
            }
            return WorkspaceArchiveResult(
                fileCount = stats.files,
                directoryCount = stats.directories,
            )
        }
    }


    private suspend fun writeTree(
        file: File,
        rel: String,
        base: String,
        zip: ZipOutputStream,
        visitedDirs: MutableSet<String>,
        stats: Stats,
    ) {
        coroutineContext.ensureActive()
        check(!Files.isSymbolicLink(file.toPath())) {
            "Refusing to archive symbolic link: $rel"
        }
        val entryName = entryName(rel, base)
        when {
            file.isDirectory -> {
                // 符号链接已在上面拦掉; canonical 去重兜底防目录循环重复打包
                if (!visitedDirs.add(file.canonicalPath)) return
                zip.putNextEntry(ZipEntry("$entryName/").apply { time = file.lastModified() })
                zip.closeEntry()
                stats.directories++
                val children = file.listFiles()
                    ?: error("Failed to list directory: $rel")
                // 排序保证 entry 顺序确定; 跳过 proot 的 .l2s. 内部工件 (与 list/glob 一致)
                for (child in children.sortedBy { it.name }) {
                    if (child.name.startsWith(L2S_PREFIX)) continue
                    writeTree(child, "$rel/${child.name}", base, zip, visitedDirs, stats)
                }
            }

            file.isFile -> {
                zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                copyStream(file.inputStream(), zip)
                zip.closeEntry()
                stats.files++
            }

            else -> check(false) { "Unsupported file type: $rel" }
        }
    }

    private suspend fun copyStream(input: InputStream, zip: ZipOutputStream) {
        input.use { source ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                zip.write(buffer, 0, read)
            }
        }
    }

    private data class Selection(val file: File, val rel: String)

    private class Stats(
        var files: Int = 0,
        var directories: Int = 0,
    )

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val L2S_PREFIX = ".l2s."

        /** 相对 [base] 的 entry 名; base 恒为 rel 的严格父路径或空串 */
        internal fun entryName(rel: String, base: String): String {
            val name = if (base.isEmpty()) rel else rel.removePrefix("$base/")
            requireValidEntryName(name)
            return name
        }

        /** 二次校验: entry 名不得为空、不得是绝对路径、不得含 "." / ".." / 空段、反斜线或 NUL */
        private fun requireValidEntryName(name: String) {
            require(name.isNotEmpty() && !name.startsWith("/") && !name.contains('\\') &&
                !name.contains('\u0000')) { "Illegal archive entry name" }
            require(name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "Illegal archive entry name: $name"
            }
        }

        /** 两个 "a/b" 风格相对路径的最长公共祖先; 无公共段时返回 "" (存储区根) */
        private fun commonParentPath(a: String, b: String): String {
            if (a.isEmpty() || b.isEmpty()) return ""
            val aSegs = a.split('/')
            val bSegs = b.split('/')
            val n = minOf(aSegs.size, bSegs.size)
            var i = 0
            while (i < n && aSegs[i] == bSegs[i]) i++
            return aSegs.subList(0, i).joinToString("/")
        }
    }
}
