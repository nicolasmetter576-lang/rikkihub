package me.rerere.rikkihub.ui.components.message.tools

import java.io.File
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.utils.JsonInstant

/**
 * 子代理运行日志的解析与标题格式化工具。
 *
 * 本文件只包含纯 Kotlin/JVM 逻辑 (无 Android / Compose 依赖), 便于单元测试。
 * 日志 schema 见 WorkspaceSubagentRunner.subagentLogObject (version=2):
 * messages 为完整 UIMessage 数组, 旧版日志只有 transcript(role/text/reasoning/tools)。
 */

/** 详情页内文本块展示上限, 超长截断避免巨大的工具输出拖垮列表 */
const val SUBAGENT_TEXT_DISPLAY_LIMIT = 12_000

/** 卡片标题里任务名最多保留的字符数 */
const val SUBAGENT_TASK_NAME_MAX_CHARS = 28

private const val SUBAGENT_STATUS_RUNNING = "running"

/**
 * 将 logPath (形如 `/subagent_runs/{uuid}.json` 或旧版 `/tool_outputs/xxx.json`)
 * 解析为 filesDir 下的宿主文件。仅允许两个白名单目录内的 `.json` 文件,
 * 拒绝绝对路径、`..` 越界、嵌套子目录与非 json 文件; 不合法时返回 null。
 */
fun subagentLogFile(filesDir: File, logPath: String?): File? {
    val raw = logPath?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val relative = raw.removePrefix("/")
    if (relative.isEmpty() || relative.contains('\\')) return null
    val segments = relative.split('/')
    // 必须是 <白名单目录>/<文件名>.json, 不允许更深目录或穿越
    if (segments.size != 2) return null
    val dirName = segments[0]
    if (dirName != FileFolders.SUBAGENT_RUNS && dirName != FileFolders.TOOL_OUTPUTS) return null
    val fileName = segments[1]
    if (fileName.isEmpty() || !fileName.endsWith(".json")) return null
    if (fileName == ".json" || fileName.contains("..")) return null
    val dir = File(filesDir, dirName)
    val candidate = File(dir, fileName)
    // canonical 兜底: 防止符号链接或编码形式绕过前缀检查
    return runCatching {
        val canonicalDir = dir.canonicalFile
        val canonicalFile = candidate.canonicalFile
        candidate.takeIf {
            canonicalDir.parentFile == filesDir.canonicalFile && canonicalFile.parentFile == canonicalDir
        }
    }.getOrNull()
}

/**
 * 汇总展示用的 SubagentMetadata:
 * 优先取工具 part 上的实时 metadata (运行中由 publishMetadata 持续更新),
 * 其次输出 Text part 的 metadata, 最后兼容旧版输出 JSON (snake_case 字段)。
 */
fun subagentMetadata(
    tool: UIMessagePart.Tool,
    outputJson: JsonObject?,
): SubagentMetadata? {
    val fallback = outputJson?.takeIf {
        "status" in it || "log_path" in it || "step_count" in it ||
            "preset_id" in it || "preset_key" in it || "preset_name" in it || "preset_revision" in it
    }?.let(::legacySubagentMetadata)
    val outputMetadata = tool.output.firstNotNullOfOrNull {
        it.metadataAs<SubagentMetadata>()?.takeUnless { metadata -> metadata == SubagentMetadata() }
    }
    val metadata = tool.metadataAs<SubagentMetadata>()?.takeUnless { it == SubagentMetadata() }
        ?: outputMetadata
    return metadata?.let { source -> source.copy(
        presetId = source.presetId ?: outputMetadata?.presetId ?: fallback?.presetId,
        presetKey = source.presetKey ?: outputMetadata?.presetKey ?: fallback?.presetKey,
        presetName = source.presetName ?: outputMetadata?.presetName ?: fallback?.presetName,
        presetRevision = source.presetRevision ?: outputMetadata?.presetRevision ?: fallback?.presetRevision,
    ) } ?: fallback
}

