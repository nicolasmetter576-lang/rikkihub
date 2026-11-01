package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.util.toHeaders
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.MAX_MODEL_CONTEXT_LENGTH
import me.rerere.rikkihub.data.ai.mcp.McpServerConfig
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkihub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkihub.data.datastore.DEFAULT_PROVIDERS
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import kotlin.uuid.Uuid

internal val BUILTIN_PROVIDER_IDS: Set<Uuid> = DEFAULT_PROVIDERS.map { it.id }.toSet()

internal fun isDefaultAssistant(id: Uuid): Boolean = id in DEFAULT_ASSISTANTS_IDS

internal fun isBuiltInProvider(id: Uuid): Boolean = id in BUILTIN_PROVIDER_IDS

/**
 * Hard cap on the raw `localTools` array size. Only [LOCAL_TOOL_OPTIONS].size
 * distinct values exist, so anything larger is either a duplicate-heavy or
 * malformed payload — reject it rather than silently truncating.
 */
private const val LOCAL_TOOL_MAX_ITEMS = 8

private val LOCAL_TOOL_OPTIONS: Map<String, LocalToolOption> = mapOf(
    "javascript_engine" to LocalToolOption.JavascriptEngine,
    "time_info" to LocalToolOption.TimeInfo,
    "clipboard" to LocalToolOption.Clipboard,
    "tts" to LocalToolOption.Tts,
    "ask_user" to LocalToolOption.AskUser,
    "screen_time" to LocalToolOption.ScreenTime,
    "calendar" to LocalToolOption.Calendar,
    "chart_display" to LocalToolOption.ChartDisplay,
)

/**
 * Extract `localTools` from the raw PUT body by field presence.
 *
 * Returns null when the key is absent so the caller keeps `existing.localTools`
 * instead of letting the Kotlin default (empty list) clobber the existing tools
 * of older clients that don't serialize the field. When the key is
 * present the value must be a JSON array of sealed-object entries
 * (`{"type": "<serialName>"}`); unknown types, non-array values, explicit
 * null and oversized arrays are all rejected with [BadRequestException].
 * Duplicate types are deduped preserving first-seen order.
 */
internal fun parseLocalToolsField(rawBody: JsonObject?): List<LocalToolOption>? {
    if (rawBody == null || !rawBody.containsKey("localTools")) return null
    val raw = rawBody["localTools"] as? JsonArray
        ?: throw BadRequestException("localTools must be an array")
    if (raw.size > LOCAL_TOOL_MAX_ITEMS) {
        throw BadRequestException("localTools must contain at most $LOCAL_TOOL_MAX_ITEMS items")
    }
    return raw.map { element ->
        val type = (element as? JsonObject)?.get("type")
            ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.contentOrNull
            ?: throw BadRequestException("localTools entries must be objects with a string type field")
        LOCAL_TOOL_OPTIONS[type]
            ?: throw BadRequestException("Unknown localTools type: $type")
    }.distinct()
}

private const val ENABLED_SKILLS_MAX_ITEMS = 512
private const val SKILL_NAME_MAX_CHARS = 256

/**
 * `enabledSkills` by field presence: absent → null (keep the stored set), `[]` clears.
 * Must be an array of non-blank strings; names that no longer exist are dropped by the caller.
 */
internal fun parseEnabledSkillsField(rawBody: JsonObject?): Set<String>? {
    if (rawBody == null || !rawBody.containsKey("enabledSkills")) return null
    val raw = rawBody["enabledSkills"] as? JsonArray
        ?: throw BadRequestException("enabledSkills must be an array")
    if (raw.size > ENABLED_SKILLS_MAX_ITEMS) {
        throw BadRequestException("enabledSkills must contain at most $ENABLED_SKILLS_MAX_ITEMS items")
    }
    return raw.mapTo(LinkedHashSet()) { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.length <= SKILL_NAME_MAX_CHARS }
            ?: throw BadRequestException("enabledSkills entries must be non-empty strings")
    }
}

