package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示与输出截断的快照驱动行为（纯 JVM，无 Android 依赖）。
 *
 * 覆盖：全关 / 部分开启 / Override 别名 / 挂载与回读措辞 / 截断策略双分支 /
 * caps==null 旧路径逐字兼容。
 */
class WorkspaceRequestCapabilitiesTest {
    @org.junit.Test
    fun `override build fingerprint is stable and changes with prompt or wire name`() {
        val a = mapOf("bash" to me.rerere.rikkihub.data.ai.tools.ToolOverride(name = "terminal"),
            "workspace_read_file" to me.rerere.rikkihub.data.ai.tools.ToolOverride(systemPrompt = "p"))
        val reordered = a.entries.reversed().associate { it.toPair() }
        org.junit.Assert.assertEquals(
            me.rerere.rikkihub.data.ai.tools.toolOverridesFingerprint(a),
            me.rerere.rikkihub.data.ai.tools.toolOverridesFingerprint(reordered),
        )
        org.junit.Assert.assertNotEquals(
            me.rerere.rikkihub.data.ai.tools.toolOverridesFingerprint(a),
            me.rerere.rikkihub.data.ai.tools.toolOverridesFingerprint(
                a + ("bash" to me.rerere.rikkihub.data.ai.tools.ToolOverride(name = "changed"))),
        )
    }


    private fun caps(
        harnessMinimal: Boolean = false,
        tools: Map<String, String>,
        mounts: Set<String> = setOf("/skills", "/upload", "/tool_outputs", "/subagent_runs"),
    ) = WorkspaceRequestCapabilities(
        workspaceId = "w1",
        workspaceName = "ws",
        harnessMinimal = harnessMinimal,
        availableToolNames = tools,
        exposedTargets = mounts,
        policyEpoch = 7L,
        viewFingerprint = "root:/x:mode:normal",
    )

    private val normalAllOn = mapOf(
        BuiltinToolIds.WORKSPACE_READ_FILE to "workspace_read_file",
        BuiltinToolIds.WORKSPACE_WRITE_FILE to "workspace_write_file",
        BuiltinToolIds.WORKSPACE_EDIT_FILE to "workspace_edit_file",
        BuiltinToolIds.WORKSPACE_SHELL to "workspace_shell",
        BuiltinToolIds.SUBAGENT to "subagent",
    )

    private val minimalAllOn = mapOf(
        BuiltinToolIds.BASH to "bash",
        BuiltinToolIds.STR_REPLACE_EDITOR to "str_replace_editor",
        BuiltinToolIds.SUBAGENT to "subagent",
    )

    // ---------- reminder block: all-off ----------

    @Test
    fun `all tools off produces unavailable line and recommends nothing`() {
        val block = availableWorkspaceToolsBlock(caps(tools = emptyMap()))
        assertTrue(block.contains("unavailable"))
        listOf(
            "workspace_read_file", "workspace_write_file", "workspace_edit_file",
            "workspace_shell", "subagent", "bash", "str_replace_editor",
        ).forEach { assertFalse("must not mention $it", block.contains("`$it`")) }
        assertFalse(block.contains("Available tools"))
        assertFalse(block.contains("Prefer"))

        val prompt = workspaceReminderPrompt(
            workspaceName = "ws", harnessMinimal = false,
            exposedTargets = caps(tools = emptyMap()).exposedTargets,
            cwd = null, overrides = emptyMap(),
            capabilities = caps(tools = emptyMap()),
        )
        assertFalse(prompt.contains("`workspace_shell`"))
        assertFalse(prompt.contains("`subagent`"))
        assertTrue(prompt.contains("/workspace"))
        // 无回读工具时挂载行不指引读文件
        assertTrue(prompt.contains("no file-reading tool is enabled"))
        // 附件解析提示仍如实说明挂载（不假称可读）
        assertTrue(prompt.contains("`/upload`"))
    }

