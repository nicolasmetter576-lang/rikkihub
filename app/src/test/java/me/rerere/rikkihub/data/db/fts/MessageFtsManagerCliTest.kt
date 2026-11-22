package me.rerere.rikkihub.data.db.fts

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.cli.AcpParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFtsManagerCliTest {
    @Test
    fun `prioritizes Text parts with full 10k budget before indexing acp tools`() {
        val longText = "x".repeat(10_000)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(longText),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "acp_run_command",
                    input = """{"CommandLine":"echo secret_marker"}""",
                    output = listOf(UIMessagePart.Text("secret_marker")),
                    metadata = buildJsonObject {
                        putJsonObject(AcpParts.META_KEY) {
                            put("title", "Run secret_marker")
                            put("status", "completed")
                        }
                    },
                ),
            ),
        )

        val indexed = message.extractFtsText()
        assertEquals(10_000, indexed.length)
        assertEquals(longText, indexed)
        assertFalse(indexed.contains("secret_marker"))
    }

    @Test
    fun `indexes acp tool title, paths from locations diff and rawInput, and bounded plain output`() {
        val longOutput = "line_ok " + "y".repeat(900)
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Applied changes."),
                UIMessagePart.Tool(
                    toolCallId = "call-edit",
                    toolName = "acp_edit_file",
                    input = """{"file_path":"/workspace/src/Main.kt","ignored":"val"}""",
                    output = listOf(UIMessagePart.Text(longOutput)),
                    metadata = buildJsonObject {
                        putJsonObject(AcpParts.META_KEY) {
                            put("title", "Edit Main.kt")
                            put("status", "completed")
                            put(
                                "locations",
                                buildJsonArray {
                                    add(buildJsonObject { put("path", "/workspace/src/Main.kt") })
                                    add(buildJsonObject { put("path", "/workspace/src/Helper.kt") })
                                },
                            )
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "diff")
                                            put("path", "/workspace/src/DiffTarget.kt")
                                            put("oldText", "old_body_should_not_appear")
                                            put("newText", "new_body_should_not_appear")
                                        },
                                    )
                                },
                            )
                        }
                    },
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-local",
                    toolName = "acp_run_command",
                    input = """{"cwd":"/workspace/app"}""",
                    output = listOf(UIMessagePart.Text("[completed]")),
                    metadata = buildJsonObject {
                        putJsonObject(AcpParts.META_KEY) {
                            put("local", true)
                            put("status", "completed")
                        }
                    },
                ),
            ),
        )

        val indexed = message.extractFtsText()
        assertTrue(indexed.contains("Applied changes."))
        assertTrue(indexed.contains("Edit Main.kt"))
        assertTrue(indexed.contains("/workspace/src/Main.kt"))
        assertTrue(indexed.contains("/workspace/src/Helper.kt"))
        assertTrue(indexed.contains("/workspace/src/DiffTarget.kt"))
        assertTrue(indexed.contains("line_ok"))
        assertTrue(indexed.contains("run command"))
        assertTrue(indexed.contains("/workspace/app"))
        assertFalse(indexed.contains("old_body_should_not_appear"))
        assertFalse(indexed.contains("new_body_should_not_appear"))
        assertFalse(indexed.contains("[completed]"))
        assertTrue(indexed.length <= 10_000)
    }

    @Test
    fun `skips large diffs and raw json blobs in acp tool output and enforces 10k cap`() {
        val unifiedDiff = """
            diff --git a/foo.kt b/foo.kt
            --- a/foo.kt
            +++ b/foo.kt
            @@ -1,3 +1,3 @@
            -old_line_diff_noise
            +new_line_diff_noise
        """.trimIndent()
        val rawJson = """{"status":"ok","items":[1,2,3],"blob":"json_noise_should_skip"}"""
        val bigTextPrefix = "p".repeat(9_970)

        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(bigTextPrefix),
                UIMessagePart.Tool(
                    toolCallId = "call-diff",
                    toolName = "acp_patch",
                    input = "{}",
                    output = listOf(
                        UIMessagePart.Text(unifiedDiff),
                        UIMessagePart.Text(rawJson),
                        UIMessagePart.Text("useful plain summary"),
                    ),
                    metadata = buildJsonObject {
                        putJsonObject(AcpParts.META_KEY) {
                            put("title", "Patch foo.kt")
                            put("status", "completed")
                        }
                    },
                ),
            ),
        )

        val indexed = message.extractFtsText()
        assertTrue(indexed.length <= 10_000)
        assertTrue(indexed.contains("Patch foo.kt"))
        assertFalse(indexed.contains("old_line_diff_noise"))
        assertFalse(indexed.contains("json_noise_should_skip"))
    }
}
