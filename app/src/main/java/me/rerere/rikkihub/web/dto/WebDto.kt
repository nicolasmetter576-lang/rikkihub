package me.rerere.rikkihub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode

// ========== Request DTOs ==========

@Serializable
data class SendMessageRequest(
    val parts: List<UIMessagePart>,
    /** 新会话首条消息归入的文件夹（侧边栏当前选中的）；已存在的会话忽略 */
    val folderId: String? = null,
    val modeInjectionIds: List<String>? = null,
    val lorebookIds: List<String>? = null,
    val answer: Boolean = true,
)

@Serializable
data class RegenerateRequest(
    val messageId: String
)

@Serializable
data class ToolApprovalRequest(
    val toolCallId: String,
    val approved: Boolean,
    val reason: String = "",
    val answer: String? = null,
)

@Serializable
data class EditMessageRequest(
    val parts: List<UIMessagePart>
)

@Serializable
data class ForkConversationRequest(
    val messageId: String
)

@Serializable
data class SelectMessageNodeRequest(
    val selectIndex: Int
)

@Serializable
data class MoveConversationRequest(
    val assistantId: String
)

@Serializable
data class UpdateConversationTitleRequest(
    val title: String
)

@Serializable
data class UpdateConversationInjectionsRequest(
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
)

@Serializable
data class CreateFolderRequest(
    val name: String
)

@Serializable
data class RenameFolderRequest(
    val name: String
)

@Serializable
data class MoveConversationToFolderRequest(
    // null 表示移出文件夹（未归类）
    val folderId: String? = null
)

@Serializable
data class UpdateAssistantRequest(
    val assistantId: String
)

@Serializable
data class UpdateAssistantModelRequest(
    val assistantId: String,
    val modelId: String,
)

@Serializable
data class UpdateAssistantReasoningLevelRequest(
    val assistantId: String,
    val reasoningLevel: ReasoningLevel,
)

@Serializable
data class UpdateAssistantMcpServersRequest(
    val assistantId: String,
    val mcpServerIds: List<String>,
)

@Serializable
data class UpdateAssistantInjectionsRequest(
    val assistantId: String,
    val modeInjectionIds: List<String>,
    val lorebookIds: List<String>,
    val quickMessageIds: List<String> = emptyList(),
)

@Serializable
data class UpdateSearchEnabledRequest(
    val assistantId: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateSearchServiceRequest(
    val index: Int,
)

@Serializable
data class UpdateBuiltInToolRequest(
    val modelId: String,
    val tool: String,
    val enabled: Boolean,
)

@Serializable
data class UpdateFavoriteModelsRequest(
    val modelIds: List<String>,
)

@Serializable
data class WebAuthTokenRequest(
    val password: String,
)

/**
 * Validated merge-patch body for PUT /api/conversations/{id}.
 *
 * A patchedXxx flag marks the key as present in the request body; a null value
 * then means "clear the field" (explicit JSON null), not "leave unchanged".
 */
data class ConversationMetadataPatch(
    val patchedTitle: Boolean = false,
    val title: String? = null,
    val patchedCustomSystemPrompt: Boolean = false,
    val customSystemPrompt: String? = null,
    val patchedWorkspaceCwd: Boolean = false,
    val workspaceCwd: String? = null,
    /** null = unchanged. */
    val workspaceCwdScoped: Boolean? = null,
)

@Serializable
data class FinishEditQueuedMessageRequest(
    // null 取消编辑（仅解锁，不动附件）；非 null 更新排队内容
    val parts: List<UIMessagePart>? = null,
)

@Serializable
data class TranslateMessageRequest(
    val language: String,
)

// ========== Response DTOs ==========

@Serializable
data class ConversationListDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val folderId: String? = null,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false
)

@Serializable
data class FolderDto(
    val id: String,
    val assistantId: String,
    val name: String,
    val sortIndex: Int,
    val createAt: Long,
)

@Serializable
data class PagedResult<T>(
    val items: List<T>,
    val nextOffset: Int? = null,
    val hasMore: Boolean = nextOffset != null
)

@Serializable
data class UploadedFileDto(
    val id: Long,
    val url: String,
    val fileName: String,
    val mime: String,
    val size: Long
)

@Serializable
data class UploadFilesResponseDto(
    val files: List<UploadedFileDto>
)

@Serializable
data class ConversationDto(
    val id: String,
    val assistantId: String,
    val title: String,
    val messages: List<MessageNodeDto>,
    val chatSuggestions: List<String>,
    val isPinned: Boolean,
    val customSystemPrompt: String? = null,
    val modeInjectionIds: List<String> = emptyList(),
    val lorebookIds: List<String> = emptyList(),
    val workspaceCwd: String? = null,
    val workspaceCwdScoped: Boolean = true,
    val folderId: String? = null,
    val createAt: Long,
    val updateAt: Long,
    val isGenerating: Boolean = false,
    val queueSize: Int = 0,
    val queuePaused: Boolean = false,
)

@Serializable
data class MessageNodeDto(
    val id: String,
    val messages: List<MessageDto>,
    val selectIndex: Int,
    val isFavorite: Boolean = false,
)

@Serializable
data class QueuedMessageDto(
    val id: String,
    val parts: List<UIMessagePart>,
    val answer: Boolean,
    val isEditing: Boolean,
)

