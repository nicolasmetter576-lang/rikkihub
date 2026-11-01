package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolConcurrency
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.db.fts.MessageSearchResult
import me.rerere.rikkihub.data.db.fts.MessageSearchSort
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.utils.JsonInstantPretty
import me.rerere.rikkihub.utils.toLocalDate
import kotlin.uuid.Uuid

/**
 * Tools that let the assistant query the user's past conversations on demand, instead of
 * statically injecting recent chats into the system prompt (which would break prompt caching).
 */
fun createConversationTools(
    conversationRepo: ConversationRepository,
    assistantId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = BuiltinToolIds.RECENT_CHATS,
        description = BuiltinToolCatalog.require(BuiltinToolIds.RECENT_CHATS).defaultDescription,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of recent conversations to return (default: 10, max: 30)"
                        )
                    })
                }
            )
        },
        execute = {
            val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 30)
            val recent = conversationRepo.getRecentConversations(
                assistantId = assistantId,
                limit = limit,
            )
            val payload = buildJsonArray {
                recent.forEach { conversation ->
                    add(buildJsonObject {
                        put("id", conversation.id.toString())
                        put("title", conversation.title.ifBlank { "Untitled" })
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
        }
    ),
    conversationSearchTool { query -> conversationRepo.searchMessages(query, MessageSearchSort.RELEVANCE) },
)

private fun conversationSearchTool(search: suspend (query: String) -> List<MessageSearchResult>) = Tool(
    name = BuiltinToolIds.CONVERSATION_SEARCH,
    description = BuiltinToolCatalog.require(BuiltinToolIds.CONVERSATION_SEARCH).defaultDescription,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keywords to search for in past conversation messages")
                })
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum number of results to return (default: 15, max: 50)"
                    )
                })
            },
            required = listOf("query")
        )
    },
    execute = {
        val query = it.jsonObject["query"]?.jsonPrimitive?.contentOrNull
            ?: error("query is required")
        val limit = (it.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 15).coerceIn(1, 50)
        val results = search(query).take(limit)
        val payload = buildJsonArray {
            results.forEach { result ->
                add(buildJsonObject {
                    put("conversation_id", result.conversationId)
                    put("title", result.title.ifBlank { "Untitled" })
                    put("snippet", result.snippet)
                    put("date", result.updateAt.toLocalDate())
                })
            }
        }
        listOf(UIMessagePart.Text(JsonInstantPretty.encodeToString(payload)))
    }
)

private const val READ_DEFAULT_LIMIT = 20
private const val READ_MAX_LIMIT = 40
private const val READ_MESSAGE_LENGTH = 12_000

/** 后台记忆整理专用的读对话工具名（不进内置工具目录，不能改名） */
const val CONVERSATION_READ_TOOL = "conversation_read"

/**
 * 给后台记忆整理用的对话查询工具，只能看到 [assistantIds] 这些助手的对话：
 * 独立记忆库的助手不该读到别的助手的对话内容。
 */
fun createMemoryConversationTools(
    conversationRepo: ConversationRepository,
    assistantIds: Set<Uuid>,
): List<Tool> = listOf(
    conversationSearchTool { query -> conversationRepo.searchMessagesOfAssistants(query, assistantIds) },
    Tool(
        name = CONVERSATION_READ_TOOL,
        description = """
            Read the messages of one conversation in full, by position.
            Use it when a message you were given was cut off, or a search snippet needs its surrounding context.
            Positions are the #numbers shown next to each message.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("conversation_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Id of the conversation")
                    })
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Position of the first message to return (default: 0)")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum number of messages to return (default: $READ_DEFAULT_LIMIT, max: $READ_MAX_LIMIT)"
                        )
                    })
                },
                required = listOf("conversation_id")
            )
        },
        concurrency = ToolConcurrency.Parallel,
        execute = {
            val args = it.jsonObject
            val conversation = args["conversation_id"]?.jsonPrimitive?.contentOrNull
                ?.let { id -> runCatching { Uuid.parse(id) }.getOrNull() }
                ?.let { id -> conversationRepo.getConversationById(id) }
                ?.takeIf { conversation -> conversation.assistantId in assistantIds }
                ?: error("conversation not found")
            val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: READ_DEFAULT_LIMIT).coerceIn(1, READ_MAX_LIMIT)
            val messages = conversation.dialogueMessages()
            val payload = buildJsonObject {
                put("title", conversation.title.ifBlank { "Untitled" })
                put("total", messages.size)
                put("messages", buildJsonArray {
                    messages.drop(offset).take(limit).forEachIndexed { index, message ->
                        add(buildJsonObject {
                            put("position", offset + index)
                            put("role", message.role.name)
                            put("date", message.createdAt.date.toString())
                            put("text", message.toText().take(READ_MESSAGE_LENGTH))
                        })
                    }
                })
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    ),
)

/** 当前分支上用户和助手的消息，位置编号以这个列表为准 */
internal fun Conversation.dialogueMessages(): List<UIMessage> =
    currentMessages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
