package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.markedAsStepStart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.resolvedName

/** 本次请求里读、写两个工具的线上名（已按「设置-工具」改名）。 */
data class PseudoIoNames(val read: String, val write: String) {
    companion object {
        fun of(overrides: Map<String, ToolOverride>) = PseudoIoNames(
            read = BuiltinToolCatalog.require(BuiltinToolIds.PSEUDO_READ).resolvedName(overrides),
            write = BuiltinToolCatalog.require(BuiltinToolIds.PSEUDO_WRITE).resolvedName(overrides),
        )
    }
}

internal fun buildPseudoIoTools(): List<Tool> = listOf(
    Tool(
        name = BuiltinToolIds.PSEUDO_READ,
        description = BuiltinToolCatalog.require(BuiltinToolIds.PSEUDO_READ).defaultDescription,
        systemPrompt = { _, _ -> BuiltinToolCatalog.require(BuiltinToolIds.PSEUDO_READ).defaultSystemPrompt.orEmpty() },
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = { error("read is settled by the generation loop") },
    ),
    Tool(
        name = BuiltinToolIds.PSEUDO_WRITE,
        description = BuiltinToolCatalog.require(BuiltinToolIds.PSEUDO_WRITE).defaultDescription,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(PseudoIo.CONTENT, buildJsonObject {
                        put("type", "string")
                        put("description", "The full text of your reply, in Markdown")
                    })
                },
                required = listOf(PseudoIo.CONTENT),
            )
        },
        execute = { error("write is settled by the generation loop") },
    ),
)

/**
 * 伪工具读写：模型用 write 回复、用 read 取用户输入。
 *
 * 会话里存的仍是普通消息——write 的内容落成带标记的文字 part，read 是隐藏的工具 part——
 * 只在发请求前由 [toWire] 改写成工具调用与结果。[toWire] 只看消息本身、不看时间和状态，
 * 同一段历史每次改写的结果逐字相同，已缓存的请求前缀不会变；关掉开关后历史就是普通对话。
 */
object PseudoIo {
    const val KEY = "pseudo_io"
    const val CONTENT = "content"

    const val DELIVERED = "Delivered to the user."
    const val INTERRUPTED = "Interrupted: the reply was cut off here, the user only received the text above."
    const val NO_INPUT = "(no new user message)"
    const val DUPLICATE_READ = "Ignored: call the tool once per response."

    private const val KIND = "kind"
    private const val WRITE = "write"
    private const val READ = "read"
    private const val ID = "id"
    private const val NAME = "name"
    private const val PARTIAL = "partial"
    private const val OPEN = "open"
    private const val TOOL = "tool"

    private fun UIMessagePart.pseudo(kind: String): JsonObject? =
        (metadata?.get(KEY) as? JsonObject)?.takeIf { it[KIND] == JsonPrimitive(kind) }

    private fun JsonObject.flag(key: String) = this[key] == JsonPrimitive(true)

    /** read 调用（界面不显示）。 */
    fun isRead(part: UIMessagePart): Boolean = part is UIMessagePart.Tool && part.pseudo(READ) != null

    private fun isOpenRead(part: UIMessagePart): Boolean =
        part is UIMessagePart.Tool && part.pseudo(READ)?.flag(OPEN) == true

    /** 回合已经交给用户：最后一步里有等待输入的 read。 */
    fun awaitsUser(message: UIMessage): Boolean =
        message.role == MessageRole.ASSISTANT && message.parts.any(::isOpenRead)

    /** 一步回复整理后的结果；[retry] = 模型收到了提示或报错，需要再请求一次。 */
    data class Settled(val message: UIMessage, val retry: Boolean)

    /**
     * 流式途中的显示版本：write 的参数还没收完，已有的内容先显示成文字。
     * 用户此时停止，留下的就是这段带 `partial` 标记的文字。
     */
    fun preview(message: UIMessage, newPartsFrom: Int, names: PseudoIoNames): UIMessage =
        message.mapNewParts(newPartsFrom) { part ->
            when {
                part !is UIMessagePart.Tool || part.isExecuted -> part
                part.toolName == names.write -> part.asWrittenText(part.partialContent().orEmpty(), partial = true)
                part.toolName == names.read -> part.markedRead(open = false)
                else -> part
            }
        }

