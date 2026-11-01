package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceDeviceDirectoryGuard

/**
 * 设备目录连接页的可注入纯逻辑（UI 校验/展示映射），不依赖 Android/服务实例，
 * 供 `app/src/test` 直接做单测。
 */

enum class WorkspaceDeviceAliasViolation {
    /** 空 / 全空白 */
    EMPTY,

    /** 不匹配 `^[a-z][a-z0-9_-]{0,31}$`（见 [WorkspaceDeviceDirectoryGuard.ALIAS_REGEX]） */
    FORMAT,
}

/**
 * 校验连接 alias。规则与服务端 `WorkspaceDeviceDirectoryGuard` 完全一致：
 * `^[a-z][a-z0-9_-]{0,31}$`。仅做 UI 前置校验，真正约束仍以服务/DAO 为准。
 */
fun validateDeviceMountAlias(alias: String): WorkspaceDeviceAliasViolation? {
    val trimmed = alias.trim()
    if (trimmed.isEmpty()) return WorkspaceDeviceAliasViolation.EMPTY
    return if (WorkspaceDeviceDirectoryGuard.ALIAS_REGEX.matches(trimmed)) {
        null
    } else {
        WorkspaceDeviceAliasViolation.FORMAT
    }
}

/**
 * 把存储的「主卷根相对 source 路径」转为展示用的友好名称：
 * - 永不回传绝对路径（调用方拿到的本来就不是绝对路径，这里再显式剥一次
 *   `/`/`..` 兜底，防日志/UI 泄露卷根前缀）；
 * - 分隔符统一为 `/`，仅用于显示。
 */
fun deviceMountDisplayPath(sourceRelativePath: String): String {
    val normalized = sourceRelativePath
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." && it != ".." }
        .joinToString("/")
    return normalized.ifEmpty { "/" }
}