@Serializable
data class MessageQueueDto(
    val messages: List<QueuedMessageDto>,
    val paused: Boolean,
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: String,
    val finishedAt: String? = null,
    val modelId: String? = null,
    /** 模型名称快照：模型被删除后用它显示这条消息由谁生成 */
    val modelSnapshot: ModelSnapshot? = null,
    /** 多步工具调用时是各步累计值 */
    val usage: TokenUsage? = null,
    /** 模型实际生成耗时（毫秒），不含首 token 延迟、工具执行和审批等待 */
    val generationTimeMs: Long? = null,
    /** 最后一次请求自己的 usage（上下文占用、缓存命中看这个） */
    val lastUsage: TokenUsage? = null,
    val stepCount: Int? = null,
    val translation: String? = null,
    /** 压缩检查点：此消息是它之前全部历史的摘要，之前的消息不再发送给模型 */
    val isContextCheckpoint: Boolean = false,
    /** Validated chart_display results; null when the message has none. */
    val charts: List<ChartDto>? = null,
)

@Serializable
data class ForkConversationResponse(
    val conversationId: String
)

@Serializable
data class MessageSearchResultDto(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Long,
    val snippet: String,
)

@Serializable
data class WebAuthTokenResponse(
    val token: String,
    val expiresAt: Long,
)

// ========== Error Response ==========

@Serializable
data class ErrorResponse(
    val error: String,
    val code: Int,
    val errorCode: String? = null,
)

// ========== SSE Event DTOs ==========

@Serializable
data class ConversationUpdateEvent(
    val type: String = "update",
    val conversation: ConversationDto
)

@Serializable
data class ConversationSnapshotEvent(
    val type: String = "snapshot",
    val seq: Long,
    val conversation: ConversationDto,
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class ConversationNodeUpdateEvent(
    val type: String = "node_update",
    val seq: Long,
    val conversationId: String,
    val nodeId: String,
    val nodeIndex: Int,
    val node: MessageNodeDto,
    val updateAt: Long,
    val isGenerating: Boolean,
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class GenerationDoneEvent(
    val type: String = "done",
    val conversationId: String
)

@Serializable
data class ConversationQueueEvent(
    val type: String = "queue_update",
    val conversationId: String,
    val queue: MessageQueueDto,
    val serverTime: Long = System.currentTimeMillis()
)

@Serializable
data class ErrorEvent(
    val type: String = "error",
    val message: String
)

@Serializable
data class ConversationListInvalidateEvent(
    val type: String = "invalidate",
    val assistantId: String,
    val timestamp: Long
)

@Serializable
data class FolderListEvent(
    val assistantId: String,
    val folders: List<FolderDto>,
)

/**
 * `workspace_update` 多路事件: 工作区行任意列变化后推送受影响 id。
 * 前端据此 invalidate React Query 工作区 detail/list, 跨 App/Web 修改可见。
 */
@Serializable
data class WorkspaceUpdateEvent(
    val workspaceIds: List<String>,
    val timestamp: Long,
)

// ========== Conversion Extensions ==========

fun Conversation.toListDto(isGenerating: Boolean = false) = ConversationListDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    isPinned = isPinned,
    folderId = folderId?.toString(),
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating
)

fun me.rerere.rikkihub.data.model.Folder.toDto() = FolderDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    name = name,
    sortIndex = sortIndex,
    createAt = createAt.toEpochMilli(),
)

fun Conversation.toDto(
    isGenerating: Boolean = false,
    queueSize: Int = 0,
    queuePaused: Boolean = false,
) = ConversationDto(
    id = id.toString(),
    assistantId = assistantId.toString(),
    title = title,
    messages = messageNodes.map { it.toDto() },
    chatSuggestions = chatSuggestions,
    isPinned = isPinned,
    customSystemPrompt = customSystemPrompt,
    modeInjectionIds = modeInjectionIds.map { it.toString() },
    lorebookIds = lorebookIds.map { it.toString() },
    workspaceCwd = workspaceCwd,
    workspaceCwdScoped = workspaceCwdScoped,
    folderId = folderId?.toString(),
    createAt = createAt.toEpochMilli(),
    updateAt = updateAt.toEpochMilli(),
    isGenerating = isGenerating,
    queueSize = queueSize,
    queuePaused = queuePaused,
)

fun MessageNode.toDto() = MessageNodeDto(
    id = id.toString(),
    messages = messages.map { it.toDto() },
    selectIndex = selectIndex,
    isFavorite = isFavorite,
)

fun me.rerere.rikkihub.service.QueuedMessage.toDto() = QueuedMessageDto(
    id = id.toString(),
    parts = parts,
    answer = answer,
    isEditing = isEditing,
)

fun me.rerere.rikkihub.service.MessageQueueState.toDto() = MessageQueueDto(
    messages = messages.map { it.toDto() },
    paused = paused,
)

fun UIMessage.toDto() = MessageDto(
    id = id.toString(),
    role = role.name,
    parts = parts,
    annotations = annotations,
    createdAt = createdAt.toString(),
    finishedAt = finishedAt?.toString(),
    modelId = modelId?.toString(),
    modelSnapshot = modelSnapshot,
    usage = usage,
    generationTimeMs = generationTimeMs,
    lastUsage = lastUsage,
    stepCount = stepCount,
    translation = translation,
    isContextCheckpoint = isContextCheckpoint,
    charts = WebChartProjection.chartsOf(this),
)
