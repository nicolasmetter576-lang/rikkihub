package me.rerere.rikkihub.ui.pages.extensions.workspace

import me.rerere.rikkihub.data.workspace.WORKSPACE_TEXT_EXTENSIONS
import me.rerere.workspace.WorkspaceFileEntry

/**
 * 工作区文件的粗略分类, 用于决定点击文件时的行为:
 * - TEXT: 应用内文本编辑/预览
 * - IMAGE: 应用内可缩放图片预览
 * - OTHER: 交给系统应用 (视频/音频/文档等) 打开
 */
enum class WorkspaceFileType { TEXT, IMAGE, OTHER }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

fun WorkspaceFileEntry.detectFileType(): WorkspaceFileType {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext.isEmpty() -> WorkspaceFileType.OTHER
        ext in IMAGE_EXTENSIONS -> WorkspaceFileType.IMAGE
        ext in WORKSPACE_TEXT_EXTENSIONS -> WorkspaceFileType.TEXT
        else -> WorkspaceFileType.OTHER
    }
}
