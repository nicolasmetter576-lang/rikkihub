package me.rerere.rikkihub.ui.pages.extensions.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceFilePreviewKindTest {

    @Test
    fun `markdown extensions are recognized`() {
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind("README.md"))
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind("notes.markdown"))
    }

    @Test
    fun `markdown extension matching is case insensitive`() {
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind("README.MD"))
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind("doc.Markdown"))
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind("DOC.mD"))
        // 多段扩展名以最后一段为准
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind("a.b.md"))
        // 点文件按后缀处理, 与 WorkspaceFileType 的扩展名约定一致 (如 .gitignore)
        assertEquals(WorkspaceFilePreviewKind.MARKDOWN, workspaceFilePreviewKind(".md"))
    }

    @Test
    fun `markdown detection works on full paths`() {
        // helper 只需要文件名片段, 但传入完整路径时也不应被目录名干扰
        assertEquals(
            WorkspaceFilePreviewKind.MARKDOWN,
            workspaceFilePreviewKind("dir.with.dots/guide.md")
        )
        assertEquals(
            WorkspaceFilePreviewKind.NONE,
            workspaceFilePreviewKind("docs.md/config.txt")
        )
    }

    @Test
    fun `web extensions map to WEB`() {
        assertEquals(WorkspaceFilePreviewKind.WEB, workspaceFilePreviewKind("index.html"))
        assertEquals(WorkspaceFilePreviewKind.WEB, workspaceFilePreviewKind("page.HTM"))
        assertEquals(WorkspaceFilePreviewKind.WEB, workspaceFilePreviewKind("icon.svg"))
    }

    @Test
    fun `non previewable names map to NONE`() {
        // 无扩展名
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("README"))
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("LICENSE"))
        // 仅在文件名中间出现的扩展名不算
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("markdown_notes.txt"))
        // 以点结尾, 扩展名为空
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("weird."))
        // 空白与空串
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind(""))
        // 普通文本/代码文件不进入 Markdown/Web 预览
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("a.txt"))
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("main.kt"))
        // 图片(非 svg)不进入 Web 预览
        assertEquals(WorkspaceFilePreviewKind.NONE, workspaceFilePreviewKind("pic.png"))
    }
}