/** 旧版工具输出 JSON (snake_case) 到 SubagentMetadata 的兼容映射 */
fun legacySubagentMetadata(output: JsonObject): SubagentMetadata {
    fun string(key: String): String? =
        output[key]?.jsonPrimitiveOrNull?.contentOrNull

    fun stringList(key: String): List<String> =
        output[key]?.jsonArrayOrNull
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty()

    return SubagentMetadata(
        title = string("title"),
        status = string("status"),
        summary = string("summary"),
        toolsUsed = stringList("tools_used"),
        editedPaths = stringList("edited_paths"),
        truncated = output["truncated"]?.jsonPrimitiveOrNull?.booleanOrNull,
        logPath = string("log_path"),
        stepCount = output["step_count"]?.jsonPrimitiveOrNull?.intOrNull,
        error = string("error"),
        modelId = string("model_id"),
        elapsedMs = output["elapsed_ms"]?.jsonPrimitiveOrNull?.longOrNull,
        totalTokens = output["total_tokens"]?.jsonPrimitiveOrNull?.longOrNull,
        startedAtEpochMs = output["started_at_epoch_ms"]?.jsonPrimitiveOrNull?.longOrNull,
        completedAtEpochMs = output["completed_at_epoch_ms"]?.jsonPrimitiveOrNull?.longOrNull,
        presetId = string("preset_id"),
        presetKey = string("preset_key"),
        presetName = string("preset_name"),
        presetRevision = output["preset_revision"]?.jsonPrimitiveOrNull?.longOrNull,
    )
}

/** Role identity is independent of the delegated task's title; old generic-worker runs return null. */
fun subagentPresetIdentity(metadata: SubagentMetadata?): String? {
    val name = metadata?.presetName?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
    val key = metadata?.presetKey?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        name != null && key != null -> "$name ($key)"
        name != null -> name
        else -> key
    }
}

/**
 * 卡片标题中的任务名: metadata.title > arguments.description > arguments.prompt,
 * 压平换行后按 [maxChars] 截断。
 */
fun subagentTaskName(
    metadata: SubagentMetadata?,
    description: String?,
    prompt: String?,
    maxChars: Int = SUBAGENT_TASK_NAME_MAX_CHARS,
): String? {
    val raw = listOfNotNull(metadata?.title, description, prompt)
        .firstNotNullOfOrNull { candidate ->
            candidate.replace('\n', ' ').trim().takeIf { it.isNotEmpty() }
        }
        ?: return null
    return if (raw.length > maxChars) raw.take(maxChars) + "…" else raw
}

/**
 * 是否仍在运行: 仅当父级生成仍在进行 (loading) 且工具尚未落终态输出,
 * 且 metadata 未宣告终态时才算运行中。
 * 重启后残留的 status=running metadata (loading=false) 不会被认为是运行中,
 * 计时器因此不会无限走下去。
 */
fun subagentIsRunning(
    loading: Boolean,
    isExecuted: Boolean,
    metadata: SubagentMetadata?,
): Boolean {
    if (!loading || isExecuted) return false
    val status = metadata?.status ?: return true // 还没发布过 metadata, 刚开始运行
    return status == SUBAGENT_STATUS_RUNNING
}

/**
 * 标题用的秒数:
 * - running: 由 startedAtEpochMs 实时推算，服务端 elapsedMs 作下界（等待工具时也计时）
 * - 终态: elapsedMs, 或 completedAt-startedAt; 全程不允许出现负数
 * 返回 null 表示完全无计时信息, 调用方显示未知占位符。
 */
fun subagentElapsedSeconds(
    metadata: SubagentMetadata?,
    isRunning: Boolean,
    nowMs: Long,
): Long? {
    if (metadata == null) return null
    val elapsedMs = metadata.elapsedMs
    if (isRunning && metadata.completedAtEpochMs == null) {
        val started = metadata.startedAtEpochMs
        if (started != null) return (maxOf(nowMs - started, elapsedMs ?: 0, 0) / 1000)
    }
    if (elapsedMs != null) return (elapsedMs / 1000).coerceAtLeast(0)
    val started = metadata.startedAtEpochMs
    val completed = metadata.completedAtEpochMs
    if (started != null && completed != null) {
        return ((completed - started) / 1000).coerceAtLeast(0)
    }
    return null
}

