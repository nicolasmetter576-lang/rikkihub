package me.rerere.workspace

import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.coroutineContext

/** 单个选中项的删除结果 */
@kotlinx.serialization.Serializable
data class WorkspaceDeleteItemResult(
    /** 相对存储区根的规范化路径 */
    val path: String,
    val deleted: Boolean,
    val error: String? = null,
)

/** 一次批量删除的汇总; 已删除的条目无法回滚, 失败项保留供上层重试/展示 */
@kotlinx.serialization.Serializable
data class WorkspaceDeleteResult(
    val deletedPaths: List<String>,
    val failures: List<WorkspaceDeleteItemResult>,
) {
    val deletedCount: Int get() = deletedPaths.size
}

/**
 * 多选批量删除: 文件与目录混选, 目录递归删除.
 *
 * 契约与安全边界 (与 [WorkspaceArchiveExporter] 同一套选中解析):
 * - paths 必须是相对存储区根的路径; 空选择、存储区根本身 ("", ".", "/")
 *   和逃逸出根的路径一律拒绝, 在删任何东西之前先校验完所有选中项
 * - 祖先目录覆盖后代的重复选中项去重, 只删一次
 * - 显式选中的符号链接一律拒绝 (末端或中间组件), 不跟随链接删目标;
 *   递归遍历时遇到的链接只删链接本身, 绝不进入链接目标, 因此不会逃出存储区
 * - 逐项串行删除, 单项失败不影响其他项, 失败记入 [WorkspaceDeleteResult.failures];
 *   每个节点间检查取消, 取消抛 CancellationException (已删项不回滚)
 */
class WorkspaceBatchDeleter(
    private val fileSystem: WorkspaceFileSystem = WorkspaceFileSystem(),
) {
    suspend fun deleteBatch(root: File, paths: List<String>): WorkspaceDeleteResult =
        WorkspaceFileMutations.withSuspendingLock(root) { deleteBatchUnlocked(root, paths) }

    private suspend fun deleteBatchUnlocked(root: File, paths: List<String>): WorkspaceDeleteResult {
        coroutineContext.ensureActive()
        require(root.isDirectory) { "Storage root is not a directory: $root" }
        val rootCanonical = root.canonicalFile
        // 先整体校验再动手: 任一路径非法 (越界/根/链接) 时什么都不删
        val items = resolveWorkspaceSelection(fileSystem, rootCanonical, paths, "delete")

        val deleted = mutableListOf<String>()
        val failures = mutableListOf<WorkspaceDeleteItemResult>()
        for (item in items) {
            coroutineContext.ensureActive()
            try {
                if (deleteTree(item.lexicalFile, rootCanonical)) {
                    deleted += item.rel
                } else {
                    failures += WorkspaceDeleteItemResult(item.rel, false, "Delete failed: ${item.rel}")
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                failures += WorkspaceDeleteItemResult(item.rel, false, error.message ?: "Delete failed: ${item.rel}")
            }
        }
        return WorkspaceDeleteResult(deleted, failures)
    }

    /**
     * 不跟随符号链接的递归删除. [file] 是词法路径 (链接未解析);
     * 链接本身用 [Files.delete] 删掉, 目录链接不会进入目标.
     */
    private suspend fun deleteTree(file: File, rootCanonical: File): Boolean {
        coroutineContext.ensureActive()
        val path = file.toPath()
        if (Files.isSymbolicLink(path)) {
            return runCatching { Files.delete(path); true }.getOrDefault(false)
        }
        // 词法路径上逐组件已校验无链接; 防御性 canonical 边界检查, 防遍历途中父目录被换成链接
        val canonical = file.canonicalFile
        require(
            canonical.path == rootCanonical.path ||
                canonical.path.startsWith(rootCanonical.path + File.separator)
        ) { "Path escapes storage root during delete: ${file.path}" }

        return when {
            file.isDirectory -> {
                var ok = true
                val children = file.listFiles()
                    ?: throw IllegalStateException("Failed to list directory: ${file.path}")
                for (child in children) {
                    if (!deleteTree(child, rootCanonical)) ok = false
                }
                ok && file.delete()
            }

            else -> file.delete()
        }
    }
}
