package me.rerere.rikkihub.data.creator.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.creator.session.CreatorOperation
import me.rerere.rikkihub.data.management.adapters.SettingsAction
import me.rerere.rikkihub.data.management.adapters.LenientEnums
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand
import me.rerere.rikkihub.data.management.adapters.conversation.ConversationManagementCommand
import me.rerere.rikkihub.data.management.adapters.skills.SkillCommand
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetCommand
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand

/** A model operation that cannot become a typed command (structured error back to the model). */
class CreatorCommandRejected(val code: String, detail: String) : IllegalArgumentException(detail)

/**
 * Lookups that let the decoder accept the natural forms a model writes after reading an object:
 * `targetId` instead of a command-specific id parameter, no `providerId` when patching a model,
 * no `workspaceId` when patching a workspace-local preset, generic `Patch`/`Delete` names.
 */
interface CreatorDecodeContext {
    /** Provider owning model [modelId] (a MODEL_DEFINITION id). */
    fun providerOfModel(modelId: String): String? = null

    /** Workspace holding the workspace-local preset [presetId]. */
    fun workspaceOfLocalPreset(presetId: String): String? = null

    /** True for a shared library preset, false for a workspace-local one, null when unknown. */
    fun isLibraryPreset(presetId: String): Boolean? = null

    /** Current key of preset [presetId] (keys are immutable). */
    fun presetKey(presetId: String): String? = null

    object Empty : CreatorDecodeContext
}

/**
 * Decoder from the model's `creator_preview_change` operation to ONE typed management command.
 * Closed command names per kind (matched case-insensitively, with documented aliases); unknown
 * keys/values are rejected, never ignored. Commands that would let the model supply a serialized
 * entity or recovery snapshot (Restore*) are not decodable here: Undo/restore is produced only by
 * the host from its own audited recovery material.
 */
object CreatorCommandDecoder {
    private val strict = Json { ignoreUnknownKeys = false; encodeDefaults = true; isLenient = false }

    private val SETTINGS_COMMANDS = listOf("Create", "Patch", "Delete", "Clone", "Reorder", "Bind", "Unbind")
    private val TAG_COMMANDS = listOf("Create", "Patch", "Delete", "Reorder")
    private val PROVIDER_COMMANDS = listOf("Create", "Patch")

