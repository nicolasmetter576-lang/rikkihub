package me.rerere.rikkihub.ui.components.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkihub.ui.components.message.tools.ShellToolUI
import me.rerere.rikkihub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackGetScreenTimePreview
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackReadFilePreview
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackScrapeWebPreview
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackSearchWebPreview
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackShellPreview
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackSubagentPreview
import me.rerere.rikkihub.ui.components.message.tools.shouldFallbackWriteFilePreview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPreviewEligibilityTest {
    private fun toolContext(
        toolName: String,
        arguments: JsonElement = JsonObject(emptyMap()),
        content: JsonElement? = null,
        executed: Boolean = content != null,
        output: List<UIMessagePart> = if (executed) listOf(UIMessagePart.Text("output")) else emptyList(),
        metadata: JsonObject? = null,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        loading: Boolean = false,
    ): ToolUIContext = ToolUIContext(
        tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = toolName,
            input = arguments.toString(),
            output = output,
            approvalState = approvalState,
            metadata = metadata,
        ),
        arguments = arguments,
        content = content,
        loading = loading,
    )

    @Test
    fun `WriteFileToolUI falls back on missing text, non-object output, or error output after execution`() {
        val argsWithText = buildJsonObject {
            put("path", "/workspace/a.txt")
            put("text", "hello")
        }

        // Unexecuted (streaming / pending approval) with text argument previews the proposed file.
        assertFalse(shouldFallbackWriteFilePreview(toolContext("workspace_write_file", argsWithText, content = null, executed = false)))
        // Missing text argument always falls back.
        assertTrue(shouldFallbackWriteFilePreview(toolContext("workspace_write_file", buildJsonObject { put("path", "/workspace/a.txt") }, content = null, executed = false)))

        // Executed successfully with JSON object result previews the file.
        assertFalse(
            shouldFallbackWriteFilePreview(
                toolContext(
                    toolName = "workspace_write_file",
                    arguments = argsWithText,
                    content = buildJsonObject { put("path", "/workspace/a.txt"); put("sizeBytes", 5) },
                    executed = true,
                )
            )
        )

        // Executed with error object, non-JSON (null content), JSON array, or JSON primitive falls back to DefaultToolPreview.
        assertTrue(
            shouldFallbackWriteFilePreview(
                toolContext(
                    toolName = "workspace_write_file",
                    arguments = argsWithText,
                    content = buildJsonObject { put("error", "Permission denied") },
                    executed = true,
                )
            )
        )
        assertTrue(
            shouldFallbackWriteFilePreview(
                toolContext(
                    toolName = "workspace_write_file",
                    arguments = argsWithText,
                    content = null,
                    executed = true,
                    output = listOf(UIMessagePart.Text("[Tool output truncated]")),
                )
            )
        )
        assertTrue(
            shouldFallbackWriteFilePreview(
                toolContext(
                    toolName = "workspace_write_file",
                    arguments = argsWithText,
                    content = JsonArray(emptyList()),
                    executed = true,
                )
            )
        )
        assertTrue(
            shouldFallbackWriteFilePreview(
                toolContext(
                    toolName = "workspace_write_file",
                    arguments = argsWithText,
                    content = JsonPrimitive("plain"),
                    executed = true,
                )
            )
        )
    }

    @Test
    fun `SubagentToolUI falls back only when executed without metadata and without JSON object output`() {
        val guardPlainText = toolContext(
            toolName = "subagent",
            content = null,
            executed = true,
            output = listOf(UIMessagePart.Text("Guard blocked subagent call")),
        )
        assertTrue(shouldFallbackSubagentPreview(guardPlainText))

        val arrayWithoutMetadata = toolContext(
            toolName = "subagent",
            content = JsonArray(listOf(JsonPrimitive("bad"))),
            executed = true,
        )
        assertTrue(shouldFallbackSubagentPreview(arrayWithoutMetadata))

        // Truncated log still keeps SubagentMetadata -> enters SubagentRunPreview.
        val truncatedWithToolMetadata = toolContext(
            toolName = "subagent",
            content = null,
            executed = true,
            output = listOf(UIMessagePart.Text("[Tool output truncated: 20000 characters total]")),
            metadata = SubagentMetadata(title = "Task", status = "ok", logPath = "/subagent_runs/1.json").toMetadata(),
        )
        assertFalse(shouldFallbackSubagentPreview(truncatedWithToolMetadata))

        val truncatedWithOutputMetadata = toolContext(
            toolName = "subagent",
            content = null,
            executed = true,
            output = listOf(
                UIMessagePart.Text(
                    text = "[Tool output truncated: 20000 characters total]",
                    metadata = SubagentMetadata(title = "Task", status = "ok", logPath = "/subagent_runs/2.json").toMetadata(),
                )
            ),
        )
        assertFalse(shouldFallbackSubagentPreview(truncatedWithOutputMetadata))

        // Legacy JSON output produces SubagentMetadata via fallback parser -> enters SubagentRunPreview.
        val legacyJson = toolContext(
            toolName = "subagent",
            content = buildJsonObject { put("status", "ok"); put("step_count", 2) },
            executed = true,
        )
        assertFalse(shouldFallbackSubagentPreview(legacyJson))

        // Unexecuted subagent enters SubagentRunPreview (shows running/prompt state).
        val unexecuted = toolContext(
            toolName = "subagent",
            arguments = buildJsonObject { put("prompt", "Inspect repo") },
            content = null,
            executed = false,
            loading = true,
        )
        assertFalse(shouldFallbackSubagentPreview(unexecuted))
    }

    @Test
    fun `ShellToolUI hasSummary and Preview require JsonObject content`() {
        assertFalse(ShellToolUI.hasSummary(toolContext("workspace_shell", content = null, executed = false)))
        assertFalse(ShellToolUI.hasSummary(toolContext("workspace_shell", content = null, executed = true)))
        assertFalse(ShellToolUI.hasSummary(toolContext("workspace_shell", content = JsonArray(emptyList()), executed = true)))
        assertFalse(ShellToolUI.hasSummary(toolContext("workspace_shell", content = JsonPrimitive("truncated"), executed = true)))
        assertFalse(ShellToolUI.hasSummary(toolContext("workspace_shell", content = JsonNull, executed = true)))

        assertTrue(shouldFallbackShellPreview(toolContext("workspace_shell", content = null, executed = true)))
        assertTrue(shouldFallbackShellPreview(toolContext("workspace_shell", content = JsonArray(emptyList()), executed = true)))
        assertTrue(shouldFallbackShellPreview(toolContext("workspace_shell", content = JsonPrimitive(42), executed = true)))

        val validShell = toolContext(
            toolName = "workspace_shell",
            content = buildJsonObject { put("exitCode", 0); put("stdout", "ok"); put("stderr", ""); put("timedOut", false) },
            executed = true,
        )
        assertTrue(ShellToolUI.hasSummary(validShell))
        assertFalse(shouldFallbackShellPreview(validShell))
    }

    @Test
    fun `ReadFile SearchWeb ScrapeWeb GetScreenTime guard against null and non-object JSON`() {
        for (badContent in listOf(null, JsonArray(emptyList()), JsonPrimitive("raw"), JsonNull)) {
            assertTrue(shouldFallbackReadFilePreview(toolContext("workspace_read_file", content = badContent, executed = true)))
            assertTrue(shouldFallbackSearchWebPreview(toolContext("search_web", content = badContent, executed = true)))
            assertTrue(shouldFallbackScrapeWebPreview(toolContext("scrape_web", content = badContent, executed = true)))
            assertTrue(shouldFallbackGetScreenTimePreview(toolContext("get_screen_time", content = badContent, executed = true)))
        }

        assertTrue(
            shouldFallbackReadFilePreview(
                toolContext("workspace_read_file", content = buildJsonObject { put("error", "ENOENT") }, executed = true)
            )
        )
        assertFalse(
            shouldFallbackReadFilePreview(
                toolContext("workspace_read_file", content = buildJsonObject { put("text", "hello") }, executed = true)
            )
        )

        assertFalse(
            shouldFallbackSearchWebPreview(
                toolContext("search_web", content = buildJsonObject { put("items", buildJsonArray {}) }, executed = true)
            )
        )

        assertTrue(
            shouldFallbackScrapeWebPreview(
                toolContext("scrape_web", content = buildJsonObject { put("error", "timeout") }, executed = true)
            )
        )
        assertFalse(
            shouldFallbackScrapeWebPreview(
                toolContext("scrape_web", content = buildJsonObject { put("urls", buildJsonArray {}) }, executed = true)
            )
        )

        assertTrue(
            shouldFallbackGetScreenTimePreview(
                toolContext("get_screen_time", content = buildJsonObject { put("error", "NO_PERMISSION") }, executed = true)
            )
        )
        assertFalse(
            shouldFallbackGetScreenTimePreview(
                toolContext(
                    toolName = "get_screen_time",
                    content = buildJsonObject {
                        put("apps", buildJsonArray { add(buildJsonObject { put("app_name", "App") }) })
                    },
                    executed = true,
                )
            )
        )
    }
}
