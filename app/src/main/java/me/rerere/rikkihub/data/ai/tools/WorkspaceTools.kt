package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolIdentity
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.workspace.WorkspaceUnavailableException
import me.rerere.rikkihub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to false,
    BuiltinToolIds.BASH to false,
    BuiltinToolIds.STR_REPLACE_EDITOR to false,
    BuiltinToolIds.SUBAGENT to false,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

fun workspaceToolApprovalItemsFor(harnessMinimal: Boolean): List<String> =
    workspaceToolNamesFor(harnessMinimal)

fun workspaceToolNamesFor(harnessMinimal: Boolean): List<String> =
    if (harnessMinimal) {
        listOf(BuiltinToolIds.BASH, BuiltinToolIds.STR_REPLACE_EDITOR, BuiltinToolIds.SUBAGENT)
    } else {
        listOf(
            BuiltinToolIds.WORKSPACE_READ_FILE,
            BuiltinToolIds.WORKSPACE_WRITE_FILE,
            BuiltinToolIds.WORKSPACE_EDIT_FILE,
            BuiltinToolIds.WORKSPACE_SHELL,
            BuiltinToolIds.SUBAGENT,
        )
    }

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager? = null,
    subagentHost: WorkspaceSubagentHost? = null,
    includeSubagent: Boolean = true,
    preloadedWorkspace: WorkspaceEntity? = null,
    toolRunner: WorkspaceToolRunner? = null,
    caller: WorkspaceToolCaller = WorkspaceToolCaller.PARENT,
    filesScope: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val workspace = preloadedWorkspace ?: workspaceRepository.getById(workspaceId) ?: return emptyList()
    return buildWorkspaceTools(
        workspace = workspace,
        workspaceRepository = workspaceRepository,
        cwd = cwd,
        persistentBashSessionManager = persistentBashSessionManager,
        subagentHost = subagentHost,
        includeSubagent = includeSubagent,
        toolRunner = toolRunner,
        caller = caller,
        loadWorkspace = { id -> workspaceRepository.getById(id) },
        filesScope = filesScope,
    )
}

/**
 * 候选 → 过滤 → 守卫包装的统一装配点。
 *
 * - 每个候选在构造时填入 [Tool.workspaceIdentity]（稳定归属，不经 Override 反查）；
 * - 按 [workspace] 的 READY 状态 + 模式 + 最新 tool_enablement 过滤 schema；
 * - 过滤后的工具统一过 [Tool.withWorkspaceExecutionGuard] 包装 execute/
 *   executeWithContext 双入口（守卫先于 body 日志/metadata/bash），
 *   再由调用方（ChatToolFactory）统一 applyBuiltinOverrides。
 *
 * [workspaceRepository] 在守卫包装场景必须非空（工具 body 的真实后端）；
 * 单测可传 null 以只验证守卫拒绝路径（守卫先于 body，repo 不会解引用）。
 */
