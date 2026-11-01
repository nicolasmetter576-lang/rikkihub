package me.rerere.rikkihub.data.creator.storage

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * 一次性迁移（2.8.0）：把 2.7.x 的独立创造会话 transcript 导入为普通会话。
 *
 * 会话 ID 沿用旧创造会话 ID——旧提案的 `sessionId` 正是它，迁移后旧提案自动
 * 出现在该会话的提案面板。成功（或已存在同 ID 会话）后删除旧文件；文件损坏或
 * 写入失败保留原文件，下次启动重试。
 */
class CreatorTranscriptMigration(
    private val transcripts: CreatorTranscriptStore,
    private val conversationExists: suspend (Uuid) -> Boolean,
    private val insertConversation: suspend (Conversation) -> Unit,
) {
    enum class Result { NOTHING_TO_DO, MIGRATED, ALREADY_PRESENT, EMPTY }

    suspend fun migrate(): Result {
        if (!transcripts.exists(LEGACY_SESSION_ID)) return Result.NOTHING_TO_DO
        if (conversationExists(LEGACY_SESSION_ID)) {
            transcripts.delete(LEGACY_SESSION_ID)
            return Result.ALREADY_PRESENT
        }
        val messages = transcripts.loadStrict(LEGACY_SESSION_ID)
        if (messages.isEmpty()) {
            transcripts.delete(LEGACY_SESSION_ID)
            return Result.EMPTY
        }
        val title = messages.firstOrNull { it.role == MessageRole.USER }
            ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString(" ") { it.text }
            ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(40)
            .orEmpty()
        insertConversation(
            Conversation(
                id = LEGACY_SESSION_ID,
                assistantId = CreatorAssistantDefinition.ID,
                title = title,
                messageNodes = messages.map { MessageNode.of(it) },
            )
        )
        transcripts.delete(LEGACY_SESSION_ID)
        return Result.MIGRATED
    }

    companion object {
        /** 2.7.x 唯一创造会话 ID（旧提案的 sessionId）。 */
        val LEGACY_SESSION_ID: Uuid = Uuid.parse("c0ae7a11-7a3d-4b2e-9f1a-2c4e5d6b7a91")
    }
}
