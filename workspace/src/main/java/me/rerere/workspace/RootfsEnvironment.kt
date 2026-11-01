package me.rerere.workspace

import java.io.File
import java.nio.file.Files

/** Rootfs 实际使用的 shell 与发行版名称，写进工作区系统提示，避免模型默认按 bash / apt 写命令。 */
data class RootfsEnvironment(
    val shell: String,
    val distro: String?,
)

private const val MAX_OS_RELEASE_BYTES = 16L * 1024
private const val MAX_DISTRO_NAME_LENGTH = 80
private val OS_RELEASE_PATHS = listOf("etc/os-release", "usr/lib/os-release")

/** 从 os-release 内容中取发行版名称，优先 PRETTY_NAME，其次 NAME + VERSION_ID */
fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    // 内容来自 Rootfs 内的文件，拼进系统提示前去掉控制字符并限制长度
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).trim().ifBlank { null }
}

/**
 * 直接在宿主机上读 Rootfs 的 os-release（不启动 proot）。文件不存在、过大或不可读时发行版为 null。
 * 绝对路径软链（宿主机上解析不到）按 Rootfs 内的路径解析。
 */
fun readRootfsEnvironment(linuxDir: File): RootfsEnvironment = RootfsEnvironment(
    shell = WorkspaceManager.rootfsShell(linuxDir),
    distro = OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
        runCatching { linuxDir.resolveInRootfs(path) }.getOrNull()
            ?.takeIf { it.isFile && it.length() <= MAX_OS_RELEASE_BYTES }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.let(::parseOsReleaseName)
    },
)

private fun File.resolveInRootfs(path: String, depth: Int = 0): File {
    val file = File(this, path)
    if (depth >= 8 || !Files.isSymbolicLink(file.toPath())) return file
    val target = Files.readSymbolicLink(file.toPath()).toString()
    return if (target.startsWith("/")) resolveInRootfs(target.trimStart('/'), depth + 1) else file
}
