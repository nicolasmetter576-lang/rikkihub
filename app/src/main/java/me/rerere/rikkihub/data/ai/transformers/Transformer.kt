package me.rerere.rikkihub.data.ai.transformers

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities
import kotlin.uuid.Uuid

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
    val harnessMinimalWorkspace: Boolean = false,
    val exposeUpload: Boolean = true,
    /**
     * 本次请求的工作区能力快照：真实启用工具 stableId→wireName、挂载目标、
     * policyEpoch/viewFingerprint。由装配方（ChatToolFactory/GenerationLoop）填入；
     * 约定：assistant 绑定工作区且实体存在时非 null，shell 未 READY 时
     * availableToolNames 为空表（提示明确 unavailable，不回退旧固定清单）。
     * null = 旧调用方未提供快照 → transformer 走旧 DB/固定清单行为。
     */
    val workspaceCapabilities: WorkspaceRequestCapabilities? = null,
    /** 本回合的酒馆准备结果（正则脚本表与宏环境，含角色自带的脚本和角色名）；没有回合的调用方为 null。 */
    val turn: me.rerere.rikkihub.data.ai.tavern.PreparedTurn? = null,
    /** 这一步之前的会话消息；只有 [OutputMessageTransformer.onGenerationFinish] 能拿到。 */
    val history: List<UIMessage> = emptyList(),
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

/**
 * 在酒馆组装之前运行的输入转换器：处理的是聊天消息本身（比如在用户消息前插时间提醒），
 * 此时还没有系统块和注入。其余输入转换器在组装之后处理完整的请求消息。
 */
interface PreAssemblyTransformer : InputMessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
    harnessMinimalWorkspace: Boolean = false,
    exposeUpload: Boolean = true,
    workspaceCapabilities: WorkspaceRequestCapabilities? = null,
    turn: me.rerere.rikkihub.data.ai.tavern.PreparedTurn? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
        harnessMinimalWorkspace = harnessMinimalWorkspace,
        exposeUpload = exposeUpload,
        workspaceCapabilities = workspaceCapabilities,
        turn = turn,
    )
    return transformers.fold(this) { acc, transformer ->
        transformer.transform(ctx, acc)
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    turn: me.rerere.rikkihub.data.ai.tavern.PreparedTurn? = null,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings, turn = turn)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.visualTransform(ctx, acc)
        } else {
            acc
        }
    }
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    turn: me.rerere.rikkihub.data.ai.tavern.PreparedTurn? = null,
    history: List<UIMessage> = emptyList(),
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings, turn = turn, history = history)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            transformer.onGenerationFinish(ctx, acc)
        } else {
            acc
        }
    }
}