/**
 * Shared PUT /assistants/{id} body decoding: presence-aware `localTools`
 * extraction plus the Assistant decode, with every decode/validation failure
 * mapped to [BadRequestException] (400) so malformed payloads never reach the
 * generic 500 handler.
 *
 * `kotlinx.serialization.SerializationException` extends
 * `IllegalArgumentException` (verified on kotlinx-serialization 1.11.0), so
 * JsonDecodingException / MissingFieldException thrown by
 * `decodeFromJsonElement` are already covered — do NOT widen this catch to
 * Throwable, and keep CancellationException-style control flow untouched
 * upstream.
 *
 * @return the decoded incoming Assistant and the parsed localTools override
 *         (null when the key was absent → caller keeps `existing.localTools`).
 */
internal fun parseAssistantUpdate(
    rawBody: JsonObject,
): Pair<Assistant, List<LocalToolOption>?> {
    val localTools = parseLocalToolsField(rawBody)
    val incoming = try {
        JsonInstant.decodeFromJsonElement(Assistant.serializer(), rawBody)
    } catch (e: IllegalArgumentException) {
        throw BadRequestException("Invalid assistant payload")
    }
    return incoming to localTools
}

/**
 * @param presentKeys top-level keys of the raw PUT body; `systemPromptMode` /
 *        `promptSections` absent from it keep the stored value (older clients
 *        must not reset the prompt mode or drop sections). null = all present.
 */
/** [assistants] with [id]'s enabled skills replaced; null when the assistant is gone. */
internal fun withEnabledSkills(assistants: List<Assistant>, id: kotlin.uuid.Uuid, skills: Set<String>): List<Assistant>? {
    if (assistants.none { it.id == id }) return null
    return assistants.map { if (it.id == id) it.copy(enabledSkills = skills) else it }
}

/** `{"workspaceId": "<uuid>" | null}`; the key is required so a typo cannot silently unbind. */
internal fun parseAssistantWorkspaceId(body: kotlinx.serialization.json.JsonObject): Uuid? {
    if ("workspaceId" !in body) throw BadRequestException("Missing workspaceId")
    return when (val raw = body["workspaceId"]) {
        kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> {
            if (!raw.isString) throw BadRequestException("workspaceId must be a string or null")
            runCatching { Uuid.parse(raw.content) }.getOrElse { throw BadRequestException("Invalid workspaceId") }
        }
        else -> throw BadRequestException("workspaceId must be a string or null")
    }
}

internal fun withWorkspaceId(assistants: List<Assistant>, id: Uuid, workspaceId: Uuid?): List<Assistant>? {
    if (assistants.none { it.id == id }) return null
    return assistants.map { if (it.id == id) it.copy(workspaceId = workspaceId) else it }
}

internal fun mergeAssistant(
    existing: Assistant,
    incoming: Assistant,
    localTools: List<LocalToolOption>? = null,
    presentKeys: Set<String>? = null,
    enabledSkills: Set<String>? = null,
): Assistant {
    fun present(key: String) = presentKeys == null || key in presentKeys
    return existing.copy(
        localTools = localTools ?: existing.localTools,
        enabledSkills = enabledSkills ?: existing.enabledSkills,
        name = incoming.name,
        tags = incoming.tags,
        chatModelId = incoming.chatModelId,
        temperature = incoming.temperature,
        topP = incoming.topP,
        contextMessageLimit = incoming.contextMessageLimit,
        streamOutput = incoming.streamOutput,
        reasoningLevel = incoming.reasoningLevel,
        maxTokens = incoming.maxTokens,
        systemPrompt = incoming.systemPrompt,
        systemPromptMode = if (present("systemPromptMode")) incoming.systemPromptMode else existing.systemPromptMode,
        promptSections = if (present("promptSections")) incoming.promptSections else existing.promptSections,
        messageTemplate = incoming.messageTemplate,
        allowConversationSystemPrompt = incoming.allowConversationSystemPrompt,
        allowConversationPromptInjection = incoming.allowConversationPromptInjection,
        mcpServers = incoming.mcpServers,
        modeInjectionIds = incoming.modeInjectionIds,
        lorebookIds = incoming.lorebookIds,
        quickMessageIds = incoming.quickMessageIds,
        enableWebSearch = incoming.enableWebSearch,
        useAssistantAvatar = incoming.useAssistantAvatar,
        workspaceId = incoming.workspaceId,
        enableMemory = incoming.enableMemory,
        useGlobalMemory = incoming.useGlobalMemory,
        // 旧版网页不带这个键，不能拿默认值覆盖用户关掉的设置
        autoConsolidateMemory = if (present("autoConsolidateMemory")) incoming.autoConsolidateMemory else existing.autoConsolidateMemory,
        enableRecentChatsReference = incoming.enableRecentChatsReference,
        enableTimeReminder = incoming.enableTimeReminder,
        timeReminderIntervalMinutes = incoming.timeReminderIntervalMinutes,
    )
}

