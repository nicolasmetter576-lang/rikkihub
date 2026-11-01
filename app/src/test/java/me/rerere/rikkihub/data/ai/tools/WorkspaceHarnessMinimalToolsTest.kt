package me.rerere.rikkihub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceHarnessMinimalToolsTest {

    @Test
    fun harnessMinimalSelectsDshToolsInsteadOfRikkihubFileTools() {
        assertEquals(
            listOf(
                BuiltinToolIds.WORKSPACE_READ_FILE,
                BuiltinToolIds.WORKSPACE_WRITE_FILE,
                BuiltinToolIds.WORKSPACE_EDIT_FILE,
                BuiltinToolIds.WORKSPACE_SHELL,
                BuiltinToolIds.SUBAGENT,
            ),
            workspaceToolNamesFor(false),
        )
        assertEquals(
            listOf(BuiltinToolIds.BASH, BuiltinToolIds.STR_REPLACE_EDITOR, BuiltinToolIds.SUBAGENT),
            workspaceToolNamesFor(true),
        )
        assertTrue(workspaceToolNamesFor(true).none { it.startsWith("workspace_") })
    }

    @Test
    fun approvalItemsFollowWorkspaceMode() {
        assertEquals(
            listOf(
                BuiltinToolIds.WORKSPACE_READ_FILE,
                BuiltinToolIds.WORKSPACE_WRITE_FILE,
                BuiltinToolIds.WORKSPACE_EDIT_FILE,
                BuiltinToolIds.WORKSPACE_SHELL,
                BuiltinToolIds.SUBAGENT,
            ),
            workspaceToolApprovalItemsFor(false),
        )
        assertEquals(
            listOf(BuiltinToolIds.BASH, BuiltinToolIds.STR_REPLACE_EDITOR, BuiltinToolIds.SUBAGENT),
            workspaceToolApprovalItemsFor(true),
        )
        assertFalse(resolveWorkspaceToolApproval(BuiltinToolIds.BASH, emptyMap()))
        assertFalse(resolveWorkspaceToolApproval(BuiltinToolIds.STR_REPLACE_EDITOR, emptyMap()))
        assertFalse(resolveWorkspaceToolApproval(BuiltinToolIds.WORKSPACE_SHELL, emptyMap()))
        assertFalse(resolveWorkspaceToolApproval(BuiltinToolIds.WORKSPACE_READ_FILE, emptyMap()))
    }

    @Test
    fun strReplaceEditorRejectsAmbiguousAndMissingMatches() {
        val error = runCatching {
            applyStrReplace("foo foo", "foo", "bar", "/workspace/a.txt")
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("Multiple occurrences"))
        assertTrue(error.message!!.contains("lines [1, 1]"))

        val missing = runCatching {
            applyStrReplace("hello", "missing", "x", "/workspace/a.txt")
        }.exceptionOrNull()
        assertTrue(missing!!.message!!.contains("did not appear verbatim"))
    }

    @Test
    fun strReplaceEditorReplacesUniqueLiteral() {
        assertEquals(
            "hello kotlin\n",
            applyStrReplace("hello world\n", "world", "kotlin", "/workspace/notes.md"),
        )
    }

    @Test
    fun insertUsesZeroBasedBoundaryWithoutImplicitNewline() {
        val after = applyInsert("alpha\nbeta\n", 1, "mid")
        assertEquals("alpha\nmid\nbeta\n", after)
    }

    @Test
    fun viewRangeAndDirectoryListingMatchDsh() {
        val numbered = formatFileView(
            path = "/workspace/a.txt",
            content = "one\ntwo\nthree",
            maxOutputChars = 16_000,
            viewRange = listOf(2, 3),
        )
        assertTrue(numbered.contains("with view_range=[2, 3]"))
        assertTrue(numbered.contains("     2  two"))
        assertTrue(numbered.contains("     3  three"))
        assertFalse(numbered.contains("     1  one"))

        val listing = formatDirectoryListing(
            "/workspace",
            listOf("d\t/workspace", "f\t/workspace/a.txt", "d\t/workspace/src"),
            16_000,
        )
        assertTrue(listing.contains("up to 2 levels deep"))
        assertTrue(shouldHideDirectoryEntry("node_modules"))
        assertTrue(shouldHideDirectoryEntry("__pycache__"))
        assertTrue(shouldHideDirectoryEntry(".hidden"))
        assertFalse(shouldHideDirectoryEntry("src"))
    }

    @Test
    fun persistentBashWrapsCommandAndRendersExitCode() {
        val marker = persistentBashMarkers("nonce")
        val wrapped = wrapPersistentBashCommand("pwd", marker)
        // busybox ash 不认 "eval --"，改为参数前加空格
        assertFalse(wrapped.contains("eval --"))
        assertTrue(wrapped.contains("eval \$' pwd'"))
        assertTrue(wrapped.contains(marker.start))

        val captured = commandOutputFromScrollback(
            text = "${marker.start}\n/workspace\n${marker.end}1\n",
            marker = marker,
        )!!
        assertEquals("/workspace", captured.text)
        assertEquals(1, captured.exitCode)
        val rendered = renderPersistentBashCaptured(captured, 16_000)
        assertTrue(rendered.contains("[exit code: 1]"))
        assertFalse(rendered.contains(marker.start))
        assertFalse(rendered.contains(marker.end))
    }

    @Test
    fun persistentBashTimeoutMessageMentionsReset() {
        val message = renderPersistentBashTimeout("partial", 30_000)
        assertTrue(message.contains("timed out after 30 seconds"))
        assertTrue(message.contains("The persistent bash shell was reset"))
        assertTrue(message.contains("partial"))
    }

    @Test
    fun catalogContainsHarnessToolsAndReminder() {
        assertEquals(BuiltinToolIds.BASH, BuiltinToolCatalog.require(BuiltinToolIds.BASH).id)
        assertEquals(BuiltinToolIds.STR_REPLACE_EDITOR, BuiltinToolCatalog.require(BuiltinToolIds.STR_REPLACE_EDITOR).id)
        val reminder = BuiltinToolCatalog.require(BuiltinToolIds.HARNESS_MINIMAL_REMINDER).defaultSystemPrompt.orEmpty()
        assertTrue(reminder.contains("{{available_tools}}"))
        assertTrue(reminder.contains("Do not call `workspace_read_file`"))
        assertTrue(reminder.contains("{{extra_mounts}}"))
        val defaultReminder = BuiltinToolCatalog.require(BuiltinToolIds.WORKSPACE_REMINDER).defaultSystemPrompt.orEmpty()
        assertTrue(defaultReminder.contains("{{available_tools}}"))
        assertTrue(defaultReminder.contains("{{extra_mounts}}"))
        assertFalse(defaultReminder.contains("`str_replace_editor`"))
        val isolated = extraMountPromptLines(emptySet())
        assertTrue(isolated.contains("does not mount `/skills`"))
        assertTrue(isolated.contains("does not mount `/upload`"))
        assertFalse(isolated.contains("/tool_outputs"))
        val exposed = extraMountPromptLines(setOf("/skills", "/upload", "/tool_outputs"))
        assertTrue(exposed.contains("mounted at `/skills`"))
        assertTrue(exposed.contains("mounted at `/upload`"))
        assertTrue(exposed.contains("`/tool_outputs`"))
    }

    @Test
    fun relativePathIsRejectedLikeDsh() {
        val error = runCatching { requireAbsoluteRootfsPath("notes.md") }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("not an absolute path"))
        assertTrue(error.message!!.contains("/notes.md"))
        assertEquals("/workspace/notes.md", requireAbsoluteRootfsPath("/workspace/notes.md"))
    }

    @Test
    fun bashCommandMustBeQuotedForEval() {
        val marker = persistentBashMarkers("x")
        val wrapped = wrapPersistentBashCommand("echo 'hi'", marker)
        assertTrue(wrapped.contains("\$' echo \\'hi\\''"))
    }

    @Test
    fun wrappedCommandRunsUnderBashWithStatusAndDashLeadingCommands() {
        val bash = java.io.File("/bin/bash")
        org.junit.Assume.assumeTrue(bash.canExecute())
        val marker = persistentBashMarkers("run")
        fun run(command: String): String {
            val process = ProcessBuilder(bash.path, "-c", wrapPersistentBashCommand(command, marker))
                .redirectErrorStream(true)
                .start()
            return process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }
        val ok = commandOutputFromScrollback(run("echo 'a b'; false"), marker)!!
        assertEquals("a b", ok.text.trim())
        assertEquals(1, ok.exitCode)
        // 以 - 开头的命令按命令执行（找不到 → 127），而不是被 eval 当成选项
        assertEquals(127, commandOutputFromScrollback(run("-x"), marker)!!.exitCode)
    }
}