/**
 * 累计 token 换算为 k 值文本 (1 位小数, 如 "4.2")。
 * null 或负数返回 null, 调用方显示未知占位而不是 0。
 */
fun subagentKiloTokens(totalTokens: Long?): String? {
    val tokens = totalTokens?.takeIf { it >= 0 } ?: return null
    return String.format(Locale.US, "%.1f", tokens / 1000.0)
}

/**
 * 组装卡片标题行: `任务名 · 模型id · 步数 · x s · xx k tok`。
 * 各统计段由调用方预先本地化 (如 "3 steps" / "— steps"); 缺失的段显示占位符,
 * 任务名为空时回退 [fallback]。
 */
fun subagentHeaderLine(
    taskName: String?,
    modelId: String?,
    stepsLabel: String?,
    elapsedLabel: String?,
    tokensLabel: String?,
    fallback: String,
): String {
    val segments = buildList {
        add(taskName?.takeIf { it.isNotBlank() } ?: fallback)
        add(modelId?.trim()?.takeIf { it.isNotEmpty() } ?: "—")
        add(stepsLabel?.takeIf { it.isNotBlank() } ?: "—")
        add(elapsedLabel?.takeIf { it.isNotBlank() } ?: "— s")
        add(tokensLabel?.takeIf { it.isNotBlank() } ?: "— k tok")
    }
    return segments.joinToString(" · ")
}

/** 解析后的子代理运行日志 */
data class SubagentRunLog(
    val prompt: String? = null,
    val description: String? = null,
    val context: String? = null,
    val preamble: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val stepCount: Int? = null,
    val error: String? = null,
    val modelId: String? = null,
    val elapsedMs: Long? = null,
    val totalTokens: Long? = null,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val toolsUsed: List<String> = emptyList(),
    val editedPaths: List<String> = emptyList(),
    val presetId: String? = null,
    val presetKey: String? = null,
    val presetName: String? = null,
    val presetRevision: Long? = null,
    /** v2 完整消息 (含 reasoning / 工具 I/O / usage); 旧日志为 null */
    val messages: List<UIMessage>? = null,
    /** 旧版 transcript; v2 日志也会附带, 仅在 messages 缺失/解析失败时使用 */
    val legacyTranscript: List<SubagentLegacyTurn> = emptyList(),
)

/** 旧版 transcript 的一条记录 */
data class SubagentLegacyTurn(
    val role: String?,
    val text: String?,
    val reasoning: String? = null,
    val tools: List<SubagentLegacyTool> = emptyList(),
)

/** 旧版 transcript 中的工具调用记录 */
data class SubagentLegacyTool(
    val name: String?,
    val input: String?,
    val output: String?,
)

/**
 * 解析日志 JSON 文本; 非 JSON 对象 / 解析异常一律返回 null, 不向 UI 抛错。
 * v2 优先读 `messages`, 解码失败或缺失时回落 `transcript`。
 */
