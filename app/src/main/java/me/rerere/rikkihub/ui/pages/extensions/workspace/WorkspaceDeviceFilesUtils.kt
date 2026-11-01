package me.rerere.rikkihub.ui.pages.extensions.workspace

import java.io.Serializable

/**
 * 设备文件面板的纯路径工具。设备文件的身份 = mountId + 相对 mount.source 的
 * 路径（分隔符统一 `/`，无绝对宿主路径）。这些 helper 不信任 display name，
 * 只处理相对路径片段。
 */

/** 规范化相对路径：去首尾空白/斜杠、`\`→`/`、丢弃空段与 `.`；`..` 原样抛错。 */
internal fun normalizeDeviceRelativePath(path: String): String {
    val normalized = path.replace('\\', '/')
    val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
    require(parts.none { it == ".." }) { "Path escapes the connected directory" }
    return parts.joinToString("/")
}

/** 父目录相对路径；根返回 ""。 */
internal fun deviceParentPath(path: String): String {
    val normalized = normalizeDeviceRelativePath(path)
    return normalized.substringBeforeLast('/', missingDelimiterValue = "")
}

/** 子路径拼接（child 可以是相对多级路径；仍不含 `..`/绝对路径）。 */
internal fun deviceChildPath(parent: String, child: String): String {
    val joined = listOf(parent, child)
        .filter { it.isNotBlank() }
        .joinToString("/")
    return normalizeDeviceRelativePath(joined)
}

/** 从相对路径取文件名（末段）。 */
internal fun deviceFileName(path: String): String =
    normalizeDeviceRelativePath(path).substringAfterLast('/')

/**
 * 「移动/重命名」目标合法性：非空、不逃逸、不等于源路径本身。
 * 返回规范化后的目标；非法返回 null（供 UI 禁用确认键）。
 */
internal fun validDeviceMoveTarget(source: String, target: String): String? {
    val normalized = runCatching { normalizeDeviceRelativePath(target) }.getOrNull()
        ?: return null
    if (normalized.isBlank()) return null
    if (normalized == normalizeDeviceRelativePath(source)) return null
    return normalized
}

/** SAF 文件夹导入发起前快照的目标目录。 */
internal data class PendingDeviceFolderImport(
    val destinationPath: String,
) : Serializable

/** CreateDocument 导出前快照的源路径（单文件导出）。 */
internal data class PendingDeviceFileExport(
    val path: String,
    val suggestedName: String,
) : Serializable

/** ZIP 导出快照：勾选/目录在 picker 打开期间可能变化，固定发起时路径集。 */
internal data class PendingDeviceZipExport(
    val paths: List<String>,
    val suggestedName: String,
) : Serializable

/** 批量删除确认快照：确认时绝不回读当前选择。 */
internal data class PendingDeviceBatchDelete(
    val paths: List<String>,
    val includesDirectories: Boolean,
) : Serializable
