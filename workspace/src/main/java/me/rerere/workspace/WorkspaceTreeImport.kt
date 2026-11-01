package me.rerere.workspace

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * 整文件夹导入的树节点抽象.
 *
 * App 侧用 DocumentsContract 包装 ACTION_OPEN_DOCUMENT_TREE 返回的 tree Uri 实现它
 * (ui/pages/extensions/workspace/WorkspaceFolderImporter.kt), workspace 模块本身
 * 不碰 Android API, 因此命名清洗/冲突重命名/循环检测/取消都可以纯 JVM 单测.
 */
interface WorkspaceImportNode {
    /** 整棵树内唯一且稳定的 id (SAF documentId), 用于重复/循环检测 */
    val stableId: String

    /** provider 给的原始显示名; null 表示 provider 没给, 调用方必须显式报错 */
    val displayName: String?

    val isDirectory: Boolean

    /** 列子节点; provider 失败 (如 null cursor) 必须抛异常, 不允许静默返回空 */
    fun listChildren(): List<WorkspaceImportNode>

    /** 打开文件内容流; 返回 null 视为 provider 故障, 调用方显式报错 */
    fun openInputStream(): InputStream?
}

/** 一次文件夹导入的结果统计 */
@kotlinx.serialization.Serializable
data class WorkspaceImportResult(
    /** 实际落盘的根目录名 (清洗或 " (n)" 冲突重命名后可能与 displayName 不同) */
    val rootName: String,
    val fileCount: Int,
    val directoryCount: Int,
)

/**
 * 把 [WorkspaceImportNode] 描述的整棵文件夹树流式复制进某个存储区目录.
 *
 * - 保留所选根文件夹自身以及所有层级, 包括空目录
 * - 目标处已有同名根目录时整体重命名为 "name (n)", 不混入旧目录、不覆盖现存文件
 * - provider 给的显示名先清洗成安全路径段, 清洗后仍非法 ("." / ".." / 空) 直接报错,
 *   未验证的 displayName 永远不会被拼进路径
 * - documentId 重复 (包括回指祖先形成的循环) 直接报错
 * - 文件分块拷贝而不是一次读入内存, 每块之间可取消
 * - 失败/取消时把本次新建的根目录整体删掉, 不留下半成品导入
 */
