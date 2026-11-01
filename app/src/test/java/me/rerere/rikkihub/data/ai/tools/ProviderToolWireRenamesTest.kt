package me.rerere.rikkihub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.ui.WorkspaceToolMode
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import me.rerere.rikkihub.data.workspace.workspaceReminderPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [withProviderWireToolRenames] 的离线回归：Devin 上游把 `bash` 当保留名
 * 做 MCP 配置校验（实测 permission_denied），启用时必须端到端改名：
 * tools/bindings/capabilities/reminder/wireRenames 同源，非 Devin 不动。
 */
class ProviderToolWireRenamesTest {
    private val devin = ProviderSetting.Devin()
    private val openAi = ProviderSetting.OpenAI(apiKey = "k")

    private fun wsTool(name: String, stableId: String = name) = Tool(
        name = name,
        description = "desc-$name",
        workspaceIdentity = WorkspaceToolIdentity(
            workspaceId = "ws-1",
            stableId = stableId,
            mode = WorkspaceToolMode.HARNESS_MINIMAL,
        ),
        execute = { emptyList() },
    )

    private fun harnessCaps(): WorkspaceRequestCapabilities {
        val base = WorkspaceRequestCapabilities(
            workspaceId = "ws-1",
            workspaceName = "test",
            harnessMinimal = true,
            availableToolNames = mapOf(
                BuiltinToolIds.BASH to "bash",
                BuiltinToolIds.STR_REPLACE_EDITOR to "str_replace_editor",
                BuiltinToolIds.SUBAGENT to "subagent",
            ),
            exposedTargets = emptySet(),
        )
        return base.copy(
            reminderPrompt = workspaceReminderPrompt(
                workspaceName = "test",
                harnessMinimal = true,
                exposedTargets = emptySet(),
                cwd = null,
                overrides = emptyMap(),
                capabilities = base,
            )
        )
    }

    private fun snapshotWith(tools: List<Tool>, caps: WorkspaceRequestCapabilities? = null) =
        ChatToolSnapshot(
            tools = tools,
            bindings = buildToolBindings(tools),
            workspaceCapabilities = caps,
        )

    @Test
    fun `bash is renamed end to end for devin`() {
        val tools = listOf(
            wsTool("bash"),
            wsTool("str_replace_editor"),
            wsTool("subagent"),
        )
        val snapshot = snapshotWith(tools, harnessCaps())
            .withProviderWireToolRenames(devin, emptyMap())

        // 声明面
        assertEquals(
            listOf("rikki_bash", "str_replace_editor", "subagent"),
            snapshot.tools.map { it.name },
        )
        // bindings 按新名解析且身份不变
        assertEquals("bash", snapshot.bindings["rikki_bash"]?.workspaceIdentity?.stableId)
        assertNull(snapshot.bindings["bash"])
        // 改名表记录原 wire 名 → 发出名
        assertEquals(mapOf("bash" to "rikki_bash"), snapshot.wireRenames)
        // capabilities 与 reminder 同源展示发出名
        val caps = snapshot.workspaceCapabilities!!
        assertEquals("rikki_bash", caps.availableToolNames[BuiltinToolIds.BASH])
        assertTrue(caps.reminderPrompt!!.contains("`rikki_bash`"))
        assertTrue(!caps.reminderPrompt!!.contains("`bash`"))
    }

    @Test
    fun `non devin provider is untouched`() {
        val snapshot = snapshotWith(listOf(wsTool("bash")), harnessCaps())
        assertSame(snapshot, snapshot.withProviderWireToolRenames(openAi, emptyMap()))
    }

    @Test
    fun `no reserved tool emits no renames`() {
        val snapshot = snapshotWith(
            listOf(wsTool("str_replace_editor"), wsTool("subagent")),
            harnessCaps(),
        )
        assertSame(snapshot, snapshot.withProviderWireToolRenames(devin, emptyMap()))
    }

    @Test
    fun `alias collision falls back to numbered alias`() {
        val tools = listOf(
            wsTool("rikki_bash", stableId = "custom_tool"),
            wsTool("bash"),
        )
        val snapshot = snapshotWith(tools, null)
            .withProviderWireToolRenames(devin, emptyMap())

        assertEquals("rikki_bash_2", snapshot.bindings.keys.firstOrNull {
            snapshot.bindings[it]?.workspaceIdentity?.stableId == "bash"
        })
        assertEquals(mapOf("bash" to "rikki_bash_2"), snapshot.wireRenames)
        // 重名不会出现：bindings 构建仍会抛 DuplicateToolWireNameException
        assertEquals(tools.size, snapshot.tools.size)
    }

    @Test
    fun `alias colliding forbidden name is skipped`() {
        val tools = listOf(wsTool("bash"))
        val snapshot = ChatToolSnapshot(
            tools = tools,
            bindings = buildToolBindings(tools),
            forbiddenWorkspaceToolNames = setOf("rikki_bash", "bash"),
        ).withProviderWireToolRenames(devin, emptyMap())

        // `rikki_bash` 撞禁用别名 → 落到 _2；原 wire 名保持映射给历史回放
        assertEquals("rikki_bash_2", snapshot.tools.single().name)
        assertEquals(mapOf("bash" to "rikki_bash_2"), snapshot.wireRenames)
    }

    @Test
    fun `overridden bash wire name is not a reserved literal and stays`() {
        // 用户把 bash Override 成 `sh`：发出的不是保留字面量，不再二次改名。
        val snapshot = snapshotWith(listOf(wsTool("sh", stableId = "bash")), null)
            .withProviderWireToolRenames(devin, emptyMap())
        assertSame(snapshot, snapshot.withProviderWireToolRenames(devin, emptyMap()))
        assertEquals("sh", snapshot.tools.single().name)
    }
}
