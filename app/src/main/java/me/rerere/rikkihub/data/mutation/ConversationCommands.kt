package me.rerere.rikkihub.data.mutation

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 普通会话管理命令集（`applyConversationCommand` 的 typed 入参）。
 *
 * 约束：
 * - 只承载普通文本/元数据语义；**不含** Tool/ServerTool part、provider 签名、
 *   审批状态或执行结果——那些字段在命令应用时被拒绝或剥离。
 * - 所有命令经 ChatService 的统一通道：等完整初始化 → BUSY/token → Room 事务
 *   CAS + FTS + receipt → 安装内存快照 → dispatch。
 * - ID/时间由宿主分配；命令里的 clientId 仅作请求级去重参考，不是对象 ID。
 */
@Serializable
sealed interface ConversationMutationCommand {

    /** 元数据 patch；`null` 字段表示"不触碰"，显式 clearX 为 true 时才清除可空列。 */
    @Serializable
    data class PatchMetadata(
        val title: String? = null,
        val customSystemPrompt: String? = null,
        val clearCustomSystemPrompt: Boolean = false,
        val workspaceCwd: String? = null,
        val clearWorkspaceCwd: Boolean = false,
        val isPinned: Boolean? = null,
        /** 必须指向已存在普通 assistant；跨创造边界（保留 ID）被拒绝。 */
        val moveToAssistantId: String? = null,
        val folderId: String? = null,
        val clearFolder: Boolean = false,
        val modeInjectionIds: Set<String>? = null,
        val lorebookIds: Set<String>? = null,
    ) : ConversationMutationCommand

    /** 新建普通会话；只允许有效普通 assistantId 与普通初始消息。 */
    @Serializable
    data class CreateOrdinaryConversation(
        val assistantId: String,
        val title: String = "",
        val folderId: String? = null,
        val pinned: Boolean = false,
        val initialPlainMessages: List<String> = emptyList(),
        val customSystemPrompt: String? = null,
        val workspaceCwd: String? = null,
        val modeInjectionIds: Set<String> = emptySet(),
        val lorebookIds: Set<String> = emptySet(),
    ) : ConversationMutationCommand

    /** 追加普通用户/助手文本消息（parts 只允许 Text/普通媒体引用）。 */
    @Serializable
    data class AppendPlainMessage(
        val role: Role,
        val parts: List<PlainPart>,
    ) : ConversationMutationCommand

    /** 在指定节点上以**新候选**形式编辑纯文本（不改写既有候选）。 */
    @Serializable
    data class EditPlainTextAsNewCandidate(
        val nodeId: String,
        val text: String,
    ) : ConversationMutationCommand

    @Serializable
    data class SelectCandidate(
        val nodeId: String,
        val selectIndex: Int,
    ) : ConversationMutationCommand

    /** 删除单个候选消息；最后一个候选被删时升级为 DeleteNode。 */
    @Serializable
    data class DeleteCandidate(
        val nodeId: String,
        val messageId: String,
    ) : ConversationMutationCommand

    /** 剪掉整棵节点（全部候选），派生关系（FTS/收藏/附件）同事务失效。 */
    @Serializable
    data class PruneNode(
        val nodeId: String,
    ) : ConversationMutationCommand

    /** 软删除（tombstone）；文件保留期内的 Undo 依赖它。 */
    @Serializable
    data object SoftDelete : ConversationMutationCommand

    /** 从 tombstone 恢复；要求对象 generation 匹配（防 ABA）。 */
    @Serializable
    data object Restore : ConversationMutationCommand

    enum class Role { USER, ASSISTANT }

    /** 命令允许携带的 part：纯文本或普通 file:// 媒体 URL；拒绝 Tool/ServerTool/签名/审批。 */
    @Serializable
    sealed interface PlainPart {
        @Serializable
        data class Text(val text: String) : PlainPart

        @Serializable
        data class Image(val url: String) : PlainPart

        @Serializable
        data class Document(val url: String, val fileName: String = "", val mime: String = "") : PlainPart
    }
}

/** 会话版本断言（对象层）：`applyConversationCommand` 的 expectedToken 对应物。 */
data class ConversationVersion(
    val epoch: Long,
    val generation: Long,
    val revision: Long,
) {
    fun asToken(conversationId: Uuid): VersionToken = VersionToken(
        domain = MutationDomain.ROOM_CONVERSATION,
        objectKey = "conversation:$conversationId",
        epoch = epoch,
        generation = generation,
        revision = revision,
    )

    companion object {
        fun fromToken(token: VersionToken): ConversationVersion {
            require(token.domain == MutationDomain.ROOM_CONVERSATION) {
                "conversation token must have ROOM_CONVERSATION domain"
            }
            return ConversationVersion(token.epoch, token.generation, token.revision)
        }
    }
}
