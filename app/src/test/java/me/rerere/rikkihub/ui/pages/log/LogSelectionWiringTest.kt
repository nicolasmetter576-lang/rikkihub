package me.rerere.rikkihub.ui.pages.log

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring guard only; actual cross-window long-press/clipboard needs Android acceptance. */
class LogSelectionWiringTest {
    private fun source(relative: String): String {
        val base = File("src/main/java/me/rerere/rikkihub")
            .takeIf { it.isDirectory } ?: File("app/src/main/java/me/rerere/rikkihub")
        return File(base, relative).readText()
    }

    @Test fun `JSON string sheet owns a selection container inside its own window`() {
        val sheet = source("ui/components/ui/JsonTree.kt").substringAfter("selectedString?.let { content ->")
            .substringBefore("private fun JsonNode")
        assertTrue(sheet.contains("ModalBottomSheet("))
        assertTrue(Regex("SelectionContainer\\s*\\{\\s*Text\\(").containsMatchIn(sheet))
        assertTrue(sheet.indexOf("SelectionContainer {") > sheet.indexOf("ModalBottomSheet("))
        assertTrue(sheet.contains("text = content"))
    }

    @Test fun `body copy uses the guarded suspend path and localized error feedback`() {
        val page = source("ui/pages/log/LogPage.kt")
        assertTrue(page.contains("copyLogBodyToClipboard("))
        assertTrue(page.contains("ClipData.newPlainText(\"Request Body\", body)"))
        assertTrue(page.contains("R.string.log_page_copy_body_failed"))
        assertTrue(page.contains("toaster.show(copyFailureMessage, type = ToastType.Error)"))
    }

    @Test fun `provider drag handle and callbacks both reject a filtered list`() {
        val page = source("ui/pages/setting/SettingProviderPage.kt")
        // 按 key 在最新的提供商列表上重放（搜索词决定可见子集）
        assertTrue(page.contains("val query = currentSearchQuery"))
        assertTrue(page.contains("reorderProvidersByKey(latest.providers, query, from.key, to.key)"))
        assertTrue(Regex("longPressDraggableHandle\\(\\s*enabled = searchQuery.isBlank\\(\\)").containsMatchIn(page))
        assertTrue(!page.contains("removeAt(from.index)"))
    }
}
