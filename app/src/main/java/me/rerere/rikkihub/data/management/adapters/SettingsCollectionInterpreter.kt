package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.ports.*
import me.rerere.rikkihub.data.model.*
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.rikkihub.data.character.PresetFormatException
import me.rerere.rikkihub.data.character.PresetPorting
import kotlin.uuid.Uuid

/** Domain-constrained fields, NOT RFC6902, arbitrary Settings JSON or paths. */
object SettingsCollectionInterpreter {
    val strictJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; explicitNulls = true }
    val assistantFields = setOf("name", "enabled", "avatar", "useAssistantAvatar", "tags",
        "messageTemplate", "presetMessages", "temperature", "topP", "topK", "frequencyPenalty", "presencePenalty",
        "repetitionPenalty", "minP", "topA", "seed", "maxTokens", "reasoningLevel",
        "contextMessageLimit", "streamOutput", "chatModelId", "workspaceId", "localTools",
        "mcpServers", "enabledSkills", "modeInjectionIds", "lorebookIds", "quickMessageIds",
        "enableMemory", "useGlobalMemory", "autoConsolidateMemory", "enableRecentChatsReference", "enableWebSearch",
        "background", "backgroundOpacity", "useGradientBackground", "enableTimeReminder",
        "timeReminderIntervalMinutes", "allowConversationSystemPrompt", "allowConversationPromptInjection", "tavernMode", "enableMessageTitle",
        "promptOptions", "presetExtra",
        // Create only: the initial main prompt text, and an assistant whose prompt layout, options and regex scripts are copied;
        // a SillyTavern preset to take the prompt and sampling parameters from
        "systemPrompt", "copyFrom", "fromPreset")
    val modelFields = setOf("modelId", "displayName", "type", "inputModalities", "outputModalities",
        "abilities", "tools", "ccDisguise", "contextLength", "pricing")

    /** Most object patches one Batch op may hold (bulk create / patch of lorebook entries). */
    const val MAX_BATCH_PATCHES = 500

    const val PROVIDERS = "settings.providers"
    /** Key-based provider types (the ones that can be created); the others sign in with OAuth. */
    val creatableProviderTypes = listOf("openai", "google", "claude")
    val providerCommonFields = setOf("name", "enabled", "customHeaders", "balanceOption", "rotationStrategy")
    /** Write-only: never in a projection or a model-visible diff (header values are secret too). */
    val providerSecretFields = setOf("apiKey", "privateKey", "apiKeys", "serviceAccounts")

    /** Writable fields of a provider of serial type [type]. */
    fun providerFields(type: String): Set<String> = when (type) {
        "openai" -> providerCommonFields + setOf("apiKey", "apiKeys", "baseUrl", "chatCompletionsPath", "useResponseApi",
            "includeHistoryReasoning", "responsesPath")
        "google" -> providerCommonFields + setOf("apiKey", "apiKeys", "baseUrl", "vertexAI", "useServiceAccount", "privateKey",
            "serviceAccountEmail", "location", "projectId", "serviceAccounts", "useInteractionsApi", "streamFunctionCallArguments")
        "claude" -> providerCommonFields + setOf("apiKey", "apiKeys", "baseUrl", "promptCaching", "promptCacheTtl")
        else -> setOf("name", "enabled")
    }

    /** Diff lines of these fields carry no values. */
    fun isSecretField(collectionId: String, field: String): Boolean =
        collectionId == PROVIDERS && (field in providerSecretFields || field == "customHeaders")

    /** Serial type name for a loosely written provider type, or null. */
    fun providerType(raw: String): String? = when (val key = raw.trim().lowercase().replace("-", "").replace("_", "")) {
        "openai", "google", "claude", "codex", "devin", "antigravity" -> key
        "gemini", "vertex", "vertexai" -> "google"
        "anthropic" -> "claude"
        else -> null
    }

    fun providerType(provider: ProviderSetting): String =
        objectJson(ProviderSetting.serializer(), provider).getValue("type").jsonPrimitive.content

    fun apply(settings: Settings, op: StoreOperation, authorizedImageUris: Set<String> = emptySet()): Settings = when (op) {
        is SettingsCollectionOp -> applyCollection(settings, op, authorizedImageUris)
        is SettingsFieldOp -> applyField(settings, op)
        is SettingsTransactionOp -> {
            require(op.operations.size in 1..64)
            op.operations.map(StoreOperationCodec::decode).fold(settings) { current, child ->
                require(child is SettingsCollectionOp || child is SettingsFieldOp)
                apply(current, child, authorizedImageUris)
            }
        }
        else -> rejectInvalid("Not a Settings operation")
    }

    fun collectionJson(settings: Settings, collectionId: String): List<JsonObject> = when (collectionId) {
        "settings.quickMessages" -> settings.quickMessages.map { objectJson(QuickMessage.serializer(), it) }
        "settings.assistants" -> settings.assistants.map { assistant ->
            JsonObject(assistantView(assistant).filterKeys { it == "id" || it in assistantFields })
        }
        "settings.assistantTags" -> settings.assistantTags.map { objectJson(Tag.serializer(), it) }
        PROVIDERS -> settings.providers.map(::providerProjection)
        else -> NativeCollections.resolve(collectionId)?.read?.invoke(settings) ?: run {
            val provider = providerFor(settings, collectionId)
            provider.models.map { model -> JsonObject(objectJson(Model.serializer(), model)
                .filterKeys { it == "id" || it in modelFields }) }
        }
    }

    /**
     * The assistant as the creator sees it: every assistant field, with the prompt layout, the prompt entries and the
     * regex scripts living in their own objects (PRESET_ENTRY, REGEX_SCRIPT). `modeInjectionIds` are the prompt library
     * entries it uses; `promptOptions` and `presetExtra` are the rest of its SillyTavern preset.
     */
    fun assistantView(assistant: Assistant): JsonObject {
        val base = objectJson(Assistant.serializer(), assistant).filterKeys { it != "promptPreset" && it != "libraryEntryIds" }
        return JsonObject(base + mapOf(
            "modeInjectionIds" to JsonArray(assistant.libraryEntryIds.map { JsonPrimitive(it.toString()) }),
            "promptOptions" to NativeViews.optionsJson(assistant.promptPreset.options),
            "presetExtra" to NativeViews.presetExtraJson(assistant.promptPreset),
        ))
    }

    /** id, type and the writable non-secret fields; custom headers by name only (values are secret). */
    private fun providerProjection(provider: ProviderSetting): JsonObject {
        val raw = objectJson(ProviderSetting.serializer(), provider)
        val type = raw.getValue("type").jsonPrimitive.content
        val visible = providerFields(type) - providerSecretFields
        return JsonObject(buildMap {
            put("id", raw.getValue("id"))
            put("type", JsonPrimitive(type))
            raw.forEach { (key, value) ->
                if (key in visible) put(key, if (key == "customHeaders") JsonArray(provider.customHeaders.map {
                    JsonObject(mapOf("name" to JsonPrimitive(it.name)))
                }) else value)
            }
        })
    }

    /**
     * Fields with enum strings / sealed `type`s in their stored form (see [LenientEnums]), so the
     * planned operation, the diff and the "unchanged field" check all use what will be stored.
     */
    fun normalizeFields(collectionId: String, fields: Map<String, JsonElement>): Map<String, JsonElement> {
        NativeCollections.resolve(collectionId)?.let { return NativeFields.normalize(it.fields, fields) }
        if (collectionId == PROVIDERS) return fields.mapValues { (key, value) ->
            val raw = (value as? JsonPrimitive)?.takeIf { key == "type" && it.isString }?.content
            raw?.let { providerType(it) }?.let(::JsonPrimitive) ?: value
        }
        val descriptor = when {
            collectionId == "settings.quickMessages" -> QuickMessage.serializer().descriptor
            collectionId == "settings.assistants" -> Assistant.serializer().descriptor
            collectionId == "settings.assistantTags" -> Tag.serializer().descriptor
            collectionId.startsWith("settings.providers.") -> Model.serializer().descriptor
            else -> return fields
        }
        return fields.mapValues { (key, value) ->
            val index = descriptor.getElementIndex(key)
            if (index < 0) value else LenientEnums.normalize(value, descriptor.getElementDescriptor(index))
        }
    }

    private fun applyCollection(settings: Settings, op: SettingsCollectionOp, authorizedImageUris: Set<String>): Settings = when (op.collectionId) {
        "settings.quickMessages" -> settings.copy(quickMessages = patchList(settings.quickMessages,
            QuickMessage.serializer(), setOf("title", "content"), op.patch, { QuickMessage(id = Uuid.parse(it)) }) {
                require(it.title.length <= 1024) { "title must be at most 1024 characters" }
                require(it.content.length <= 64 * 1024) { "content must be at most 65536 characters" }
            })
        "settings.assistants" -> settings.copy(assistants = patchList(settings.assistants,
            Assistant.serializer(), assistantFields, op.patch, { Assistant(id = Uuid.parse(it)) },
            validate = { validateAssistant(it, settings, authorizedImageUris) },
            encode = ::assistantView,
            decode = { current, json -> assistantFrom(settings, current, json) },
            normalize = { LenientEnums.normalize(it, Assistant.serializer().descriptor).jsonObject }))
        "settings.assistantTags" -> settings.copy(assistantTags = patchList(settings.assistantTags,
            Tag.serializer(), setOf("name"), op.patch, { Tag(id = Uuid.parse(it), name = "") }) { require(it.name.length in 1..1024) })
        PROVIDERS -> settings.copy(providers = patchProviders(settings.providers, op.patch))
        else -> NativeCollections.resolve(op.collectionId)?.let { applyNative(settings, it, op.patch) } ?: run {
            val provider = providerFor(settings, op.collectionId)
            val models = patchList(provider.models, Model.serializer(), modelFields, op.patch,
                { Model(id = Uuid.parse(it)) }, validate = ::validateModel)
            settings.copy(providers = settings.providers.map {
                if (it.id == provider.id) it.copyProvider(models = models) else it
            })
        }
    }

    /** A native collection (see [NativeCollections]): patched as plain JSON objects, then stored through the collection's writer. */
    private fun applyNative(settings: Settings, collection: NativeCollection, patch: CollectionPatch): Settings {
        val old = collection.read(settings)
        val allowed = collection.allowed
        if (collection.singleton && patch !is CollectionPatch.PatchFields) rejectForbidden("${collection.label} cannot be created, deleted or reordered; patch them")
        val new = patchList(old, JsonObject.serializer(), allowed, patch,
            create = { id -> collection.create(settings, id) },
            encode = { it }, decode = { _, json -> json }, normalize = { it }, validate = {})
        return collection.write(settings, new, old)
    }

    /** Next free object id of a numbered collection (world book entries); null for UUID collections. */
    fun nextObjectId(settings: Settings, collectionId: String): String? =
        NativeCollections.resolve(collectionId)?.nextId?.invoke(settings)

    private val ASSISTANT_VIEW_ONLY = setOf("modeInjectionIds", "promptOptions", "presetExtra", "systemPrompt", "copyFrom", "fromPreset")

    /** Largest SillyTavern preset (as JSON text) one operation may carry. */
    const val MAX_PRESET_CHARS = 1_000_000

    private fun assistantFrom(settings: Settings, current: Assistant, json: JsonObject): Assistant {
        val ids = strictJson.decodeFromJsonElement(
            kotlinx.serialization.builtins.SetSerializer(Uuid.serializer()), json["modeInjectionIds"] ?: JsonArray(emptyList()))
        var out = strictJson.decodeFromJsonElement(Assistant.serializer(), JsonObject(
            json.filterKeys { it !in ASSISTANT_VIEW_ONLY } + mapOf(
                "promptPreset" to strictJson.encodeToJsonElement(PromptPresetSerializer, current.promptPreset),
                "libraryEntryIds" to JsonArray(ids.map { JsonPrimitive(it.toString()) }),
            )))
        // json 是当前对象加上改动，分不出「没写」和「写了同样的值」：和当前值一样就当没写，跟着 copyFrom / fromPreset 走
        val tavernModeSet = out.tavernMode != current.tavernMode
        (json["copyFrom"] as? JsonPrimitive)?.content?.let { source ->
            val from = runCatching { Uuid.parse(source) }.getOrNull()?.let { id -> settings.assistants.firstOrNull { it.id == id } }
                ?: rejectInvalid("copyFrom '$source' is not the id of an assistant")
            AssistantRegistry().requireOrdinaryAssistantId(from.id)
            out = out.copy(promptPreset = from.promptPreset, tavernMode = if (tavernModeSet) out.tavernMode else from.tavernMode)
        }
        (json["fromPreset"] as? JsonObject)?.let { preset ->
            if (json["copyFrom"] != null || json["systemPrompt"] != null) rejectInvalid("fromPreset brings the whole prompt: do not combine it with copyFrom or systemPrompt")
            if (preset.toString().length > MAX_PRESET_CHARS) rejectInvalid("fromPreset must be at most $MAX_PRESET_CHARS characters")
            val imported = try {
                PresetPorting.import(preset, out.name).assistant
            } catch (e: PresetFormatException) {
                rejectInvalid("fromPreset: ${e.message}")
            }
            // the preset's prompt and sampling replace the assistant's own; connection settings never come in and its regex scripts start off
            out = out.copy(
                promptPreset = imported.promptPreset, temperature = imported.temperature, topP = imported.topP, topK = imported.topK,
                frequencyPenalty = imported.frequencyPenalty, presencePenalty = imported.presencePenalty,
                repetitionPenalty = imported.repetitionPenalty, minP = imported.minP, topA = imported.topA, seed = imported.seed,
                maxTokens = imported.maxTokens, streamOutput = imported.streamOutput,
                tavernMode = if (tavernModeSet) out.tavernMode else imported.tavernMode,
            )
        }
        (json["systemPrompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { out = out.withMainPromptText(it) }
        (json["promptOptions"] as? JsonObject)?.takeIf { it != NativeViews.optionsJson(current.promptPreset.options) }?.let {
            out = out.copy(promptPreset = out.promptPreset.copy(options = NativeViews.optionsFrom(it, out.promptPreset.options)))
        }
        (json["presetExtra"] as? JsonObject)?.takeIf { it != NativeViews.presetExtraJson(current.promptPreset) }?.let {
            out = out.copy(promptPreset = NativeViews.withPresetExtra(out.promptPreset, it))
        }
        return out
    }

    private fun providerFor(settings: Settings, collection: String): ProviderSetting {
        val match = Regex("settings\\.providers\\.([0-9a-fA-F-]{36})\\.models").matchEntire(collection)
            ?: rejectInvalid("Unknown Settings collection")
        val id = Uuid.parse(match.groupValues[1])
        return settings.providers.firstOrNull { it.id == id } ?: rejectNotFound("Provider not found")
    }

    /** Providers can be created and patched; deleting or reordering them stays in the app. */
    private fun patchProviders(initial: List<ProviderSetting>, patch: CollectionPatch): List<ProviderSetting> = when (patch) {
        is CollectionPatch.Create -> {
            val id = Uuid.parse(patch.id)
            require(initial.none { it.id == id }) { "Object already exists" }
            val raw = (patch.fields["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: rejectInvalid("type is required to create a provider: one of $creatableProviderTypes")
            val base = when (providerType(raw)) {
                "openai" -> ProviderSetting.OpenAI(id = id)
                "google" -> ProviderSetting.Google(id = id)
                "claude" -> ProviderSetting.Claude(id = id)
                else -> rejectInvalid("type '$raw' cannot be created: one of $creatableProviderTypes " +
                    "(codex, devin and antigravity are built in and sign in from the app)")
            }
            initial + mergeProvider(base, patch.fields - "type", existingHeaders = emptyList(), creating = true)
        }
        is CollectionPatch.PatchFields -> {
            val current = initial.firstOrNull { it.id.toString() == patch.id } ?: rejectNotFound("Object not found")
            initial.map { if (it.id == current.id) mergeProvider(it, patch.fields, current.customHeaders, creating = false) else it }
        }
        is CollectionPatch.Remove -> rejectForbidden("Providers cannot be deleted here; the user deletes them in the app")
        is CollectionPatch.Reorder -> rejectForbidden("Providers cannot be reordered here")
        is CollectionPatch.Batch -> {
            require(patch.patches.size in 1..64 && patch.patches.none { it is CollectionPatch.Batch }) { "Invalid nested batch" }
            patch.patches.fold(initial) { current, child -> patchProviders(current, child) }
        }
    }

    private fun mergeProvider(
        current: ProviderSetting, input: Map<String, JsonElement>, existingHeaders: List<CustomHeader>, creating: Boolean,
    ): ProviderSetting {
        val raw = objectJson(ProviderSetting.serializer(), current)
        val type = raw.getValue("type").jsonPrimitive.content
        val fields = input.toMutableMap()
        fields.remove("type")?.let { given ->
            val asked = (given as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { providerType(it) }
            if (asked != type) rejectForbidden("A provider's type cannot be changed (this one is $type)")
        }
        val allowed = providerFields(type)
        val unknown = fields.keys.filterNot { it in allowed }
        if (unknown.isNotEmpty()) rejectForbidden("Field(s) $unknown cannot be set on a $type provider; writable fields: ${allowed.sorted()}")
        require(fields.size <= 64 && fields.toString().length <= 256 * 1024) { "At most 64 fields and 256 KiB per change" }
        fields["customHeaders"]?.let { fields["customHeaders"] = mergeHeaders(it, existingHeaders) }
        if ("apiKey" in fields && "apiKeys" !in fields && type in creatableProviderTypes) {
            fields["apiKeys"] = JsonArray(emptyList())
        }
        if (type == "google" &&
            listOf("privateKey", "serviceAccountEmail", "projectId").any { it in fields } &&
            "serviceAccounts" !in fields
        ) {
            fields["serviceAccounts"] = JsonArray(emptyList())
        }
        val decoded = try {
            strictJson.decodeFromJsonElement(ProviderSetting.serializer(),
                LenientEnums.normalize(JsonObject(raw + fields), ProviderSetting.serializer().descriptor))
        } catch (e: kotlinx.serialization.SerializationException) {
            rejectInvalid("Field value has the wrong shape: ${describeSerializationError(e)}")
        }
        // A patch checks only what it sets: values the user typed in the app are not re-validated.
        return decoded.also { validateProvider(it, if (creating) null else fields.keys) }
    }

    /** `[{name, value?}]`: an entry without value keeps the stored value of that header. */
    private fun mergeHeaders(value: JsonElement, existing: List<CustomHeader>): JsonArray {
        val rows = value as? JsonArray ?: rejectInvalid("customHeaders must be an array of {name, value}")
        require(rows.size <= 64) { "at most 64 customHeaders" }
        return JsonArray(rows.map { row ->
            val o = row as? JsonObject ?: rejectInvalid("customHeaders must be an array of {name, value}")
            val extra = o.keys - setOf("name", "value")
            if (extra.isNotEmpty()) rejectInvalid("customHeaders entries only have name and value (got ${extra.sorted()})")
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: rejectInvalid("customHeaders[].name must be a string")
            val given = o["value"]?.let { v -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: rejectInvalid("customHeaders[].value must be a string") }
            val kept = given ?: existing.firstOrNull { it.name == name }?.value
                ?: rejectInvalid("customHeaders: '$name' is a new header, so it needs a value")
            JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(kept)))
        })
    }

    private val HTTP_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    /** Checks the fields in [touched] (null = every field, for a new provider). */
    private fun validateProvider(p: ProviderSetting, touched: Set<String>?) {
        fun check(field: String, block: () -> Unit) { if (touched == null || field in touched) block() }
        check("name") { require(p.name.isNotBlank() && p.name.length <= 1024) { "name must be 1..1024 characters" } }
        check("customHeaders") {
            require(p.customHeaders.map { it.name.lowercase() }.distinct().size == p.customHeaders.size) { "customHeaders names must not repeat" }
            p.customHeaders.forEach { h ->
                require(h.name.length <= 256 && HTTP_TOKEN.matches(h.name)) { "customHeaders: '${h.name.take(64)}' is not a valid header name" }
                require(h.value.length <= 8192 && h.value.none { it == '\r' || it == '\n' }) {
                    "customHeaders: the value of '${h.name}' must be one line of at most 8192 characters"
                }
            }
        }
        check("balanceOption") {
            require(p.balanceOption.apiPath.length <= 1024 && p.balanceOption.resultPath.length <= 1024) {
                "balanceOption paths must be at most 1024 characters"
            }
        }
        fun url(value: String) = check("baseUrl") {
            val uri = runCatching { java.net.URI(value) }.getOrNull()
            require(value.length <= 2048 && uri != null && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) {
                "baseUrl must be an http(s) address such as https://api.example.com/v1 (got '${value.take(200)}')"
            }
        }
        fun path(name: String, value: String) = check(name) {
            require(value.startsWith("/") && value.length <= 1024) { "$name must start with / (at most 1024 characters)" }
        }
        fun key(name: String, value: String) = check(name) {
            require(value.length <= 64 * 1024) { "$name must be at most 65536 characters" }
        }
        fun text(name: String, value: String) = check(name) {
            require(value.length <= 1024) { "$name must be at most 1024 characters" }
        }
        fun apiKeyPool(entries: List<ApiKeyEntry>) = check("apiKeys") {
            require(entries.size <= 200) { "apiKeys must have at most 200 entries" }
            entries.forEach { entry ->
                require(entry.key.length <= 64 * 1024) { "apiKeys[].key must be at most 65536 characters" }
                require(entry.label.length <= 256) { "apiKeys[].label must be at most 256 characters" }
            }
        }
        when (p) {
            is ProviderSetting.OpenAI -> {
                url(p.baseUrl); key("apiKey", p.apiKey); apiKeyPool(p.apiKeys)
                path("chatCompletionsPath", p.chatCompletionsPath); path("responsesPath", p.responsesPath)
            }
            is ProviderSetting.Google -> {
                url(p.baseUrl); key("apiKey", p.apiKey); apiKeyPool(p.apiKeys); key("privateKey", p.privateKey)
                text("serviceAccountEmail", p.serviceAccountEmail); text("location", p.location); text("projectId", p.projectId)
                check("serviceAccounts") {
                    require(p.serviceAccounts.size <= 200) { "serviceAccounts must have at most 200 entries" }
                    p.serviceAccounts.forEach { acc ->
                        require(acc.privateKey.length <= 64 * 1024) { "serviceAccounts[].privateKey must be at most 65536 characters" }
                        require(acc.projectId.length <= 1024 && acc.serviceAccountEmail.length <= 1024) {
                            "serviceAccounts[] projectId/serviceAccountEmail must be at most 1024 characters"
                        }
                        require(acc.location.length <= 256 && acc.label.length <= 256) {
                            "serviceAccounts[] location/label must be at most 256 characters"
                        }
                    }
                }
            }
            is ProviderSetting.Claude -> { url(p.baseUrl); key("apiKey", p.apiKey); apiKeyPool(p.apiKeys) }
            else -> Unit
        }
    }

    private fun <T> objectJson(serializer: KSerializer<T>, item: T): JsonObject =
        strictJson.encodeToJsonElement(serializer, item).jsonObject

    /**
     * [encode] / [decode] / [normalize]：对象在创造助手眼里的形状与内部形状不同时（模式注入、世界书、助手），
     * 由外观层 [LegacyViews] 互转；缺省是按 [serializer] 直接编解码。
     */
    private fun <T> patchList(
        initial: List<T>, serializer: KSerializer<T>, allowed: Set<String>, patch: CollectionPatch,
        create: (String) -> T,
        encode: (T) -> JsonObject = { objectJson(serializer, it) },
        decode: (T, JsonObject) -> T = { _, json -> strictJson.decodeFromJsonElement(serializer, json) },
        normalize: (JsonObject) -> JsonObject = { LenientEnums.normalize(it, serializer.descriptor).jsonObject },
        validate: (T) -> Unit,
    ): List<T> {
        fun id(item: T) = encode(item).getValue("id").jsonPrimitive.content
        fun merge(current: T, fields: Map<String, JsonElement>): T {
            val unknown = fields.keys.filterNot { it in allowed }
            if (unknown.isNotEmpty()) rejectForbidden("Field(s) $unknown cannot be set; writable fields: ${allowed.sorted()}")
            require(fields.size <= 64 && fields.toString().length <= 256 * 1024) { "At most 64 fields and 256 KiB per change" }
            val json = normalize(JsonObject(encode(current) + fields))
            val decoded = try {
                decode(current, json)
            } catch (e: kotlinx.serialization.SerializationException) {
                rejectInvalid("Field value has the wrong shape: ${describeSerializationError(e)}")
            }
            return decoded.also(validate)
        }
        return when (patch) {
            is CollectionPatch.Create -> {
                require(initial.none { id(it) == patch.id }) { "Object already exists" }
                val created = merge(create(patch.id), patch.fields)
                initial + created
            }
            is CollectionPatch.PatchFields -> {
                require(initial.any { id(it) == patch.id }) { "Object not found" }
                initial.map { if (id(it) == patch.id) merge(it, patch.fields) else it }
            }
            is CollectionPatch.Remove -> {
                require(patch.ids.isNotEmpty() && patch.ids.distinct().size == patch.ids.size) { "Nothing (or a duplicate) to remove" }
                require(patch.ids.all { target -> initial.any { id(it) == target } }) { "Object not found" }
                initial.filterNot { id(it) in patch.ids }
            }
            is CollectionPatch.Reorder -> {
                val byId = initial.associateBy(::id)
                require(patch.orderedIds.size == initial.size && patch.orderedIds.toSet() == byId.keys) {
                    "Reorder must contain each existing object exactly once"
                }
                patch.orderedIds.map(byId::getValue)
            }
            is CollectionPatch.Batch -> {
                require(patch.patches.size in 1..MAX_BATCH_PATCHES && patch.patches.none { it is CollectionPatch.Batch }) { "Invalid nested batch" }
                patch.patches.fold(initial) { current, child -> patchList(current, serializer, allowed, child, create, encode, decode, normalize, validate) }
            }
        }
    }

    private fun applyField(s: Settings, op: SettingsFieldOp): Settings {
        if (op.fieldId.startsWith("settings.defaults.slot.")) {
            val raw = op.value.jsonPrimitive.also { require(it.isString) { "modelId must be a string" } }.content
            val id = runCatching { Uuid.parse(raw) }.getOrNull()
                ?: rejectInvalid("modelId '$raw' is not a model id; use the id from creator_list_objects kind=MODEL_DEFINITION (not the provider's model name)")
            val model = s.providers.flatMap { it.models }.firstOrNull { it.id == id }
                ?: rejectInvalid("No model with id '$raw'")
            val slot = op.fieldId.substringAfterLast('.')
            val expected = if (slot == "IMAGE_GENERATION") ModelType.IMAGE else ModelType.CHAT
            require(model.type == expected) { "Slot $slot needs a ${expected.name} model, but '${model.displayName.ifBlank { model.modelId }}' is ${model.type.name}" }
            return when (slot) {
                "CHAT" -> s.copy(chatModelId = id)
                "FAST" -> s.copy(fastModelId = id)
                "TRANSLATE" -> s.copy(translateModeId = id)
                "IMAGE_GENERATION" -> s.copy(imageGenerationModelId = id)
                "OCR" -> { require(Modality.IMAGE in model.inputModalities) { "Slot OCR needs a model with IMAGE input" }; s.copy(ocrModelId = id) }
                else -> rejectInvalid("Unknown default model slot")
            }
        }
        val value = op.value.jsonPrimitive.also { require(it.isString) { "text must be a string" } }.content
        require(value.length <= 64 * 1024) { "text must be at most 65536 characters" }
        return when (op.fieldId) {
            "settings.defaults.prompt.TITLE" -> s.copy(titlePrompt = value)
            "settings.defaults.prompt.TRANSLATE" -> s.copy(translatePrompt = value)
            "settings.defaults.prompt.SUGGESTION" -> s.copy(suggestionPrompt = value)
            "settings.defaults.prompt.OCR" -> s.copy(ocrPrompt = value)
            else -> rejectInvalid("Unknown default prompt")
        }
    }

    // 保留助手（创造助手）不能被直接指定为目标（SettingsDomainAdapter.objectBaseline / Bind 拒绝）；
    // 这里不再拒绝，删除快捷消息/世界书/模式注入/标签时可以顺带解除它上面的绑定。
    private fun validateAssistant(a: Assistant, s: Settings, authorizedImageUris: Set<String>) {
        require(a.name.length <= 1024) { "name must be at most 1024 characters" }
        require(a.mainPromptText.length <= 64 * 1024) { "the main prompt entry must be at most 65536 characters" }
        require(a.promptPreset.layout().entries.size <= NativeCollections.MAX_PRESET_ENTRIES) {
            "an assistant's prompt holds at most ${NativeCollections.MAX_PRESET_ENTRIES} entries"
        }
        require(a.messageTemplate.length <= 64 * 1024) { "messageTemplate must be at most 65536 characters" }
        require(a.contextMessageLimit in 0..100_000) { "contextMessageLimit must be 0..100000" }
        require(a.timeReminderIntervalMinutes in 1..525_600) { "timeReminderIntervalMinutes must be 1..525600" }
        require(a.maxTokens == null || a.maxTokens in 1..1_000_000) { "maxTokens must be null or 1..1000000" }
        require(a.temperature == null || a.temperature.isFinite() && a.temperature in 0f..2f) { "temperature must be null or 0..2" }
        require(a.topP == null || a.topP.isFinite() && a.topP in 0f..1f) { "topP must be null or 0..1" }
        require(a.topK == null || a.topK in 1..100_000) { "topK must be null or 1..100000" }
        require(a.frequencyPenalty == null || a.frequencyPenalty.isFinite() && a.frequencyPenalty in -2f..2f) { "frequencyPenalty must be null or -2..2" }
        require(a.presencePenalty == null || a.presencePenalty.isFinite() && a.presencePenalty in -2f..2f) { "presencePenalty must be null or -2..2" }
        require(a.repetitionPenalty == null || a.repetitionPenalty.isFinite() && a.repetitionPenalty in 0.01f..3f) { "repetitionPenalty must be null or 0.01..3" }
        require(a.minP == null || a.minP.isFinite() && a.minP in 0f..1f) { "minP must be null or 0..1" }
        require(a.topA == null || a.topA.isFinite() && a.topA in 0f..1f) { "topA must be null or 0..1" }
        require(a.seed == null || a.seed >= 0) { "seed must be null or 0 or more" }
        require(a.backgroundOpacity.isFinite() && a.backgroundOpacity in 0f..1f) { "backgroundOpacity must be 0..1" }
        missingRefs("tags", a.tags, s.assistantTags.map { it.id }, "ASSISTANT_TAG")
        missingRefs("quickMessageIds", a.quickMessageIds, s.quickMessages.map { it.id }, "QUICK_MESSAGE")
        missingRefs("lorebookIds", a.lorebookIds, s.lorebooks.map { it.id }, "LOREBOOK")
        missingRefs("modeInjectionIds", a.libraryEntryIds, s.promptLibraryIds().toList(), "MODE_INJECTION")
        missingRefs("mcpServers", a.mcpServers, s.mcpServers.map { it.id }, "MCP_SERVER")
        require(a.chatModelId == null || s.providers.flatMap { it.models }.any { it.id == a.chatModelId && it.type == ModelType.CHAT }) {
            "chatModelId '${a.chatModelId}' is not a CHAT model id (list MODEL_DEFINITION for ids; null = use the default chat model)"
        }
        require(a.presetMessages.size <= 100) { "at most 100 presetMessages" }
        require(a.presetMessages.all { msg -> msg.parts.all { it is UIMessagePart.Text && it.metadata.isNullOrEmpty() } &&
            msg.annotations.isEmpty() && msg.usage == null && msg.modelId == null && msg.translation == null &&
            msg.modelSnapshot == null && msg.lastUsage == null && msg.stepCount == null &&
            msg.generationTimeMs == null && !msg.isContextCheckpoint && msg.stopReason == null &&
            msg.finishedAt == null && msg.role != MessageRole.TOOL }) { "presetMessages must be plain text messages (role USER, ASSISTANT or SYSTEM)" }
        // Managed image IDs are resolved by the native planner, never arbitrary host URIs.
        val before = s.assistants.firstOrNull { it.id == a.id }
        require(a.avatar !is Avatar.Image || a.avatar == before?.avatar || a.avatar.url in authorizedImageUris) {
            "Avatar requires an authorized managed image"
        }
        require(a.background == null || a.background == before?.background || a.background in authorizedImageUris) {
            "Background requires an authorized managed image"
        }
        if (a.avatar is Avatar.Emoji) require(a.avatar.content.length in 1..64) { "avatar emoji content must be 1..64 characters" }
    }

    private fun missingRefs(field: String, ids: Collection<Uuid>, known: List<Uuid>, kind: String) {
        val missing = ids.filterNot { it in known }
        require(missing.isEmpty()) { "$field contains unknown id(s) $missing; list kind=$kind for valid ids" }
    }

    /** kotlinx messages name serial classes by FQN and append the whole input; keep the useful part. */
    fun describeSerializationError(e: kotlinx.serialization.SerializationException): String =
        e.message.orEmpty()
            .substringBefore("\nJSON input:")
            .substringBefore("\nUse '")
            .replace(Regex("([a-z][a-z0-9_]*\\.)+([A-Z][A-Za-z0-9_]*(\\.[A-Z][A-Za-z0-9_]*)*)")) { it.groupValues[2] }
            .trim().take(400)

    private fun validateModel(m: Model) {
        require(m.modelId.isNotBlank() && m.modelId.length <= 1024) { "modelId (the provider's model name) is required, at most 1024 characters" }
        require(m.contextLength in 1..MAX_MODEL_CONTEXT_LENGTH) { "contextLength must be 1..$MAX_MODEL_CONTEXT_LENGTH tokens" }
        require(m.displayName.length <= 1024) { "displayName must be at most 1024 characters" }
        m.pricing?.let { p ->
            require(listOfNotNull(p.input, p.output, p.cachedInput).all { it.isFinite() && it in 0.0..1_000_000.0 }) {
                "pricing values are per million tokens and must be 0..1000000"
            }
        }
        require(m.inputModalities.isNotEmpty() && m.outputModalities.isNotEmpty()) { "inputModalities and outputModalities must not be empty" }
        require(m.inputModalities.distinct().size == m.inputModalities.size && m.outputModalities.distinct().size == m.outputModalities.size) {
            "modalities must not repeat"
        }
        require(m.abilities.distinct().size == m.abilities.size) { "abilities must not repeat" }
        require(m.type == ModelType.CHAT || m.tools.isEmpty()) { "only CHAT models can have tools" }
    }
}
