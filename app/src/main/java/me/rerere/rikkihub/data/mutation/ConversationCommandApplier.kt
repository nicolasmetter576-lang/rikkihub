package me.rerere.rikkihub.data.mutation

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.localFileUrls
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * Host-frozen identities for one conversation command. They are allocated once when
 * the change is planned and frozen into the approved proposal, so a replay of the same
 * operation produces exactly the same rows (and the same reviewed digest).
 */
@Serializable
data class ConversationHostIds(
    val conversationId: String? = null,
    val nodeIds: List<String> = emptyList(),
    val messageIds: List<String> = emptyList(),
    val atMillis: Long = 0,
)

/** Facts the command validator needs; read from the latest Settings/Room state. */
data class ConversationCommandContext(
    val ordinaryAssistantIds: Set<Uuid>,
    val folderIds: Set<Uuid>,
    val modeInjectionIds: Set<Uuid>,
    val lorebookIds: Set<Uuid>,
)

data class ConversationCommandResult(
    val conversation: Conversation,
    /** file:// URLs no longer referenced by this conversation; they enter retention, never immediate deletion. */
    val releasedFiles: Set<String>,
)

/**
 * Pure tree/metadata transformation for [ConversationMutationCommand]. Only ordinary
 * text and validated metadata are produced: no Tool/ServerTool parts, provider
 * signatures, approval state or execution results can be created through this path.
 * Persistence, BUSY checks and CAS live in the caller (ChatService).
 */
object ConversationCommandApplier {
    const val MAX_TEXT_CHARS = 256 * 1024
    const val MAX_TITLE_CHARS = 512
    const val MAX_PROMPT_CHARS = 64 * 1024
    const val MAX_CWD_CHARS = 4096
    const val MAX_INITIAL_MESSAGES = 32

