package me.rerere.rikkihub.data.management.adapters.character

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.character.CardPatch
import me.rerere.rikkihub.data.character.CardPatchException
import me.rerere.rikkihub.data.management.adapters.NativeField
import me.rerere.rikkihub.data.management.adapters.NativeFields
import me.rerere.rikkihub.data.management.adapters.rejectInvalid
import me.rerere.rikkihub.data.management.adapters.rejectNotFound
import me.rerere.rikkihub.data.management.common.DiffLine
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.RetentionNote
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.coordinator.ManagementDomainAdapter
import me.rerere.rikkihub.data.management.coordinator.PlanContext
import me.rerere.rikkihub.data.management.coordinator.PlannedMutation
import me.rerere.rikkihub.data.management.ports.CharacterStoreOp
import me.rerere.rikkihub.data.management.ports.ManagementCommand
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.tavern.card.CharacterCard
import kotlin.uuid.Uuid

/** Read-port collection owned by the Room character projection. */
object CharacterCollections {
    const val CARDS = "room.characters"
}

enum class CharacterAction { CREATE, PATCH }

/** One typed character command (create a character, or change fields of one). */
@Serializable
data class CharacterManagementCommand(
    val action: CharacterAction,
    /** Null for [CharacterAction.CREATE]: the host allocates the id. */
    val characterId: String? = null,
    /** Card fields, as [CardPatch] accepts them. */
    val fields: JsonObject = JsonObject(emptyMap()),
    /** The world books tied to the character besides the one from the card; null leaves them as they are. */
    val extraBookIds: List<String>? = null,
    /** Unties the world book that came with the card. */
    val detachCardBook: Boolean = false,
) : ManagementCommand {
    override val domain: ManagementDomain get() = ManagementDomain.CHARACTER
    override fun affectedRefs(): List<ObjectRef> = listOfNotNull(characterId?.let { ObjectRef(domain, it) })
    override fun risk(): RiskLevel = RiskLevel.MEDIUM
}

/**
 * The character as the creator sees it: the writable fields at the top level (the card's text fields, tags,
 * alternate greetings, depth prompt, favourite, and the world books tied to the character), context that cannot be
 * written under `readOnly`.
 */
internal object CharacterNative {
    const val MAX_BOOKS = 64

    val FIELDS: List<NativeField> =
        CardPatch.TEXT_FIELDS.map { key ->
            NativeField(key, "string", when (key) {
                "name" -> "the character's name ({{char}})"
                "description" -> "who the character is (sent at the Character Description marker of an assistant's prompt layout)"
                "personality" -> "short personality summary (Character Personality marker)"
                "scenario" -> "the setting (Scenario marker)"
                "first_mes" -> "the greeting a new chat starts with"
                "mes_example" -> "example dialogue, blocks separated by <START>"
                "creator_notes" -> "notes for readers of the card; not sent to the model"
                "system_prompt" -> "replaces the assistant's main prompt in chats with this character (empty = keep the assistant's)"
                "post_history_instructions" -> "replaces the assistant's post-history instructions (empty = keep)"
                "creator" -> "author of the card"
                "character_version" -> "version label of the card"
                else -> ""
            })
        } + listOf(
            NativeField("tags", "array<string>", "labels for searching; at most 100", max = 100),
            NativeField("alternate_greetings", "array<string>", "more greetings; a new chat can switch between them and first_mes", max = 200),
            NativeField("fav", "boolean", "favourite"),
            NativeField("depth_prompt", "object", "text inserted into the chat at a depth: {prompt, depth (0..10000), role: system|user|assistant}; send only the keys to change"),
            NativeField("extraBookIds", "array<string>", "LOREBOOK ids tied to this character (used only in chats with it); replaces the list. The book that came with the card is separate", max = MAX_BOOKS.toLong()),
            NativeField("embeddedBookId", "string|null", "the LOREBOOK id that came with the card; the only value that can be sent is null (untie it, the book stays)"),
        )

    private val CARD_KEYS = CardPatch.KEYS