    @Test
    fun `all tools off in minimal mode keeps do-not-call line and lists nothing`() {
        val prompt = workspaceReminderPrompt(
            workspaceName = "ws", harnessMinimal = true,
            exposedTargets = emptySet(), cwd = null, overrides = emptyMap(),
            capabilities = caps(harnessMinimal = true, tools = emptyMap()),
        )
        assertTrue(prompt.contains("Do not call `workspace_read_file`"))
        assertFalse(prompt.contains("`bash`"))
        assertFalse(prompt.contains("`str_replace_editor`"))
        assertTrue(prompt.contains("unavailable"))
    }

    // ---------- partial enablement ----------

    @Test
    fun `partial enablement lists only enabled tools by wire name`() {
        val c = caps(
            tools = mapOf(
                BuiltinToolIds.WORKSPACE_READ_FILE to "workspace_read_file",
                BuiltinToolIds.WORKSPACE_SHELL to "ws_exec", // Override 改名
            )
        )
        val block = availableWorkspaceToolsBlock(c)
        assertTrue(block.contains("`workspace_read_file`"))
        assertTrue(block.contains("`ws_exec`"))
        assertFalse(block.contains("`workspace_shell`"))
        assertFalse(block.contains("`workspace_write_file`"))
        assertFalse(block.contains("`subagent`"))
        // 只有 shell 无 editor → Prefer 只提 shell
        assertTrue(block.contains("Prefer `ws_exec`"))
        assertFalse(block.contains("workspace_edit_file"))
    }

    @Test
    fun `minimal partial enablement with editor only prefers editor`() {
        val c = caps(
            harnessMinimal = true,
            tools = mapOf(BuiltinToolIds.STR_REPLACE_EDITOR to "my_editor"),
        )
        val block = availableWorkspaceToolsBlock(c)
        assertTrue(block.contains("`my_editor`"))
        assertFalse(block.contains("`bash`"))
        assertTrue(block.contains("Prefer `my_editor`"))
    }

    // ---------- caps==null legacy path ----------

    @Test
    fun `null capabilities reproduces the legacy fixed block verbatim`() {
        val normal = availableWorkspaceToolsBlock(null, harnessMinimalFallback = false)
        assertTrue(normal.contains("`workspace_read_file`: read file contents."))
        assertTrue(normal.contains("`workspace_write_file` / `workspace_edit_file`"))
        assertTrue(normal.contains("`workspace_shell`: run shell commands"))
        assertTrue(normal.contains("`subagent`"))
        assertTrue(normal.contains("Prefer `workspace_shell`"))
        assertTrue(normal.contains("prefer `workspace_edit_file` for targeted edits"))

        val minimal = availableWorkspaceToolsBlock(null, harnessMinimalFallback = true)
        assertTrue(minimal.contains("`bash`: a persistent bash shell"))
        assertTrue(minimal.contains("`str_replace_editor`: view, create"))
        assertTrue(minimal.contains("prefer `str_replace_editor` with `str_replace`"))

        // 旧路径挂载文案逐字保留（无回读弱化措辞）
        val prompt = workspaceReminderPrompt(
            workspaceName = "ws", harnessMinimal = false,
            exposedTargets = setOf("/skills", "/upload", "/tool_outputs"),
            cwd = "/workspace", overrides = emptyMap(), capabilities = null,
        )
        assertTrue(prompt.contains("mounted at `/skills`"))
        assertTrue(prompt.contains("mounted at `/upload`. Treat `/upload` as READ-ONLY"))
        assertTrue(prompt.contains("Use the shell to read those files"))
        assertTrue(prompt.contains("- Current working directory: `/workspace`"))
    }

    // ---------- mounts + readback wording ----------

    @Test
    fun `mount lines describe mounts but omit read guidance without readback tool`() {
        val lines = workspaceMountPromptLines(
            setOf("/skills", "/upload", "/tool_outputs"),
            canReadFiles = false,
        )
        assertTrue(lines.contains("mounted at `/skills`"))
        assertTrue(lines.contains("use_skill"))
        assertTrue(lines.contains("mounted at `/upload`"))
        assertFalse(lines.contains("READ-ONLY"))          // 不指引读写细节
        assertTrue(lines.contains("`/tool_outputs` is mounted"))
        assertFalse(lines.contains("Use the shell to read"))
    }

