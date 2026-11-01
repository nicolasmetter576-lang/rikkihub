package me.rerere.rikkihub.data.workspace

import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.builtinToolPlaceholders
import me.rerere.rikkihub.data.ai.tools.extraMountPromptLines
import me.rerere.rikkihub.data.ai.tools.interpolateToolText
import me.rerere.rikkihub.data.ai.tools.resolveBuiltinToolId

/**
 * 工作区提示/输出截断的纯函数辅助（无 Android 依赖，可 JVM 单测）。
 * 「真实能力」一律来自 [WorkspaceRequestCapabilities]；capabilities == null 的分支
 * 逐字复刻旧固定文案，供固定 tools 的旧调用方保持兼容。
 */

/** 模板占位符：整条「Available tools + Prefer …」块（reminder 默认模板专用）。 */
const val WORKSPACE_TOOLS_PLACEHOLDER = "available_tools"
const val ROOTFS_ENVIRONMENT_PLACEHOLDER = "rootfs_environment"

private fun legacyToolLines(harnessMinimal: Boolean): List<String> = if (harnessMinimal) {
    listOf(
        "  - `bash`: a persistent bash shell. Current directory and environment persist across calls.",
        "  - `str_replace_editor`: view, create, replace, or insert files. Paths must be absolute inside Rootfs (for example `/workspace/notes.md`).",
        "  - `subagent`: dispatch a nested workspace worker for a bounded task. It cannot call `subagent` itself.",
    )
} else {
    listOf(
        "  - `workspace_read_file`: read file contents.",
        "  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.",
        "  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).",
        "  - `subagent`: dispatch a nested workspace worker for a bounded task. It cannot call `subagent` itself.",
    )
}

/** caps 模式下单个已启用工具的提示行，使用 wire 名（Override 后真实发出的名字）。 */
private fun capabilityToolLine(stableId: String, wireName: String): String = when (stableId) {
    BuiltinToolIds.WORKSPACE_READ_FILE -> "  - `$wireName`: read file contents."
    BuiltinToolIds.WORKSPACE_WRITE_FILE -> "  - `$wireName`: create files."
    BuiltinToolIds.WORKSPACE_EDIT_FILE -> "  - `$wireName`: make precise edits to existing files."
    BuiltinToolIds.WORKSPACE_SHELL ->
        "  - `$wireName`: run shell commands (the files area is mounted at /workspace)."
    BuiltinToolIds.BASH ->
        "  - `$wireName`: a persistent bash shell. Current directory and environment persist across calls."
    BuiltinToolIds.STR_REPLACE_EDITOR ->
        "  - `$wireName`: view, create, replace, or insert files. Paths must be absolute inside Rootfs (for example `/workspace/notes.md`)."
    BuiltinToolIds.SUBAGENT ->
        "  - `$wireName`: dispatch a nested workspace worker for a bounded task. It cannot dispatch further subagents."
    else -> "  - `$wireName`"
}

private fun shellWireName(caps: WorkspaceRequestCapabilities): String? =
    caps.wireNameOf(BuiltinToolIds.BASH) ?: caps.wireNameOf(BuiltinToolIds.WORKSPACE_SHELL)

private fun editorWireName(caps: WorkspaceRequestCapabilities): String? =
    caps.wireNameOf(BuiltinToolIds.WORKSPACE_EDIT_FILE) ?: caps.wireNameOf(BuiltinToolIds.STR_REPLACE_EDITOR)

private fun preferLine(harnessMinimal: Boolean, shell: String?, editor: String?): String = when {
    shell != null && editor != null ->
        "- Prefer `$shell` for tasks that standard Unix tools handle well, and prefer `$editor` " +
            (if (harnessMinimal) "with `str_replace` for targeted edits over rewriting whole files."
            else "for targeted edits over rewriting whole files.")
    shell != null -> "- Prefer `$shell` for tasks that standard Unix tools handle well."
    editor != null -> "- Prefer `$editor` for targeted edits over rewriting whole files."
    else -> ""
}