internal fun validateAssistantWritable(assistant: Assistant, settings: Settings) {
    val tagIds = settings.assistantTags.map { it.id }.toSet()
    if (!tagIds.containsAll(assistant.tags)) {
        throw BadRequestException("tags contains unknown tag id")
    }

    assistant.chatModelId?.let { modelId ->
        if (settings.findModelById(modelId) == null) {
            throw BadRequestException("Unknown chatModelId")
        }
    }

    val mcpIds = settings.mcpServers.map { it.id }.toSet()
    if (!mcpIds.containsAll(assistant.mcpServers)) {
        throw BadRequestException("mcpServers contains unknown server id")
    }

    val modeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    if (!modeInjectionIds.containsAll(assistant.modeInjectionIds)) {
        throw BadRequestException("modeInjectionIds contains unknown injection id")
    }

    val lorebookIds = settings.lorebooks.map { it.id }.toSet()
    if (!lorebookIds.containsAll(assistant.lorebookIds)) {
        throw BadRequestException("lorebookIds contains unknown lorebook id")
    }

    val quickMessageIds = settings.quickMessages.map { it.id }.toSet()
    if (!quickMessageIds.containsAll(assistant.quickMessageIds)) {
        throw BadRequestException("quickMessageIds contains unknown quick message id")
    }

    assistant.temperature?.let { temperature ->
        if (temperature !in 0f..2f) {
            throw BadRequestException("temperature must be 0-2")
        }
    }
    assistant.topP?.let { topP ->
        if (topP !in 0f..1f) {
            throw BadRequestException("topP must be 0-1")
        }
    }
    if (assistant.contextMessageLimit < 0) {
        throw BadRequestException("contextMessageLimit must be >= 0")
    }
    assistant.maxTokens?.let { maxTokens ->
        if (maxTokens <= 0) {
            throw BadRequestException("maxTokens must be > 0")
        }
    }
    if (assistant.timeReminderIntervalMinutes < 1) {
        throw BadRequestException("timeReminderIntervalMinutes must be >= 1")
    }
    if (assistant.promptSections.map { it.id }.distinct().size != assistant.promptSections.size) {
        throw BadRequestException("promptSections[].id must be unique")
    }
}

/** One `{name,value}` header slot; null newValue preserves the stored value for that name. */
internal data class HeaderValueDraft(val name: String, val newValue: String?)

/** One `{key,value}` body slot; null newValue preserves the stored value for that key. */
internal data class BodyValueDraft(val key: String, val newValue: JsonElement?)

/**
 * Presence-aware provider write draft: every credential slot is independently
 * tracked so a projected (secret-free) client round-trip can never clobber a
 * stored apiKey/privateKey/customHeaders value it never saw.
 */
internal data class ProviderUpdateDraft(
    val incoming: ProviderSetting,
    /** null when the key was absent → keep the stored list unchanged. */
    val customHeaders: List<HeaderValueDraft>?,
    /** null when absent → preserve stored apiKey. */
    val apiKey: String?,
    /** null when absent → preserve stored privateKey (Google SA only). */
    val privateKey: String?,
    val useInteractionsApi: Boolean?,
)