internal fun buildWorkspaceTools(
    workspace: WorkspaceEntity,
    workspaceRepository: WorkspaceRepository?,
    cwd: String? = null,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager? = null,
    subagentHost: WorkspaceSubagentHost? = null,
    includeSubagent: Boolean = true,
    toolRunner: WorkspaceToolRunner? = null,
    caller: WorkspaceToolCaller = WorkspaceToolCaller.PARENT,
    loadWorkspace: suspend (String) -> WorkspaceEntity?,
    subagentCatalog: SubagentCatalogSnapshot? = null,
    /** Working-directory confinement ([me.rerere.workspace.WorkspaceFilesScope]); null = whole workspace. */
    filesScope: String? = null,
): List<Tool> {
    // schema 过滤口径与守卫一致：非 READY 一个工具都不声明
    if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val enabledIds = enabledWorkspaceToolIds(workspace)
    // 候选身份统一取装配时刻的 per-tool epoch（runner.currentToolEpoch）；
    // activationEpoch 与守卫 admission.expectedToolEpoch 同源，防关→开旧实例复活。
    fun identityOf(stableId: String) = workspaceToolIdentity(workspace, stableId, toolRunner)
    // 守卫身份与 Tool.workspaceIdentity 同源——候选 createXxx 的裸身份
    // （无 runner，epoch/session=0/null）一律覆写为带 runner 读数的签发身份，
    // 否则 admission.expectedToolEpoch 与 binding 检查形同虚设。
    fun guard(identity: WorkspaceToolIdentity) = { tool: Tool ->
        tool.copy(workspaceIdentity = identity)
            .withWorkspaceExecutionGuard(identity, loadWorkspace, toolRunner, caller)
    }

    return buildList {
        if (workspace.harnessMinimal) {
            if (BuiltinToolIds.BASH in enabledIds) {
                val bashManager = persistentBashSessionManager
                    ?: error("DeepSeek Harness workspace requires a persistent bash session manager")
                add(createPersistentBashTool(workspace, ::needsApproval, bashManager, filesScope).let(guard(identityOf(BuiltinToolIds.BASH))))
            }
            if (BuiltinToolIds.STR_REPLACE_EDITOR in enabledIds) {
                add(createStrReplaceEditorTool(workspace, ::needsApproval, workspaceRepository, filesScope).let(guard(identityOf(BuiltinToolIds.STR_REPLACE_EDITOR))))
            }
        } else {
            if (BuiltinToolIds.WORKSPACE_READ_FILE in enabledIds) {
                add(createReadFileTool(workspace, ::needsApproval, workspaceRepository, filesScope).let(guard(identityOf(BuiltinToolIds.WORKSPACE_READ_FILE))))
            }
            if (BuiltinToolIds.WORKSPACE_WRITE_FILE in enabledIds) {
                add(createWriteFileTool(workspace, ::needsApproval, workspaceRepository, filesScope).let(guard(identityOf(BuiltinToolIds.WORKSPACE_WRITE_FILE))))
            }
            if (BuiltinToolIds.WORKSPACE_EDIT_FILE in enabledIds) {
                add(createEditFileTool(workspace, ::needsApproval, workspaceRepository, filesScope).let(guard(identityOf(BuiltinToolIds.WORKSPACE_EDIT_FILE))))
            }
            if (BuiltinToolIds.WORKSPACE_SHELL in enabledIds) {
                val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")
                add(createShellTool(workspace, ::needsApproval, workspaceRepository, shellCwd, filesScope).let(guard(identityOf(BuiltinToolIds.WORKSPACE_SHELL))))
            }
        }
        // subagent 工具按启用面装配；includeSubagent=false 用于子代理 child，保持不可递归
        if (includeSubagent && subagentHost != null && BuiltinToolIds.SUBAGENT in enabledIds) {
            add(
                createSubagentTool(
                    workspace = workspace,
                    needsApproval = ::needsApproval,
                    host = subagentHost,
                    workspaceRepository = workspaceRepository,
                    cwd = cwd,
                    persistentBashSessionManager = persistentBashSessionManager,
                    toolRunner = toolRunner,
                    catalog = subagentCatalog ?: captureSubagentCatalog(workspace, subagentHost),
                    filesScope = filesScope,
                ).let(guard(identityOf(BuiltinToolIds.SUBAGENT))),
            )
        }
    }
}

private fun requireWorkspaceRepo(repo: WorkspaceRepository?): WorkspaceRepository =
    requireNotNull(repo) { "WorkspaceRepository is required for workspace tool execution" }

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository?,
    filesScope: String?,
) = Tool(
    name = BuiltinToolIds.WORKSPACE_READ_FILE,
    description = BuiltinToolCatalog.require(BuiltinToolIds.WORKSPACE_READ_FILE).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval(BuiltinToolIds.WORKSPACE_READ_FILE) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.WORKSPACE_READ_FILE),
    execute = {
        val repo = requireWorkspaceRepo(workspaceRepository)
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            repo.readImageInRootfs(workspace, workspace.id, path, filesScope)
        } else {
            // 超大文件的回读指引按「当前实体上 shell 族工具是否启用」分支：
            // 关闭 workspace_shell 不推荐已禁用的 shell 命令路径；文件工具内部
            // 命令执行（write/edit 走 executeCommand）不受该开关影响。
            val latest = repo.getById(workspace.id) ?: workspace
            val text = repo.readTextInRootfs(latest, workspace.id, path, filesScope)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository?,
    filesScope: String?,
) = Tool(
    name = BuiltinToolIds.WORKSPACE_WRITE_FILE,
    description = BuiltinToolCatalog.require(BuiltinToolIds.WORKSPACE_WRITE_FILE).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    // 审批只看用户配置：不再因路径位于 /workspace 之外而强制 Pending
    needsApproval = { needsApproval(BuiltinToolIds.WORKSPACE_WRITE_FILE) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.WORKSPACE_WRITE_FILE),
    execute = {
        val repo = requireWorkspaceRepo(workspaceRepository)
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = repo.writeTextInRootfs(workspace.id, path, text, overwrite, filesScope)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository?,
    filesScope: String?,
) = Tool(
    name = BuiltinToolIds.WORKSPACE_EDIT_FILE,
    description = BuiltinToolCatalog.require(BuiltinToolIds.WORKSPACE_EDIT_FILE).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    // 审批只看用户配置：不再因路径位于 /workspace 之外而强制 Pending
    needsApproval = { needsApproval(BuiltinToolIds.WORKSPACE_EDIT_FILE) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.WORKSPACE_EDIT_FILE),
    execute = {
        val repo = requireWorkspaceRepo(workspaceRepository)
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = repo.readTextInRootfs(workspace, workspace.id, path, filesScope)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = repo.writeTextInRootfs(workspace.id, path, result.updated, overwrite = true, filesScope = filesScope)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createPersistentBashTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    sessionManager: WorkspacePersistentBashSessionManager,
    filesScope: String?,
) = Tool(
    name = BuiltinToolIds.BASH,
    description = BuiltinToolCatalog.require(BuiltinToolIds.BASH).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "The bash command to run. Relative path is preferred in the command.")
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval(BuiltinToolIds.BASH) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.BASH),
    execute = {
        val command = it.jsonObject.string("command") ?: error("command is required")
        require(command.isNotBlank()) { "command must be a non-empty string" }
        val output = sessionManager.execute(workspace.id, command, filesScope = filesScope)
        listOf(UIMessagePart.Text(output))
    },
)