/**
 * 「Available tools」整块（含标题行与 Prefer 行）。
 * - [capabilities] == null：按 [harnessMinimalFallback] 渲染旧固定清单（与旧模板字面一致）。
 * - 非 null：按 [WorkspaceRequestCapabilities.availableToolNames] 的真实 wire 名渲染；
 *   空表（全关）不推荐任何工具，仅输出一行说明。
 */
fun availableWorkspaceToolsBlock(
    capabilities: WorkspaceRequestCapabilities?,
    harnessMinimalFallback: Boolean = false,
): String {
    if (capabilities == null) {
        return buildString {
            appendLine("- Available tools:")
            legacyToolLines(harnessMinimalFallback).forEach { appendLine(it) }
            append("- Prefer `" + (if (harnessMinimalFallback) "bash" else "workspace_shell") +
                "` for tasks that standard Unix tools handle well, and prefer `" +
                (if (harnessMinimalFallback) "str_replace_editor` with `str_replace`" else "workspace_edit_file`") +
                " for targeted edits over rewriting whole files.")
        }
    }
    val ordered = LinkedHashSet<String>().apply {
        addAll(WorkspaceRequestCapabilities.READBACK_TOOL_IDS)
        add(BuiltinToolIds.WORKSPACE_WRITE_FILE)
        add(BuiltinToolIds.SUBAGENT)
        addAll(capabilities.availableToolNames.keys)
    }
    val lines = ordered.mapNotNull { stableId ->
        capabilities.availableToolNames[stableId]?.let { capabilityToolLine(stableId, it) }
    }
    return buildString {
        if (lines.isEmpty()) {
            append("- Workspace tools are currently unavailable for this workspace.")
        } else {
            appendLine("- Available tools:")
            lines.forEach { appendLine(it) }
            val prefer = preferLine(
                harnessMinimal = capabilities.harnessMinimal,
                shell = shellWireName(capabilities),
                editor = editorWireName(capabilities),
            )
            if (prefer.isNotBlank()) append(prefer)
        }
    }.trimEnd()
}

/**
 * 挂载目标提示行（caps 路径用；旧路径仍用 [extraMountPromptLines] 保持逐字兼容）。
 * [canReadFiles] = capabilities.hasReadbackTool：没有可用读/编辑/shell 工具时，
 * 挂载行不指引「读文件」，只说挂载存在 —— 不虚构可回读能力。
 */
fun workspaceMountPromptLines(
    exposedTargets: Set<String>,
    canReadFiles: Boolean,
): String = buildString {
    append(deviceDirectoryPromptLines(exposedTargets))
    if ("/skills" in exposedTargets) {
        if (canReadFiles) {
            appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<directory-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
        } else {
            appendLine("- The skills directory is mounted at `/skills`, but no file-reading tool is enabled; use `use_skill` for installed skills.")
        }
    } else {
        appendLine("- This workspace does not mount `/skills`. Use `use_skill` for installed skills.")
    }
    append(builtinSkillsMountGuidance(exposedTargets))
    if ("/upload" in exposedTargets) {
        if (canReadFiles) {
            appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
        } else {
            appendLine("- Files the user uploaded are mounted at `/upload`, but no file-reading tool is enabled.")
        }
    } else {
        appendLine("- This workspace does not mount `/upload`. Chat attachments are provided in the conversation text, not as files under `/upload`.")
    }
    if (WorkspaceRequestCapabilities.TOOL_OUTPUTS_GUEST_PATH in exposedTargets) {
        if (canReadFiles) {
            appendLine("- Truncated tool output may be saved at `/tool_outputs`. Use the shell to read those files if a tool result says the full output was saved there.")
        } else {
            appendLine("- `/tool_outputs` is mounted for saved tool outputs, but no file-reading or shell tool is enabled to read them.")
        }
    }
}

/** Fixed confinement line: only the selected directory exists under `/workspace`. */
fun workspaceScopePromptLine(filesScope: String): String {
    val path = me.rerere.workspace.WorkspaceFilesScope.guestPath(filesScope)
    return "- You are confined to `$path`. Nothing else under `/workspace` exists for you, " +
        "and there are no other workspace files to look for. Keep all work inside `$path`; " +
        "anything written elsewhere under `/workspace` is not saved.\n"
}