/** Read a credential slot: absent → preserve; null or ""+valueConfigured → preserve; else literal. */
private fun parseSecretStringField(obj: JsonObject, field: String): String? {
    if (!obj.containsKey(field)) return null
    val raw = obj[field] ?: return null
    if (raw is JsonNull) return null
    val primitive = raw as? JsonPrimitive
        ?: throw BadRequestException("$field must be a string")
    if (!primitive.isString) throw BadRequestException("$field must be a string")
    val configuredFlag = (obj[field + "Configured"] as? JsonPrimitive)?.booleanOrNull == true
    if (configuredFlag && primitive.content.isEmpty()) return null // masked echo → preserve
    return primitive.content
}

/**
 * Read a `{name, value}` credential-pair list.
 * - key absent → null (preserve whole list)
 * - per entry: `value` absent/null or ""+`valueConfigured:true` → preserve that
 *   entry's stored value (matched by name); literal otherwise.
 */
internal fun parseHeaderDraftList(obj: JsonObject, field: String): List<HeaderValueDraft>? {
    if (!obj.containsKey(field)) return null
    val headers = obj[field] as? JsonArray
        ?: throw BadRequestException("$field must be an array")
    return headers.map { element ->
        val entry = element as? JsonObject
            ?: throw BadRequestException("$field entries must be objects")
        val namePrimitive = entry["name"] as? JsonPrimitive
        if (namePrimitive?.isString != true) {
            throw BadRequestException("Header name must be a string")
        }
        val name = namePrimitive.content
        if (name.any { it == '\r' || it == '\n' } ||
            (name.isNotBlank() && !HTTP_HEADER_NAME.matches(name))
        ) {
            throw BadRequestException("Invalid custom header name")
        }
        // Missing value key is malformed (400); explicit null or the
        // `""`+`valueConfigured:true` masked echo means "preserve stored".
        if (!entry.containsKey("value")) {
            throw BadRequestException("Header entries must include a value")
        }
        val valueElement = entry["value"]
        val configuredFlag = (entry["valueConfigured"] as? JsonPrimitive)?.booleanOrNull == true
        val newValue: String? = when {
            valueElement is JsonNull -> null
            else -> {
                val primitive = valueElement as? JsonPrimitive
                if (primitive?.isString != true) {
                    throw BadRequestException("Header value must be a string")
                }
                if (configuredFlag && primitive.content.isEmpty()) null else primitive.content
            }
        }
        if (newValue != null) {
            try {
                // Validate values even for an ignored blank-name draft row. Never
                // echo the exception text: it can contain a credential value.
                okhttp3.Headers.Builder().add("X-Validation", newValue).build()
            } catch (_: IllegalArgumentException) {
                throw BadRequestException("Invalid custom header value")
            }
        }
        HeaderValueDraft(name = name, newValue = newValue)
    }
}

/** Read a `{key, value}` body-override list with the same preserve semantics. */
internal fun parseBodyDraftList(obj: JsonObject, field: String): List<BodyValueDraft>? {
    if (!obj.containsKey(field)) return null
    val entries = obj[field] as? JsonArray
        ?: throw BadRequestException("$field must be an array")
    return entries.map { element ->
        val entry = element as? JsonObject
            ?: throw BadRequestException("$field entries must be objects")
        val keyPrimitive = entry["key"] as? JsonPrimitive
        if (keyPrimitive?.isString != true) {
            throw BadRequestException("$field entries need a string key")
        }
        if (!entry.containsKey("value")) {
            throw BadRequestException("$field entries must include a value")
        }
        val valueElement = entry["value"]
        val newValue: JsonElement? = when {
            valueElement is JsonNull -> null
            else -> valueElement
        }
        BodyValueDraft(key = keyPrimitive.content, newValue = newValue)
    }
}

/**
 * Normalize credential slots so the raw body decodes into the typed entity:
 * masked/preserved `value`/`second` entries become `""`, null secret strings
 * become `""` (the draft slots carry the real semantics — the decode result
 * is only used for non-secret fields). Recurses into `providerOverwrite`.
 */
