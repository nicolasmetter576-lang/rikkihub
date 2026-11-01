package me.rerere.ai.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.providers.devin.DevinTool
import java.util.TreeSet

/**
 * 工作区禁用工具的最终 wire 保护（独立 pure seam）。
 *
 * 语义：工作区按稳定内建 ID 禁用某个工具后，装配层已经不再把它放进
 * `params.tools`，但 customBody 覆盖 / tool_choice 命名 / 协议工具表仍可能
 * 把同一个 wire 名写回最终请求体。本策略挂在各协议「构建 + mergeCustomBody
 * （Claude 还有 CC disguise applyBody）之后、发出之前」的单一收口点，只读
 * 最终 JSON/工具列表里的名字：
 *
 * - 命中 `forbiddenWireNames` → 抛 [WorkspaceToolPolicyConflictException]，
 *   不修改 body、不做静默裁剪；
 * - 只采集**客户端函数声明与选择器名字**：OpenAI 方言认
 *   `type=function/custom`（或无 type 但带 function/custom 容器的声明），
 *   Claude 认无 type / `type=custom` 的 `name`+`input_schema` 声明与
 *   `tool_choice type=tool` 的 name，Google 只认
 *   `tools[].functionDeclarations[].name` 与
 *   `toolConfig.functionCallingConfig.allowedFunctionNames`；Interactions 认扁平
 *   `tools[type=function].name` 与 `generation_config.tool_choice` 的命名函数；
 * - typed server/built-in 工具条目（web_search / mcp / image_generation /
 *   googleSearch / urlContext / code_execution_* 等）整项跳过——它们由
 *   上游执行，不是工作区工具，同名不比（不扩大误伤范围）；
 * - 不扫描消息历史（messages/input/contents）里已完成的工具调用与结果
 *   name 字段，也不比对工具声明的 parameters/input_schema/schema 子树内
 *   的任何 `name` 键（那是 schema 自身字段，不是工具名）；
 * - 不新增协议合法性校验：形状是否合法仍归各协议既有 preflight / 上游。
 *
 * `forbiddenWireNames` 由调用方装配：本次 workspace disabled 或非当前模式
 * 的稳定 ID，叠加 ToolOverride 生效名；为 null / 空时全部 enforce* 为
 * no-op，现有自定义工具/choice 配置行为完全不变。
 *
 * 其余字段是**本请求捕获的本地上下文**（由 Factory 在构建
 * `TextGenerationParams` 时填充）：它们随 params 走，只供 observer /
 * 保活侧做 freshness 比较与诊断，**绝不会写进任何 provider 请求体**——
 * 本类对外只暴露 enforce* 检查，不含序列化到 wire 的通道。
 */