    /** 一步回复结束后落定：write 变成文字，read 写入结果。 */
    fun settle(message: UIMessage, newPartsFrom: Int, names: PseudoIoNames): Settled {
        var retry = false
        var readSeen = false
        // 这条回复到目前为止有没有给用户看的内容（含本步的 write，不论它排在 read 前还是后）
        val replied = message.parts.any { part ->
            part is UIMessagePart.Text && part.text.isNotBlank() ||
                part is UIMessagePart.Tool && !part.isExecuted && part.toolName == names.write &&
                !part.partialContent().isNullOrBlank()
        }
        val nudged = message.parts.any { it is UIMessagePart.Tool && it.isExecuted && it.pseudo(READ)?.flag(OPEN) == false }
        val settled = message.mapNewParts(newPartsFrom) { part ->
            when {
                part !is UIMessagePart.Tool || part.isExecuted -> part
                part.toolName == names.write -> {
                    val complete = part.completeContent()
                    val partial = part.partialContent()
                    when {
                        complete != null -> part.asWrittenText(complete, partial = false)
                        // 参数没收完（多半是撞了输出上限）：已有的内容照样交给用户
                        !partial.isNullOrBlank() -> part.asWrittenText(partial, partial = true)
                        else -> {
                            retry = true
                            part.copy(output = listOf(UIMessagePart.Text(invalidWrite(names))))
                        }
                    }
                }

                part.toolName == names.read -> when {
                    readSeen -> part.markedRead(open = false).copy(output = listOf(UIMessagePart.Text(DUPLICATE_READ)))
                    // 还没回复就先来读：提醒一次；再犯就按等待输入处理，不和模型来回拉扯
                    !replied && !nudged -> {
                        readSeen = true
                        retry = true
                        part.markedRead(open = false).copy(output = listOf(UIMessagePart.Text(readBeforeReply(names))))
                    }

                    else -> {
                        readSeen = true
                        part.markedRead(open = true).copy(output = listOf(UIMessagePart.Text(NO_INPUT)))
                    }
                }

                else -> part
            }
        }
        return Settled(settled, retry)
    }

    /**
     * 流式工具参数中途中断时落定已写出的回复：本步没有非空 write 内容就返回 null（由调用方整步丢弃）；
     * 有则去掉本步未执行的其他工具调用与空 write，打上步界标记后按 [settle] 转为文字。
     */
    fun settleInterrupted(message: UIMessage, newPartsFrom: Int, names: PseudoIoNames): UIMessage? {
        val keepFrom = newPartsFrom.coerceIn(0, message.parts.size)
        val hasWrite = message.parts.drop(keepFrom).any { part ->
            part is UIMessagePart.Tool && !part.isExecuted && part.toolName == names.write && part.hasWrittenContent()
        }
        if (!hasWrite) return null
        val filteredParts = message.parts.filterIndexed { index, part ->
            index < keepFrom || part !is UIMessagePart.Tool || part.isExecuted ||
                (part.toolName == names.write && part.hasWrittenContent())
        }.toMutableList()
        val firstToolIndex = filteredParts.indices.firstOrNull { index ->
            index >= keepFrom && (filteredParts[index] as? UIMessagePart.Tool)?.isExecuted == false
        }
        if (firstToolIndex != null) {
            filteredParts[firstToolIndex] = (filteredParts[firstToolIndex] as UIMessagePart.Tool).markedAsStepStart()
        }
        return settle(message.copy(parts = filteredParts), keepFrom, names).message
    }