internal fun sanitizeCredentialPayloadForDecode(obj: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(obj.size)
    for ((key, value) in obj) {
        when {
            key == "customHeaders" && value is JsonArray -> out[key] = JsonArray(
                value.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    kotlinx.serialization.json.buildJsonObject {
                        put("name", entry["name"] ?: JsonPrimitive(""))
                        val v = entry["value"]
                        put("value", if (v is JsonPrimitive && v.isString) v else JsonPrimitive(""))
                    }
                }
            )

            key == "headers" && value is JsonArray -> out[key] = JsonArray(
                value.mapNotNull { element ->
                    when {
                        element is JsonObject -> kotlinx.serialization.json.buildJsonObject {
                            put("first", element["first"] ?: JsonPrimitive(""))
                            val v = element["second"]
                            put("second", if (v is JsonPrimitive && v.isString) v else JsonPrimitive(""))
                        }
                        element is JsonArray && element.size == 2 -> element
                        else -> null
                    }
                }
            )

            key == "customBodies" && value is JsonArray -> out[key] = JsonArray(
                value.mapNotNull { element ->
                    val entry = element as? JsonObject ?: return@mapNotNull null
                    kotlinx.serialization.json.buildJsonObject {
                        put("key", entry["key"] ?: JsonPrimitive(""))
                        put("value", entry["value"] ?: JsonNull)
                    }
                }
            )

            (key == "apiKey" || key == "privateKey") && value is JsonNull -> {
                out[key] = JsonPrimitive("")
            }

            key == "providerOverwrite" && value is JsonObject -> {
                out[key] = sanitizeCredentialPayloadForDecode(value)
            }

            else -> out[key] = value
        }
    }
    return JsonObject(out)
}

/** Provider PUT is presence-aware; invalid literal types/HTTP headers are always 400. */
internal fun parseProviderUpdate(raw: JsonObject): ProviderUpdateDraft {
    val customHeaders = parseHeaderDraftList(raw, "customHeaders")
    val apiKey = parseSecretStringField(raw, "apiKey")
    val privateKey = parseSecretStringField(raw, "privateKey")
    val incoming = try {
        JsonInstant.decodeFromJsonElement(
            ProviderSetting.serializer(),
            sanitizeCredentialPayloadForDecode(raw),
        )
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid provider payload")
    }
    val useInteractionsApi = if (incoming is ProviderSetting.Google) {
        parseGoogleInteractionsField(raw)
    } else {
        null
    }
    return ProviderUpdateDraft(
        incoming = incoming,
        customHeaders = customHeaders,
        apiKey = apiKey,
        privateKey = privateKey,
        useInteractionsApi = useInteractionsApi,
    )
}

/** Apply a header draft list onto the stored list, preserving by name. */
internal fun mergeHeaderDrafts(
    existing: List<CustomHeader>,
    drafts: List<HeaderValueDraft>,
): List<CustomHeader> = drafts.map { draft ->
    val preserved = draft.newValue ?: existing.firstOrNull { it.name == draft.name }?.value.orEmpty()
    CustomHeader(name = draft.name, value = draft.newValue ?: preserved)
}

/** Apply a body draft list onto the stored list, preserving by key. */
internal fun mergeBodyDrafts(
    existing: List<CustomBody>,
    drafts: List<BodyValueDraft>,
): List<CustomBody> = drafts.map { draft ->
    val preserved = draft.newValue ?: existing.firstOrNull { it.key == draft.key }?.value
    CustomBody(key = draft.key, value = preserved ?: JsonNull)
}

/** Merge a nested `providerOverwrite` object (its own credential slots are presence-aware). */
internal fun mergeProviderOverwrite(
    existing: ProviderSetting?,
    overwriteElement: JsonElement?,
): ProviderSetting? = when (overwriteElement) {
    null -> existing
    JsonNull -> null
    is JsonObject -> {
        val draft = parseProviderUpdate(overwriteElement)
        val base = existing ?: draft.incoming
        mergeProvider(base, draft)
    }
    else -> throw BadRequestException("providerOverwrite must be an object or null")
}