    fun decode(op: CreatorOperation, ctx: CreatorDecodeContext = CreatorDecodeContext.Empty): ManagementCommand {
        val kind = CreatorObjectKind.parse(op.kind)
            ?: reject("BAD_KIND", "Unknown kind '${op.kind}'. Kinds: ${CreatorObjectKind.names}")
        if (!kind.writable) reject("READ_ONLY_KIND",
            "${kind.name} is a read-only reference list; it cannot be created or changed here")
        val p = op.params
        return when (kind) {
            CreatorObjectKind.QUICK_MESSAGE -> settings(kind, "settings.quickMessages", op, p, SETTINGS_COMMANDS)
            CreatorObjectKind.MODE_INJECTION -> settings(kind, "settings.modeInjections", op, p, SETTINGS_COMMANDS)
            CreatorObjectKind.LOREBOOK -> settings(kind, "settings.lorebooks", op, p, SETTINGS_COMMANDS)
            CreatorObjectKind.ASSISTANT_TAG -> settings(kind, "settings.assistantTags", op, p, TAG_COMMANDS)
            CreatorObjectKind.ASSISTANT -> when (canonical(op.command, listOf("CreateTag", "PatchTag", "DeleteTag"))) {
                // Older form of ASSISTANT_TAG commands.
                "CreateTag", "PatchTag", "DeleteTag" -> settings(CreatorObjectKind.ASSISTANT_TAG, "settings.assistantTags",
                    op.copy(command = canonical(op.command, listOf("CreateTag", "PatchTag", "DeleteTag"))!!.removeSuffix("Tag")), p, TAG_COMMANDS)
                else -> settings(kind, "settings.assistants", op, p, listOf("Create", "Patch", "Delete", "Clone", "Reorder"))
            }
            CreatorObjectKind.MODEL_DEFINITION -> {
                val explicit = (p["providerId"] as? JsonPrimitive)?.contentOrNull
                val provider = explicit ?: op.targetId?.let(ctx::providerOfModel)
                    ?: reject("BAD_INPUT", if (op.targetId == null)
                        "params.providerId is required to create a model; list kind=PROVIDER for ids"
                        else "No model with id '${op.targetId}'; list kind=MODEL_DEFINITION for ids")
                settings(kind, "settings.providers.$provider.models", op, JsonObject(p - "providerId"),
                    listOf("Create", "Patch", "Delete", "Clone", "Reorder"))
            }
            CreatorObjectKind.DEFAULT_SLOT -> {
                val command = canonical(op.command, listOf("SetSlot", "Patch", "Set"))
                    ?: reject("BAD_COMMAND", "DEFAULT_SLOT supports SetSlot")
                val slot = slot(p, op.targetId, MODEL_SLOTS)
                val modelId = if (command == "Patch") fieldString(p, "modelId") else {
                    onlyKeys(p, "slot", "modelId"); string(p, "modelId")
                }
                SettingsDomainCommand("settings.defaults.slot",
                    SettingsAction.SetField("settings.defaults.slot.$slot", JsonPrimitive(modelId)))
            }
            CreatorObjectKind.PROMPT -> {
                val slot = slot(p, op.targetId, PROMPT_SLOTS)
                when (canonical(op.command, listOf("SetPrompt", "ResetPrompt", "Patch", "Set", "Reset"))) {
                    "SetPrompt", "Set" -> {
                        onlyKeys(p, "slot", "text")
                        SettingsDomainCommand("settings.defaults.prompt",
                            SettingsAction.SetField("settings.defaults.prompt.$slot", JsonPrimitive(string(p, "text"))))
                    }
                    "Patch" -> SettingsDomainCommand("settings.defaults.prompt",
                        SettingsAction.SetField("settings.defaults.prompt.$slot", JsonPrimitive(fieldString(p, "text"))))
                    "ResetPrompt", "Reset" -> { onlyKeys(p, "slot"); SettingsDomainCommand("settings.defaults.prompt", SettingsAction.ResetPrompt(slot)) }
                    else -> reject("BAD_COMMAND", "PROMPT supports SetPrompt, ResetPrompt")
                }
            }
            CreatorObjectKind.PROVIDER -> {
                if (canonical(op.command, listOf("Delete", "Remove", "Clone", "Reorder")) != null) reject("NOT_ALLOWED",
                    "PROVIDER supports only ${PROVIDER_COMMANDS}: deleting, cloning or reordering providers is up to " +
                        "the user in Settings › Providers")
                settings(kind, "settings.providers", op, p, PROVIDER_COMMANDS)
            }
            CreatorObjectKind.SUBAGENT_PRESET -> subagent(op, p, ctx)
            CreatorObjectKind.SKILL -> skill(op, p)
            CreatorObjectKind.CONVERSATION -> conversation(op, p)
            CreatorObjectKind.MCP_SERVER, CreatorObjectKind.WORKSPACE -> error("read-only")
        }
    }

    // ------------------------------------------------------------------ Settings collections

    private fun settings(
        kind: CreatorObjectKind, collection: String, op: CreatorOperation, p: JsonObject, supported: List<String>,
    ): SettingsDomainCommand {
        val command = canonical(op.command, supported)
            ?: reject("BAD_COMMAND", "${kind.name} supports: $supported")
        val action = when (command) {
            "Create" -> SettingsAction.Create(fields(p, null))
            "Patch" -> { val id = target(op); SettingsAction.Patch(id, fields(p, id)) }
            "Delete" -> { onlyKeys(p); SettingsAction.Delete(target(op)) }
            "Clone" -> { onlyKeys(p, "name"); SettingsAction.Clone(target(op), string(p, "name")) }
            "Reorder" -> {
                onlyKeys(p, "orderedIds")
                val ids = p["orderedIds"] as? JsonArray ?: reject("BAD_INPUT", "params.orderedIds (array of ids) is required")
                SettingsAction.Reorder(ids.map { (it as? JsonPrimitive)?.contentOrNull ?: reject("BAD_INPUT", "orderedIds must be strings") })
            }
            "Bind", "Unbind" -> {
                onlyKeys(p, "assistantId")
                SettingsAction.BindAssistant(string(p, "assistantId"), target(op), command == "Bind")
            }
            else -> reject("BAD_COMMAND", "${kind.name} supports: $supported")
        }
        return SettingsDomainCommand(collection, action)
    }

