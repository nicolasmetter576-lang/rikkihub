package me.rerere.rikkihub.data.ai.tools

import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.workspace.workspaceReminderPrompt

/**
 * Provider 级 wire 工具改名（T-DEVIN-RESERVED）。
 *
 * 背景：Devin 上游（devin-cli 的 Codeium 后端）对部分名字做 MCP 配置校验。
 * 实测 `bash`（harness_minimal 工作区的持久 bash 工具）声明进 Field 10 即
 * `permission_denied: Unable to process request due to an MCP configuration
 * issue`，而 `str_replace_editor`/`subagent`/`workspace_*` 均放行。同一
 * 保留名校验也可能覆盖历史消息里回放的调用名，因此改名必须端到端同源：
 *
 * - `tools`/`bindings`：`bash` → `rikki_bash`，模型回调与绑定签发
 *   （wireName → workspaceIdentity.stableId）全部命中新名，执行端不受影响；
 * - `workspaceCapabilities.availableToolNames`（stableId → wire 名）与
 *   `reminderPrompt` 同步重渲，提示里的工具清单和声明一致；
 * - 历史调用名经 `TextGenerationParams.toolWireRenames` 在 Devin wire 构建
 *   时回放成发出名；
 * - `forbiddenWorkspaceToolNames`/`wirePolicy` 不改：改名只发生在启用工具上，
 *   禁用 `bash` 时它根本不在 tools 里；但别名不得撞上禁用别名表（用户把
 *   别的工具 Override 成 `rikki_bash` 时换一个备选名）。
 */

/** Devin 上游实测会按 MCP 配置校验的保留 wire 名 → 首选别名。 */
private val DEVIN_RESERVED_WIRE_TOOL_NAMES: Map<String, String> = mapOf(
    BuiltinToolIds.BASH to "rikki_bash",
)

private const val MAX_ALIAS_ATTEMPTS = 10

/**
 * 按 provider 要求重映射本快照的工具 wire 名。非 Devin provider 或无命中时
 * 原样返回；命中时 [ChatToolSnapshot.wireRenames] 记录原 wire 名 → 发出名，
 * 供生成循环写进 `TextGenerationParams.toolWireRenames`。
 *
 * 冲突处理：目标别名已被其它工具占用或与禁用 workspace 别名重合时，依次
 * 尝试 `<base>_2..<base>_10`；全部失败则该工具保持原名（让上游真实报错，
 * 不静默伪装成已修复）。
 */
internal fun ChatToolSnapshot.withProviderWireToolRenames(
    provider: ProviderSetting,
    overrides: Map<String, ToolOverride>,
): ChatToolSnapshot {
    if (provider !is ProviderSetting.Devin) return this

    val taken = tools.mapTo(HashSet(tools.size)) { it.name }
    val forbidden = forbiddenWorkspaceToolNames.orEmpty()

    fun allocate(base: String): String? {
        for (i in 0..MAX_ALIAS_ATTEMPTS) {
            val candidate = if (i == 0) base else "${base}_${i + 1}"
            if (candidate !in taken && candidate !in forbidden) return candidate
        }
        return null
    }

    // 逐工具解析改名（同名在装配层已因 bindings 查重不可能重复出现）
    val renames = LinkedHashMap<String, String>()
    val newTools = tools.map { tool ->
        val alias = DEVIN_RESERVED_WIRE_TOOL_NAMES[tool.name]?.let(::allocate)
        if (alias != null) {
            renames[tool.name] = alias
            tool.copy(name = alias)
        } else {
            tool
        }
    }
    if (renames.isEmpty()) return this

    val newCapabilities = workspaceCapabilities?.let { caps ->
        val newNames = caps.availableToolNames.mapValues { (_, wire) -> renames[wire] ?: wire }
        val renamed = caps.copy(availableToolNames = newNames)
        renamed.copy(
            reminderPrompt = caps.reminderPrompt?.let {
                workspaceReminderPrompt(
                    workspaceName = caps.workspaceName,
                    harnessMinimal = caps.harnessMinimal,
                    exposedTargets = caps.exposedTargets,
                    cwd = caps.instructionsCwd,
                    overrides = overrides,
                    capabilities = renamed,
                )
            },
        )
    }

    return copy(
        tools = newTools,
        bindings = buildToolBindings(newTools),
        workspaceCapabilities = newCapabilities,
        wireRenames = renames,
    )
}