/** Missing means preserve the latest lock-held setting, not the decoder default. */
internal fun parseGoogleInteractionsField(raw: JsonObject): Boolean? {
    // Unknown fields on other provider types do not expand their writable allowlist.
    if (raw["type"] != JsonPrimitive("google") || !raw.containsKey("useInteractionsApi")) return null
    val value = raw["useInteractionsApi"] as? JsonPrimitive
    if (value == null || value.isString || value.content !in setOf("true", "false")) {
        throw BadRequestException("useInteractionsApi must be a boolean")
    }
    return value.content == "true"
}

private val HTTP_HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

internal fun mergeProvider(
    existing: ProviderSetting,
    draft: ProviderUpdateDraft,
): ProviderSetting {
    val incoming = draft.incoming
    if (existing::class != incoming::class) {
        throw BadRequestException("Cannot change provider type")
    }
    val mergedHeaders = draft.customHeaders?.let { mergeHeaderDrafts(existing.customHeaders, it) }
        ?: existing.customHeaders
    return when (existing) {
        is ProviderSetting.OpenAI -> {
            val next = incoming as ProviderSetting.OpenAI
            existing.copy(
                enabled = next.enabled,
                name = next.name,
                customHeaders = mergedHeaders,
                apiKey = draft.apiKey ?: existing.apiKey,
                baseUrl = next.baseUrl,
                chatCompletionsPath = next.chatCompletionsPath,
                useResponseApi = next.useResponseApi,
                includeHistoryReasoning = next.includeHistoryReasoning,
                responsesPath = next.responsesPath,
                models = existing.models,
                builtIn = existing.builtIn,
                description = existing.description,
                shortDescription = existing.shortDescription,
                balanceOption = existing.balanceOption,
            )
        }

        is ProviderSetting.Google -> {
            val next = incoming as ProviderSetting.Google
            existing.copy(
                enabled = next.enabled,
                name = next.name,
                customHeaders = mergedHeaders,
                apiKey = draft.apiKey ?: existing.apiKey,
                baseUrl = next.baseUrl,
                vertexAI = next.vertexAI,
                useInteractionsApi = draft.useInteractionsApi ?: existing.useInteractionsApi,
                useServiceAccount = next.useServiceAccount,
                privateKey = draft.privateKey ?: existing.privateKey,
                serviceAccountEmail = next.serviceAccountEmail,
                location = next.location,
                projectId = next.projectId,
                models = existing.models,
                builtIn = existing.builtIn,
                description = existing.description,
                shortDescription = existing.shortDescription,
                balanceOption = existing.balanceOption,
            )
        }

        is ProviderSetting.Claude -> {
            val next = incoming as ProviderSetting.Claude
            existing.copy(
                enabled = next.enabled,
                name = next.name,
                customHeaders = mergedHeaders,
                apiKey = draft.apiKey ?: existing.apiKey,
                baseUrl = next.baseUrl,
                promptCaching = next.promptCaching,
                promptCacheTtl = next.promptCacheTtl,
                models = existing.models,
                builtIn = existing.builtIn,
                description = existing.description,
                shortDescription = existing.shortDescription,
                balanceOption = existing.balanceOption,
            )
        }

        is ProviderSetting.Codex -> {
            val next = incoming as ProviderSetting.Codex
            existing.copy(
                enabled = next.enabled,
                name = next.name,
                customHeaders = mergedHeaders,
                models = existing.models,
                builtIn = existing.builtIn,
                description = existing.description,
                shortDescription = existing.shortDescription,
                balanceOption = existing.balanceOption,
            )
        }

        is ProviderSetting.Devin -> {
            val next = incoming as ProviderSetting.Devin
            existing.copy(
                enabled = next.enabled,
                name = next.name,
                customHeaders = mergedHeaders,
                clientVersion = next.clientVersion,
                models = existing.models,
                builtIn = existing.builtIn,
                description = existing.description,
                shortDescription = existing.shortDescription,
                balanceOption = existing.balanceOption,
            )
        }

        is ProviderSetting.Antigravity -> {
            val next = incoming as ProviderSetting.Antigravity
            existing.copy(
                enabled = next.enabled,
                name = next.name,
                customHeaders = mergedHeaders,
                clientVersion = next.clientVersion,
                models = existing.models,
                builtIn = existing.builtIn,
                description = existing.description,
                shortDescription = existing.shortDescription,
                balanceOption = existing.balanceOption,
            )
        }
    }
}

