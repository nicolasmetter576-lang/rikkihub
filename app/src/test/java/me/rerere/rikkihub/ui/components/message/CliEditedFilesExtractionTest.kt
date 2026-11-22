package me.rerere.rikkihub.ui.components.message

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.cli.AcpParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliEditedFilesExtractionTest {
    private fun acpTool(
        name: String,
        kind: String? = null,
        status: String? = "completed",
        input: JsonObject = JsonObject(emptyMap()),
        content: JsonArray? = null,
        locations: JsonArray? = null,
        local: Boolean = false,
        executed: Boolean = true,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "call-$name-${kind ?: "none"}",
        toolName = if (name.startsWith(AcpParts.TOOL_PREFIX)) name else AcpParts.TOOL_PREFIX + name,
        input = input.toString(),
        output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
        approvalState = approvalState,
        metadata = buildJsonObject {
            putJsonObject(AcpParts.META_KEY) {
                kind?.let { put("kind", it) }
                status?.let { put("status", it) }
                if (local) put("local", true)
                content?.let { put("content", it) }
                locations?.let { put("locations", it) }
            }
        },
    )

    private fun diffItem(path: String, oldText: String = "a", newText: String = "b"): JsonObject = buildJsonObject {
        put("type", "diff")
        put("path", path)
        put("oldText", oldText)
        put("newText", newText)
    }

    private fun locationItem(path: String): JsonObject = buildJsonObject {
        put("path", path)
    }

    private fun nativeTool(
        name: String,
        input: JsonObject,
        executed: Boolean = true,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = "native-$name",
        toolName = name,
        input = input.toString(),
        output = if (executed) listOf(UIMessagePart.Text("""{"ok":true}""")) else emptyList(),
        approvalState = approvalState,
    )

    @Test
    fun `acp extraction respects diff then locations then rawInput priority and deduplicates in order`() {
        val diffWins = acpTool(
            name = "edit",
            kind = "edit",
            content = buildJsonArray {
                add(buildJsonObject { put("type", "content") })
                add(diffItem("/workspace/from_diff.kt"))
                add(diffItem("relative_diff.kt"))
            },
            locations = buildJsonArray { add(locationItem("/workspace/from_location.kt")) },
            input = buildJsonObject { put("path", "/workspace/from_input.kt") },
        )
        val locationsWin = acpTool(
            name = "edit",
            kind = "edit",
            locations = buildJsonArray {
                add(locationItem("/workspace/from_location.kt"))
                add(locationItem("/workspace/from_diff.kt")) // duplicate of earlier diff item
            },
            input = buildJsonObject { put("path", "/workspace/from_input.kt") },
        )
        val inputFallback = acpTool(
            name = "write",
            kind = "edit",
            input = buildJsonObject {
                put("AbsolutePath", "/workspace/abs.kt")
                put("TargetFile", "/workspace/target.kt")
            },
        )
        val extraInputKeys = listOf(
            acpTool("write_file", kind = "edit", input = buildJsonObject { put("file_path", "fp1.kt") }),
            acpTool("write_file", kind = "edit", input = buildJsonObject { put("filePath", "fp2.kt") }),
            acpTool("write_file", kind = "edit", input = buildJsonObject { put("target_file", "tf.kt") }),
            acpTool("write_file", kind = "edit", input = buildJsonObject { put("destination", "dest1.kt") }),
            acpTool("write_file", kind = "edit", input = buildJsonObject { put("Destination", "dest2.kt") }),
            acpTool("write_file", kind = "edit", input = buildJsonObject { put("AbsolutePath", "/workspace/abs_only.kt") }),
        )

        val extracted = extractEditedFilePaths(
            parts = listOf(diffWins, locationsWin, inputFallback) + extraInputKeys,
        )

        assertEquals(
            listOf(
                "/workspace/from_diff.kt",
                "/workspace/relative_diff.kt",
                "/workspace/from_location.kt",
                "/workspace/target.kt",
                "/workspace/fp1.kt",
                "/workspace/fp2.kt",
                "/workspace/tf.kt",
                "/workspace/dest1.kt",
                "/workspace/dest2.kt",
                "/workspace/abs_only.kt",
            ),
            extracted,
        )
    }

    @Test
    fun `acp move prefers diff then rawInput destination then last location`() {
        val moveWithDiff = acpTool(
            name = "move",
            kind = "move",
            content = buildJsonArray { add(diffItem("/workspace/diff_dest.txt")) },
            locations = buildJsonArray {
                add(locationItem("/workspace/old.txt"))
                add(locationItem("/workspace/loc_dest.txt"))
            },
            input = buildJsonObject { put("destination", "/workspace/input_dest.txt") },
        )
        val moveWithDestinationInput = acpTool(
            name = "move",
            kind = "move",
            locations = buildJsonArray {
                add(locationItem("/workspace/old.txt"))
                add(locationItem("/workspace/loc_dest.txt"))
            },
            input = buildJsonObject {
                put("path", "/workspace/old.txt")
                put("destination", "/workspace/input_dest.txt")
            },
        )
        val moveWithLocationsOnly = acpTool(
            name = "move",
            kind = "move",
            locations = buildJsonArray {
                add(locationItem("/workspace/old.txt"))
                add(locationItem("/workspace/new_from_loc.txt"))
            },
            input = buildJsonObject { put("source", "/workspace/old.txt") },
        )

        assertEquals(
            listOf("/workspace/diff_dest.txt", "/workspace/input_dest.txt", "/workspace/new_from_loc.txt"),
            extractEditedFilePaths(listOf(moveWithDiff, moveWithDestinationInput, moveWithLocationsOnly)),
        )
    }

    @Test
    fun `acp null or other kind requires diff or known mutation name and never ask_question`() {
        val customWithDiff = acpTool(
            name = "custom_tool",
            kind = "other",
            content = buildJsonArray { add(diffItem("custom.txt")) },
        )
        val writeWithoutKind = acpTool(
            name = "write_to_file",
            kind = null,
            input = buildJsonObject { put("TargetFile", "written.txt") },
        )
        val otherUnknownTool = acpTool(
            name = "other",
            kind = "other",
            input = buildJsonObject { put("path", "/workspace/ignored.txt") },
        )
        val askQuestionOther = acpTool(
            name = AcpParts.ASK_QUESTION,
            kind = "other",
            input = buildJsonObject { put("path", "/workspace/ignored_q.txt") },
        )
        val askQuestionEdit = acpTool(
            name = AcpParts.ASK_QUESTION,
            kind = "edit",
            content = buildJsonArray { add(diffItem("/workspace/ignored_q2.txt")) },
        )
        val strReplaceView = acpTool(
            name = "str_replace_editor",
            kind = "other",
            input = buildJsonObject { put("command", "view"); put("path", "/workspace/viewed.txt") },
        )

        assertEquals(
            listOf("/workspace/custom.txt", "/workspace/written.txt"),
            extractEditedFilePaths(
                listOf(customWithDiff, writeWithoutKind, otherUnknownTool, askQuestionOther, askQuestionEdit, strReplaceView)
            ),
        )
    }

    @Test
    fun `rejects non-mutation kinds, unfinished, failed, denied, and local commands`() {
        val deniedKinds = listOf("delete", "read", "search", "fetch", "think", "plan", "execute").map { kind ->
            acpTool(
                name = "write_file",
                kind = kind,
                content = buildJsonArray { add(diffItem("/workspace/$kind.txt")) },
                locations = buildJsonArray { add(locationItem("/workspace/$kind.txt")) },
                input = buildJsonObject { put("path", "/workspace/$kind.txt") },
            )
        }
        val failed = acpTool(
            name = "edit",
            kind = "edit",
            status = "failed",
            input = buildJsonObject { put("path", "/workspace/failed.txt") },
        )
        val notExecuted = acpTool(
            name = "edit",
            kind = "edit",
            status = "completed",
            executed = false,
            input = buildJsonObject { put("path", "/workspace/pending.txt") },
        )
        val deniedApproval = acpTool(
            name = "edit",
            kind = "edit",
            status = "completed",
            approvalState = ToolApprovalState.Denied("no"),
            input = buildJsonObject { put("path", "/workspace/denied.txt") },
        )
        val localCmd = acpTool(
            name = "shell",
            kind = "edit",
            status = "completed",
            local = true,
            input = buildJsonObject { put("path", "/workspace/local.txt") },
        )

        val extracted = extractEditedFilePaths(
            deniedKinds + listOf(failed, notExecuted, deniedApproval, localCmd)
        )
        assertEquals(emptyList<String>(), extracted)
    }

    @Test
    fun `path normalization resolves relative paths and rejects traversal and non-workspace targets`() {
        assertEquals(
            "/workspace/sub/dir/file.kt",
            resolvePreviewableAcpPath("./dir//file.kt", workspaceCwd = "/workspace/sub/", workspaceCwdScoped = false),
        )
        assertEquals(
            "/workspace/sub/.gitignore",
            resolvePreviewableAcpPath(".gitignore", workspaceCwd = "sub", workspaceCwdScoped = true),
        )
        assertEquals(
            "/workspace/a..b.txt",
            resolvePreviewableAcpPath("a..b.txt", workspaceCwd = null, workspaceCwdScoped = false),
        )

        val invalidPaths = listOf(
            "",
            "   ",
            "/",
            "/workspace",
            "/workspace/",
            "dir/",
            ".",
            "dir/.",
            "..",
            "../secret.txt",
            "sub/../a.txt",
            "/workspace/../etc/passwd",
            "/workspace/a/../a/file.txt",
            "/workspacex/file.txt",
            "foo\u0000bar.txt",
            "foo.txt\u0000",
            "foo\\bar.txt",
            "file:///workspace/a.txt",
            "http://example.com/a.txt",
            "https://example.com/a.txt",
            "content://media/1",
            "C:/workspace/a.txt",
            "//workspace/a.txt",
            "/device/sdcard/a.txt",
            "/etc/passwd",
            "/tmp/a.txt",
            "/dev/null",
            "/proc/1/status",
            "/sys/kernel/x",
        )
        for (bad in invalidPaths) {
            assertNull("Expected null for '$bad'", resolvePreviewableAcpPath(bad, workspaceCwd = "/workspace/sub", workspaceCwdScoped = false))
        }
    }

    @Test
    fun `higher priority invalid path does not fall back to lower priority fields`() {
        val badDiffGoodInput = acpTool(
            name = "edit",
            kind = "edit",
            content = buildJsonArray { add(diffItem("/etc/passwd")) },
            input = buildJsonObject { put("path", "/workspace/allowed.txt") },
        )
        val badLocationGoodInput = acpTool(
            name = "edit",
            kind = "edit",
            locations = buildJsonArray { add(locationItem("../escape.txt")) },
            input = buildJsonObject { put("path", "/workspace/allowed.txt") },
        )
        assertEquals(emptyList<String>(), extractEditedFilePaths(listOf(badDiffGoodInput, badLocationGoodInput)))
    }

    @Test
    fun `scope confinement enforces workspaceCwdScoped and fails closed on invalid workspaceCwd`() {
        val inside = acpTool(name = "write", kind = "edit", input = buildJsonObject { put("path", "/workspace/notes/a.txt") })
        val relative = acpTool(name = "write", kind = "edit", input = buildJsonObject { put("path", "b.txt") })
        val outside = acpTool(name = "write", kind = "edit", input = buildJsonObject { put("path", "/workspace/other/secret.txt") })
        val prefixCollision = acpTool(name = "write", kind = "edit", input = buildJsonObject { put("path", "/workspace/notes_extra/c.txt") })

        assertEquals(
            listOf("/workspace/notes/a.txt", "/workspace/notes/b.txt"),
            extractEditedFilePaths(
                parts = listOf(inside, relative, outside, prefixCollision),
                workspaceCwd = "/workspace/notes",
                workspaceCwdScoped = true,
            ),
        )
        assertEquals(
            listOf(
                "/workspace/notes/a.txt",
                "/workspace/notes/b.txt",
                "/workspace/other/secret.txt",
                "/workspace/notes_extra/c.txt",
            ),
            extractEditedFilePaths(
                parts = listOf(inside, relative, outside, prefixCollision),
                workspaceCwd = "/workspace/notes",
                workspaceCwdScoped = false,
            ),
        )
        for (badCwd in listOf("/etc", "../bad", "a\\b", "a\u0000b")) {
            assertEquals(
                "Invalid cwd '$badCwd' must fail closed",
                emptyList<String>(),
                extractEditedFilePaths(
                    parts = listOf(inside, relative),
                    workspaceCwd = badCwd,
                    workspaceCwdScoped = true,
                ),
            )
            assertNull(resolvePreviewableAcpPath("/workspace/notes/a.txt", workspaceCwd = badCwd, workspaceCwdScoped = false))
        }
    }

    @Test
    fun `native workspace file tools preserve existing contract including out-of-scope previewability`() {
        val parts = listOf(
            nativeTool("workspace_write_file", buildJsonObject { put("path", "/workspace/notes/preview.txt"); put("text", "hi") }),
            nativeTool("workspace_write_file", buildJsonObject { put("path", "/workspace/other/private.txt"); put("text", "secret") }),
            nativeTool("workspace_edit_file", buildJsonObject { put("path", "/workspace/notes/edited.kt") }),
            nativeTool("workspace_write_file", buildJsonObject { put("path", "/workspace/notes/unexecuted.txt") }, executed = false),
            // 被中断/关闭、从未执行（unavailable）的写调用没有写过文件
            nativeTool("workspace_write_file", buildJsonObject { put("path", "/workspace/notes/interrupted.txt") }).copy(
                output = listOf(UIMessagePart.Text("""{"status":"unavailable","reason":"RESPONSE_INTERRUPTED"}""")),
            ),
            nativeTool("str_replace_editor", buildJsonObject { put("command", "view"); put("path", "/workspace/notes/view.txt") }),
            nativeTool("str_replace_editor", buildJsonObject { put("command", "create"); put("path", "/workspace/notes/created.txt") }),
            nativeTool("str_replace_editor", buildJsonObject { put("command", "str_replace"); put("path", "/workspace/notes/replaced.txt") }),
            nativeTool("str_replace_editor", buildJsonObject { put("command", "insert"); put("path", "/workspace/notes/inserted.txt") }),
        )

        assertEquals(
            listOf(
                "/workspace/notes/preview.txt",
                "/workspace/other/private.txt",
                "/workspace/notes/edited.kt",
                "/workspace/notes/created.txt",
                "/workspace/notes/replaced.txt",
                "/workspace/notes/inserted.txt",
            ),
            extractEditedFilePaths(
                parts = parts,
                workspaceCwd = "/workspace/notes",
                workspaceCwdScoped = true,
            ),
        )
    }
}
