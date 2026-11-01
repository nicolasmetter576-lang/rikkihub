package me.rerere.rikkihub.data.creator.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkihub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer
import me.rerere.rikkihub.data.creator.session.CreatorListObjectsInput
import me.rerere.rikkihub.data.creator.session.CreatorListObjectsOutput
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.session.CreatorObjectSummary
import me.rerere.rikkihub.data.creator.session.CreatorReadObjectOutput
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.adapters.conversation.ConversationCollections
import me.rerere.rikkihub.data.management.adapters.skills.SkillCollections
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentCollections
import me.rerere.rikkihub.data.management.ports.ManagementReadPort
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One ordinary conversation row for `creator_list_objects kind=CONVERSATION` (newest first). */
data class CreatorConversationRow(
    val id: String,
    val assistantId: String,
    val title: String,
    val updateAtMillis: Long,
    val pinned: Boolean,
    val deleted: Boolean,
)

/**
 * 创造工具的读取面：十二类可写对象 + 两类只读引用目录（MCP 服务器、工作区）。
 *
 * - **读写同形**：read 的 `contentJson` 只含该对象可写字段，形状与 `creator_preview_change`
 *   的 Patch `fields` 完全一致（同一份 `SettingsCollectionInterpreter.collectionJson` 投影）；
 *   不可写但有用的上下文放在 `readOnly`，引用的 id 在 `refs` 里给出名称；
 * - Settings 域直接读设置快照；子代理预设、技能、会话、工作区经管理链读口
 *   [managementRead]（与提案规划同一份数据）；会话列表经 [conversationLister]；
 * - 秘密不出现在任何投影里（API Key、服务账号私钥、自定义请求头的值/请求体、MCP 地址与认证、provider 覆盖）；
 *   提供商的 Key 只写不读，读到的只有「是否已设置」；
 *   创造助手自身和它的会话不可见；单次读取上限 [MAX_READ_CHARS]。
 */