    /** `params.fields`, or the params themselves when the wrapper was left out. */
    private fun fields(p: JsonObject, targetId: String?): Map<String, JsonElement> {
        val wrapped = p["fields"]
        val fields = when {
            wrapped is JsonObject -> { onlyKeys(p, "fields"); wrapped }
            wrapped != null -> reject("BAD_INPUT", "params.fields must be an object of field values")
            else -> p
        }
        val id = fields["id"]
        val cleaned = if (id != null && targetId != null && (id as? JsonPrimitive)?.contentOrNull == targetId) JsonObject(fields - "id") else fields
        if ("id" in cleaned) reject("FORBIDDEN_FIELD", "ids are assigned by the app; leave id out of fields")
        if (cleaned.isEmpty()) reject("BAD_INPUT", "params.fields must contain at least one field")
        if (cleaned.size > 64) reject("BAD_INPUT", "at most 64 fields per operation")
        return cleaned
    }

    private fun target(op: CreatorOperation): String = op.targetId?.takeIf { it.isNotBlank() && it.length <= 128 }
        ?: reject("BAD_INPUT", "targetId (the id of the object to change) is required")

    private fun string(p: JsonObject, key: String): String {
        val value = p[key] as? JsonPrimitive ?: reject("BAD_INPUT", "params.$key is required")
        if (!value.isString) reject("BAD_INPUT", "params.$key must be a string")
        return value.content
    }

    private fun fieldString(p: JsonObject, key: String): String {
        val fields = p["fields"] as? JsonObject ?: p
        val value = fields[key] as? JsonPrimitive ?: reject("BAD_INPUT", "fields.$key is required")
        if (!value.isString) reject("BAD_INPUT", "fields.$key must be a string")
        return value.content
    }

    private fun slot(p: JsonObject, targetId: String?, allowed: List<String>): String {
        val slot = ((p["slot"] as? JsonPrimitive)?.contentOrNull ?: targetId)?.uppercase()
            ?: reject("BAD_INPUT", "slot is required (targetId or params.slot): one of $allowed")
        if (slot !in allowed) reject("BAD_INPUT", "slot must be one of $allowed")
        return slot
    }

    private fun onlyKeys(p: JsonObject, vararg keys: String) {
        val extra = p.keys - keys.toSet() - "providerId"
        if (extra.isNotEmpty()) reject("BAD_INPUT", "Unexpected params: ${extra.sorted()} (expected: ${keys.toList().ifEmpty { listOf("none") }})")
    }

    // ------------------------------------------------------------------ sealed domains

    private fun subagent(op: CreatorOperation, raw: JsonObject, ctx: CreatorDecodeContext): SubagentPresetCommand {
        val supported = SUBAGENT_COMMANDS.keys.toList() + listOf("Create", "Patch", "Delete")
        val target = op.targetId?.takeIf { it.isNotBlank() }
        val isLibrary = target?.let(ctx::isLibraryPreset)
        val command = when (val name = canonical(op.command, supported) ?: reject("BAD_COMMAND", "SUBAGENT_PRESET supports: ${SUBAGENT_COMMANDS.keys}")) {
            "Create" -> if ("workspaceId" in raw) "CreateLocalPreset" else "CreateLibraryPreset"
            "Patch" -> if (isLibrary == false) "PatchLocalPreset" else "PatchLibraryPreset"
            "Delete" -> if (isLibrary == false) "DeleteLocalPreset" else "DeleteLibraryPreset"
            // A library/local mix-up is corrected when the id tells which one it is.
            "PatchLibraryPreset", "PatchLocalPreset" -> when (isLibrary) { true -> "PatchLibraryPreset"; false -> "PatchLocalPreset"; null -> name }
            "DeleteLibraryPreset", "DeleteLocalPreset" -> when (isLibrary) { true -> "DeleteLibraryPreset"; false -> "DeleteLocalPreset"; null -> name }
            else -> name
        }
        val p = raw.toMutableMap()
        // The read shape is {fields:{...}}; the commands call it draft / patch.
        (p.remove("fields") as? JsonObject)?.let { fields ->
            if (command.startsWith("Create")) p.putIfAbsent("draft", fields) else p.putIfAbsent("patch", fields)
        }
        val takesPresetId = command !in setOf("CreateLibraryPreset", "CreateLocalPreset", "ReorderLibrary", "ReorderLocalPresets")
        if (takesPresetId && "presetId" !in p && target != null) p["presetId"] = JsonPrimitive(target)
        if (command.endsWith("LocalPreset") && command != "CreateLocalPreset" && "workspaceId" !in p) {
            (p["presetId"] as? JsonPrimitive)?.contentOrNull?.let(ctx::workspaceOfLocalPreset)?.let { p["workspaceId"] = JsonPrimitive(it) }
        }
        (p["patch"] as? JsonObject)?.let { patch ->
            val key = (patch["key"] as? JsonPrimitive)?.contentOrNull ?: return@let
            // Echoing the unchanged key from the read result is fine; changing it is not possible.
            val current = (p["presetId"] as? JsonPrimitive)?.contentOrNull?.let(ctx::presetKey)
            if (key != current) reject("FORBIDDEN_FIELD", "key cannot be changed after creation; leave it out of the patch")
            p["patch"] = JsonObject(patch - "key")
        }
        return decodeSealed(SubagentPresetCommand.serializer(), CreatorObjectKind.SUBAGENT_PRESET, command, JsonObject(p), SUBAGENT_COMMANDS)
    }