/**
 * Presence-aware Model update draft for `PUT /providers/{id}/models/{modelId}`.
 * The model PUT used to be a whole-object replace, which would wipe
 * customHeaders/customBodies/providerOverwrite secrets projected out of the
 * client's copy — every credential slot is now presence-tracked.
 */
internal data class ModelUpdateDraft(
    val incoming: Model,
    val customHeaders: List<HeaderValueDraft>?,
    val customBodies: List<BodyValueDraft>?,
    val providerOverwrite: JsonElement?,
    /** 请求里带了 contextLength 才改；旧版客户端不带时保留原值，不回落成默认 1M */
    val contextLengthPresent: Boolean = true,
)

internal fun parseModelUpdate(raw: JsonObject): ModelUpdateDraft {
    val customHeaders = parseHeaderDraftList(raw, "customHeaders")
    val customBodies = parseBodyDraftList(raw, "customBodies")
    val providerOverwrite = if (raw.containsKey("providerOverwrite")) raw["providerOverwrite"] else null
    // Credential slots are reconstructed by the merge, not the incoming decode —
    // masked entries are sanitized to decode-legal placeholders first.
    val incoming = try {
        JsonInstant.decodeFromJsonElement(Model.serializer(), sanitizeCredentialPayloadForDecode(raw))
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid model payload")
    }
    if (incoming.contextLength !in 1..MAX_MODEL_CONTEXT_LENGTH) {
        throw BadRequestException("contextLength must be 1..$MAX_MODEL_CONTEXT_LENGTH")
    }
    return ModelUpdateDraft(
        incoming = incoming,
        customHeaders = customHeaders,
        customBodies = customBodies,
        providerOverwrite = providerOverwrite,
        contextLengthPresent = raw.containsKey("contextLength"),
    )
}

internal fun mergeModel(existing: Model, draft: ModelUpdateDraft): Model {
    val incoming = draft.incoming
    return incoming.copy(
        id = existing.id,
        contextLength = if (draft.contextLengthPresent) incoming.contextLength else existing.contextLength,
        customHeaders = draft.customHeaders?.let { mergeHeaderDrafts(existing.customHeaders, it) }
            ?: existing.customHeaders,
        customBodies = draft.customBodies?.let { mergeBodyDrafts(existing.customBodies, it) }
            ?: existing.customBodies,
        providerOverwrite = mergeProviderOverwrite(existing.providerOverwrite, draft.providerOverwrite),
    )
}

internal fun createProvider(type: String, name: String?): ProviderSetting {
    val trimmedName = name?.trim().orEmpty()
    return when (type.trim().lowercase()) {
        "openai" -> ProviderSetting.OpenAI(name = trimmedName.ifEmpty { "OpenAI" })
        "google" -> ProviderSetting.Google(name = trimmedName.ifEmpty { "Google" })
        "claude" -> ProviderSetting.Claude(name = trimmedName.ifEmpty { "Claude" })
        else -> throw BadRequestException("Unsupported provider type")
    }
}

/**
 * Presence-aware MCP server update draft. `commonOptions.headers` are
 * `{first,second}` credential pairs on the wire — the projection masks them,
 * so a save from a projected client must preserve each stored value by name.
 */
internal data class McpUpdateDraft(
    val incoming: McpServerConfig,
    /** null when `commonOptions.headers` key absent → keep stored list. */
    val headers: List<McpHeaderDraft>?,
)

internal data class McpHeaderDraft(val name: String, val newValue: String?)