private fun createStrReplaceEditorTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository?,
    filesScope: String?,
) = Tool(
    name = BuiltinToolIds.STR_REPLACE_EDITOR,
    description = BuiltinToolCatalog.require(BuiltinToolIds.STR_REPLACE_EDITOR).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add("view")
                        add("create")
                        add("str_replace")
                        add("insert")
                    })
                    put(
                        "description",
                        "The commands to run. Allowed options are: `view`, `create`, `str_replace`, `insert`.",
                    )
                })
                put("path", buildJsonObject {
                    put("type", "string")
                    put("description", "Absolute path to file or directory, e.g. `/workspace/file.py` or `/workspace`.")
                })
                put("file_text", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Required parameter of `create` command, with the content of the file to be created.",
                    )
                })
                put("insert_line", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Required parameter of `insert` command. The `new_str` will be inserted AFTER the line `insert_line` of `path`.",
                    )
                })
                put("new_str", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Optional parameter of `str_replace` command containing the new string (if not given, no string will be added). Required parameter of `insert` command containing the string to insert.",
                    )
                })
                put("old_str", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Required parameter of `str_replace` command containing the string in `path` to replace.",
                    )
                })
                put("view_range", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "integer") })
                    put(
                        "description",
                        "Optional parameter of `view` command when `path` points to a file. If none is given, the full file is shown. If provided, the file will be shown in the indicated line number range, e.g. [11, 12] will show lines 11 and 12. Indexing at 1 to start. Setting `[start_line, -1]` shows all lines from `start_line` to the end of the file.",
                    )
                })
            },
            required = listOf("command", "path"),
        )
    },
    // 审批只看用户配置：mutating 命令落在非法路径时由执行层报错，不转 Pending
    needsApproval = { needsApproval(BuiltinToolIds.STR_REPLACE_EDITOR) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.STR_REPLACE_EDITOR),
    execute = {
        val repo = requireWorkspaceRepo(workspaceRepository)
        val params = it.jsonObject
        val output = executeStrReplaceEditor(
            workspaceId = workspace.id,
            args = StrReplaceEditorArgs(
                command = StrReplaceEditorCommand.parse(params.string("command")),
                path = params.string("path").orEmpty(),
                fileText = params.string("file_text"),
                insertLine = params["insert_line"]?.jsonPrimitive?.intOrNull,
                newStr = params.string("new_str"),
                oldStr = params.string("old_str"),
                viewRange = params["view_range"]?.jsonArray?.map { element ->
                    element.jsonPrimitive.intOrNull
                        ?: error("Invalid `view_range`. It should be a list of two integers.")
                },
            ),
            workspaceRepository = repo,
            filesScope = filesScope,
        )
        listOf(UIMessagePart.Text(output))
    },
)