    private fun skill(op: CreatorOperation, raw: JsonObject): SkillCommand {
        val supported = SKILL_COMMANDS.keys.toList() + listOf("Create", "Patch", "Delete", "Rename")
        val p = raw.toMutableMap()
        val target = op.targetId?.takeIf { it.isNotBlank() }
        val command = when (val name = canonical(op.command, supported) ?: reject("BAD_COMMAND", "SKILL supports: ${SKILL_COMMANDS.keys}")) {
            "Create" -> "CreateSkill"
            "Patch" -> "PatchTextFile"
            "Delete" -> if ("path" in p) "DeleteFile" else "DeleteSkill"
            "Rename" -> "RenameSkill"
            else -> name
        }
        when (command) {
            "RenameSkill" -> if ("fromName" !in p && target != null) p["fromName"] = JsonPrimitive(target)
            "CreateSkill" -> if ("dirName" !in p) {
                // A new skill's directory defaults to its frontmatter name.
                val name = (p["skillMd"] as? JsonPrimitive)?.contentOrNull?.let(::frontmatterName) ?: target
                if (name != null) p["dirName"] = JsonPrimitive(name)
            }
            else -> if ("skillName" !in p && target != null) p["skillName"] = JsonPrimitive(target)
        }
        if (command == "PatchTextFile" && "newContent" !in p && "content" in p) p["newContent"] = p.remove("content")!!
        return decodeSealed(SkillCommand.serializer(), CreatorObjectKind.SKILL, command, JsonObject(p), SKILL_COMMANDS)
    }

    private fun frontmatterName(md: String): String? {
        if (!md.trimStart().startsWith("---")) return null
        return md.lineSequence().drop(1).takeWhile { it.trim() != "---" }
            .firstOrNull { it.trimStart().startsWith("name:") }
            ?.substringAfter("name:")?.trim()?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
    }