class CreatorReadDomain(
    private val json: Json,
    private val settingsProvider: suspend () -> Settings,
    private val assistantRegistry: AssistantRegistry,
    /** 管理链读口；null（测试）时子代理预设/技能/会话/工作区不可用。 */
    private val managementRead: (suspend () -> ManagementReadPort)? = null,
    /** 普通会话分页（不含创造助手的会话），按更新时间倒序；null 时会话列表不可用。 */
    private val conversationLister: (suspend (filter: String, offset: Int, limit: Int) -> List<CreatorConversationRow>)? = null,
) {
    companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_READ_CHARS = 64 * 1024
        const val MAX_PROPOSAL_CHARS = 256 * 1024
        const val MAX_PROPOSAL_STEPS = 64
        const val MAX_PROPOSAL_TARGETS = 256
        private const val MAX_MESSAGE_CHARS = 2000
        private const val MAX_OTHER_CANDIDATE_CHARS = 200

        val MODEL_SLOTS = listOf("CHAT", "FAST", "TRANSLATE", "IMAGE_GENERATION", "OCR")
        val PROMPT_SLOTS = listOf("TITLE", "TRANSLATE", "SUGGESTION", "OCR")

        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    sealed interface ReadResult {
        data class Ok(val output: CreatorListObjectsOutput) : ReadResult
        data class Denied(val reason: String) : ReadResult
    }

    sealed interface DetailResult {
        data class Ok(val output: CreatorReadObjectOutput) : DetailResult
        data class Missing(val code: String, val detail: String) : DetailResult
    }

    private data class Row(val id: String, val title: String, val digestSource: JsonElement)

    // ------------------------------------------------------------------ list

    /** `creator_list_objects`：摘要分页（id、标题、digest），不发正文。 */
    suspend fun list(input: CreatorListObjectsInput): ReadResult {
        val kind = CreatorObjectKind.parse(input.kind)
            ?: return ReadResult.Denied("unknown kind '${input.kind}'; kinds: ${CreatorObjectKind.names}")
        if (input.pageSize <= 0) return ReadResult.Denied("pageSize must be 1..$MAX_PAGE_SIZE")
        val pageSize = minOf(input.pageSize, MAX_PAGE_SIZE)
        val start = input.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        if (kind == CreatorObjectKind.CONVERSATION) {
            val lister = conversationLister ?: return ReadResult.Denied("conversations are not available")
            val settings = settingsProvider()
            val names = settings.assistants.associate { it.id.toString() to it.name }
            val rows = lister(input.filter, start, pageSize + 1)
            val page = rows.take(pageSize).map { row ->
                val title = buildString {
                    append(row.title.ifBlank { "(untitled)" })
                    append(" · ").append(names[row.assistantId] ?: "unknown assistant")
                    append(" · ").append(DATE.format(Instant.ofEpochMilli(row.updateAtMillis).atZone(ZoneId.systemDefault())))
                    if (row.pinned) append(" · pinned")
                    if (row.deleted) append(" · DELETED (can be restored)")
                }
                CreatorObjectSummary(kind.name, row.id, title,
                    CreatorCanonicalizer.canonicalDigest(JsonPrimitive("${row.id}:${row.updateAtMillis}:${row.deleted}")))
            }
            return ReadResult.Ok(CreatorListObjectsOutput(
                items = page,
                nextPageToken = if (rows.size > pageSize) (start + pageSize).toString() else null,
            ))
        }

        val rows = try {
            rowsOf(kind)
        } catch (e: UnavailableDomain) {
            return ReadResult.Denied(e.message.orEmpty())
        }
        val filtered = rows.filter { input.filter.isBlank() || it.title.contains(input.filter, ignoreCase = true) }
        val from = start.coerceAtMost(filtered.size)
        val page = filtered.subList(from, minOf(from + pageSize, filtered.size))
        return ReadResult.Ok(CreatorListObjectsOutput(
            items = page.map { CreatorObjectSummary(kind.name, it.id, it.title, CreatorCanonicalizer.canonicalDigest(it.digestSource)) },
            nextPageToken = if (from + pageSize < filtered.size) (from + pageSize).toString() else null,
            omittedCount = filtered.size - from - page.size,
            note = noteFor(kind, filtered.isEmpty()),
        ))
    }

    private fun noteFor(kind: CreatorObjectKind, empty: Boolean): String? = when {
        kind == CreatorObjectKind.ASSISTANT -> "The creator assistant itself is not listed and cannot be changed."
        kind == CreatorObjectKind.SKILL -> "Built-in skills are read-only; change one with SKILL CreateBuiltinOverride."
        kind == CreatorObjectKind.SUBAGENT_PRESET ->
            "Titles show the scope: [library] = shared library preset, [workspace: X] = local preset of workspace X."
        empty -> "There are no ${kind.name} objects yet."
        else -> null
    }

    private class UnavailableDomain(message: String) : IllegalStateException(message)

    private suspend fun port(): ManagementReadPort =
        managementRead?.invoke() ?: throw UnavailableDomain("this kind is not available right now")

    private suspend fun rowsOf(kind: CreatorObjectKind): List<Row> {
        val settings = settingsProvider()
        return when (kind) {
            CreatorObjectKind.ASSISTANT -> assistantRegistry.sanitizeOrdinaryAssistants(settings.assistants)
                .map { Row(it.id.toString(), it.name, writable(settings, "settings.assistants", it.id.toString()) ?: JsonNull) }
            CreatorObjectKind.ASSISTANT_TAG -> settings.assistantTags
                .map { Row(it.id.toString(), it.name, JsonPrimitive(it.name)) }
            CreatorObjectKind.QUICK_MESSAGE -> collectionRows(settings, "settings.quickMessages", "title")
            CreatorObjectKind.MODE_INJECTION -> collectionRows(settings, "settings.modeInjections", "name")
            CreatorObjectKind.LOREBOOK -> collectionRows(settings, "settings.lorebooks", "name")
            CreatorObjectKind.MODEL_DEFINITION -> settings.providers.flatMap { provider ->
                SettingsCollectionInterpreter.collectionJson(settings, "settings.providers.${provider.id}.models").map { model ->
                    val name = model["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val modelId = model["modelId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val type = model["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    Row(model.getValue("id").jsonPrimitive.content,
                        "${provider.name} › ${name.ifBlank { modelId }} ($modelId, $type)", model)
                }
            }
            CreatorObjectKind.DEFAULT_SLOT -> MODEL_SLOTS.map { slot ->
                val id = slotModelId(settings, slot)
                Row(slot, "$slot → ${modelLabel(settings, id) ?: "(not set)"}", JsonPrimitive(id?.toString() ?: ""))
            }
            CreatorObjectKind.PROMPT -> PROMPT_SLOTS.map { slot ->
                val text = promptText(settings, slot)
                val preview = text.lineSequence().firstOrNull().orEmpty().take(40)
                Row(slot, "$slot${if (text == defaultPrompt(slot)) " (default)" else " (customized)"}: $preview", JsonPrimitive(text))
            }
            CreatorObjectKind.SUBAGENT_PRESET -> subagentRows()
            CreatorObjectKind.SKILL -> {
                val port = port()
                port.snapshotIds(SkillCollections.CATALOG).mapNotNull { name ->
                    val manifest = port.readObjectJson(SkillCollections.CATALOG, name) ?: return@mapNotNull null
                    val origin = manifest["origin"]?.jsonPrimitive?.contentOrNull ?: "user"
                    Row(name, "$name ($origin)", manifest["revision"] ?: JsonPrimitive(name))
                }
            }
            CreatorObjectKind.PROVIDER -> settings.providers.map { provider ->
                val type = SettingsCollectionInterpreter.providerType(provider)
                val credential = when {
                    type !in SettingsCollectionInterpreter.creatableProviderTypes -> "signs in from the app"
                    providerKey(provider).isNotBlank() -> "key set"
                    else -> "no key"
                }
                Row(provider.id.toString(),
                    "${provider.name} ($type, ${provider.models.size} models, $credential${if (provider.enabled) "" else ", disabled"})",
                    JsonArray(listOf(writable(settings, SettingsCollectionInterpreter.PROVIDERS, provider.id.toString()) ?: JsonNull,
                        JsonPrimitive(provider.models.size), JsonPrimitive(credential))))
            }
            CreatorObjectKind.MCP_SERVER -> settings.mcpServers.map { server ->
                val options = server.commonOptions
                Row(server.id.toString(), "${options.name.ifBlank { "(unnamed)" }}${if (options.enable) "" else " (disabled)"}",
                    JsonPrimitive("${options.name}:${options.enable}"))
            }
            CreatorObjectKind.WORKSPACE -> {
                val port = port()
                port.snapshotIds("room.workspaces").mapNotNull { id ->
                    port.readObjectJson("room.workspaces", id)?.let { Row(id, it["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), JsonPrimitive(id)) }
                }
            }
            CreatorObjectKind.CONVERSATION -> emptyList()
        }
    }

    private fun collectionRows(settings: Settings, collection: String, titleKey: String): List<Row> =
        SettingsCollectionInterpreter.collectionJson(settings, collection).map {
            Row(it.getValue("id").jsonPrimitive.content, it[titleKey]?.jsonPrimitive?.contentOrNull.orEmpty(), it)
        }

    private suspend fun subagentRows(): List<Row> {
        val port = port()
        val library = port.snapshotIds(SubagentCollections.LIBRARY).mapNotNull { id ->
            port.readObjectJson("room.subagentLibrary", id)?.let { preset ->
                Row(id, "[library] ${preset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} (key ${preset["key"]?.jsonPrimitive?.contentOrNull})", preset)
            }
        }
        val local = workspacePresets(port).filter { it.second.libraryPresetId == null }.map { (workspace, preset) ->
            Row(preset.id, "[workspace: ${workspace.second}] ${preset.name} (key ${preset.key})",
                JsonPrimitive("${preset.id}:${preset.version}"))
        }
        return library + local
    }

    /** (workspace id, workspace name) to each preset stored in that workspace (local and library copies). */
    private suspend fun workspacePresets(port: ManagementReadPort): List<Pair<Pair<String, String>, WorkspaceSubagentPreset>> =
        port.snapshotIds("room.workspaces").flatMap { id ->
            val row = port.readObjectJson("room.workspaces", id) ?: return@flatMap emptyList()
            val name = row["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val presets = row["subagentPresets"]?.jsonPrimitive?.contentOrNull
                ?.let { WorkspaceSubagentPresets.tryParse(it) }.orEmpty()
            presets.map { (id to name) to it }
        }

    // ------------------------------------------------------------------ read

    /** `creator_read_object`。 */
    suspend fun read(kind: CreatorObjectKind, id: String): DetailResult {
        val settings = settingsProvider()
        return try {
            when (kind) {
                CreatorObjectKind.ASSISTANT -> readAssistant(settings, id)
                CreatorObjectKind.ASSISTANT_TAG -> settings.assistantTags.firstOrNull { it.id.toString() == id }?.let { tag ->
                    ok(kind, id, JsonObject(mapOf("name" to JsonPrimitive(tag.name))),
                        readOnly = JsonObject(mapOf("usedBy" to names(settings.assistants.filter { tag.id in it.tags }.map { it.name }))))
                } ?: missing(kind, id)
                CreatorObjectKind.QUICK_MESSAGE -> readBindable(settings, kind, id, "settings.quickMessages") { it.quickMessageIds.map(Any::toString) }
                CreatorObjectKind.MODE_INJECTION -> readBindable(settings, kind, id, "settings.modeInjections") { it.modeInjectionIds.map(Any::toString) }
                CreatorObjectKind.LOREBOOK -> readBindable(settings, kind, id, "settings.lorebooks") { it.lorebookIds.map(Any::toString) }
                CreatorObjectKind.MODEL_DEFINITION -> readModel(settings, id)
                CreatorObjectKind.DEFAULT_SLOT -> readSlot(settings, id.uppercase())
                CreatorObjectKind.PROMPT -> readPrompt(settings, id.uppercase())
                CreatorObjectKind.SUBAGENT_PRESET -> readSubagent(id)
                CreatorObjectKind.SKILL -> readSkill(id)
                CreatorObjectKind.CONVERSATION -> readConversation(settings, id)
                CreatorObjectKind.PROVIDER -> readProvider(settings, id)
                CreatorObjectKind.MCP_SERVER -> settings.mcpServers.firstOrNull { it.id.toString() == id }?.let { server ->
                    ok(kind, id, JsonObject(mapOf(
                        "name" to JsonPrimitive(server.commonOptions.name),
                        "enabled" to JsonPrimitive(server.commonOptions.enable),
                        "tools" to JsonArray(server.commonOptions.tools.map { JsonPrimitive(it.name) }),
                    )), hint = "MCP servers are read-only here. Put this id in an assistant's mcpServers to enable it for that assistant.")
                } ?: missing(kind, id)
                CreatorObjectKind.WORKSPACE -> {
                    val port = port()
                    val row = port.readObjectJson("room.workspaces", id) ?: return missing(kind, id)
                    val presets = workspacePresets(port).filter { it.first.first == id }.map { it.second }
                    ok(kind, id, JsonObject(mapOf(
                        "name" to (row["name"] ?: JsonNull),
                        "subagentPresets" to JsonArray(presets.map {
                            JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name),
                                "fromLibrary" to JsonPrimitive(it.libraryPresetId != null)))
                        }),
                    )), hint = "Workspaces are read-only here. Use this id in SUBAGENT_PRESET workspaceIds / workspaceId.")
                }
            }
        } catch (e: UnavailableDomain) {
            DetailResult.Missing("UNAVAILABLE", e.message.orEmpty())
        }
    }

    private fun missing(kind: CreatorObjectKind, id: String) = DetailResult.Missing("NOT_FOUND",
        "no ${kind.name} with id '$id'; call creator_list_objects kind=${kind.name} for valid ids")

    private fun ok(
        kind: CreatorObjectKind, id: String, content: JsonElement,
        readOnly: JsonObject? = null, refs: Map<String, String>? = null, hint: String? = null,
    ): DetailResult {
        val digest = CreatorCanonicalizer.canonicalDigest(content)
        val text = content.toString()
        val truncated = text.length > MAX_READ_CHARS
        return DetailResult.Ok(CreatorReadObjectOutput(
            kind = kind.name, id = id, digest = digest,
            contentJson = if (truncated) JsonPrimitive(text.take(MAX_READ_CHARS)) else content,
            truncated = truncated,
            readOnly = readOnly,
            refs = refs?.takeIf { it.isNotEmpty() },
            hint = if (truncated) "Content exceeds 64 KiB and was cut; do not send it back as a whole field." else hint,
        ))
    }

    private fun writable(settings: Settings, collection: String, id: String): JsonObject? =
        SettingsCollectionInterpreter.collectionJson(settings, collection)
            .firstOrNull { it["id"]?.jsonPrimitive?.content == id }
            ?.let { JsonObject(it - "id") }

    private fun names(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun readAssistant(settings: Settings, id: String): DetailResult {
        if (AssistantRegistry.isReservedAssistantId(id)) return DetailResult.Missing("RESERVED",
            "the creator assistant (you) cannot be read or changed through these tools; the user edits it in the app")
        val content = writable(settings, "settings.assistants", id) ?: return missing(CreatorObjectKind.ASSISTANT, id)
        val assistant = settings.assistants.first { it.id.toString() == id }
        val refs = buildMap {
            assistant.tags.forEach { tag -> put(tag.toString(), settings.assistantTags.firstOrNull { it.id == tag }?.name ?: "?") }
            assistant.lorebookIds.forEach { book -> put(book.toString(), settings.lorebooks.firstOrNull { it.id == book }?.name ?: "?") }
            assistant.modeInjectionIds.forEach { mode -> put(mode.toString(), settings.modeInjections.firstOrNull { it.id == mode }?.name ?: "?") }
            assistant.quickMessageIds.forEach { qm -> put(qm.toString(), settings.quickMessages.firstOrNull { it.id == qm }?.title ?: "?") }
            assistant.mcpServers.forEach { mcp -> put(mcp.toString(), settings.mcpServers.firstOrNull { it.id == mcp }?.commonOptions?.name ?: "?") }
            assistant.chatModelId?.let { put(it.toString(), modelLabel(settings, it) ?: "?") }
            assistant.workspaceId?.let { put(it.toString(), "workspace") }
        }
        val sectionsHint = if (assistant.promptSections.isNotEmpty())
            " Its systemPrompt is the joined text of promptSections: patch one section in promptSections, " +
                "or patch systemPrompt and the edit lands in the matching sections." else ""
        return ok(CreatorObjectKind.ASSISTANT, id, content, refs = refs,
            hint = "To change it, send only the changed fields: Patch {targetId:\"$id\", params:{fields:{...}}}. " +
                "List fields (tags, lorebookIds, ...) are replaced as a whole.$sectionsHint")
    }

    private fun readBindable(
        settings: Settings, kind: CreatorObjectKind, id: String, collection: String,
        bound: (me.rerere.rikkihub.data.model.Assistant) -> List<String>,
    ): DetailResult {
        val content = writable(settings, collection, id) ?: return missing(kind, id)
        val users = settings.assistants.filter { id in bound(it) }
            .map { if (CreatorAssistantDefinition.isCreator(it.id)) "${it.name} (creator)" else it.name }
        return ok(kind, id, content, readOnly = JsonObject(mapOf("boundToAssistants" to names(users))),
            hint = if (kind == CreatorObjectKind.LOREBOOK) "Patch fields.entries replaces the whole entry list: " +
                "keep the id of every existing entry you keep, omit id for new entries." else null)
    }

    private fun readProvider(settings: Settings, id: String): DetailResult {
        val provider = settings.providers.firstOrNull { it.id.toString() == id } ?: return missing(CreatorObjectKind.PROVIDER, id)
        val projection = writable(settings, SettingsCollectionInterpreter.PROVIDERS, id) ?: return missing(CreatorObjectKind.PROVIDER, id)
        val type = SettingsCollectionInterpreter.providerType(provider)
        val loginBased = type !in SettingsCollectionInterpreter.creatableProviderTypes
        val readOnly = buildMap {
            put("type", JsonPrimitive(type))
            if (loginBased) put("signIn", JsonPrimitive("signs in with an account in the app; only name and enabled can be changed here"))
            else put("apiKeyConfigured", JsonPrimitive(providerKey(provider).isNotBlank()))
            if (provider is ProviderSetting.Google) put("privateKeyConfigured", JsonPrimitive(provider.privateKey.isNotBlank()))
            put("models", JsonArray(provider.models.map { model ->
                JsonObject(mapOf("id" to JsonPrimitive(model.id.toString()), "modelId" to JsonPrimitive(model.modelId),
                    "displayName" to JsonPrimitive(model.displayName), "type" to JsonPrimitive(model.type.name)))
            }))
        }
        return ok(CreatorObjectKind.PROVIDER, id, JsonObject(projection - "type"), readOnly = JsonObject(readOnly),
            hint = (if (loginBased) "A built-in provider that signs in from the app. " else
                "apiKey${if (provider is ProviderSetting.Google) ", privateKey" else ""} and header values are write-only: send a " +
                    "new value to replace one, never ask to see it. customHeaders entries without value keep their stored value. ") +
                "Providers cannot be deleted here. Use this id as params.providerId when creating a MODEL_DEFINITION; " +
                "check the connection with creator_test_provider once a change is applied.")
    }

    private fun providerKey(provider: ProviderSetting): String = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey
        is ProviderSetting.Google -> provider.apiKey
        is ProviderSetting.Claude -> provider.apiKey
        else -> ""
    }

    private fun readModel(settings: Settings, id: String): DetailResult {
        val provider = settings.providers.firstOrNull { p -> p.models.any { it.id.toString() == id } }
            ?: return missing(CreatorObjectKind.MODEL_DEFINITION, id)
        val content = writable(settings, "settings.providers.${provider.id}.models", id)
            ?: return missing(CreatorObjectKind.MODEL_DEFINITION, id)
        val uuid = provider.models.first { it.id.toString() == id }.id
        val slots = MODEL_SLOTS.filter { slotModelId(settings, it) == uuid }
        return ok(CreatorObjectKind.MODEL_DEFINITION, id, content, readOnly = JsonObject(mapOf(
            "providerId" to JsonPrimitive(provider.id.toString()),
            "providerName" to JsonPrimitive(provider.name),
            "defaultSlots" to names(slots),
            "usedByAssistants" to names(settings.assistants.filter { it.chatModelId == uuid }.map { it.name }),
        )))
    }

    private fun readSlot(settings: Settings, slot: String): DetailResult {
        if (slot !in MODEL_SLOTS) return DetailResult.Missing("NOT_FOUND", "slot must be one of $MODEL_SLOTS")
        val id = slotModelId(settings, slot)
        return ok(CreatorObjectKind.DEFAULT_SLOT, slot, JsonObject(mapOf("modelId" to (id?.let { JsonPrimitive(it.toString()) } ?: JsonNull))),
            readOnly = JsonObject(mapOf(
                "modelName" to (modelLabel(settings, id)?.let(::JsonPrimitive) ?: JsonNull),
                "requiredModelType" to JsonPrimitive(if (slot == "IMAGE_GENERATION") ModelType.IMAGE.name else ModelType.CHAT.name),
                "requiresImageInput" to JsonPrimitive(slot == "OCR"),
            )),
            hint = "Change it with DEFAULT_SLOT SetSlot {targetId:\"$slot\", params:{modelId:<MODEL_DEFINITION id>}}.")
    }

    private fun readPrompt(settings: Settings, slot: String): DetailResult {
        if (slot !in PROMPT_SLOTS) return DetailResult.Missing("NOT_FOUND", "slot must be one of $PROMPT_SLOTS")
        val text = promptText(settings, slot)
        return ok(CreatorObjectKind.PROMPT, slot, JsonObject(mapOf("text" to JsonPrimitive(text))),
            readOnly = JsonObject(mapOf("isDefault" to JsonPrimitive(text == defaultPrompt(slot)))))
    }

    private suspend fun readSubagent(id: String): DetailResult {
        val port = port()
        val placements = workspacePresets(port)
        val library = port.readObjectJson("room.subagentLibrary", id)
        if (library != null) {
            val preset = json.decodeFromJsonElement(WorkspaceSubagentPreset.serializer(), library)
            val bound = placements.filter { it.second.libraryPresetId == id }.map { it.first }
            return ok(CreatorObjectKind.SUBAGENT_PRESET, id, draftJson(preset), readOnly = JsonObject(mapOf(
                "scope" to JsonPrimitive("library"),
                "boundWorkspaceIds" to names(bound.map { it.first }),
                "boundWorkspaceNames" to names(bound.map { it.second }),
                "version" to JsonPrimitive(preset.version),
            )), hint = "Library preset: change with PatchLibraryPreset {targetId:\"$id\", params:{patch:{...}}}; " +
                "bind to workspaces with SetPresetScope. key cannot change.")
        }
        val local = placements.firstOrNull { it.second.id == id && it.second.libraryPresetId == null }
            ?: return missing(CreatorObjectKind.SUBAGENT_PRESET, id)
        return ok(CreatorObjectKind.SUBAGENT_PRESET, id, draftJson(local.second), readOnly = JsonObject(mapOf(
            "scope" to JsonPrimitive("workspace"),
            "workspaceId" to JsonPrimitive(local.first.first),
            "workspaceName" to JsonPrimitive(local.first.second),
            "version" to JsonPrimitive(local.second.version),
        )), hint = "Workspace-local preset: change with PatchLocalPreset {targetId:\"$id\", params:{patch:{...}}} " +
            "(workspaceId is filled in for you). key cannot change.")
    }

    /** The writable part of a preset, in the shape of `SubagentPresetDraft`. */
    private fun draftJson(preset: WorkspaceSubagentPreset): JsonObject {
        val full = json.encodeToJsonElement(WorkspaceSubagentPreset.serializer(), preset).jsonObject
        return JsonObject(full.filterKeys { it in setOf("key", "name", "description", "systemPrompt", "enabled",
            "modelSelection", "modelId", "generationConfig", "acceptContext", "maxSteps") })
    }

    private suspend fun readSkill(id: String): DetailResult {
        val port = port()
        val names = port.snapshotIds(SkillCollections.CATALOG)
        val skillName = names.firstOrNull { it == id } ?: names.filter { id.startsWith("$it/") }.maxByOrNull { it.length }
            ?: return missing(CreatorObjectKind.SKILL, id)
        val manifest = port.readObjectJson(SkillCollections.CATALOG, skillName) ?: return missing(CreatorObjectKind.SKILL, id)
        val dirName = manifest["dirName"]?.jsonPrimitive?.contentOrNull ?: skillName
        val origin = manifest["origin"]?.jsonPrimitive?.contentOrNull ?: "user"
        val path = if (id == skillName) "SKILL.md" else id.removePrefix("$skillName/")
        val file = port.readObjectJson(SkillCollections.CATALOG, "file:$dirName/$path")
            ?: return DetailResult.Missing("NOT_FOUND", "skill '$skillName' has no file '$path'; files: " +
                manifest["files"]?.jsonArray?.mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.contentOrNull })
        val files = manifest["files"]?.jsonArray.orEmpty().map { entry ->
            val o = entry.jsonObject
            JsonObject(mapOf("path" to (o["path"] ?: JsonNull), "isDirectory" to (o["isDirectory"] ?: JsonNull),
                "sizeBytes" to (o["sizeBytes"] ?: JsonNull)))
        }
        return ok(CreatorObjectKind.SKILL, id,
            JsonObject(mapOf("path" to JsonPrimitive(path), "content" to (file["content"] ?: JsonPrimitive("")))),
            readOnly = JsonObject(mapOf("skillName" to JsonPrimitive(skillName), "dirName" to JsonPrimitive(dirName),
                "origin" to JsonPrimitive(origin), "files" to JsonArray(files))),
            hint = if (origin == "builtin") "Built-in skill: read-only. To customize it, CreateBuiltinOverride {skillName, skillMd}."
                else "Read another file with id \"$skillName/<path>\". Change this file with PatchTextFile " +
                    "{targetId:\"$skillName\", params:{path:\"$path\", newContent:<whole new file>}}.")
    }

    private suspend fun readConversation(settings: Settings, id: String): DetailResult {
        val port = port()
        val view = port.readObjectJson(ConversationCollections.TREE, id) ?: return missing(CreatorObjectKind.CONVERSATION, id)
        val assistantId = view["assistantId"]?.jsonPrimitive?.contentOrNull
        if (AssistantRegistry.isReservedAssistantId(assistantId.orEmpty())) return DetailResult.Missing("RESERVED",
            "creator conversations cannot be read or managed")
        val content = JsonObject(view.filterKeys { it in setOf("title", "isPinned", "folderId", "customSystemPrompt",
            "workspaceCwd", "modeInjectionIds", "lorebookIds") })
        val nodes = view["nodes"]?.jsonArray.orEmpty().map { it.jsonObject }.map { node ->
            val select = node["selectIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val candidates = node["candidates"]?.jsonArray.orEmpty().map { it.jsonObject }
            JsonObject(mapOf(
                "nodeId" to (node["id"] ?: JsonNull),
                "selectIndex" to JsonPrimitive(select),
                "candidates" to JsonArray(candidates.mapIndexed { index, c ->
                    val text = c["text"]?.jsonPrimitive?.contentOrNull
                    val limit = if (index == select) MAX_MESSAGE_CHARS else MAX_OTHER_CANDIDATE_CHARS
                    JsonObject(mapOf(
                        "messageId" to (c["messageId"] ?: JsonNull),
                        "role" to (c["role"] ?: JsonNull),
                        "text" to (text?.let { JsonPrimitive(if (it.length > limit) it.take(limit) + "…[cut]" else it) }
                            ?: JsonPrimitive("[non-text content: tools, images or files]")),
                    ))
                }),
            ))
        }
        // Keep the newest nodes within budget; older ones are counted, not sent.
        var kept = nodes
        while (kept.isNotEmpty() && JsonArray(kept).toString().length > MAX_READ_CHARS - 4096) kept = kept.drop(maxOf(1, kept.size / 8))
        val readOnly = JsonObject(mapOf(
            "assistantId" to JsonPrimitive(assistantId),
            "assistantName" to JsonPrimitive(settings.assistants.firstOrNull { it.id.toString() == assistantId }?.name ?: "?"),
            "deleted" to (view["deleted"] ?: JsonPrimitive(false)),
            "busy" to (view["busy"] ?: JsonNull),
            "omittedOlderNodes" to JsonPrimitive(nodes.size - kept.size),
            "nodes" to JsonArray(kept),
        ))
        val deleted = view["deleted"]?.jsonPrimitive?.booleanOrNull == true
        return ok(CreatorObjectKind.CONVERSATION, id, content, readOnly = readOnly,
            hint = if (deleted) "This conversation is deleted; CONVERSATION Restore {targetId:\"$id\"} brings it back."
                else "Edit metadata with PatchMetadata; messages via AppendPlainMessage / EditPlainTextAsNewCandidate " +
                    "(use nodeId) / SelectCandidate / DeleteCandidate / PruneNode.")
    }

    // ------------------------------------------------------------------ helpers

    private fun slotModelId(settings: Settings, slot: String) = when (slot) {
        "CHAT" -> settings.chatModelId
        "FAST" -> settings.fastModelId
        "TRANSLATE" -> settings.translateModeId
        "IMAGE_GENERATION" -> settings.imageGenerationModelId
        "OCR" -> settings.ocrModelId
        else -> null
    }

    private fun modelLabel(settings: Settings, id: kotlin.uuid.Uuid?): String? {
        if (id == null) return null
        settings.providers.forEach { provider ->
            provider.models.firstOrNull { it.id == id }?.let { return "${provider.name} › ${it.displayName.ifBlank { it.modelId }}" }
        }
        return null
    }

    private fun promptText(settings: Settings, slot: String) = when (slot) {
        "TITLE" -> settings.titlePrompt
        "TRANSLATE" -> settings.translatePrompt
        "SUGGESTION" -> settings.suggestionPrompt
        "OCR" -> settings.ocrPrompt
        else -> ""
    }

    private fun defaultPrompt(slot: String) = when (slot) {
        "TITLE" -> DEFAULT_TITLE_PROMPT
        "TRANSLATE" -> DEFAULT_TRANSLATION_PROMPT
        "SUGGESTION" -> DEFAULT_SUGGESTION_PROMPT
        "OCR" -> DEFAULT_OCR_PROMPT
        else -> ""
    }
}