private fun createSubagentTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    host: WorkspaceSubagentHost,
    workspaceRepository: WorkspaceRepository?,
    cwd: String?,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager?,
    toolRunner: WorkspaceToolRunner?,
    catalog: SubagentCatalogSnapshot,
    filesScope: String?,
) = Tool(
    name = BuiltinToolIds.SUBAGENT,
    description = BuiltinToolCatalog.require(BuiltinToolIds.SUBAGENT).defaultDescription,
    parameters = { subagentParameters(catalog.allowContext, catalog.available) },
    needsApproval = { needsApproval(BuiltinToolIds.SUBAGENT) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.SUBAGENT),
    execute = { args ->
        dispatchSubagent(
            host, workspace, workspaceRepository, cwd,
            persistentBashSessionManager, toolRunner, null, args, catalog, filesScope,
        )
    },
    // 有上下文时发布实时进度 metadata (running -> 终态), 卡片可点开读工作过程
    executeWithContext = { args, toolContext ->
        dispatchSubagent(
            host, workspace, workspaceRepository, cwd,
            persistentBashSessionManager, toolRunner, toolContext, args, catalog, filesScope,
        )
    },
)

private suspend fun dispatchSubagent(
    host: WorkspaceSubagentHost,
    workspace: WorkspaceEntity,
    workspaceRepository: WorkspaceRepository?,
    cwd: String?,
    persistentBashSessionManager: WorkspacePersistentBashSessionManager?,
    toolRunner: WorkspaceToolRunner?,
    toolContext: ToolExecutionContext?,
    args: JsonElement,
    catalog: SubagentCatalogSnapshot,
    filesScope: String?,
): List<UIMessagePart> {
    // Selection, context policy and model availability precede child mounting, runner/log/metadata.
    val parsed = parseSubagentArgs(args, catalog.allowContext)
    if (parsed is SubagentParseResult.Fail) return failParts(parsed.status, parsed.message, null)
    val request = (parsed as SubagentParseResult.Ok).args
    val preset = if (request.agent == WorkspaceSubagentPresets.DEFAULT_AGENT) null else {
        catalog.available.firstOrNull { it.key == request.agent }
            ?: return failParts("unavailable", "Selected subagent preset is unavailable", request.description)
    }
    if (preset != null && args is kotlinx.serialization.json.JsonObject && "context" in args &&
        (!catalog.allowContext || !preset.acceptContext)) {
        return failParts("error", "Context is not allowed for this subagent preset", request.description)
    }
    if (preset != null && availableSubagentPresetModel(host.settingsStore.settingsFlow.value,
        host.parentModel, catalog.workspaceModelId, preset) == null) {
        return failParts("model_unavailable", "Selected preset model or configuration is unavailable", request.description)
    }
    val repo = requireWorkspaceRepo(workspaceRepository)
    // child 工具快照 provider：每个 provider 请求/重试都在 gate.withSnapshot
    // 锁内重读最新实体装配（child 继承最新禁用集合、capabilities 与 forbidden
    // 同步同一时刻实体）；不带 subagent（不递归）、不带审批（runner 侧剥）。
    // 派发瞬间的 harnessMinimal/allowContext/subagentModelId 也取锁内最新值，
    // 不信任装配时刻的 workspace 参数体。
    val childToolSnapshotProvider: suspend () -> WorkspaceToolSnapshot? = {
        try {
            repo.runtimeGate.withSnapshot(workspace.id) { epochs ->
                repo.getById(workspace.id)?.let { latest ->
                    createWorkspaceToolSnapshot(
                        workspace = latest,
                        workspaceRepository = repo,
                        cwd = cwd,
                        persistentBashSessionManager = persistentBashSessionManager,
                        subagentHost = null,
                        includeSubagent = false,
                        toolRunner = toolRunner,
                        caller = WorkspaceToolCaller.SUBAGENT_CHILD,
                        loadWorkspace = { id -> repo.getById(id) },
                        overrides = emptyMap(), // child 不应用 Override：wire 名即稳定 ID
                        snapshotEpochs = epochs,
                        exposedTargets = repo.executionMountTargets(latest.id, filesScope),
                        filesScope = filesScope,
                    )
                }
            }
        } catch (_: me.rerere.rikkihub.data.workspace.WorkspaceDeviceConfigException) {
            null
        } catch (denied: WorkspaceUnavailableException) {
            // closed/quarantined 快照读快失败：工作区不可用，child 无法装配
            null
        }
    }
    // 每次子代理运行一个独立的持久 bash 所属者：并行的子代理不共用 shell 状态
    //（cwd/环境变量），运行结束（含取消、出错）关闭自己的 shell。
    val bashOwner = PersistentBashOwner("subagent:${kotlin.uuid.Uuid.random()}")
    try {
        return kotlinx.coroutines.withContext(bashOwner) {
            WorkspaceSubagentRunner(
                generationHandler = host.generationHandler,
                settingsStore = host.settingsStore,
                reminderTransformer = host.reminderTransformer,
                context = host.context,
            ).run(
                parentAssistant = host.parentAssistant,
                parentModel = host.parentModel,
                cwd = cwd,
                harnessMinimal = workspace.harnessMinimal,
                allowContext = catalog.allowContext,
                subagentModelId = catalog.workspaceModelId,
                args = args,
                childTools = emptyList(),
                toolContext = toolContext,
                childToolSnapshotProvider = childToolSnapshotProvider,
                preset = preset,
            )
        }
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            persistentBashSessionManager?.closeOwner(workspace.id, bashOwner.owner, filesScope)
        }
    }
}