@Serializable
data class WorkspaceToolWirePolicy(
    /** 禁用的最终 wire 工具名集合（稳定 ID + Override 生效名，逐个精确比对） */
    val forbiddenWireNames: Set<String> = emptySet(),
    /** 本策略所属工作区（本地上下文，不进 wire；诊断/归属判定用） */
    val workspaceId: String? = null,
    /** 装配本策略时的 policy/view epoch 快照（本地上下文，不进 wire） */
    val policyEpoch: Long? = null,
    /** 装配本策略时的设备视图指纹快照（本地上下文，不进 wire） */
    val viewFingerprint: String? = null,
    /** Local request-build provenance for cache replay; never part of provider wire. */
    val toolOverridesFingerprint: String? = null,
) {

    // ============================ 通用收口 ============================

    /**
     * 对一批已提取的工具名做精确比对。命中的名字排序后报出（确定性输出，
     * 不携带 body/schema/参数正文）。
     */
    fun enforceToolNames(names: Collection<String>, protocol: String) {
        if (forbiddenWireNames.isEmpty() || names.isEmpty()) return
        val conflicts = names.filterTo(TreeSet()) { it in forbiddenWireNames }
        if (conflicts.isNotEmpty()) {
            throw WorkspaceToolPolicyConflictException(
                names = conflicts.toSet(),
                protocol = protocol,
            )
        }
    }

    /**
     * 本地 epoch 一致性检查（供 observer/缓存保活侧使用，纯比较不进 wire）：
     * 消费方应取**本请求捕获的** `params.workspaceToolPolicy.policyEpoch`，
     * 而不是消息完成时的旧快照；结构变化后旧轮次/旧 warmer 调用须被拒。
     *
     * 语义：本策略未记 epoch（null）→ 无法证明过期，返回 false，由调用方
     * 决定是否叠加更严的 fail-closed；已记 epoch 且与 [currentEpoch]
     * 不等（含 currentEpoch 为 null）→ 视为过期返回 true。
     */
    fun isStale(currentEpoch: Long?): Boolean =
        policyEpoch != null && policyEpoch != currentEpoch

    // ============================ 各协议入口 ============================

    /**
     * OpenAI Chat Completions：扫描最终 body 的 `tools`（type=function/custom
     * 或无 type 的 function 容器条目）与 `tool_choice`（function/custom/
     * allowed_tools 命名条目），另覆盖 legacy `functions`/`function_call`
     * 的 name（customBody 可写入；形状是否合法仍由 RequestToolPreflight 判
     * 定，这里只看名字）。
     */
    fun enforceChatCompletionsBody(body: JsonObject) {
        val names = LinkedHashSet<String>()
        collectOpenAiToolEntryNames(body["tools"], names)
        collectOpenAiToolChoiceNames(body["tool_choice"], names)
        // legacy functions/function_call 也携名；customBody 可写入，先行比对。
        (body["functions"] as? JsonArray)?.forEach { entry ->
            (entry as? JsonObject)?.stringField("name")?.let(names::add)
        }
        (body["function_call"] as? JsonObject)?.stringField("name")?.let(names::add)
        enforceToolNames(names, PROTOCOL_CHAT_COMPLETIONS)
    }

    /**
     * OpenAI Responses API（Codex 走同一 `buildRequestBody` 出口）：
     * 扫描最终 body 的扁平 `tools` 数组（仅 type=function/custom 条目）与
     * `tool_choice`（含 allowed_tools 内层 function 条目）。mcp/web_search/
     * image_generation 等 server 条目与其 allowed_tools 字符串列表不采集。
     */
    fun enforceResponsesApiBody(body: JsonObject) {
        val names = LinkedHashSet<String>()
        collectOpenAiToolEntryNames(body["tools"], names)
        collectOpenAiToolChoiceNames(body["tool_choice"], names)
        enforceToolNames(names, PROTOCOL_RESPONSES_API)
    }

    /**
     * Claude `/messages`（含 CC disguise）：扫描最终 body 的 `tools`（无
     * type 的客户端声明与 type=custom 条目；web_search_* 等 typed server
     * tool 跳过）与 `tool_choice`（type=tool 或缺省形态里的 name）。
     * 调用点位于 mergeCustomBody + ClaudeCodeDisguise.applyBody 之后，
     * 覆盖被 disguise/自定义体加回的名字。
     */
    fun enforceClaudeMessagesBody(body: JsonObject) {
        val names = LinkedHashSet<String>()
        (body["tools"] as? JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            // Anthropic 客户端函数声明：无 type 的 {name, description,
            // input_schema} 或 type=custom；其余 typed 条目都是 server tool。
            val type = obj.stringField("type")
            if (type == null || type == "custom") {
                obj.stringField("name")?.let(names::add)
            }
        }
        (body["tool_choice"] as? JsonObject)?.let { choice ->
            val type = choice.stringField("type")
            // type=tool 显式点名；无 type 的残缺形态也按 name 采集（形状
            // 是否合法交给上游，禁用名先行拒）。
            if (type == null || type == "tool") {
                choice.stringField("name")?.let(names::add)
            }
        }
        enforceToolNames(names, PROTOCOL_CLAUDE_MESSAGES)
    }

    /**
     * Google generateContent：仅采集 `tools[].functionDeclarations[].name`
     * （客户端函数声明）与 `toolConfig.functionCallingConfig
     * .allowedFunctionNames`（客户端选择器字符串表）。googleSearch /
     * urlContext / codeExecution / mcpServers 等条目不携客户端函数名，
     * 一律不展开采集。
     */
    fun enforceGoogleGenerateContentBody(body: JsonObject) {
        val names = LinkedHashSet<String>()
        (body["tools"] as? JsonArray)?.forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            (obj["functionDeclarations"] as? JsonArray)?.forEach { declaration ->
                (declaration as? JsonObject)?.stringField("name")?.let(names::add)
            }
        }
        val functionCallingConfig = (body["toolConfig"] as? JsonObject)
            ?.get("functionCallingConfig") as? JsonObject
        (functionCallingConfig?.get("allowedFunctionNames") as? JsonArray)?.forEach { item ->
            (item as? JsonPrimitive)?.contentOrNull?.let(names::add)
        }
        enforceToolNames(names, PROTOCOL_GOOGLE_GENERATE_CONTENT)
    }

    /** Interactions flattened client declarations and generation_config.tool_choice.
     * Only the named function selector is client identity; server/history/schema
     * literals (including generateContent's unrelated toolConfig) are not scanned. */
    fun enforceGoogleInteractionsBody(body: JsonObject) {
        val names = LinkedHashSet<String>()
        (body["tools"] as? JsonArray)?.forEach { entry ->
            val tool = entry as? JsonObject ?: return@forEach
            if (tool.stringField("type") == "function") tool.stringField("name")?.let(names::add)
        }
        val choice = (body["generation_config"] as? JsonObject)?.get("tool_choice") as? JsonObject
        if (choice?.stringField("type") == "function") choice.stringField("name")?.let(names::add)
        enforceToolNames(names, "google_interactions")
    }

    /**
     * Devin GetChatMessage：wire 无 JSON body 与 tool_choice，Field 10 的
     * `DevinTool.name` 全部是本轮下发的客户端工具名，直接精确比对。
     */
    fun enforceDevinChatTools(tools: List<DevinTool>) {
        enforceToolNames(tools.map { it.name }, PROTOCOL_DEVIN_GET_CHAT_MESSAGE)
    }

    private companion object {
        const val PROTOCOL_CHAT_COMPLETIONS = "chat_completions"
        const val PROTOCOL_RESPONSES_API = "responses_api"
        const val PROTOCOL_CLAUDE_MESSAGES = "claude_messages"
        const val PROTOCOL_GOOGLE_GENERATE_CONTENT = "google_generate_content"
        const val PROTOCOL_DEVIN_GET_CHAT_MESSAGE = "devin_get_chat_message"
    }
}

