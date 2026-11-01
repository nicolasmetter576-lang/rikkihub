package me.rerere.ai.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolIdentity

@Serializable
data class Tool(
    val name: String,
    val description: String,
    /**
     * 可选的稳定工作区工具身份；非工作区工具（memory/search/local/skill/MCP）恒为 null。
     *
     * 由装配端在候选阶段填入，过滤与包装后再应用 Override；记录的是稳定归属
     * （workspaceId/stableId/mode），不是随请求冻结的 wire 名（[name] 可能已被
     * ToolOverride 改名）。本字段不进入 provider wire——各 provider 只读取
     * name/description/parameters 构造请求体。
     */
    val workspaceIdentity: WorkspaceToolIdentity? = null,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    /**
     * 可选的扩展入口。非空时生成循环优先调用它而不是 [execute], 让工具拿到
     * [ToolExecutionContext] (toolCallId + 实时 metadata 发布通道)。
     *
     * 同一调用内, 发布进度只能通过 `context.publishMetadata` 直接挂起调用, 不能派生
     * 并发协程去发; 不同调用 (按 [concurrency] 并行执行时) 各自的发布可以同时发生。
     */
    val executeWithContext: (suspend (JsonElement, ToolExecutionContext) -> List<UIMessagePart>)? = null,
    /**
     * 调度提示：本工具的调用能否与同一回复里其他可并行的调用同时执行。默认独占
     * （与以前的逐个执行相同）；只读/无副作用的工具由装配端标注。不进入 provider wire。
     */
    val concurrency: ToolConcurrency = ToolConcurrency.Exclusive,
)

/**
 * 工具调用的并发策略。生成循环把一次回复里**连续**的可并行调用放在一起同时执行，
 * 独占调用单独执行并作为分隔点，段与段按原顺序进行。
 */
sealed interface ToolConcurrency {
    fun isParallel(args: JsonElement): Boolean

    /** 必须单独执行（有副作用、需要交互或无法判断）。 */
    data object Exclusive : ToolConcurrency {
        override fun isParallel(args: JsonElement): Boolean = false
    }

    /** 可与其他可并行调用同时执行。 */
    data object Parallel : ToolConcurrency {
        override fun isParallel(args: JsonElement): Boolean = true
    }

    /** 只有部分参数形态可并行（如 `str_replace_editor` 的 `view`）。 */
    class ParallelWhen(private val predicate: (JsonElement) -> Boolean) : ToolConcurrency {
        override fun isParallel(args: JsonElement): Boolean = runCatching { predicate(args) }.getOrDefault(false)
    }
}

/** 是否有任何参数形态可以并行（提示文案与界面用；不看具体参数）。 */
val ToolConcurrency.mayRunInParallel: Boolean get() = this !is ToolConcurrency.Exclusive

/**
 * 单次工具调用的执行上下文, 只提供给实现了 [Tool.executeWithContext] 的工具。
 *
 * [publishMetadata] 会把 metadata 写回所属 toolCallId 的 [UIMessagePart.Tool.metadata]
 * 并让外层 flow 重新发射消息列表（多个调用同时在跑时合并限频）。同一调用内它必须在
 * 调用方协程里顺序挂起调用, 不得另起协程并发调用; 不同调用之间的并发由生成循环加锁处理。
 */
class ToolExecutionContext(
    val toolCallId: String,
    private val publishMetadataCallback: suspend (JsonObject) -> Unit,
) {
    suspend fun publishMetadata(metadata: JsonObject) {
        try {
            publishMetadataCallback(metadata)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // This is a failure of the progress consumer, not of the tool. Tool runners
            // must propagate it rather than emit another result to a failed Flow collector.
            throw ToolProgressException(failure)
        }
    }
}

class ToolProgressException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
