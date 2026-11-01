package me.rerere.rikkihub.ui.pages.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring only; these guards do not claim bitmap, MediaStore, permission or chooser UI acceptance. */
class ConversationExportWiringTest {
    private fun source(relative: String): String {
        val base = File("src/main/java/me/rerere/rikkihub")
            .takeIf { it.isDirectory } ?: File("app/src/main/java/me/rerere/rikkihub")
        return File(base, relative).readText()
    }

    @Test fun `both export actions await the outcome guard and freeze their options`() {
        val page = source("ui/pages/chat/Export.kt")
        assertEquals(2, Regex("performConversationExport\\(").findAll(page).count())
        assertEquals(2, Regex("val messages = selectedMessages.toList\\(\\)").findAll(page).count())
        assertEquals(2, Regex("val options = imageExportOptions").findAll(page).count())
        assertFalse(page.contains("runCatching {" + "\n                                            exportToImage"))
    }

    @Test fun `reasoning exclusion feeds image grouping before empty message check`() {
        val page = source("ui/pages/chat/Export.kt").substringAfter("private fun ExportedChatMessage(")
        assertTrue(page.contains("exportedMessageParts(message.parts, options)"))
        assertTrue(page.contains("if (parts.isEmptyUIMessage()) return"))
        assertTrue(page.contains("parts.groupMessageParts()"))
        assertTrue(page.contains("ThinkingStep.ServerToolStep"))
    }

    @Test fun `image exporter propagates failures and always recycles its acquired bitmap`() {
        val page = source("ui/pages/chat/Export.kt").substringAfter("private suspend fun exportToImage(")
            .substringBefore("private fun ExportedChatImage(")
        assertFalse(page.contains("catch ("))
        assertTrue(page.contains("throwOnFailure = true"))
        assertTrue(page.contains("check(bitmap.compress("))
        assertTrue(page.contains("finally {\n        bitmap.recycle()"))
    }

    @Test fun `strict gallery path reports null insert stream encoding and permission failures`() {
        val util = source("utils/ContextUtil.kt").substringAfter("fun Context.exportImage(")
            .substringBefore("fun Context.exportImageFile(")
        assertTrue(util.contains("throwOnFailure: Boolean = false"))
        assertTrue(util.contains("if (throwOnFailure) error(\"Gallery permission requested"))
        assertTrue(util.contains("?: error(\"Could not create gallery image\")"))
        assertTrue(util.contains("?: error(\"Could not open gallery image\")"))
        assertEquals(2, Regex("check\\(bitmap.compress").findAll(util).count())
        assertTrue(util.contains("if (throwOnFailure) throw e"))
    }

    @Test fun `expand switch is disabled when excluding reasoning or exporting`() {
        val page = source("ui/pages/chat/Export.kt")
        assertTrue(page.contains("enabled = imageExportOptions.includeReasoning && !exporting"))
        assertTrue(page.contains("R.string.chat_page_export_include_reasoning"))
        assertTrue(page.contains("R.string.chat_page_export_failed"))
    }
}