    private fun conversation(op: CreatorOperation, raw: JsonObject): ConversationManagementCommand {
        val supported = CONVERSATION_COMMANDS.keys.toList() + listOf("SoftDelete", "Restore", "Patch", "Delete", "Create", "Append")
        val name = canonical(op.command, supported) ?: reject("BAD_COMMAND",
            "CONVERSATION supports: ${CONVERSATION_COMMANDS.keys + listOf("SoftDelete", "Restore")}")
        val command = when (name) {
            "SoftDelete", "Delete" -> { onlyKeys(raw); ConversationMutationCommand.SoftDelete }
            "Restore" -> { onlyKeys(raw); ConversationMutationCommand.Restore }
            else -> {
                val p = raw.toMutableMap()
                (p.remove("fields") as? JsonObject)?.let { fields -> fields.forEach { (k, v) -> p.putIfAbsent(k, v) } }
                val resolved = when (name) { "Patch" -> "PatchMetadata"; "Create" -> "CreateOrdinaryConversation"; "Append" -> "AppendPlainMessage"; else -> name }
                // Plain text is the common case: {role, text} instead of {role, parts:[{type:"text", text}]}.
                if (resolved == "AppendPlainMessage" && "parts" !in p && p["text"] is JsonPrimitive) {
                    val text = p.remove("text")!!
                    p["parts"] = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive(PLAIN_TEXT_PART), "text" to text))))
                }
                decodeSealed(ConversationMutationCommand.serializer(), CreatorObjectKind.CONVERSATION, resolved, JsonObject(p), CONVERSATION_COMMANDS)
            }
        }
        if (command is ConversationMutationCommand.CreateOrdinaryConversation && op.targetId != null)
            reject("BAD_INPUT", "New conversation ids are assigned by the app; leave targetId out")
        if (command !is ConversationMutationCommand.CreateOrdinaryConversation && op.targetId.isNullOrBlank())
            reject("BAD_INPUT", "targetId (the conversation id) is required")
        return ConversationManagementCommand(op.targetId, command)
    }

    /** Polymorphic decode of a CLOSED subtype list; the discriminator comes from `command`. */
    private fun <T> decodeSealed(
        serializer: KSerializer<T>, kind: CreatorObjectKind, command: String, p: JsonObject, allowed: Map<String, String>,
    ): T {
        val serialName = allowed[command] ?: reject("BAD_COMMAND", "${kind.name} supports: ${allowed.keys.sorted()}")
        if ("type" in p) reject("BAD_INPUT", "params.type is reserved")
        return try {
            strict.decodeFromJsonElement(serializer,
                LenientEnums.normalize(JsonObject(p + ("type" to JsonPrimitive(serialName))), serializer.descriptor))
        } catch (e: SerializationException) {
            reject("BAD_INPUT", "Invalid params for $command: ${SettingsCollectionInterpreter.describeSerializationError(e)}. " +
                "Expected params: ${paramSignature(serializer, serialName)}")
        } catch (e: IllegalArgumentException) {
            reject("BAD_INPUT", "Invalid params for $command: ${e.message.orEmpty().take(300)}")
        }
    }

    private fun reject(code: String, detail: String): Nothing = throw CreatorCommandRejected(code, detail)

    /** Exact name, else a case-insensitive match. */
    private fun canonical(command: String, supported: List<String>): String? =
        supported.firstOrNull { it == command } ?: supported.firstOrNull { it.equals(command.trim(), ignoreCase = true) }

    val MODEL_SLOTS = listOf("CHAT", "FAST", "TRANSLATE", "IMAGE_GENERATION", "OCR")
    val PROMPT_SLOTS = listOf("TITLE", "TRANSLATE", "SUGGESTION", "OCR")

    private val PLAIN_TEXT_PART by lazy {
        names(ConversationMutationCommand.PlainPart.serializer(), "Text").getValue("Text")
    }

    private fun names(serializer: KSerializer<*>, vararg simple: String): Map<String, String> {
        val descriptor = serializer.descriptor
        // Sealed descriptors expose subclass serial names as the elements of their second element.
        val subtypes = descriptor.getElementDescriptor(1)
        val all = (0 until subtypes.elementsCount).map { subtypes.getElementName(it) }
        return simple.associateWith { name -> all.first { it == name || it.endsWith(".$name") } }
    }

    /** `{field: type, field?: type}` of one sealed subtype, for error messages and describe. */
    fun paramSignature(serializer: KSerializer<*>, serialName: String): String {
        val subtypes = serializer.descriptor.getElementDescriptor(1)
        val index = (0 until subtypes.elementsCount).firstOrNull { subtypes.getElementName(it) == serialName } ?: return "{}"
        val d = subtypes.getElementDescriptor(index)
        return (0 until d.elementsCount).joinToString(", ", "{", "}") { i ->
            d.getElementName(i) + (if (d.isElementOptional(i)) "?" else "") + ": " + typeName(d.getElementDescriptor(i))
        }
    }

    internal fun typeName(d: SerialDescriptor): String {
        val base = when (d.kind) {
            kotlinx.serialization.descriptors.PrimitiveKind.STRING -> "string"
            kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> "boolean"
            kotlinx.serialization.descriptors.PrimitiveKind.INT, kotlinx.serialization.descriptors.PrimitiveKind.LONG,
            kotlinx.serialization.descriptors.PrimitiveKind.SHORT, kotlinx.serialization.descriptors.PrimitiveKind.BYTE -> "integer"
            kotlinx.serialization.descriptors.PrimitiveKind.FLOAT, kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE -> "number"
            kotlinx.serialization.descriptors.SerialKind.ENUM ->
                (0 until d.elementsCount).joinToString("|", "\"", "\"") { d.getElementName(it) }
            kotlinx.serialization.descriptors.StructureKind.LIST -> "array<${typeName(d.getElementDescriptor(0))}>"
            kotlinx.serialization.descriptors.StructureKind.MAP -> "object<string,${typeName(d.getElementDescriptor(1))}>"
            kotlinx.serialization.descriptors.StructureKind.CLASS -> when {
                d.serialName.startsWith("kotlin.uuid.Uuid") -> "string(id)"
                d.elementsCount == 0 -> "object"
                else -> (0 until d.elementsCount).joinToString(", ", "{", "}") { i ->
                    d.getElementName(i) + (if (d.isElementOptional(i)) "?" else "") + ": " + typeName(d.getElementDescriptor(i))
                }
            }
            kotlinx.serialization.descriptors.PolymorphicKind.SEALED -> {
                val subtypes = d.getElementDescriptor(1)
                (0 until subtypes.elementsCount).joinToString(" | ") { i ->
                    val sub = subtypes.getElementDescriptor(i)
                    "{type:\"${sub.serialName}\"" + (0 until sub.elementsCount).joinToString("") { j ->
                        ", " + sub.getElementName(j) + (if (sub.isElementOptional(j)) "?" else "") + ": " + typeName(sub.getElementDescriptor(j))
                    } + "}"
                }
            }
            else -> if (d.serialName.contains("Uuid")) "string(id)" else "value"
        }
        return if (d.isNullable) "$base|null" else base
    }

    val SUBAGENT_COMMANDS by lazy {
        names(SubagentPresetCommand.serializer(), "CreateLibraryPreset", "PatchLibraryPreset", "DeleteLibraryPreset",
            "ReorderLibrary", "SetPresetScope", "CreateLocalPreset", "PatchLocalPreset", "DeleteLocalPreset",
            "ReorderLocalPresets")
    }
    val SKILL_COMMANDS by lazy {
        names(SkillCommand.serializer(), "CreateSkill", "CreateBuiltinOverride", "PatchTextFile", "CreateFile",
            "DeleteFile", "RenameFile", "CreateDirectory", "DeleteDirectory", "RenameDirectory", "RenameSkill", "DeleteSkill")
    }
    val CONVERSATION_COMMANDS by lazy {
        names(ConversationMutationCommand.serializer(), "PatchMetadata", "CreateOrdinaryConversation",
            "AppendPlainMessage", "EditPlainTextAsNewCandidate", "SelectCandidate", "DeleteCandidate", "PruneNode")
    }

    /** Commands per writable kind (for describe and error messages). */
    fun commandsFor(kind: CreatorObjectKind): List<String> = when (kind) {
        CreatorObjectKind.QUICK_MESSAGE, CreatorObjectKind.MODE_INJECTION, CreatorObjectKind.LOREBOOK -> SETTINGS_COMMANDS
        CreatorObjectKind.ASSISTANT -> listOf("Create", "Patch", "Delete", "Clone", "Reorder")
        CreatorObjectKind.ASSISTANT_TAG -> TAG_COMMANDS
        CreatorObjectKind.MODEL_DEFINITION -> listOf("Create", "Patch", "Delete", "Clone", "Reorder")
        CreatorObjectKind.PROVIDER -> PROVIDER_COMMANDS
        CreatorObjectKind.DEFAULT_SLOT -> listOf("SetSlot")
        CreatorObjectKind.PROMPT -> listOf("SetPrompt", "ResetPrompt")
        CreatorObjectKind.SUBAGENT_PRESET -> SUBAGENT_COMMANDS.keys.toList()
        CreatorObjectKind.SKILL -> SKILL_COMMANDS.keys.toList()
        CreatorObjectKind.CONVERSATION -> CONVERSATION_COMMANDS.keys.toList() + listOf("SoftDelete", "Restore")
        CreatorObjectKind.MCP_SERVER, CreatorObjectKind.WORKSPACE -> emptyList()
    }

    /** `{{creator_commands}}` placeholder: one line per kind (kept for customized descriptions). */
    fun catalogue(): String = CreatorObjectKind.entries.filter { it.writable }
        .joinToString("; ") { "${it.name}: ${commandsFor(it).joinToString(", ")}" } +
        ". Call creator_describe for fields and params."
}