internal fun parseMcpUpdate(raw: JsonObject): McpUpdateDraft {
    val common = raw["commonOptions"] as? JsonObject
    val headers: List<McpHeaderDraft>? = if (common != null && common.containsKey("headers")) {
        val array = common["headers"] as? JsonArray
            ?: throw BadRequestException("headers must be an array")
        array.map { element ->
            val namePrimitive: JsonPrimitive?
            val valueElement: JsonElement?
            var configuredFlag = false
            when (element) {
                is JsonObject -> {
                    namePrimitive = element["first"] as? JsonPrimitive
                    valueElement = element["second"]
                    configuredFlag = (element["valueConfigured"] as? JsonPrimitive)?.booleanOrNull == true
                }

                is JsonArray -> {
                    if (element.size != 2) throw BadRequestException("header pairs must have 2 items")
                    namePrimitive = element[0] as? JsonPrimitive
                    valueElement = element[1]
                }

                else -> throw BadRequestException("headers entries must be pairs")
            }
            if (namePrimitive?.isString != true) {
                throw BadRequestException("header name must be a string")
            }
            val name = namePrimitive.content
            if (name.any { it == '\r' || it == '\n' } ||
                (name.isNotBlank() && !HTTP_HEADER_NAME.matches(name))
            ) {
                throw BadRequestException("Invalid custom header name")
            }
            if (element is JsonObject && !element.containsKey("second")) {
                throw BadRequestException("header entries must include a value")
            }
            val newValue: String? = when {
                valueElement == null || valueElement is JsonNull -> null
                else -> {
                    val primitive = valueElement as? JsonPrimitive
                    if (primitive?.isString != true) {
                        throw BadRequestException("header value must be a string")
                    }
                    if (configuredFlag && primitive.content.isEmpty()) null else primitive.content
                }
            }
            if (newValue != null) {
                try {
                    okhttp3.Headers.Builder().add("X-Validation", newValue).build()
                } catch (_: IllegalArgumentException) {
                    throw BadRequestException("Invalid custom header value")
                }
            }
            McpHeaderDraft(name = name, newValue = newValue)
        }
    } else {
        null
    }
    val incoming = try {
        JsonInstant.decodeFromJsonElement(McpServerConfig.serializer(), sanitizeCredentialPayloadForDecode(raw))
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid MCP server payload")
    }
    return McpUpdateDraft(incoming = incoming, headers = headers)
}

internal fun mergeMcpServer(existing: McpServerConfig, draft: McpUpdateDraft): McpServerConfig {
    val incoming = draft.incoming
    val mergedHeaders = draft.headers?.map { header ->
        val preserved = header.newValue
            ?: existing.commonOptions.headers.firstOrNull { it.first == header.name }?.second.orEmpty()
        header.name to preserved
    } ?: existing.commonOptions.headers
    val mergedOptions = incoming.commonOptions.copy(
        headers = mergedHeaders,
        tools = existing.commonOptions.tools,
        oauth = existing.commonOptions.oauth,
    )
    return when (incoming) {
        is McpServerConfig.SseTransportServer -> incoming.copy(
            id = existing.id,
            commonOptions = mergedOptions,
        )

        is McpServerConfig.StreamableHTTPServer -> incoming.copy(
            id = existing.id,
            commonOptions = mergedOptions,
        )
    }
}

internal fun <T> reorderByIds(
    existing: List<T>,
    ids: List<Uuid>,
    getId: (T) -> Uuid,
): List<T> {
    val existingIds = existing.map(getId)
    if (ids.size != existingIds.size || ids.toSet() != existingIds.toSet()) {
        throw BadRequestException("ids must be a permutation of existing ids")
    }
    val byId = existing.associateBy(getId)
    return ids.map { byId.getValue(it) }
}

internal fun parseUuidList(ids: List<String>, name: String = "ids"): List<Uuid> {
    return ids.map { it.toUuid(name) }
}

internal fun requireModel(provider: ProviderSetting, modelId: Uuid): Model {
    return provider.models.find { it.id == modelId }
        ?: throw me.rerere.rikkihub.web.NotFoundException("Model not found")
}