/** Management read-only guidance, not an OS/PRoot write restriction. Only actual exposure. */
fun builtinSkillsMountGuidance(exposedTargets: Set<String>): String {
    if ("/builtin_skills" !in exposedTargets) return ""
    return "- Packaged skills are mounted separately at `/builtin_skills/<directory-name>/`. " +
        "Treat this tree as READ-ONLY: copy a skill to `/skills` to customize it. " +
        "User skills take precedence by frontmatter name, not by directory name. " +
        "This is a management policy, not an OS restriction; shell writes can modify the bound source.\n"
}

/** Only validated guest aliases, never source host paths. */
fun deviceDirectoryPromptLines(exposedTargets: Set<String>): String {
    val paths = exposedTargets.filter { it.matches(Regex("/device/[a-z][a-z0-9_-]{0,31}")) }.sorted()
    if (paths.isEmpty()) return ""
    return "- Connected phone directories: " + paths.joinToString(", ") { "`$it`" } +
        ". Writes and deletes immediately modify the ORIGINAL phone files. " +
        "There is no staging, commit, or automatic rollback. Stay within these connected directories.\n"
}

/**
 * workspace reminder 组装的唯一入口（transformer 与测试共用）。
 * [capabilities] 非 null → 真实快照（wire 名清单 + 挂载可见性 + 回读措辞）；
 * null → 旧行为：按 [harnessMinimal] 固定工具清单 + [extraMountPromptLines] 原文。
 *
 * 用户自定义 systemPrompt 不强制投影：模板里没有 {{available_tools}} 就只替换原占位符。
 */
/**
 * `{{rootfs_environment}}` 的内容：Rootfs 实际使用的 shell 与发行版，每项一行（以换行结尾）；未知时为空。
 * 写进提示是为了让模型按实际环境写命令（Alpine 没有 bash、用 apk）。
 */
fun rootfsEnvironmentPromptLines(environment: me.rerere.workspace.RootfsEnvironment?): String {
    if (environment == null) return ""
    return buildString {
        environment.distro?.let {
            appendLine("- The Rootfs distribution is $it. Use its native package manager when you need to install missing tools.")
        }
        if (environment.shell == "/bin/bash") {
            appendLine("- Shell commands run with `/bin/bash`.")
        } else {
            appendLine(
                "- Shell commands run with `${environment.shell}`, a POSIX shell. Bash is not installed, " +
                    "so avoid bash-only syntax such as arrays, here-strings (`<<<`) and brace expansion."
            )
        }
    }
}

fun workspaceReminderPrompt(
    workspaceName: String,
    harnessMinimal: Boolean,
    exposedTargets: Set<String>,
    cwd: String?,
    overrides: Map<String, ToolOverride>,
    capabilities: WorkspaceRequestCapabilities?,
    rootfsEnvironment: String = capabilities?.rootfsEnvironment.orEmpty(),
): String {
    val spec = BuiltinToolCatalog.require(
        if (harnessMinimal) BuiltinToolIds.HARNESS_MINIMAL_REMINDER else BuiltinToolIds.WORKSPACE_REMINDER,
    )
    val template = overrides[spec.id]?.systemPrompt?.takeIf { it.isNotBlank() }
        ?: spec.defaultSystemPrompt.orEmpty()
    val effectiveHarnessMinimal = capabilities?.harnessMinimal ?: harnessMinimal
    val extraMounts = if (capabilities == null) {
        extraMountPromptLines(exposedTargets)
    } else {
        capabilities.filesScope?.let(::workspaceScopePromptLine).orEmpty() +
            workspaceMountPromptLines(
                exposedTargets = capabilities.exposedTargets,
                canReadFiles = capabilities.hasReadbackTool,
            )
    }
    return interpolateToolText(
        text = template,
        placeholders = builtinToolPlaceholders(
            cwd = cwd,
            workspaceName = workspaceName,
            extraMounts = extraMounts,
        ) + mapOf(
            WORKSPACE_TOOLS_PLACEHOLDER to
                availableWorkspaceToolsBlock(capabilities, effectiveHarnessMinimal),
            ROOTFS_ENVIRONMENT_PLACEHOLDER to rootfsEnvironment,
        ),
    ).trim()
}