    fun create(
        command: ConversationMutationCommand.CreateOrdinaryConversation,
        ids: ConversationHostIds,
        context: ConversationCommandContext,
    ): Conversation {
        val conversationId = Uuid.parse(requireNotNull(ids.conversationId) { "Host conversation id missing" })
        val assistantId = Uuid.parse(command.assistantId)
        require(assistantId in context.ordinaryAssistantIds) { "Target must be an existing ordinary assistant" }
        require(command.initialPlainMessages.size <= MAX_INITIAL_MESSAGES) { "Too many initial messages" }
        val folderId = command.folderId?.let(Uuid::parse)?.also {
            require(it in context.folderIds) { "Unknown folder" }
        }
        val at = Instant.ofEpochMilli(ids.atMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
        require(ids.nodeIds.size >= command.initialPlainMessages.size &&
            ids.messageIds.size >= command.initialPlainMessages.size) { "Host message ids missing" }
        val nodes = command.initialPlainMessages.mapIndexed { index, text ->
            MessageNode(
                id = Uuid.parse(ids.nodeIds[index]),
                messages = listOf(plainMessage(Uuid.parse(ids.messageIds[index]), MessageRole.USER, text)),
            )
        }
        return Conversation(
            id = conversationId,
            assistantId = assistantId,
            title = boundedTitle(command.title, allowBlank = true),
            messageNodes = nodes,
            isPinned = command.pinned,
            createAt = at,
            updateAt = at,
            customSystemPrompt = command.customSystemPrompt?.let(::boundedPrompt),
            modeInjectionIds = validatedIds(command.modeInjectionIds, context.modeInjectionIds, "mode injection"),
            lorebookIds = validatedIds(command.lorebookIds, context.lorebookIds, "lorebook"),
            workspaceCwd = command.workspaceCwd?.let(::boundedCwd),
            folderId = folderId,
        )
    }

    fun apply(
        current: Conversation,
        command: ConversationMutationCommand,
        ids: ConversationHostIds,
        context: ConversationCommandContext,
    ): ConversationCommandResult {
        val before = current.files.map { it.toString() }.toSet()
        val at = Instant.ofEpochMilli(ids.atMillis.takeIf { it > 0 } ?: System.currentTimeMillis())
        val next = when (command) {
            is ConversationMutationCommand.PatchMetadata -> patchMetadata(current, command, context)
            is ConversationMutationCommand.AppendPlainMessage -> {
                val text = plainText(command.parts)
                val role = if (command.role == ConversationMutationCommand.Role.USER) MessageRole.USER else MessageRole.ASSISTANT
                val nodeId = Uuid.parse(ids.nodeIds.firstOrNull() ?: error("Host node id missing"))
                val messageId = Uuid.parse(ids.messageIds.firstOrNull() ?: error("Host message id missing"))
                current.copy(messageNodes = current.messageNodes +
                    MessageNode(id = nodeId, messages = listOf(plainMessage(messageId, role, text))), updateAt = at)
            }
            is ConversationMutationCommand.EditPlainTextAsNewCandidate -> {
                val nodeId = Uuid.parse(command.nodeId)
                val node = current.messageNodes.firstOrNull { it.id == nodeId } ?: throw NoSuchElementException("Unknown node")
                val messageId = Uuid.parse(ids.messageIds.firstOrNull() ?: error("Host message id missing"))
                val candidate = plainMessage(messageId, node.role, boundedText(command.text))
                current.copy(messageNodes = current.messageNodes.map {
                    if (it.id == nodeId) it.copy(messages = it.messages + candidate, selectIndex = it.messages.size) else it
                }, updateAt = at)
            }
            is ConversationMutationCommand.SelectCandidate -> {
                val nodeId = Uuid.parse(command.nodeId)
                val node = current.messageNodes.firstOrNull { it.id == nodeId } ?: throw NoSuchElementException("Unknown node")
                require(command.selectIndex in node.messages.indices) { "Candidate index out of range" }
                current.copy(messageNodes = current.messageNodes.map {
                    if (it.id == nodeId) it.copy(selectIndex = command.selectIndex) else it
                })
            }
            is ConversationMutationCommand.DeleteCandidate -> {
                val nodeId = Uuid.parse(command.nodeId)
                val messageId = Uuid.parse(command.messageId)
                val node = current.messageNodes.firstOrNull { it.id == nodeId } ?: throw NoSuchElementException("Unknown node")
                val index = node.messages.indexOfFirst { it.id == messageId }
                require(index >= 0) { "Unknown candidate" }
                if (node.messages.size == 1) {
                    // The last candidate is an explicit node prune, never an empty node.
                    current.copy(messageNodes = current.messageNodes.filterNot { it.id == nodeId }, updateAt = at)
                } else {
                    val remaining = node.messages.filterIndexed { i, _ -> i != index }
                    // Deterministic selection: keep the same candidate if it survives, else the predecessor.
                    val selected = when {
                        node.selectIndex == index -> (index - 1).coerceAtLeast(0)
                        node.selectIndex > index -> node.selectIndex - 1
                        else -> node.selectIndex
                    }
                    current.copy(messageNodes = current.messageNodes.map {
                        if (it.id == nodeId) it.copy(messages = remaining, selectIndex = selected) else it
                    }, updateAt = at)
                }
            }
            is ConversationMutationCommand.PruneNode -> {
                val nodeId = Uuid.parse(command.nodeId)
                require(current.messageNodes.any { it.id == nodeId }) { "Unknown node" }
                current.copy(messageNodes = current.messageNodes.filterNot { it.id == nodeId }, updateAt = at)
            }
            is ConversationMutationCommand.CreateOrdinaryConversation,
            ConversationMutationCommand.SoftDelete,
            ConversationMutationCommand.Restore -> throw IllegalArgumentException("Lifecycle command is not a tree edit")
        }
        val after = next.messageNodes.flatMap { node -> node.messages.flatMap { it.parts } }.localFileUrls()
        // Derived per-tree data (suggestions) is stale after a tree edit; it is never regenerated automatically.
        val result = if (command is ConversationMutationCommand.PatchMetadata) next else next.copy(chatSuggestions = emptyList())
        return ConversationCommandResult(result, before - after)
    }

    private fun patchMetadata(
        current: Conversation,
        command: ConversationMutationCommand.PatchMetadata,
        context: ConversationCommandContext,
    ): Conversation {
        require(!(command.customSystemPrompt != null && command.clearCustomSystemPrompt))
        require(!(command.workspaceCwd != null && command.clearWorkspaceCwd))
        require(!(command.folderId != null && command.clearFolder))
        val assistantId = command.moveToAssistantId?.let(Uuid::parse)?.also {
            require(it in context.ordinaryAssistantIds) { "Target must be an existing ordinary assistant" }
        } ?: current.assistantId
        val folderId = when {
            command.clearFolder -> null
            command.folderId != null -> Uuid.parse(command.folderId).also { require(it in context.folderIds) { "Unknown folder" } }
            assistantId != current.assistantId -> null // folders are per assistant
            else -> current.folderId
        }
        return current.copy(
            title = command.title?.let { boundedTitle(it, allowBlank = false) } ?: current.title,
            customSystemPrompt = when {
                command.clearCustomSystemPrompt -> null
                command.customSystemPrompt != null -> boundedPrompt(command.customSystemPrompt)
                else -> current.customSystemPrompt
            },
            workspaceCwd = when {
                command.clearWorkspaceCwd -> null
                command.workspaceCwd != null -> boundedCwd(command.workspaceCwd)
                else -> current.workspaceCwd
            },
            isPinned = command.isPinned ?: current.isPinned,
            assistantId = assistantId,
            folderId = folderId,
            modeInjectionIds = command.modeInjectionIds?.let {
                validatedIds(it, context.modeInjectionIds, "mode injection")
            } ?: current.modeInjectionIds,
            lorebookIds = command.lorebookIds?.let { validatedIds(it, context.lorebookIds, "lorebook") } ?: current.lorebookIds,
        )
    }

    private fun plainText(parts: List<ConversationMutationCommand.PlainPart>): String {
        require(parts.isNotEmpty()) { "Message has no content" }
        // Management writes carry ordinary text only; media references would let the
        // model attach arbitrary host files, so they are rejected here.
        return parts.joinToString("\n") { part ->
            when (part) {
                is ConversationMutationCommand.PlainPart.Text -> part.text
                else -> throw IllegalArgumentException("Only plain text parts are accepted")
            }
        }.let(::boundedText)
    }

    private fun plainMessage(id: Uuid, role: MessageRole, text: String) =
        UIMessage(id = id, role = role, parts = listOf(UIMessagePart.Text(boundedText(text))))

    private fun boundedText(text: String): String {
        require(text.isNotEmpty() && text.length <= MAX_TEXT_CHARS) { "Text is empty or exceeds the limit" }
        require(text.none { it == '\u0000' }) { "Text contains NUL" }
        return text
    }

    private fun boundedTitle(title: String, allowBlank: Boolean): String {
        require(title.length <= MAX_TITLE_CHARS) { "Title too long" }
        require(allowBlank || title.isNotBlank()) { "Title must not be blank" }
        return title
    }

    private fun boundedPrompt(prompt: String): String {
        require(prompt.length <= MAX_PROMPT_CHARS) { "System prompt too long" }
        return prompt
    }

    private fun boundedCwd(cwd: String): String {
        require(cwd.length <= MAX_CWD_CHARS && cwd.startsWith("/") && "\u0000" !in cwd) { "Invalid workspace directory" }
        return cwd
    }

    private fun validatedIds(ids: Set<String>, known: Set<Uuid>, label: String): Set<Uuid> =
        ids.map(Uuid::parse).toSet().also { parsed ->
            require(parsed.size <= 256) { "Too many $label references" }
            require(known.containsAll(parsed)) { "Unknown $label" }
        }
}