fun parseSubagentRunLog(text: String): SubagentRunLog? {
    val obj = runCatching { JsonInstant.parseToJsonElement(text).jsonObjectOrNull }
        .getOrNull()
        ?.takeIf { "messages" in it || "transcript" in it || "status" in it || "prompt" in it }
        ?: return null

    fun string(key: String): String? = obj[key]?.jsonPrimitiveOrNull?.contentOrNull

    fun long(key: String): Long? = obj[key]?.jsonPrimitiveOrNull?.longOrNull

    val messages = obj["messages"]?.jsonArrayOrNull?.let { array ->
        runCatching { JsonInstant.decodeFromJsonElement<List<UIMessage>>(array) }.getOrNull()
    }
    val transcript = obj["transcript"]?.jsonArrayOrNull?.mapNotNull { element ->
        val turn = element.jsonObjectOrNull ?: return@mapNotNull null
        SubagentLegacyTurn(
            role = turn["role"]?.jsonPrimitiveOrNull?.contentOrNull,
            text = turn["text"]?.jsonPrimitiveOrNull?.contentOrNull,
            reasoning = turn["reasoning"]?.jsonPrimitiveOrNull?.contentOrNull,
            tools = turn["tools"]?.jsonArrayOrNull?.mapNotNull { toolElement ->
                val tool = toolElement.jsonObjectOrNull ?: return@mapNotNull null
                SubagentLegacyTool(
                    name = tool["name"]?.jsonPrimitiveOrNull?.contentOrNull,
                    input = when (val input = tool["input"]) {
                        null -> null
                        else -> input.jsonPrimitiveOrNull?.contentOrNull ?: input.toString()
                    },
                    output = tool["output"]?.jsonPrimitiveOrNull?.contentOrNull
                        ?: tool["output"]?.toString(),
                )
            }.orEmpty(),
        )
    }.orEmpty()

    return SubagentRunLog(
        prompt = string("prompt"),
        description = string("description"),
        context = string("context"),
        preamble = string("preamble"),
        status = string("status"),
        summary = string("summary"),
        stepCount = obj["step_count"]?.jsonPrimitiveOrNull?.intOrNull,
        error = string("error"),
        modelId = string("model_id"),
        elapsedMs = long("elapsed_ms"),
        totalTokens = long("total_tokens"),
        startedAtEpochMs = long("started_at_epoch_ms"),
        completedAtEpochMs = long("completed_at_epoch_ms"),
        presetId = string("preset_id"),
        presetKey = string("preset_key"),
        presetName = string("preset_name"),
        presetRevision = long("preset_revision"),
        toolsUsed = obj["tools_used"]?.jsonArrayOrNull
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty(),
        editedPaths = obj["edited_paths"]?.jsonArrayOrNull
            ?.mapNotNull { it.jsonPrimitiveOrNull?.contentOrNull }
            .orEmpty(),
        messages = messages,
        legacyTranscript = transcript,
    )
}

/** 详情时间线条目; 由 v2 messages 或旧 transcript 归一化而来 */
sealed interface SubagentTimelineItem {
    /** 子代理助手输出文本 (markdown) */
    data class AssistantText(val text: String) : SubagentTimelineItem

    /** reasoning 内容, 默认折叠 */
    data class ReasoningBlock(val text: String) : SubagentTimelineItem

    /** 工具调用; input/output 为原始文本 (input 可能是 JSON 字符串), 默认折叠 */
    data class ToolCall(
        val name: String,
        val input: String?,
        val output: String?,
    ) : SubagentTimelineItem

    /** 旁注类消息 (如 system / tool 角色的补充文本), 默认折叠 */
    data class NoteBlock(val kind: SubagentNoteKind, val text: String) : SubagentTimelineItem

    /** 图片等可视化输出 */
    data class Media(val url: String) : SubagentTimelineItem
}

enum class SubagentNoteKind { SYSTEM, TOOL_RESULT, OTHER }

/**
 * 由日志构建时间线条目 (按消息顺序)。
 * - v2 messages: 展开 parts; 跳过 user 消息 (任务提示词/上下文已单独展示,
 *   避免重复), system / tool 角色文本折叠为旁注。
 * - 旧 transcript: USER 种子同样跳过, assistant 的 text/reasoning/tools 依次展开。
 */