// ---------------------------------------------------------------------------
// 工具输出截断策略（提示侧；GenerationLoop.maybeTruncateToolOutput 接入）
// ---------------------------------------------------------------------------

/**
 * 截断决策：
 * - 返回 null = 不超阈值，原样返回 output；
 * - [persistFullOutput]=true = 把全文写入 `/tool_outputs/<file>` 并给回读指引
 *   （仅当确有可用回读工具且 `/tool_outputs` 已挂载）；
 * - 否则明确截断：不保存全文、不宣称可回读。
 */
data class ToolOutputTruncationPlan(
    val totalChars: Int,
    val persistFullOutput: Boolean,
    /** 提示中展示的回读工具 wire 名（persistFullOutput=false 时为 null）。 */
    val readbackWireName: String?,
    /** 回读工具的 stableId（决定指引措辞：shell 族 vs 读文件族）。 */
    val readbackStableId: String?,
)

/**
 * 计算截断计划。
 * - `capabilities != null` → 只信 [WorkspaceRequestCapabilities.canSaveToolOutputs]。
 * - `capabilities == null` → 用 [fallbackCanSave]（GenerationLoop 旧 hasShellAccess 判断），
 *   保持固定 tools 调用者的旧行为（有 shell 即落盘并给 shell 指引）。
 */
/**
 * Built-in tools whose output is never truncated: the search result list and citation links need
 * the complete JSON, and its size is already bounded by the configured result count.
 */
private val UNTRUNCATED_BUILTIN_TOOLS = setOf(BuiltinToolIds.SEARCH_WEB)

/** [toolName] is the wire name of the call; a ToolOverride may have renamed the built-in tool. */
fun isToolOutputTruncationExempt(toolName: String, overrides: Map<String, ToolOverride>): Boolean =
    resolveBuiltinToolId(toolName, overrides) in UNTRUNCATED_BUILTIN_TOOLS

fun planToolOutputTruncation(
    totalChars: Int,
    maxOutputChars: Int,
    capabilities: WorkspaceRequestCapabilities?,
    fallbackCanSave: Boolean,
): ToolOutputTruncationPlan? {
    if (totalChars <= maxOutputChars) return null
    val canSave = capabilities?.canSaveToolOutputs ?: fallbackCanSave
    return if (canSave) {
        ToolOutputTruncationPlan(
            totalChars = totalChars,
            persistFullOutput = true,
            readbackWireName = capabilities?.readbackWireName
                ?: BuiltinToolIds.WORKSPACE_SHELL,
            readbackStableId = capabilities?.readbackStableId
                ?: BuiltinToolIds.WORKSPACE_SHELL,
        )
    } else {
        ToolOutputTruncationPlan(totalChars = totalChars, persistFullOutput = false, readbackWireName = null, readbackStableId = null)
    }
}

/**
 * 渲染截断提示正文（preview 之后的头部分）。
 * [fileName] 仅在 persistFullOutput 时提供（已落盘的文件名，不含目录）。
 */
fun ToolOutputTruncationPlan.renderNotice(fileName: String?, preview: String): String = buildString {
    appendLine("[Tool output truncated: $totalChars characters total]")
    if (persistFullOutput && fileName != null) {
        val dir = WorkspaceRequestCapabilities.TOOL_OUTPUTS_GUEST_PATH
        appendLine("Full output saved to: $dir/$fileName")
        when (readbackStableId) {
            BuiltinToolIds.WORKSPACE_SHELL -> {
                appendLine("Use shell to read: `cat $dir/$fileName`")
                appendLine("Use shell to search: `grep \"pattern\" $dir/$fileName`")
            }
            BuiltinToolIds.BASH -> {
                appendLine("Use the bash tool to read: `cat $dir/$fileName`")
                appendLine("Use the bash tool to search: `grep \"pattern\" $dir/$fileName`")
            }
            else -> {
                val tool = readbackWireName ?: "the file read tool"
                appendLine("Use `$tool` to read `$dir/$fileName`.")
            }
        }
        appendLine()
    } else {
        appendLine("The full output was not saved: no shell/read tool is enabled or `/tool_outputs` is not mounted for this workspace.")
        appendLine()
    }
    append(preview)
}