    /**
     * 发给提供商之前的改写。[names] 为 null（本次请求没带这两个工具）时只去掉 read 调用，
     * write 的内容就是普通的助手文字。
     *
     * 带标记的文字还原成 write 调用和固定结果；紧跟在后面的用户消息成为 read 的结果——模型自己
     * 调过 read 就填进那次调用，否则在 write 旁边补一个并行的 read（调用 id 由用户消息 id 推出）。
     * 挂不上的用户消息（会话第一条、上一条回复没用 write、中间隔着注入的消息、带音视频）保持普通消息。
     */
    fun toWire(messages: List<UIMessage>, names: PseudoIoNames?): List<UIMessage> {
        if (messages.none { message -> message.parts.any { it.metadata?.containsKey(KEY) == true } }) return messages
        val out = ArrayList<UIMessage>(messages.size)
        var index = 0
        while (index < messages.size) {
            val message = messages[index++]
            if (message.role != MessageRole.ASSISTANT) {
                out += message
                continue
            }
            if (names == null) {
                out += message.copy(parts = message.parts.filterNot(::isRead))
                continue
            }
            val parts = message.parts.mapTo(ArrayList()) { part ->
                val write = (part as? UIMessagePart.Text)?.pseudo(WRITE) ?: return@mapTo part
                UIMessagePart.Tool(
                    toolCallId = (write[ID] as JsonPrimitive).content,
                    toolName = (write[NAME] as JsonPrimitive).content,
                    input = buildJsonObject { put(CONTENT, part.text) }.toString(),
                    output = listOf(UIMessagePart.Text(if (write.flag(PARTIAL)) INTERRUPTED else DELIVERED)),
                    metadata = write[TOOL] as? JsonObject,
                )
            }
            val input = messages.getOrNull(index)?.takeIf { it.carriesUserInput() }
            if (input != null) {
                val trailing = parts.indices.reversed().takeWhile { (parts[it] as? UIMessagePart.Tool)?.isExecuted == true }
                val openRead = trailing.firstOrNull { isOpenRead(parts[it]) }
                when {
                    openRead != null -> {
                        parts[openRead] = (parts[openRead] as UIMessagePart.Tool).copy(output = input.parts)
                        index++
                    }

                    trailing.isNotEmpty() && message.parts[trailing.first()].pseudo(WRITE) != null -> {
                        parts += UIMessagePart.Tool(
                            toolCallId = "call_" + input.id.toHexString(),
                            toolName = names.read,
                            input = "{}",
                            output = input.parts,
                        )
                        index++
                    }
                }
            }
            out += message.copy(parts = parts)
        }
        return out
    }

    private fun UIMessage.carriesUserInput(): Boolean =
        role == MessageRole.USER && !isSynthetic && !isContextCheckpoint && isValidToUpload() &&
            parts.all { it is UIMessagePart.Text || it is UIMessagePart.Image }

    private fun UIMessage.mapNewParts(from: Int, transform: (UIMessagePart) -> UIMessagePart): UIMessage {
        var changed = false
        val mapped = parts.mapIndexed { index, part ->
            if (index < from) part else transform(part).also { if (it !== part) changed = true }
        }
        return if (changed) copy(parts = mapped) else this
    }

    private fun UIMessagePart.Tool.completeContent(): String? =
        runCatching { Json.parseToJsonElement(input).jsonObject }.getOrNull()?.contentString()

    private fun UIMessagePart.Tool.partialContent(): String? = (inputAsPartialJson() as? JsonObject)?.contentString()

    private fun UIMessagePart.Tool.hasWrittenContent(): Boolean =
        !(completeContent() ?: partialContent()).isNullOrBlank()

    private fun JsonObject.contentString(): String? = (this[CONTENT] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun UIMessagePart.Tool.asWrittenText(text: String, partial: Boolean) = UIMessagePart.Text(
        text = text,
        metadata = buildJsonObject {
            put(KEY, buildJsonObject {
                put(KIND, WRITE)
                put(ID, toolCallId)
                put(NAME, toolName)
                if (partial) put(PARTIAL, true)
                // 提供商随调用下发、回放时要原样带回的数据（思考签名、步界标记）
                this@asWrittenText.metadata?.let { put(TOOL, it) }
            })
        },
    )

    private fun UIMessagePart.Tool.markedRead(open: Boolean): UIMessagePart.Tool = copy(
        metadata = JsonObject(metadata.orEmpty() + (KEY to buildJsonObject {
            put(KIND, READ)
            put(OPEN, open)
        })),
    )

    private fun invalidWrite(names: PseudoIoNames) = buildJsonObject {
        put("error", "Nothing was delivered: `${names.write}` needs a string argument `$CONTENT` with the text of the reply.")
    }.toString()

    private fun readBeforeReply(names: PseudoIoNames) =
        "No new user message yet: the latest one is already in the conversation above. " +
            "Answer it with `${names.write}` first."
}