private fun createShellTool(
    workspace: WorkspaceEntity,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository?,
    defaultCwd: String? = null,
    filesScope: String? = null,
) = Tool(
    name = BuiltinToolIds.WORKSPACE_SHELL,
    description = BuiltinToolCatalog.require(BuiltinToolIds.WORKSPACE_SHELL).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval(BuiltinToolIds.WORKSPACE_SHELL) },
    workspaceIdentity = workspaceToolIdentity(workspace, BuiltinToolIds.WORKSPACE_SHELL),
    execute = {
        val repo = requireWorkspaceRepo(workspaceRepository)
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = repo.executeCommand(workspace.id, command, cwd, timeoutMillis, filesScope = filesScope)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspace: WorkspaceEntity,
    workspaceId: String,
    path: String,
    filesScope: String?,
): String = readRootfsBuffer(workspace, workspaceId, path, filesScope).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspace: WorkspaceEntity,
    workspaceId: String,
    path: String,
    filesScope: String?,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path, filesScope)
    if (size > MAX_READ_FILE_BYTES) {
        // 推荐回读方式以装配时刻实体的启用面为准：shell 族关闭时不建议 shell 命令。
        error(oversizedFileReadMessage(workspace, path, size))
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it, filesScope) }
}

/** 超大文件错误文案：按 shell 族/编辑器的真实启用面给回读方案，不推荐已禁用工具。 */
internal fun oversizedFileReadMessage(
    workspace: WorkspaceEntity,
    path: String,
    sizeBytes: Long,
): String {
    val sizeMb = "${sizeBytes / 1024 / 1024}MB"
    val maxMb = "${MAX_READ_FILE_BYTES / 1024 / 1024}MB"
    val prefix = "File is too large to read: $path ($sizeMb, max $maxMb)."
    val parsed = WorkspaceToolEnablement.tryParse(workspace.toolEnablement)
    val minimal = workspace.harnessMinimal
    val shellTool = when {
        parsed == null -> null // 配置损坏 fail-closed：不推荐任何工具
        minimal -> BuiltinToolIds.BASH.takeIf { parsed.toolEnabled(it) }
        else -> BuiltinToolIds.WORKSPACE_SHELL.takeIf { parsed.toolEnabled(it) }
    }
    val editorTool = if (minimal) BuiltinToolIds.STR_REPLACE_EDITOR else null
    val editorEnabled = editorTool != null && parsed?.toolEnabled(editorTool) == true
    return when {
        shellTool != null ->
            "$prefix Use `$shellTool` commands like head, tail, or grep to read parts of it."
        editorEnabled ->
            "$prefix Use `$editorTool` with command=view and view_range to read it in chunks."
        else ->
            "$prefix No shell or file-view tool is enabled for this workspace; " +
                "ask the user to split the file or enable a readback tool."
    }
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspace: WorkspaceEntity,
    workspaceId: String,
    path: String,
    filesScope: String?,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspace, workspaceId, path, filesScope).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
    filesScope: String?,
): WorkspaceFileEntry {
    // The write runs in a shell; a confined shell would silently create outside paths in the
    // scratch shell tree, so the file-tool boundary rejects them first.
    if (filesScope != null) statRootfs(workspaceId, path, filesScope)
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        filesScope = filesScope,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
    filesScope: String? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
        filesScope = filesScope,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    require(!path.split('/').any { it == ".." }) { "$name must not contain '..' segments" }
    return path
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
