package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.ai.prompts.*
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.common.*
import me.rerere.rikkihub.data.management.coordinator.*
import me.rerere.rikkihub.data.management.ports.*
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import kotlin.uuid.Uuid

/** One implementation for the five Settings domains; domain ownership is fixed at construction. */
class SettingsDomainAdapter(override val domain: ManagementDomain) : ManagementDomainAdapter {
    init { require(domain in SETTINGS_DOMAINS) { "Not a Settings domain: $domain" } }
    private val json = SettingsCollectionInterpreter.strictJson

    /** Everything planned so far; a batch accumulates its commands here. */
    private class Accumulator {
        val operations = mutableListOf<StoreOperation>()
        val entries = mutableListOf<ReadSetEntry>()
        val refs = mutableListOf<ObjectRef>()
        val assigned = linkedMapOf<String, String>()
        val diff = mutableListOf<DiffLine>()
    }

    override suspend fun plan(ctx: PlanContext, command: ManagementCommand): PlannedMutation {
        val snapshot = ctx.read.atomicSettingsSnapshot()
        val acc = Accumulator()
        val risk: RiskLevel
        when (command) {
            is SettingsDomainCommand -> {
                require(command.domain == domain) { "Command domain ${command.domain} does not match adapter $domain" }
                planOne(ctx, snapshot, snapshot.settings, command, "new0", acc)
                risk = command.risk()
            }
            is SettingsBatchCommand -> {
                require(command.domain == domain) { "Batch domain ${command.domain} does not match adapter $domain" }
                require(command.commands.all { it.domain in SETTINGS_DOMAINS }) { "A batch only holds Settings commands" }
                var simulated = snapshot.settings
                command.commands.forEachIndexed { index, child ->
                    val label = command.labels[index]
                    try {
                        val resolved = resolveLabels(child, acc.assigned)
                        val before = acc.operations.size
                        planOne(ctx, snapshot, simulated, resolved, label, acc)
                        acc.operations.drop(before).filter { it.store == StoreKind.SETTINGS_DATASTORE }
                            .forEach { simulated = SettingsCollectionInterpreter.apply(simulated, it) }
                    } catch (e: Exception) {
                        throw SettingsBatchStepException(label, e)
                    }
                }
                risk = command.risk()
            }
            else -> throw IllegalArgumentException("Not a Settings command")
        }
        val settingsOps = acc.operations.filter { it.store == StoreKind.SETTINGS_DATASTORE }
        if (settingsOps.size > MAX_TRANSACTION_OPS) reject(ProposalErrorCode.BUDGET_EXCEEDED,
            "This change needs ${settingsOps.size} Settings edits (limit $MAX_TRANSACTION_OPS); split it into smaller proposals")
        // Final dry run of the whole transaction on the real snapshot (same code the gateway commits with).
        var simulated = snapshot.settings
        settingsOps.forEach { simulated = SettingsCollectionInterpreter.apply(simulated, it) }
        val steps = listOf(SettingsTransactionOp(settingsOps.map(StoreOperationCodec::encode),
            settingsOps.flatMap { it.objectRefs }.distinct(), "settings-transaction")) +
            acc.operations.filter { it.store != StoreKind.SETTINGS_DATASTORE }
        return PlannedMutation(ReadSet(acc.entries.distinct()), steps, acc.diff, risk,
            RetentionNote(recycleDays = 30), acc.refs.distinct(), risk == RiskLevel.HIGH, acc.assigned)
    }