    /** Writable view plus `readOnly`, as the Room read port returns it. */
    fun view(record: CharacterRecord): JsonObject {
        val fields = CardPatch.editableFields(record.card)
        return JsonObject(buildMap {
            put("id", JsonPrimitive(record.id.toString()))
            putAll(fields)
            put("tags", JsonArray(record.card.tags.map { JsonPrimitive(it) }))
            put("fav", JsonPrimitive(record.card.fav))
            put("extraBookIds", JsonArray(record.local.extraBookIds.map { JsonPrimitive(it.toString()) }))
            put("embeddedBookId", record.local.embeddedBookId?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
            put("readOnly", buildJsonObject {
                put("sourceName", record.sourceName)
                put("hasAvatar", record.avatarPath.isNotEmpty())
                put("assetCount", record.card.assets.size)
                put("cardRegexScripts", JsonArray(record.card.regexScripts.map {
                    buildJsonObject {
                        put("id", it.id); put("name", it.scriptName); put("findRegex", it.findRegex)
                        put("replaceString", it.replaceString); put("disabled", it.disabled)
                    }
                }))
                put("regexSource", ActiveCharacter.regexSource(record.id))
                put("revision", record.revision)
            })
        })
    }

    /** Splits a creator's `fields` into card fields and the book links; refuses anything else. */
    fun split(fields: JsonObject): Triple<JsonObject, List<String>?, Boolean> {
        if ("id" in fields) rejectInvalid("id cannot be set: a new character gets its id from the app, an existing one is named by targetId")
        NativeFields.validate("character", fields, FIELDS)
        var detach = false
        fields["embeddedBookId"]?.let { if (it !is JsonNull) rejectInvalid("embeddedBookId can only be set to null (untie the card's book); use extraBookIds for other books") else detach = true }
        val books = (fields["extraBookIds"] as? JsonArray)?.map { it.jsonPrimitive.content }
        return Triple(JsonObject(fields.filterKeys { it in CARD_KEYS }), books, detach)
    }
}

/**
 * Character domain planner: validates a command against the latest character and the world books that exist,
 * freezes the host id of a new character and states the diff. It writes nothing; the write is a [CharacterStoreOp]
 * executed by the Room gateway.
 */
class CharacterDomainAdapter : ManagementDomainAdapter {
    override val domain = ManagementDomain.CHARACTER
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    override suspend fun plan(ctx: PlanContext, command: ManagementCommand): PlannedMutation {
        require(command is CharacterManagementCommand)
        val books = command.extraBookIds
        if (books != null) {
            if (books.size > CharacterNative.MAX_BOOKS) rejectInvalid("At most ${CharacterNative.MAX_BOOKS} world books per character")
            val known = ctx.read.atomicSettingsSnapshot().settings.lorebooks.map { it.id.toString() }.toSet()
            books.firstOrNull { it !in known }?.let { rejectNotFound("World book '$it' does not exist (list LOREBOOK for ids)") }
        }
        // the same edit the commit makes, on a stand-in card, so a bad field is refused when the proposal is made
        val trial = try {
            CardPatch.apply(CharacterCard.blank(""), command.fields)
        } catch (e: CardPatchException) {
            rejectInvalid(e.message ?: "Invalid character field")
        }
        val diff = mutableListOf<DiffLine>()
        val assigned = linkedMapOf<String, String>()

        if (command.action == CharacterAction.CREATE) {
            if (command.characterId != null) rejectInvalid("New character ids are assigned by the app; leave targetId out")
            if (trial.name.isBlank()) rejectInvalid("name is required to create a character")
            if (command.detachCardBook) rejectInvalid("A new character has no card book to untie")
            val id = ctx.hostIds.newObjectId().also { assigned["character"] = it }
            val ref = ObjectRef(domain, id)
            command.fields.forEach { (key, value) -> diff += DiffLine("character[$id].$key", null, short(value)) }
            books?.let { diff += DiffLine("character[$id].extraBookIds", null, it.toString()) }
            val op = CharacterStoreOp.Create(id, command.fields, books.orEmpty(), listOf(ref))
            return PlannedMutation(ReadSet(emptyList()), listOf(op), diff, RiskLevel.MEDIUM, RetentionNote(),
                listOf(ref), false, assigned)
        }

        val id = command.characterId ?: rejectInvalid("targetId (the character id) is required")
        runCatching { Uuid.parse(id) }.getOrElse { rejectInvalid("Invalid character id") }
        val view = ctx.read.readObjectJson(CharacterCollections.CARDS, id) ?: rejectNotFound("Character unavailable")
        val token = ctx.read.objectToken(CharacterCollections.CARDS, id) ?: rejectNotFound("Character unavailable")
        val ref = ObjectRef(domain, id)
        command.fields.forEach { (key, value) ->
            val before = view[key]
            // depth_prompt names only the keys to change
            if (key == "depth_prompt" && value is JsonObject && before is JsonObject) {
                value.forEach { (k, v) -> if (before[k] != v) diff += DiffLine("character[$id].depth_prompt.$k", short(before[k]), short(v)) }
            } else if (before != value) diff += DiffLine("character[$id].$key", short(before), short(value))
        }
        books?.let {
            val now = (view["extraBookIds"] as? JsonArray)?.map { e -> e.jsonPrimitive.content }.orEmpty()
            if (now != it) diff += DiffLine("character[$id].extraBookIds", now.toString(), it.toString())
        }
        if (command.detachCardBook) {
            if (view["embeddedBookId"] is JsonNull) rejectInvalid("This character has no card book")
            diff += DiffLine("character[$id].embeddedBookId", view["embeddedBookId"]?.jsonPrimitive?.contentOrNull, null)
        }
        if (diff.isEmpty()) rejectInvalid("Patch changes nothing: every given field already has that value")
        val expected = token.revision
        val op = CharacterStoreOp.Patch(id, expected, command.fields, books, command.detachCardBook, listOf(ref))
        return PlannedMutation(ReadSet(listOf(ReadSetEntry.Object(ref, token))), listOf(op), diff, RiskLevel.MEDIUM,
            RetentionNote(), listOf(ref), false, assigned)
    }

    /** Characters are not edited back automatically: the user fixes a mistake in the app, or sends another patch. */
    override suspend fun inverse(ctx: PlanContext, command: ManagementCommand): ManagementCommand? = null

    override fun needsRecyclePayload(command: ManagementCommand): Boolean = false

    override fun decodeCommand(json: String): ManagementCommand =
        this.json.decodeFromString(CharacterManagementCommand.serializer(), json)

    private fun short(value: JsonElement?): String? = value?.toString()?.let { if (it.length > 600) it.take(600) + "…" else it }
}
