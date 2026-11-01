package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/**
 * 多选批量操作 (导出 / 删除) 共用的选中项解析与安全检查.
 *
 * - [WorkspaceFileSystem.resolve] 返回 canonical 路径, 会把选中的符号链接
 *   (末端或中间组件) 静默解析成目标, 因此必须对用户给的词法路径逐组件做
 *   NOFOLLOW 检查, 保证「选中链接一律拒绝」; canonical 结果上的检查保留作
 *   口径兜底
 * - 存储区根本身 ("", ".", "/") 与逃逸出根的路径一律拒绝
 * - 祖先目录已覆盖的后代去重; 后加入的祖先把已保留的后代挤掉
 */
internal class ResolvedWorkspacePath(
    /** canonical 文件 (链接已解析), 用于存在性/类型判断 */
    val file: File,
    /** 相对存储区根的规范化路径 ("a/b" 风格) */
    val rel: String,
    /** 词法文件 (链接未解析), 用于删除链接本身而非目标 */
    val lexicalFile: File,
)

internal fun resolveWorkspaceSelection(
    fileSystem: WorkspaceFileSystem,
    rootCanonical: File,
    paths: List<String>,
    operation: String,
): List<ResolvedWorkspacePath> {
    require(paths.isNotEmpty()) { "Nothing selected to $operation" }
    val resolved = paths.map { raw ->
        val file = fileSystem.resolve(rootCanonical, raw)
        val rel = file.relativeTo(rootCanonical).path.replace(File.separatorChar, '/')
        // resolve 把 "", ".", "/" 都归一成根目录; 对整个根做批量操作不在此功能范围内
        require(rel.isNotEmpty() && rel != ".") {
            "Refusing to $operation the storage root itself"
        }
        require(file.exists()) { "Path does not exist: $raw" }
        requireNoSymlinkComponents(rootCanonical, raw, operation)
        require(!Files.isSymbolicLink(file.toPath())) {
            "Refusing to $operation symbolic link: $rel"
        }
        ResolvedWorkspacePath(file, rel, lexicalPathOf(rootCanonical, raw))
    }
    // 去重: 已被某个已保留祖先覆盖的后代直接丢弃; 后加入的祖先也要把已保留的后代挤掉
    val kept = mutableListOf<ResolvedWorkspacePath>()
    for (item in resolved) {
        if (kept.any { item.rel.startsWith("${it.rel}/") }) continue
        kept.removeAll { it.rel.startsWith("${item.rel}/") }
        if (kept.none { it.rel == item.rel }) kept += item
    }
    return kept
}

/**
 * 对 [raw] 相对 [rootCanonical] 的词法路径逐组件检查符号链接 (NOFOLLOW).
 * 中间组件是链接时末端本身不是链接, 只有逐组件检查才能拦住 "link/inside.txt".
 */
internal fun requireNoSymlinkComponents(
    rootCanonical: File,
    raw: String,
    operation: String,
) {
    val segments = raw.replace('\\', '/').trim().trimStart('/').split('/')
    var cursor = rootCanonical
    for (segment in segments) {
        if (segment.isEmpty() || segment == "." || segment == "..") continue
        cursor = File(cursor, segment)
        require(!Files.isSymbolicLink(cursor.toPath())) {
            "Refusing to $operation symbolic link: ${cursor.relativeTo(rootCanonical).path}"
        }
    }
}

/**
 * [raw] 相对 [rootCanonical] 的词法落点, 不解析任何符号链接组件.
 * 仅供已通过 resolve/越界校验的路径使用.
 */
private fun lexicalPathOf(rootCanonical: File, raw: String): File {
    val segments = raw.replace('\\', '/').trim().trimStart('/').split('/')
    var cursor = rootCanonical
    for (segment in segments) {
        if (segment.isEmpty() || segment == "." || segment == "..") continue
        cursor = File(cursor, segment)
    }
    return cursor
}