    // ---------- capabilities helpers ----------

    @Test
    fun `capability helpers reflect real availability`() {
        val none = caps(tools = emptyMap())
        assertFalse(none.hasReadbackTool)
        assertNull(none.readbackStableId)
        assertNull(none.readbackWireName)
        assertTrue(none.exposesToolOutputs)
        assertFalse(none.canSaveToolOutputs)

        val readerOnly = caps(tools = mapOf(BuiltinToolIds.WORKSPACE_READ_FILE to "r"))
        assertTrue(readerOnly.hasReadbackTool)
        assertEquals(BuiltinToolIds.WORKSPACE_READ_FILE, readerOnly.readbackStableId)
        assertTrue(readerOnly.canSaveToolOutputs)

        val noMount = caps(
            tools = mapOf(BuiltinToolIds.WORKSPACE_SHELL to "workspace_shell"),
            mounts = emptySet(),
        )
        assertTrue(noMount.hasReadbackTool)
        assertFalse(noMount.exposesToolOutputs)
        assertFalse(noMount.canSaveToolOutputs)

        // shell 族优先于查看族
        val both = caps(tools = normalAllOn)
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, both.readbackStableId)
        val minimalBoth = caps(harnessMinimal = true, tools = minimalAllOn)
        assertEquals(BuiltinToolIds.BASH, minimalBoth.readbackStableId)
    }

    // ---------- truncation policy ----------

    @Test
    fun `truncation below threshold returns null`() {
        assertNull(planToolOutputTruncation(10, 32 * 1024, caps(tools = normalAllOn), fallbackCanSave = true))
    }

    @Test
    fun `caps with readback and mount persists full output and guides by stableId`() {
        val plan = planToolOutputTruncation(
            totalChars = 100_000, maxOutputChars = 32 * 1024,
            capabilities = caps(tools = normalAllOn),
            fallbackCanSave = false,
        )!!
        assertTrue(plan.persistFullOutput)
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, plan.readbackStableId)

        val notice = plan.renderNotice(fileName = "out.txt", preview = "PREVIEW")
        assertTrue(notice.contains("[Tool output truncated: 100000 characters total]"))
        assertTrue(notice.contains("/tool_outputs/out.txt"))
        assertTrue(notice.contains("cat /tool_outputs/out.txt"))
        assertTrue(notice.endsWith("PREVIEW"))
    }

    @Test
    fun `caps with bash uses bash wording`() {
        val plan = planToolOutputTruncation(
            totalChars = 100_000, maxOutputChars = 32 * 1024,
            capabilities = caps(harnessMinimal = true, tools = minimalAllOn),
            fallbackCanSave = false,
        )!!
        assertEquals(BuiltinToolIds.BASH, plan.readbackStableId)
        val notice = plan.renderNotice(fileName = "o.txt", preview = "p")
        assertTrue(notice.contains("bash tool"))
        assertTrue(notice.contains("cat /tool_outputs/o.txt"))
    }

    @Test
    fun `caps without readback or mount does not persist and says truncated`() {
        val noTool = planToolOutputTruncation(
            totalChars = 100_000, maxOutputChars = 32 * 1024,
            capabilities = caps(tools = mapOf(BuiltinToolIds.WORKSPACE_WRITE_FILE to "w")),
            fallbackCanSave = true, // fallback 不影响 caps 分支
        )!!
        assertFalse(noTool.persistFullOutput)
        assertNull(noTool.readbackWireName)
        val notice = noTool.renderNotice(fileName = null, preview = "P")
        assertTrue(notice.contains("[Tool output truncated"))
        assertTrue(notice.contains("not saved"))
        assertFalse(notice.contains("/tool_outputs/"))

        val noMount = planToolOutputTruncation(
            totalChars = 100_000, maxOutputChars = 32 * 1024,
            capabilities = caps(tools = normalAllOn, mounts = setOf("/skills")),
            fallbackCanSave = true,
        )!!
        assertFalse(noMount.persistFullOutput)
    }

    @Test
    fun `null capabilities uses fallback legacy behavior`() {
        val persisted = planToolOutputTruncation(
            totalChars = 100_000, maxOutputChars = 32 * 1024,
            capabilities = null, fallbackCanSave = true,
        )!!
        assertTrue(persisted.persistFullOutput)
        assertEquals(BuiltinToolIds.WORKSPACE_SHELL, persisted.readbackStableId)
        val notice = persisted.renderNotice("f.txt", "pv")
        assertTrue(notice.contains("Use shell to read: `cat /tool_outputs/f.txt`"))

        val unsaved = planToolOutputTruncation(
            totalChars = 100_000, maxOutputChars = 32 * 1024,
            capabilities = null, fallbackCanSave = false,
        )!!
        assertFalse(unsaved.persistFullOutput)
    }

    // ---------- user override not projected ----------

    @Test
    fun `user override template without available_tools placeholder is not projected`() {
        val custom = "CUSTOM workspace prompt for {{workspace_name}} and {{extra_mounts}}"
        val prompt = workspaceReminderPrompt(
            workspaceName = "ws", harnessMinimal = false,
            exposedTargets = setOf("/upload"), cwd = null,
            overrides = mapOf(
                BuiltinToolIds.WORKSPACE_REMINDER to ToolOverride(systemPrompt = custom)
            ),
            capabilities = caps(tools = normalAllOn),
        )
        assertTrue(prompt.contains("CUSTOM workspace prompt for ws"))
        // 自定义模板没有 {{available_tools}} → 不强行塞工具清单
        assertFalse(prompt.contains("workspace_shell"))
    }

    // ---------- rootfs shell / distribution ----------

    @Test
    fun `rootfs environment lines describe the shell and distribution`() {
        val alpine = rootfsEnvironmentPromptLines(
            me.rerere.workspace.RootfsEnvironment(shell = "/bin/sh", distro = "Alpine Linux v3.24")
        )
        assertTrue(alpine.contains("The Rootfs distribution is Alpine Linux v3.24"))
        assertTrue(alpine.contains("`/bin/sh`, a POSIX shell. Bash is not installed"))
        val ubuntu = rootfsEnvironmentPromptLines(me.rerere.workspace.RootfsEnvironment("/bin/bash", null))
        assertEquals("- Shell commands run with `/bin/bash`.\n", ubuntu)
        assertEquals("", rootfsEnvironmentPromptLines(null))
    }

    @Test
    fun `default reminder renders the captured rootfs environment and survives a re-render`() {
        val env = rootfsEnvironmentPromptLines(me.rerere.workspace.RootfsEnvironment("/bin/sh", "Alpine Linux v3.24"))
        val capabilities = caps(tools = normalAllOn).copy(rootfsEnvironment = env)
        val prompt = workspaceReminderPrompt(
            workspaceName = "ws", harnessMinimal = false,
            exposedTargets = emptySet(), cwd = null,
            overrides = emptyMap(),
            capabilities = capabilities,
        )
        assertTrue(prompt.contains("The Rootfs distribution is Alpine Linux v3.24"))
        assertFalse(prompt.contains("{{rootfs_environment}}"))
        // 默认模板里环境行在工具清单前面，旧环境（没有 rootfs 信息）不留空行
        val bare = workspaceReminderPrompt(
            workspaceName = "ws", harnessMinimal = false,
            exposedTargets = emptySet(), cwd = null,
            overrides = emptyMap(),
            capabilities = caps(tools = normalAllOn),
        )
        assertFalse(bare.contains("Rootfs distribution"))
        assertFalse(bare.contains("\n\n\n"))
    }
}