/**
 * 工作区 wire 策略冲突：最终请求里出现了被禁用的工作区工具名。
 *
 * **故意不是 IOException 子类**：这是本地策略违例而非网络故障，绝不能进
 * 网络重试路径。消息只含协议标识与工具名，不复制请求体或工具参数。
 */
class WorkspaceToolPolicyConflictException(
    /** 命中的禁用 wire 名（排序、去重后快照） */
    val names: Set<String>,
    /** 触发协议标识（chat_completions / responses_api / claude_messages / ...） */
    val protocol: String,
    message: String = "workspace tool policy conflict [$protocol]: " +
        "disabled wire tool name(s): ${names.sorted().joinToString(", ")}",
) : IllegalStateException(message)

// =============================================================================
// 名字提取（纯只读，不改 JSON；只认客户端函数声明与选择器位置）
// =============================================================================

/** 非字符串字段（对象/数组）按缺失处理。 */
private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** OpenAI 两方言中属于客户端函数声明的 type 值。 */
private val OPENAI_CLIENT_TOOL_TYPES = setOf("function", "custom")

/**
 * OpenAI 方言 tools[] 条目：
 * - type ∈ {function, custom} → 采集顶层 `name` 与 `function`/`custom`
 *   容器内 `name`（覆盖 Responses 扁平形与 Chat Completions 嵌套形，
 *   含 GPT-5 custom tool）；
 * - type 缺失 → 仅认 `function`/`custom` 容器里的 `name`（与 chat
 *   completions preflight「缺省 type 视为 function 声明」语义一致；
 *   裸 `{"name":...}` 无容器条目不采集，交给 preflight/上游判形状）；
 * - 其它 type（web_search / mcp / image_generation / file_search 等）
 *   → 整项跳过，不比对其中的任何 name / server_label。
 */
private fun collectOpenAiToolEntryNames(tools: JsonElement?, out: MutableSet<String>) {
    (tools as? JsonArray)?.forEach { entry ->
        val obj = entry as? JsonObject ?: return@forEach
        val type = obj.stringField("type")
        if (type != null && type !in OPENAI_CLIENT_TOOL_TYPES) return@forEach
        listOf("function", "custom").forEach { key ->
            (obj[key] as? JsonObject)?.stringField("name")?.let(out::add)
        }
        if (type != null) {
            obj.stringField("name")?.let(out::add)
        }
    }
}

/**
 * OpenAI tool_choice：
 * - 字符串模式（auto/none/required）不携名，跳过；
 * - `{"type":"allowed_tools","tools":[...]}` → 内层条目按
 *   type=function/custom 或缺省采集 `name`（含嵌套容器），字符串条目
 *   直接按名字采集（choice 点名语义）；内层 mcp 等 server 条目跳过；
 * - type ∈ {function, custom} 或缺失 → 采集顶层 `name` 与
 *   `function`/`custom` 容器 `name`；
 * - 其它 type（mcp 等 server 形态）→ 跳过。
 */
private fun collectOpenAiToolChoiceNames(choice: JsonElement?, out: MutableSet<String>) {
    val obj = choice as? JsonObject ?: return
    val type = obj.stringField("type")
    if (type == "allowed_tools") {
        (obj["tools"] as? JsonArray)?.forEach { item ->
            when (item) {
                is JsonObject -> {
                    val innerType = item.stringField("type")
                    if (innerType != null && innerType !in OPENAI_CLIENT_TOOL_TYPES) {
                        return@forEach
                    }
                    item.stringField("name")?.let(out::add)
                    (item["function"] as? JsonObject)?.stringField("name")?.let(out::add)
                    (item["custom"] as? JsonObject)?.stringField("name")?.let(out::add)
                }

                is JsonPrimitive -> item.contentOrNull?.let(out::add)
                else -> Unit
            }
        }
        return
    }
    if (type == null || type in OPENAI_CLIENT_TOOL_TYPES) {
        obj.stringField("name")?.let(out::add)
        (obj["function"] as? JsonObject)?.stringField("name")?.let(out::add)
        (obj["custom"] as? JsonObject)?.stringField("name")?.let(out::add)
    }
}
