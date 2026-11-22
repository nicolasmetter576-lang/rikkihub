package me.rerere.rikkihub.data.cli

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpPartsTitleDiffTest {
    private fun tool(
        toolName: String,
        title: String? = null,
        local: Boolean = false,
        content: List<Map<String, String>>? = null,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = toolName,
        input = "{}",
        metadata = buildJsonObject {
            putJsonObject(AcpParts.META_KEY) {
                title?.let { put("title", it) }
                if (local) put("local", true)
                content?.let { items ->
                    put(
                        "content",
                        buildJsonArray {
                            items.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        entry.forEach { (k, v) -> put(k, v) }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        },
    )

    @Test
    fun `titleOf uses metadata title normalized to single line`() {
        val part = tool(toolName = "acp_run_command", title = "  Run\ntests  ")
        assertEquals("Run tests", AcpParts.titleOf(part))
    }

    @Test
    fun `titleOf preserves local bang command title`() {
        val part = tool(toolName = "acp_run_command", title = "!git status", local = true)
        assertEquals("!git status", AcpParts.titleOf(part))
    }

    @Test
    fun `titleOf handles acp_ask_question with and without metadata title`() {
        val withTitle = tool(toolName = AcpParts.ASK_QUESTION, title = "Choose target branch")
        assertEquals("Choose target branch", AcpParts.titleOf(withTitle))

        val withoutTitle = tool(toolName = AcpParts.ASK_QUESTION)
        assertEquals("ask question", AcpParts.titleOf(withoutTitle))
    }

    @Test
    fun `titleOf falls back to formatted tool name when title is blank or missing`() {
        val blankTitle = tool(toolName = "acp_edit_file", title = "   \n  ")
        assertEquals("edit file", AcpParts.titleOf(blankTitle))

        val noMetadata = UIMessagePart.Tool(toolCallId = "call-2", toolName = "acp_read_file", input = "{}")
        assertEquals("read file", AcpParts.titleOf(noMetadata))
    }

    @Test
    fun `unifiedDiffOf builds unified diff for diff content items`() {
        val part = tool(
            toolName = "acp_edit",
            content = listOf(
                mapOf("type" to "terminal", "terminalId" to "t1"),
                mapOf("type" to "diff", "path" to "src/Main.kt", "oldText" to "val a = 1\n", "newText" to "val a = 2\n"),
            ),
        )
        val diff = AcpParts.unifiedDiffOf(part)!!
        assertTrue(diff.contains("--- a/src/Main.kt"))
        assertTrue(diff.contains("+++ b/src/Main.kt"))
        assertTrue(diff.contains("-val a = 1"))
        assertTrue(diff.contains("+val a = 2"))
    }

    @Test
    fun `unifiedDiffOf returns null when no diff items or identical content`() {
        val noDiff = tool(
            toolName = "acp_run_command",
            content = listOf(mapOf("type" to "terminal", "terminalId" to "t1")),
        )
        assertNull(AcpParts.unifiedDiffOf(noDiff))

        val identical = tool(
            toolName = "acp_edit",
            content = listOf(mapOf("type" to "diff", "path" to "a.txt", "oldText" to "same", "newText" to "same")),
        )
        assertNull(AcpParts.unifiedDiffOf(identical))
    }
}