class WorkspaceTreeImporter(
    private val fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
) {
    suspend fun import(
        root: File,
        destinationPath: String,
        treeRoot: WorkspaceImportNode,
    ): WorkspaceImportResult = WorkspaceFileMutations.withSuspendingLock(root) {
        importUnlocked(root, destinationPath, treeRoot)
    }

    private suspend fun importUnlocked(root: File, destinationPath: String, treeRoot: WorkspaceImportNode): WorkspaceImportResult {
        coroutineContext.ensureActive()
        require(treeRoot.isDirectory) { "Selected item is not a folder" }
        val destination = fileSystem.resolve(root, destinationPath)
        require(!destination.exists() || destination.isDirectory) {
            "Import destination is not a directory: $destinationPath"
        }
        val rootName = WorkspaceNames.sanitize(
            treeRoot.displayName ?: error("The selected folder has no display name")
        )
        val visited = HashSet<String>()
        markVisited(treeRoot, visited)

        // 根目录总是本次新建 (冲突时整体改名), 所以失败时可以安全地整个删掉
        val rootDir = File(
            destination,
            findAvailableName(destination, rootName, isDirectory = true, used = emptySet()),
        )
        require(rootDir.mkdirs() || rootDir.isDirectory) {
            "Failed to create directory: ${rootDir.name}"
        }
        val stats = Stats(directories = 1)
        try {
            importChildren(treeRoot, rootDir, visited, stats, depth = 1)
        } catch (error: Throwable) {
            rootDir.deleteRecursively()
            throw error
        }
        return WorkspaceImportResult(
            rootName = rootDir.name,
            fileCount = stats.files,
            directoryCount = stats.directories,
        )
    }

    private suspend fun importChildren(
        node: WorkspaceImportNode,
        targetDir: File,
        visited: MutableSet<String>,
        stats: Stats,
        depth: Int,
    ) {
        coroutineContext.ensureActive()
        require(depth <= MAX_TREE_DEPTH) {
            "Folder tree is too deep to import (> $MAX_TREE_DEPTH levels)"
        }
        val children = node.listChildren()
        val usedNames = HashSet<String>(children.size * 2)
        for (child in children) {
            coroutineContext.ensureActive()
            // 重复 documentId / 回指祖先形成的循环都在这一行被拦住
            markVisited(child, visited)
            val sanitized = WorkspaceNames.sanitize(
                child.displayName
                    ?: error("A child of '${node.displayName}' has no display name")
            )
            val unique = findAvailableName(targetDir, sanitized, child.isDirectory, usedNames)
            usedNames += unique
            if (child.isDirectory) {
                val dir = File(targetDir, unique)
                require(dir.mkdirs() || dir.isDirectory) {
                    "Failed to create directory: $unique"
                }
                stats.directories++
                importChildren(child, dir, visited, stats, depth + 1)
            } else {
                val stream = child.openInputStream()
                    ?: error("Provider returned no content for file: ${child.displayName}")
                copyToWorkspace(stream, File(targetDir, unique))
                stats.files++
            }
        }
    }

    private fun markVisited(node: WorkspaceImportNode, visited: MutableSet<String>) {
        require(node.stableId.isNotBlank()) { "Provider returned an empty document id" }
        require(visited.add(node.stableId)) {
            "Folder tree has a repeated document id (cycle?): ${node.stableId}"
        }
    }

    /** 分块拷贝而不是 readBytes 一把梭: 大文件不撑内存, 每块之间可取消, 失败删掉半成品 */
    private suspend fun copyToWorkspace(input: InputStream, target: File) {
        try {
            input.use { source ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** 清洗后的名字既要躲开已落盘的文件/目录, 也要躲开本次导入已用过的兄弟名 */
    private fun findAvailableName(
        dir: File,
        base: String,
        isDirectory: Boolean,
        used: Set<String>,
    ): String {
        var candidate = base
        var n = 1
        while (candidate in used || File(dir, candidate).exists()) {
            // 追加序号后仍可能超过 255 字节上限, 冲突名必须重新校验长度
            candidate = WorkspaceNames.fitConflictName(base, n, isDirectory)
            n++
        }
        return candidate
    }

    private class Stats(
        var files: Int = 0,
        var directories: Int = 0,
    )

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_TREE_DEPTH = 128
    }
}

/** provider 显示名 -> 安全路径段 的清洗与冲突命名, 只在 workspace 模块内部使用 */
internal object WorkspaceNames {
    /** 斜线/反斜线/控制字符一律替换成 '_', 点号保留 (是合法文件名字符) */
    private val ILLEGAL_CHARS = Regex("[/\\\\\\u0000-\\u001F\\u007F]")

    private const val MAX_NAME_BYTES = 255
    private const val MAX_KEPT_EXTENSION_BYTES = 32

    /**
     * 把 displayName 清洗成单个安全路径段; 清洗后为空 / "." / ".." 一律抛
     * IllegalArgumentException, 调用方不得把未验证的 displayName 直接拼进路径.
     */
    fun sanitize(raw: String): String {
        var name = raw.replace(ILLEGAL_CHARS, "_").trim()
        // 尾部的点和空格在部分文件系统上不可用, ".." 这类名字也借此落空
        name = name.trimEnd('.', ' ')
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            name = truncateUtf8(name, MAX_NAME_BYTES).trimEnd('.', ' ')
        }
        require(name.isNotEmpty() && name != "." && name != "..") {
            "Unsafe file name from document provider: ${raw.take(64)}"
        }
        return name
    }

    /**
     * "name (n)" 冲突名: 文件把序号插在扩展名之前 ("a.txt" -> "a (1).txt"),
     * 目录与无扩展名文件直接追加 ("dir" -> "dir (1)")
     */
    fun conflictName(name: String, n: Int, isDirectory: Boolean): String {
        if (isDirectory) return "$name ($n)"
        val dot = name.lastIndexOf('.')
        // dot > 0: ".gitignore" 这类点开头的名字按无扩展名处理
        return if (dot > 0) {
            "${name.substring(0, dot)} ($n)${name.substring(dot)}"
        } else {
            "$name ($n)"
        }
    }

    /**
     * [conflictName] 的长度安全版: 追加 " (n)" 可能让已贴近 255 字节上限的
     * 名字超限 (File 创建会抛 ENAMETOOLONG), 超限时先给基名截断出序号后缀的空间.
     */
    fun fitConflictName(name: String, n: Int, isDirectory: Boolean): String {
        var candidate = conflictName(name, n, isDirectory)
        if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return candidate
        // 按最坏情况预留序号空间, 避免逐次重算
        val suffix = " (" + Int.MAX_VALUE + ")"
        val budget = MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size
        // 文件优先截基名、保留扩展名 ("长名.txt" -> "长… (1).txt")；扩展名本身过长时整体截
        val dot = if (isDirectory) -1 else name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val extBytes = ext.toByteArray(Charsets.UTF_8).size
        val trimmed = if (ext.isNotEmpty() && extBytes <= MAX_KEPT_EXTENSION_BYTES) {
            truncateUtf8(name.substring(0, dot), budget - extBytes).trimEnd('.', ' ') + ext
        } else {
            truncateUtf8(name, budget).trimEnd('.', ' ')
        }
        candidate = conflictName(trimmed, n, isDirectory)
        if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return candidate
        // 仍然超限 (理论上不可能, 序号不会超过 Int.MAX_VALUE 位数): 保底再截一次
        return truncateUtf8(candidate, MAX_NAME_BYTES).trimEnd('.', ' ')
    }

    private fun truncateUtf8(name: String, maxBytes: Int): String {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return name
        var end = maxBytes
        // 回退到 UTF-8 字符边界 (continuation byte 形如 10xxxxxx), 不切断多字节字符
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