    /**
     * Plans one command on [settings] (the snapshot, or the simulated result of earlier batch
     * commands). Version tokens always come from [snapshot]: objects created earlier in the same
     * batch have no baseline of their own and are covered by their collection baseline.
     */
    private suspend fun planOne(
        ctx: PlanContext, snapshot: ManagementSettingsSnapshot, settings: Settings,
        command: SettingsDomainCommand, label: String, acc: Accumulator,
    ) {
        val commandDomain = command.domain
        val collection = command.collectionId
        fun collectionBaseline(id: String) {
            val token = snapshot.collections[id] ?: VersionToken(snapshot.epoch, 0, 0)
            acc.entries += ReadSetEntry.Collection(id, token)
            acc.refs += ObjectRef(SettingsManagementPort.domainForFenceCollection(id), "collection:$id")
        }
        fun ref(id: String) = ObjectRef(commandDomain, when (collection) {
            "settings.assistantTags" -> "tag:$id"
            SettingsCollectionInterpreter.PROVIDERS -> "provider:$id"
            else -> NativeCollections.refId(collection, id) ?: id
        })
        fun diffLine(path: String, field: String, before: String?, after: String?) =
            if (SettingsCollectionInterpreter.isSecretField(collection, field)) DiffLine(path, null, null, secret = true)
            else DiffLine(path, before, after)
        // a whole SillyTavern preset is far too long for a diff line; say what it is instead
        fun diffValue(field: String, value: JsonElement): String =
            if (field == "fromPreset" && value is JsonObject) "SillyTavern preset (${(value["prompts"] as? JsonArray)?.size ?: 0} prompts)" else value.toString()
        fun objectBaseline(id: String): JsonObject {
            if (commandDomain == ManagementDomain.ASSISTANT && collection == "settings.assistants") {
                val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: rejectNotFound("Assistant id '$id' is not a valid id")
                AssistantRegistry().requireOrdinaryAssistantId(uuid)
            }
            val target = ref(id)
            val current = SettingsCollectionInterpreter.collectionJson(settings, collection)
                .firstOrNull { it["id"]?.jsonPrimitive?.content == id }
                ?: rejectNotFound("No object with id '$id' in ${collectionLabel(collection)}")
            snapshot.objects[SettingsManagementPort.objectKey(target)]?.let { acc.entries += ReadSetEntry.Object(target, it) }
            acc.refs += target
            return current
        }
        fun addPatch(id: String, fields: Map<String, JsonElement>, targetCollection: String = collection,
            targetDomain: ManagementDomain = commandDomain) {
            acc.operations += SettingsCollectionOp(targetCollection, CollectionPatch.PatchFields(id, fields),
                listOf(ObjectRef(targetDomain, id)), "patch-object")
        }
        if (!collection.startsWith("settings.defaults.")) collectionBaseline(collection)
        if (collection == SettingsCollectionInterpreter.PROVIDERS && command.action !is SettingsAction.Create &&
            command.action !is SettingsAction.Patch) rejectForbidden("Providers can only be created and changed here; " +
                "deleting, cloning or reordering them is done by the user in the app")

        when (val action = command.action) {
            is SettingsAction.Create -> {
                val id = SettingsCollectionInterpreter.nextObjectId(settings, collection) ?: ctx.hostIds.newObjectId()
                acc.assigned[label] = id
                val fields = SettingsCollectionInterpreter.normalizeFields(collection, action.fields)
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Create(id, fields), listOf(ref(id)), "create-object")
                acc.refs += ref(id)
                acc.diff += fields.map { diffLine("$collection[$id].${it.key}", it.key, null, diffValue(it.key, it.value)) }
            }
            is SettingsAction.Patch -> {
                val old = objectBaseline(action.id)
                // Echoing an unchanged field (or the id itself) is not an error, just not a change.
                val given = SettingsCollectionInterpreter.normalizeFields(collection, action.fields)
                val createOnly = (NativeCollections.resolve(collection)?.createOnly.orEmpty() +
                    if (collection == "settings.assistants") setOf("systemPrompt", "copyFrom", "fromPreset") else emptySet())
                    .filter { it in given }
                if (createOnly.isNotEmpty()) rejectForbidden("$createOnly only apply when the object is created" + when {
                    "systemPrompt" in createOnly -> "; change an assistant's prompt text with PRESET_ENTRY Patch (targetId \"<assistant id>/main\", fields {content})"
                    else -> ""
                })
                val changed = given.filter { (key, value) -> key != "id" && old[key] != value }
                if (changed.isEmpty()) rejectInvalid("Patch changes nothing: every given field already has that value")
                val fields = changed
                addPatch(action.id, fields)
                acc.diff += fields.map { diffLine("$collection[${action.id}].${it.key}", it.key, old[it.key]?.toString(), diffValue(it.key, it.value)) }
            }
            is SettingsAction.CreateMany -> {
                requireBulk(collection, action.items.size)
                val base = SettingsCollectionInterpreter.nextObjectId(settings, collection)?.toIntOrNull()
                val ids = action.items.indices.map { i -> base?.let { (it + i).toString() } ?: ctx.hostIds.newObjectId() }
                val patches = action.items.mapIndexed { i, item ->
                    CollectionPatch.Create(ids[i], SettingsCollectionInterpreter.normalizeFields(collection, item))
                }
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Batch(patches), ids.map(::ref), "create-objects")
                ids.forEachIndexed { i, id -> acc.assigned["$label[$i]"] = id }
                acc.refs += ids.map(::ref)
                acc.diff += ids.mapIndexed { i, id -> DiffLine("$collection[$id]", null, action.items[i].toString().take(300)) }
            }
            is SettingsAction.PatchMany -> {
                requireBulk(collection, action.patches.size)
                require(action.patches.map { it.id }.distinct().size == action.patches.size) { "patches must name each object once" }
                val patches = action.patches.mapNotNull { one ->
                    val old = objectBaseline(one.id)
                    val changed = SettingsCollectionInterpreter.normalizeFields(collection, one.fields)
                        .filter { (key, value) -> key != "id" && old[key] != value }
                    if (changed.isEmpty()) null else {
                        acc.diff += changed.map { diffLine("$collection[${one.id}].${it.key}", it.key, old[it.key]?.toString(), it.value.toString()) }
                        CollectionPatch.PatchFields(one.id, changed)
                    }
                }
                if (patches.isEmpty()) rejectInvalid("Patch changes nothing: every given field already has that value")
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Batch(patches), patches.map { ref(it.id) }, "patch-objects")
            }
            is SettingsAction.DeleteMany -> {
                requireBulk(collection, action.ids.size)
                val olds = action.ids.map { it to objectBaseline(it) }
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Remove(action.ids), action.ids.map(::ref), "delete-objects")
                acc.diff += olds.map { (id, old) -> DiffLine("$collection[$id]", old.toString().take(300), null) }
            }
            is SettingsAction.Clone -> {
                val old = objectBaseline(action.id)
                if (action.name.isBlank() || action.name.length > 1024) rejectInvalid("Clone name must be 1..1024 characters")
                if (NativeCollections.resolve(collection)?.let { it.singleton || it.refPrefix.isNotEmpty() } == true)
                    rejectInvalid("Clone applies to quick messages, prompt library entries, lorebooks, assistants and models")
                val fields = old.filterKeys { it != "id" }.toMutableMap()
                val naming = if (collection == "settings.quickMessages") "title" else if (collection.endsWith(".models")) "displayName" else "name"
                fields[naming] = JsonPrimitive(action.name)
                // A lorebook / assistant clone also copies the entries / the prompt layout, options and regex scripts.
                if (collection == "settings.lorebooks" || collection == "settings.assistants") fields["copyFrom"] = JsonPrimitive(action.id)
                val id = ctx.hostIds.newObjectId(); acc.assigned[label] = id
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Create(id, fields), listOf(ref(id)), "clone-object")
                acc.refs += ref(id)
                acc.diff += DiffLine("$collection[$id]", null, "clone of ${action.id}; credentials not copied")
            }
            is SettingsAction.Reorder -> {
                val ids = SettingsCollectionInterpreter.collectionJson(settings, collection).map { it.getValue("id").jsonPrimitive.content }
                // 创造助手对管理链不可见：模型给出的顺序不含它，它保持原位置。
                val ordered = if (collection == "settings.assistants") {
                    val reserved = ids.withIndex().filter { AssistantRegistry.isReservedAssistantId(it.value) }
                    action.orderedIds.filterNot { AssistantRegistry.isReservedAssistantId(it) }.toMutableList().apply {
                        reserved.forEach { (index, id) -> add(index.coerceAtMost(size), id) }
                    }
                } else action.orderedIds
                if (ordered.size != ids.size || ordered.toSet() != ids.toSet()) {
                    val visible = ids.filterNot { AssistantRegistry.isReservedAssistantId(it) }
                    val given = action.orderedIds.filterNot { AssistantRegistry.isReservedAssistantId(it) }
                    rejectInvalid("orderedIds must list every existing id exactly once " +
                        "(missing: ${(visible - given.toSet()).take(8)}, unknown: ${(given - visible.toSet()).take(8)}, " +
                        "duplicates: ${given.groupBy { it }.filterValues { it.size > 1 }.keys.take(8)})")
                }
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Reorder(ordered),
                    ids.map(::ref), "reorder-objects")
                acc.refs += ids.map(::ref)
                acc.diff += DiffLine("$collection.order", ids.joinToString(), ordered.joinToString())
            }
            is SettingsAction.Delete -> {
                val old = objectBaseline(action.id)
                val bindingField = when (commandDomain) {
                    ManagementDomain.QUICK_MESSAGE -> "quickMessageIds"
                    ManagementDomain.MODE_INJECTION -> "modeInjectionIds"
                    ManagementDomain.WORLD_BOOK -> "lorebookIds"
                    ManagementDomain.ASSISTANT -> if (collection == "settings.assistantTags") "tags" else null
                    else -> null
                }
                if (bindingField != null) {
                    collectionBaseline("settings.assistants")
                    val projections = SettingsCollectionInterpreter.collectionJson(settings, "settings.assistants")
                    settings.assistants.forEach { assistant ->
                        val projection = projections.first { it.getValue("id").jsonPrimitive.content == assistant.id.toString() }
                        val current = projection[bindingField]?.jsonArray.orEmpty()
                        if (current.any { it.jsonPrimitive.content == action.id }) {
                            val target = ObjectRef(ManagementDomain.ASSISTANT, assistant.id.toString())
                            acc.refs += target
                            snapshot.objects[SettingsManagementPort.objectKey(target)]?.let { acc.entries += ReadSetEntry.Object(target, it) }
                            val after = JsonArray(current.filterNot { it.jsonPrimitive.content == action.id })
                            addPatch(assistant.id.toString(), mapOf(bindingField to after), "settings.assistants", ManagementDomain.ASSISTANT)
                            acc.diff += DiffLine("assistants[${assistant.id}].$bindingField", current.toString(), after.toString())
                        }
                    }
                }
                if (commandDomain == ManagementDomain.MODEL_AND_PROMPT_DEFAULTS) {
                    val id = Uuid.parse(action.id)
                    val slots = mapOf("CHAT" to settings.chatModelId, "FAST" to settings.fastModelId,
                        "TRANSLATE" to settings.translateModeId, "IMAGE_GENERATION" to settings.imageGenerationModelId,
                        "OCR" to settings.ocrModelId).filterValues { it == id }.keys
                    val assistants = settings.assistants.filter { it.chatModelId == id }.map { it.name }
                    if (slots.isNotEmpty() || assistants.isNotEmpty() || id in settings.favoriteModels) reject(
                        ProposalErrorCode.MISSING_REPLACEMENT_PLAN,
                        "This model is still in use (default slots: ${slots.toList()}, assistants: ${assistants.take(8)}, " +
                            "favorite: ${id in settings.favoriteModels}). Move those to another model first, in the same call " +
                            "before the Delete (DEFAULT_SLOT SetSlot / ASSISTANT Patch chatModelId); favorites must be changed in the app")
                }
                acc.operations += SettingsCollectionOp(collection, CollectionPatch.Remove(listOf(action.id)), listOf(ref(action.id)), "delete-object")
                if (commandDomain == ManagementDomain.MODE_INJECTION || commandDomain == ManagementDomain.WORLD_BOOK) {
                    val field = if (commandDomain == ManagementDomain.WORLD_BOOK) RefField.LOREBOOK_IDS else RefField.MODE_INJECTION_IDS
                    val predicate = "room.conversations[].${if (field == RefField.LOREBOOK_IDS) "lorebookIds" else "modeInjectionIds"}"
                    acc.entries += ReadSetEntry.Collection(predicate, ctx.read.collectionToken(predicate))
                    val ids = ctx.read.snapshotIds("room.conversations").filter { id ->
                        ctx.read.readObjectJson("room.conversations", id)?.get(if (field == RefField.LOREBOOK_IDS) "lorebookIds" else "modeInjectionIds")
                            ?.jsonArray?.any { it.jsonPrimitive.content == action.id } == true
                    }
                    ids.forEach { id ->
                        val target = ObjectRef(ManagementDomain.CONVERSATION, id); acc.refs += target
                        ctx.read.objectToken("room.conversations", id)?.let { acc.entries += ReadSetEntry.Object(target, it) }
                    }
                    if (ids.isNotEmpty()) acc.operations += ConversationRefStripOp(field, action.id, RefScope.ByIds(ids),
                        ids.map { ObjectRef(ManagementDomain.CONVERSATION, it) })
                }
                acc.diff += DiffLine("$collection[${action.id}]", old.toString(), null)
            }
            is SettingsAction.BindAssistant -> {
                if (commandDomain !in setOf(ManagementDomain.QUICK_MESSAGE, ManagementDomain.MODE_INJECTION, ManagementDomain.WORLD_BOOK))
                    rejectInvalid("Bind/Unbind only applies to quick messages, mode injections and lorebooks")
                objectBaseline(action.targetId)
                val assistant = settings.assistants.firstOrNull { it.id.toString() == action.assistantId }
                    ?: rejectNotFound("No assistant with id '${action.assistantId}'")
                AssistantRegistry().requireOrdinaryAssistantId(assistant.id)
                collectionBaseline("settings.assistants")
                val target = ObjectRef(ManagementDomain.ASSISTANT, action.assistantId); acc.refs += target
                snapshot.objects[SettingsManagementPort.objectKey(target)]?.let { acc.entries += ReadSetEntry.Object(target, it) }
                val field = when (commandDomain) { ManagementDomain.QUICK_MESSAGE -> "quickMessageIds"; ManagementDomain.WORLD_BOOK -> "lorebookIds"; else -> "modeInjectionIds" }
                val old = when (commandDomain) { ManagementDomain.QUICK_MESSAGE -> assistant.quickMessageIds; ManagementDomain.WORLD_BOOK -> assistant.lorebookIds; else -> assistant.libraryEntryIds }.map { it.toString() }
                val after = if (action.enabled) (old + action.targetId).distinct() else old - action.targetId
                if (after == old) rejectInvalid(if (action.enabled) "Already bound to this assistant" else "Not bound to this assistant")
                addPatch(action.assistantId, mapOf(field to JsonArray(after.map(::JsonPrimitive))), "settings.assistants", ManagementDomain.ASSISTANT)
                acc.diff += DiffLine("assistants[${action.assistantId}].$field", old.joinToString(), after.joinToString())
            }
            is SettingsAction.SetField -> addDefaultField(commandDomain, action.fieldId, action.value, snapshot, settings, acc)
            is SettingsAction.ResetPrompt -> {
                val text = when (action.slot) { "TITLE" -> DEFAULT_TITLE_PROMPT; "TRANSLATE" -> DEFAULT_TRANSLATION_PROMPT;
                    "SUGGESTION" -> DEFAULT_SUGGESTION_PROMPT; "OCR" -> DEFAULT_OCR_PROMPT;
                    else -> rejectInvalid("Unknown prompt slot '${action.slot}'") }
                addDefaultField(commandDomain, "settings.defaults.prompt.${action.slot}", JsonPrimitive(text), snapshot, settings, acc)
            }
        }
    }

    private fun requireBulk(collection: String, count: Int) {
        val native = NativeCollections.resolve(collection)
        if (native == null || native.singleton) rejectInvalid(
            "Bulk create/patch/delete (items, patches, ids) is for lorebooks, prompt library entries, lorebook entries, " +
                "preset entries and regex scripts; send one operation per object for ${collectionLabel(collection)}")
        require(count in 1..MAX_BULK) { "A bulk operation holds 1..$MAX_BULK objects (got $count); send several operations" }
    }

    private fun addDefaultField(commandDomain: ManagementDomain, field: String, value: JsonElement,
        snapshot: ManagementSettingsSnapshot, settings: Settings, acc: Accumulator) {
        require(commandDomain == ManagementDomain.MODEL_AND_PROMPT_DEFAULTS) { "Default fields belong to the defaults domain" }
        val key = when (field) {
            "settings.defaults.slot.CHAT" -> "chatModelId"; "settings.defaults.slot.FAST" -> "fastModelId"
            "settings.defaults.slot.TRANSLATE" -> "translateModeId"; "settings.defaults.slot.IMAGE_GENERATION" -> "imageGenerationModelId"
            "settings.defaults.slot.OCR" -> "ocrModelId"
            "settings.defaults.prompt.TITLE" -> "titlePrompt"; "settings.defaults.prompt.TRANSLATE" -> "translatePrompt"
            "settings.defaults.prompt.SUGGESTION" -> "suggestionPrompt"; "settings.defaults.prompt.OCR" -> "ocrPrompt"
            else -> rejectInvalid("Unknown default field '$field'")
        }
        val target = ObjectRef(commandDomain, "setting:$key"); acc.refs += target
        snapshot.objects["setting:$key"]?.let { acc.entries += ReadSetEntry.Object(target, it) }
        acc.operations += SettingsFieldOp(field, value, listOf(target), "set-default")
        val raw = json.encodeToJsonElement(Settings.serializer(), settings).jsonObject
        if (raw[key] == value) rejectInvalid("$field already has this value")
        acc.diff += DiffLine(field, raw[key]?.toString(), value.toString())
    }

    override suspend fun inverse(ctx: PlanContext, command: ManagementCommand): ManagementCommand? = null
    override fun needsRecyclePayload(command: ManagementCommand): Boolean = true
    override fun decodeCommand(json: String): ManagementCommand {
        val root = this.json.parseToJsonElement(json).jsonObject
        return if ("commands" in root) this.json.decodeFromJsonElement(SettingsBatchCommand.serializer(), root)
        else this.json.decodeFromJsonElement(SettingsDomainCommand.serializer(), root)
    }

    companion object {
        val SETTINGS_DOMAINS = setOf(ManagementDomain.QUICK_MESSAGE, ManagementDomain.MODE_INJECTION,
            ManagementDomain.WORLD_BOOK, ManagementDomain.ASSISTANT, ManagementDomain.MODEL_AND_PROMPT_DEFAULTS)
        const val MAX_TRANSACTION_OPS = 64
        const val MAX_BULK = 200

        /**
         * Replaces string values equal to the label of an earlier Create/Clone with its host-allocated id.
         * Other strings (including "$5" written as text) stay untouched; the creator bridge validates
         * references in id positions before planning.
         */
        internal fun resolveLabels(command: SettingsDomainCommand, assigned: Map<String, String>): SettingsDomainCommand {
            if (assigned.isEmpty()) return command
            val strict = SettingsCollectionInterpreter.strictJson
            val element = strict.encodeToJsonElement(SettingsDomainCommand.serializer(), command)
            fun walk(e: JsonElement): JsonElement = when (e) {
                is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
                is JsonArray -> JsonArray(e.map(::walk))
                is JsonPrimitive -> if (e.isString) assigned[e.content]?.let(::JsonPrimitive) ?: e else e
            }
            val resolved = strict.decodeFromJsonElement(SettingsDomainCommand.serializer(), walk(element))
            // The models / entries / prompt entries / regex scripts of a parent created earlier in the batch: "settings.lorebooks.$0.entries".
            val match = NativeCollections.NESTED_LABEL.matchEntire(resolved.collectionId) ?: return resolved
            val id = assigned[match.groupValues[2]] ?: return resolved
            return resolved.copy(collectionId = "settings.${match.groupValues[1]}.$id.${match.groupValues[3]}")
        }

        fun collectionLabel(collection: String): String = when {
            collection == "settings.quickMessages" -> "quick messages"
            collection == "settings.lorebooks" -> "lorebooks"
            collection == "settings.assistants" -> "assistants"
            collection == "settings.assistantTags" -> "assistant tags"
            collection == SettingsCollectionInterpreter.PROVIDERS -> "providers"
            collection.endsWith(".models") -> "the models of this provider"
            else -> NativeCollections.resolve(collection)?.label ?: collection
        }
    }
}
