package me.rerere.rikkihub.data.creator.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ToolIds
import me.rerere.rikkihub.data.creator.session.CreatorDescribeInput
import me.rerere.rikkihub.data.creator.session.CreatorListObjectsInput
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.session.CreatorReadObjectInput
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 创造助手的七个 `creator_*` 工具。
 *
 * 会话助手为创造助手时由 `ChatToolFactory` 装配；描述与系统提示词来自
 * `BuiltinToolCatalog`（CREATOR 分类，可在「设置-工具」覆盖）。执行直接进入
 * 读取面 [CreatorReadDomain]、结构说明 [CreatorSchemaCatalog]、管理链 [CreatorManagementBridge]
 * 与提供商测试 [CreatorProviderTester]（只读，不经提案）；
 * 提案归属当前会话（`sessionId` = 会话 ID），批准只在原生提案面板。
 */
class CreatorToolSurface(
    private val readDomain: CreatorReadDomain,
    private val bridge: CreatorManagementBridge,
    /** null（测试）时 `creator_test_provider` 报不可用。 */
    private val providerTester: CreatorProviderTester? = null,
) {
    /** 一次请求构建的工具；同一次构建内的调用共享一个 runId（提案来源记录）。 */
    fun tools(conversationId: Uuid): List<Tool> {
        val runId = Uuid.random().toString()
        return ToolIds.ALL.map { id ->
            Tool(
                name = id,
                description = "",
                // 默认工具系统提示词（可在「设置-工具」覆盖，见 applyBuiltinOverride）
                systemPrompt = { _, _ -> BuiltinToolCatalog[id]?.defaultSystemPrompt.orEmpty() },
                parameters = { parametersFor(id) },
                // 批准权在提案层，工具调用本身不需要普通审批。
                needsApproval = { false },
                execute = { args -> execute(id, CreatorCallSource(conversationId, runId, Uuid.random().toString()), args) },
                executeWithContext = { args, ctx ->
                    val callId = ctx.toolCallId.ifBlank { Uuid.random().toString() }
                    execute(id, CreatorCallSource(conversationId, runId, callId), args)
                },
            )
        }
    }

    /** Input transformer reporting this conversation's finished proposals to the model. */
    fun proposalReminder(conversationId: Uuid): CreatorProposalReminderTransformer =
        CreatorProposalReminderTransformer({ bridge.sessionProposals(conversationId) })

    internal suspend fun execute(id: String, source: CreatorCallSource, args: JsonElement): List<UIMessagePart> =
        when (id) {
            ToolIds.LIST_OBJECTS -> listObjects(args)
            ToolIds.READ_OBJECT -> readObject(args)
            ToolIds.DESCRIBE -> describe(args)
            ToolIds.PREVIEW_CHANGE -> bridge.preview(source, args as? JsonObject ?: JsonObject(emptyMap()))
            ToolIds.GET_CHANGE_STATUS -> bridge.status(source.sessionId, args)
            ToolIds.PROPOSE_UNDO -> bridge.proposeUndo(source, args)
            ToolIds.TEST_PROVIDER -> providerTester?.let { text(it.test(args)) }
                ?: error("UNAVAILABLE", "provider tests are not available right now")
            else -> error("UNKNOWN_CREATOR_TOOL", "no handler for $id")
        }

    private suspend fun listObjects(args: JsonElement): List<UIMessagePart> {
        val input = decode<CreatorListObjectsInput>(args)
            ?: return error("BAD_INPUT", "expected {kind, filter?, pageToken?, pageSize?}; kinds: ${CreatorObjectKind.names}")
        return when (val result = readDomain.list(input)) {
            is CreatorReadDomain.ReadResult.Denied -> error("READ_DENIED", result.reason)
            is CreatorReadDomain.ReadResult.Ok -> text(JsonInstant.encodeToString(result.output))
        }
    }

    private suspend fun readObject(args: JsonElement): List<UIMessagePart> {
        val input = decode<CreatorReadObjectInput>(args) ?: return error("BAD_INPUT", "expected {kind, id}")
        val kind = CreatorObjectKind.parse(input.kind)
            ?: return error("BAD_INPUT", "unknown kind '${input.kind}'; kinds: ${CreatorObjectKind.names}")
        return when (val result = readDomain.read(kind, input.id)) {
            is CreatorReadDomain.DetailResult.Ok -> text(JsonInstant.encodeToString(result.output))
            is CreatorReadDomain.DetailResult.Missing -> error(result.code, result.detail)
        }
    }

    private fun describe(args: JsonElement): List<UIMessagePart> {
        val input = decode<CreatorDescribeInput>(args) ?: return error("BAD_INPUT", "expected {kind, command?}")
        val kind = CreatorObjectKind.parse(input.kind)
            ?: return error("BAD_INPUT", "unknown kind '${input.kind}'; kinds: ${CreatorObjectKind.names}")
        return text(CreatorSchemaCatalog.describe(kind, input.command).toString())
    }

    private inline fun <reified T> decode(args: JsonElement): T? =
        runCatching { JsonInstant.decodeFromJsonElement<T>(args) }.getOrNull()

    private fun text(value: String) = listOf(UIMessagePart.Text(value))

    private fun error(code: String, detail: String) = text(unavailableJson(code, detail))

    private fun parametersFor(id: String): InputSchema = when (id) {
        ToolIds.LIST_OBJECTS -> InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", enumParam("object kind", CreatorObjectKind.entries.map { it.name }))
                put("filter", stringParam("optional case-insensitive substring of the title"))
                put("pageToken", stringParam("nextPageToken from a previous response"))
                put("pageSize", integerParam("1..100, default 40"))
            },
            required = listOf("kind"),
        )
        ToolIds.READ_OBJECT -> InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", enumParam("object kind", CreatorObjectKind.entries.map { it.name }))
                put("id", stringParam("id from creator_list_objects. DEFAULT_SLOT/PROMPT: the slot name. " +
                    "SKILL: the skill name (reads SKILL.md) or \"<skill name>/<path>\" for another file"))
            },
            required = listOf("kind", "id"),
        )
        ToolIds.DESCRIBE -> InputSchema.Obj(
            properties = buildJsonObject {
                put("kind", enumParam("object kind", CreatorObjectKind.entries.map { it.name }))
                put("command", stringParam("optional: only this command"))
            },
            required = listOf("kind"),
        )
        ToolIds.PREVIEW_CHANGE -> InputSchema.Obj(
            properties = buildJsonObject {
                put("operations", buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", CreatorManagementBridge.MAX_OPERATIONS_PER_CALL)
                    put("description", "ALL changes for this request, in order. Settings changes (assistants, tags, " +
                        "quick messages, mode injections, lorebooks, providers, models, default slots, prompts) become one proposal; " +
                        "a later operation may reference an object created by an earlier one as \"\$<index>\".")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("kind", enumParam("object kind", CreatorObjectKind.entries.filter { it.writable }.map { it.name }))
                            put("command", stringParam("e.g. Create, Patch, Delete, Bind; see creator_describe"))
                            put("targetId", stringParam("id of the object to change (omit for Create); slot name for DEFAULT_SLOT/PROMPT; skill name for SKILL"))
                            put("params", buildJsonObject {
                                put("type", "object")
                                put("description", "command parameters, e.g. {\"fields\":{...}} for Create/Patch; see creator_describe")
                            })
                        })
                        put("required", JsonArray(listOf(JsonPrimitive("kind"), JsonPrimitive("command"))))
                    })
                })
            },
            required = listOf("operations"),
        )
        ToolIds.GET_CHANGE_STATUS -> InputSchema.Obj(
            properties = buildJsonObject {
                put("proposalId", stringParam("optional; omit to list every proposal of this conversation"))
            },
            required = emptyList(),
        )
        ToolIds.TEST_PROVIDER -> InputSchema.Obj(
            properties = buildJsonObject {
                put("providerId", stringParam("PROVIDER id (a saved provider; one in a pending proposal exists only after approval)"))
                put("model", stringParam("optional: a MODEL_DEFINITION id or the provider's model name (e.g. gpt-4o-mini); " +
                    "default = the provider's first chat model"))
                put("message", stringParam("optional test message, default \"hello\""))
                put("listModels", buildJsonObject {
                    put("type", "boolean")
                    put("description", "optional: also fetch the model names the service offers")
                })
            },
            required = listOf("providerId"),
        )
        ToolIds.PROPOSE_UNDO -> InputSchema.Obj(
            properties = buildJsonObject {
                put("proposalId", stringParam("an APPLIED proposal of this conversation"))
                put("auditRef", stringParam("optional audit reference"))
            },
            required = listOf("proposalId"),
        )
        else -> InputSchema.Obj(properties = JsonObject(emptyMap()), required = emptyList())
    }

    private fun stringParam(desc: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
    }

    private fun enumParam(desc: String, values: List<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun integerParam(desc: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }

    companion object {
        val ALLOWED_KINDS: String get() = CreatorObjectKind.names

        /** `{{creator_commands}}` 占位符：preview_change 描述里的命令目录（随版本更新）。 */
        fun placeholders(): Map<String, String> = mapOf("creator_commands" to CreatorCommandDecoder.catalogue())

        fun unavailableJson(reason: String, detail: String): String =
            buildJsonObject {
                put("status", "unavailable")
                put("reason", reason)
                put("error", detail)
            }.toString()
    }
}
