package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.rikkihub.data.ai.mcp.McpTool

/** 同时运行的工具调用上限（设置可在 1..此值之间调）。 */
const val MAX_PARALLEL_TOOL_CALLS = 10

/**
 * 工具调用并行执行配置（`Settings.toolExecution`）。独立于 `toolOverrides`：改它不应
 * 触发工具改名那套失效逻辑（会停掉缓存保活）。
 */
@Serializable
data class ToolExecutionConfig(
    /** 总开关；关闭 = 逐个执行（旧行为）。 */
    val parallel: Boolean = true,
    /** 同一段里最多同时运行的调用数，1..[MAX_PARALLEL_TOOL_CALLS]。 */
    val maxParallel: Int = MAX_PARALLEL_TOOL_CALLS,
    /** 内置工具稳定 id → 是否可并行；缺键 = 默认分类（[ToolConcurrencyDefaults]）。 */
    val parallelOverrides: Map<String, Boolean> = emptyMap(),
) {
    /** 生成循环实际使用的同时运行数：关闭并行为 1，越界值夹到合法范围。 */
    fun effectiveLimit(): Int = if (!parallel) 1 else maxParallel.coerceIn(1, MAX_PARALLEL_TOOL_CALLS)
}

/** 内置工具的默认并行分类。未列出的一律独占（有副作用、需要交互或无法判断）。 */
object ToolConcurrencyDefaults {
    val PARALLEL_BY_DEFAULT: Set<String> = setOf(
        BuiltinToolIds.WORKSPACE_READ_FILE,
        BuiltinToolIds.STR_REPLACE_EDITOR, // 仅 view，见 argumentFilter
        BuiltinToolIds.SUBAGENT,
        BuiltinToolIds.SEARCH_WEB,
        BuiltinToolIds.SCRAPE_WEB,
        BuiltinToolIds.MEMORY_READ,
        BuiltinToolIds.MEMORY_LIST,
        BuiltinToolIds.RECENT_CHATS,
        BuiltinToolIds.CONVERSATION_SEARCH,
        BuiltinToolIds.GET_TIME_INFO,
        BuiltinToolIds.GET_SCREEN_TIME,
        BuiltinToolIds.CALENDAR_QUERY,
        BuiltinToolIds.CHART_DISPLAY,
        BuiltinToolIds.USE_SKILL,
        BuiltinToolIds.CREATOR_LIST_OBJECTS,
        BuiltinToolIds.CREATOR_READ_OBJECT,
        BuiltinToolIds.CREATOR_DESCRIBE,
        BuiltinToolIds.CREATOR_GET_CHANGE_STATUS,
    )

    /**
     * 只在部分参数下可并行的工具：开关打开时也只放行这些参数，其余参数仍独占。
     * `str_replace_editor` 只有 `view` 是只读的。
     */
    fun argumentFilter(id: String): ((JsonElement) -> Boolean)? = when (id) {
        BuiltinToolIds.STR_REPLACE_EDITOR -> { args ->
            (args as? JsonObject)?.get("command")?.jsonPrimitive?.contentOrNull == "view"
        }
        else -> null
    }

    /** 设置里能切换的内置工具（排除只是系统提示的条目）。 */
    fun configurableIds(): List<String> = BuiltinToolCatalog.all.filter { it.isTool }.map { it.id }
}

/** 内置工具 [id] 当前是否可并行（用户覆盖优先，其次默认分类）。 */
fun ToolExecutionConfig.isBuiltinParallel(id: String): Boolean =
    parallelOverrides[id] ?: (id in ToolConcurrencyDefaults.PARALLEL_BY_DEFAULT)

/** 内置工具 [id] 的最终调度策略。 */
fun ToolExecutionConfig.builtinConcurrency(id: String): ToolConcurrency {
    if (!isBuiltinParallel(id)) return ToolConcurrency.Exclusive
    val filter = ToolConcurrencyDefaults.argumentFilter(id) ?: return ToolConcurrency.Parallel
    return ToolConcurrency.ParallelWhen(filter)
}

/** MCP 工具默认跟随服务器的只读声明，用户覆盖优先。 */
val McpTool.isParallel: Boolean get() = parallel ?: (readOnlyHint == true)

/**
 * 给内置工具写入调度策略。必须在 ToolOverride 改名**之前**调用（此时 name 就是稳定
 * id）；工作区工具按 [Tool.workspaceIdentity] 的稳定 id 识别。非内置工具（MCP、测试
 * 工具）保持自身策略不变。
 */
internal fun List<Tool>.applyBuiltinConcurrency(config: ToolExecutionConfig): List<Tool> =
    map { it.withBuiltinConcurrency(config) }

internal fun Tool.withBuiltinConcurrency(config: ToolExecutionConfig): Tool {
    val id = workspaceIdentity?.stableId ?: name
    return if (id in BuiltinToolCatalog.ids) copy(concurrency = config.builtinConcurrency(id)) else this
}