fun subagentTimelineItems(log: SubagentRunLog): List<SubagentTimelineItem> {
    val messages = log.messages
    if (messages != null) {
        return buildList {
            messages.forEach { message ->
                when (message.role) {
                    me.rerere.ai.core.MessageRole.USER -> Unit // 任务提示词单独展示
                    me.rerere.ai.core.MessageRole.SYSTEM -> {
                        val text = message.toText().trim()
                        if (text.isNotEmpty()) {
                            add(SubagentTimelineItem.NoteBlock(SubagentNoteKind.SYSTEM, text))
                        }
                    }

                    me.rerere.ai.core.MessageRole.TOOL -> {
                        val text = message.toText().trim()
                        if (text.isNotEmpty()) {
                            add(SubagentTimelineItem.NoteBlock(SubagentNoteKind.TOOL_RESULT, text))
                        }
                    }

                    else -> {
                        message.parts.forEach { part ->
                            when (part) {
                                is UIMessagePart.Text -> {
                                    val text = part.text.trim()
                                    if (text.isNotEmpty()) add(SubagentTimelineItem.AssistantText(text))
                                }

                                is UIMessagePart.Reasoning -> {
                                    val text = part.reasoning.trim()
                                    if (text.isNotEmpty()) add(SubagentTimelineItem.ReasoningBlock(text))
                                }

                                is UIMessagePart.Tool -> {
                                    add(
                                        SubagentTimelineItem.ToolCall(
                                            name = part.toolName,
                                            input = part.input.takeIf { it.isNotBlank() },
                                            output = part.output
                                                .filterIsInstance<UIMessagePart.Text>()
                                                .joinToString("\n") { it.text }
                                                .takeIf { it.isNotBlank() },
                                        )
                                    )
                                }

                                is UIMessagePart.ServerTool -> {
                                    add(
                                        SubagentTimelineItem.ToolCall(
                                            name = part.toolName,
                                            input = part.input?.toString(),
                                            output = part.output?.toString(),
                                        )
                                    )
                                }

                                is UIMessagePart.Image -> add(SubagentTimelineItem.Media(part.url))
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    return buildList {
        log.legacyTranscript.forEach { turn ->
            when (turn.role?.uppercase()) {
                "USER" -> Unit // 任务提示词单独展示
                "SYSTEM" -> turn.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { add(SubagentTimelineItem.NoteBlock(SubagentNoteKind.SYSTEM, it)) }

                "TOOL" -> turn.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { add(SubagentTimelineItem.NoteBlock(SubagentNoteKind.TOOL_RESULT, it)) }

                else -> {
                    turn.reasoning?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add(SubagentTimelineItem.ReasoningBlock(it)) }
                    turn.text?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { add(SubagentTimelineItem.AssistantText(it)) }
                    turn.tools.forEach { tool ->
                        add(
                            SubagentTimelineItem.ToolCall(
                                name = tool.name ?: "tool",
                                input = tool.input?.takeIf { it.isNotBlank() },
                                output = tool.output?.takeIf { it.isNotBlank() },
                            )
                        )
                    }
                }
            }
        }
    }
}

/** 超长文本截断: 超过 [max] 时保留头部并追加省略号 */
fun truncateSubagentText(text: String, max: Int = SUBAGENT_TEXT_DISPLAY_LIMIT): String =
    if (text.length > max) text.take(max) + "\n…" else text

/** 日志读取状态; UI 据此区分空态文案, 读取/解析错误不会向上抛 */
sealed interface SubagentLogState {
    /** 尚未开始读取 */
    data object Loading : SubagentLogState

    /** 工具结果里没有可用的 logPath (例如启动前就失败的 run) */
    data object Unavailable : SubagentLogState

    /** 有 logPath 但宿主文件不存在 (旧 tool_outputs 日志启动时会被清理) */
    data object Missing : SubagentLogState

    /** 文件存在但内容不是可解析的日志 JSON */
    data object Invalid : SubagentLogState

    data class Parsed(val log: SubagentRunLog) : SubagentLogState
}

/**
 * 读取并解析一次日志文件。调用方负责切到 IO 调度器;
 * 所有 IO/解析异常都收敛为 [SubagentLogState], 不抛给 UI。
 */
fun readSubagentLogState(filesDir: File, logPath: String?): SubagentLogState {
    if (logPath.isNullOrBlank()) return SubagentLogState.Unavailable
    val file = subagentLogFile(filesDir, logPath) ?: return SubagentLogState.Unavailable
    if (!file.isFile) return SubagentLogState.Missing
    val text = runCatching { file.readText() }.getOrNull() ?: return SubagentLogState.Invalid
    val log = parseSubagentRunLog(text) ?: return SubagentLogState.Invalid
    return SubagentLogState.Parsed(log)
}
