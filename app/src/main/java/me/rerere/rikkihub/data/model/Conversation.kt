package me.rerere.rikkihub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkihub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 选了 workspaceCwd 时模型只看得到该目录；默认开
    val workspaceCwdScoped: Boolean = true,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false,
    /**
     * 持久乐观并发版本。内存态由 Room 行读入/持久时刷新；
     * 不序列化进备份/网络 payload（本地权威字段，仅为 Room 的 CAS 服务）。
     */
    @Transient
    val revision: Long = 0,
    /** 同 ID 删除-重建换代计数（ABA 防线；软删除 tombstone 时 +1）。 */
    @Transient
    val generation: Long = 0,
    /** 软删除 tombstone 时间戳（epoch millis）；null = 活跃。 */
    @Transient
    val deletedAt: Long? = null,
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.contains(message) }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    /**
     * 把生成循环的消息列表（发起时的当前分支 + 新生成的消息）写回节点。
     *
     * 消息按 id 找节点，而不是按下标：生成期间用户可能删掉前面的消息，下标整体前移，按下标写会把
     * 删掉的消息当成新分支塞进别的节点、把回复写进错误的节点。找不到的消息分两种：后面还有在场的消息时，
     * 它是生成期间被删掉的，跳过不写回；排在所有在场消息之后的是新生成的，放进上一条消息所在节点的
     * 下一个节点（重新生成时成为新分支），没有下一个节点就追加。写到的最后一个节点之后的节点去掉
     * （从中间重新生成时，后面的对话随之截断）。
     */
    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val oldNodes = this.messageNodes

        // 流式更新只在末尾追加/改写，前部节点引用不变，整体复用避免每 chunk 重建。
        var prefixEnd = 0
        while (prefixEnd < messages.size && prefixEnd < oldNodes.size &&
            oldNodes[prefixEnd].let {
                it.selectIndex in it.messages.indices && it.messages[it.selectIndex] === messages[prefixEnd]
            }
        ) {
            prefixEnd++
        }
        if (prefixEnd == messages.size) return this

        // 只在消息不在原位时才建 id 索引；新节点只会追加在末尾，已有节点的下标不会变
        var nodeIndexById: Map<Uuid, Int>? = null
        fun nodeIndexOf(id: Uuid, expected: Int): Int {
            if (expected in oldNodes.indices && oldNodes[expected].messages.any { it.id == id }) return expected
            val index = nodeIndexById ?: buildMap {
                oldNodes.forEachIndexed { nodeIndex, node -> node.messages.forEach { putIfAbsent(it.id, nodeIndex) } }
            }.also { nodeIndexById = it }
            return index[id] ?: -1
        }

        // 最后一条还在会话里的消息；它之前找不到的消息是被删掉的，之后的是新生成的
        var lastPresent = prefixEnd - 1
        for (index in messages.indices.reversed()) {
            if (index < prefixEnd) break
            if (nodeIndexOf(messages[index].id, index) >= 0) {
                lastPresent = index
                break
            }
        }

        val newNodes = ArrayList<MessageNode>(maxOf(messages.size, oldNodes.size))
        newNodes.addAll(oldNodes)
        // 上一条写回的消息所在的节点
        var anchor = prefixEnd - 1

        for (index in prefixEnd until messages.size) {
            val message = messages[index]
            val found = if (index <= lastPresent) nodeIndexOf(message.id, index) else -1
            if (found < 0 && index <= lastPresent) continue // 生成期间被删掉了

            val nodeIndex = if (found >= 0) found else anchor + 1
            val node = newNodes.getOrNull(nodeIndex) ?: message.toMessageNode().also { newNodes.add(it) }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            val sameIdIndex = node.messages.indexOfFirst { it.id == message.id }
            when {
                // 同 id 候选可能出现在其他分支（重试共享 id），优先比对当前选中分支。
                sameIdIndex == node.selectIndex -> {
                    newMessages[node.selectIndex] = message
                }

                sameIdIndex >= 0 -> {
                    newMessages[sameIdIndex] = message
                }

                else -> {
                    newMessages.add(message)
                    newMessageIndex = newMessages.lastIndex
                }
            }

            newNodes[nodeIndex] = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )
            anchor = nodeIndex
        }

        // 写到的最后一个节点之后的不要了
        if (anchor + 1 < newNodes.size) newNodes.subList(anchor + 1, newNodes.size).clear()

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 最近一次回复结束时的上下文占用（token），取不到时返回 null。
 * 之后又插入了压缩检查点的话，记录的占用已经过时，同样返回 null；压缩时保留的最近几条留在检查点之后，
 * 但它们是压缩前生成的，按创建时间也算过时。
 */
fun Conversation.lastContextTokens(): Int? {
    val messages = currentMessages
    val replyIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (replyIndex < 0) return null
    val checkpointIndex = messages.indexOfLast { it.isContextCheckpoint }
    if (checkpointIndex > replyIndex) return null
    if (checkpointIndex >= 0 && messages[replyIndex].createdAt < messages[checkpointIndex].createdAt) return null
    return messages[replyIndex].contextTokens()
}

/**
 * 在 [afterNodeId] 节点之后插入压缩检查点（一条带标记的摘要用户消息）；该节点已不存在时返回 null。
 * 原有消息、分支和附件都保留，删除这条摘要即撤销压缩。
 */
internal fun Conversation.insertContextCheckpoint(afterNodeId: Uuid, summary: String): Conversation? {
    val index = messageNodes.indexOfFirst { it.id == afterNodeId }
    if (index == -1) return null
    val checkpoint = UIMessage.user(summary).copy(isContextCheckpoint = true).toMessageNode()
    return copy(
        messageNodes = messageNodes.subList(0, index + 1) + checkpoint +
            messageNodes.subList(index + 1, messageNodes.size),
    )
}

/**
 * 给还没有模型快照的消息补上快照，没有可补的消息时原样返回。
 *
 * 在保存会话时调用：新生成的消息由此带上快照，引入快照之前的旧消息也在会话下次保存时补齐。
 * 模型已经被删除的旧消息无从补起，保持原样。
 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    if (models.isEmpty()) return this
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果/参数中的嵌套附件。 */
@Suppress("DEPRECATION") // ToolResult/ToolCall 旧件在持久消息中仍可能出现
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                // 工具入参 JSON 字符串里可能嵌 file:// 引用（如读图工具）
                addAll(extractLocalFileUrlsFromText(part.input))
                null
            }

            is UIMessagePart.ServerTool -> {
                part.input?.let { addAll(extractLocalFileUrlsFromText(it.toString())) }
                part.output?.let { addAll(extractLocalFileUrlsFromText(it.toString())) }
                null
            }

            is UIMessagePart.ToolResult -> {
                addAll(extractLocalFileUrlsFromText(part.content.toString()))
                null
            }

            is UIMessagePart.ToolCall -> {
                addAll(extractLocalFileUrlsFromText(part.arguments))
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

// 保守地从 JSON/文本中提取 file:// 引用：误报只是多保护一个文件，不会误删。
private val LOCAL_FILE_URL_IN_TEXT_REGEX = Regex("file://[^\"'\\s\\\\]+")

internal fun extractLocalFileUrlsFromText(text: String): Set<String> =
    LOCAL_FILE_URL_IN_TEXT_REGEX.findAll(text).map { it.value }.toSet()
