package me.rerere.rikkihub.data.character

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.tavern.card.CharacterCard
import me.rerere.tavern.core.PromptRole

/** A card edit that is not allowed (unknown field, wrong type, out of range). */
class CardPatchException(message: String) : IllegalArgumentException(message)

/**
 * The card fields a person or the creator assistant may edit, and how an edit is applied: only the fields named change,
 * the keys the page cannot edit (assets, talkativeness, other tools' extras) are kept as they are, and anything that is
 * not in the list is refused instead of being dropped silently.
 */
object CardPatch {
    const val MAX_FIELD_CHARS = 2_000_000
    private const val MAX_TAGS = 100
    private const val MAX_TAG_CHARS = 100
    private const val MAX_GREETINGS = 200
    private const val MAX_DEPTH = 10_000

    /** Card `data` keys that hold plain text. */
    val TEXT_FIELDS = listOf(
        "name", "description", "personality", "scenario", "first_mes", "mes_example", "creator_notes",
        "system_prompt", "post_history_instructions", "creator", "character_version",
    )

    /** Every key [apply] accepts. */
    val KEYS: Set<String> = TEXT_FIELDS.toSet() + setOf("tags", "alternate_greetings", "fav", "depth_prompt")

    /** The editable view of a card: the text fields, tags, alternate greetings and the depth prompt. */
    fun editableFields(card: CharacterCard): JsonObject = buildJsonObject {
        for (key in TEXT_FIELDS) put(key, ((card.data[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content) ?: "")
        put("alternate_greetings", JsonArray(card.alternateGreetings.map { JsonPrimitive(it) }))
        val depth = card.depthPrompt
        put("depth_prompt", buildJsonObject {
            put("prompt", depth.prompt)
            put("depth", depth.depth)
            put("role", depth.role.name.lowercase())
        })
    }

    /** Applies [patch] to [card]; throws [CardPatchException] for anything outside [KEYS] or of the wrong type. */
    fun apply(card: CharacterCard, patch: JsonObject): CharacterCard {
        var out = card
        for ((key, value) in patch) {
            if (key !in KEYS) throw CardPatchException("Unknown field: $key")
            out = when (key) {
                "tags" -> out.withData("tags", JsonArray(tags(value).map { JsonPrimitive(it) }))
                "alternate_greetings" -> out.withData("alternate_greetings", JsonArray(greetings(value).map { JsonPrimitive(it) }))
                "fav" -> out.withExtension("fav", JsonPrimitive(bool(value, "fav")))
                "depth_prompt" -> withDepthPrompt(out, value)
                else -> out.withData(key, JsonPrimitive(text(value, key)))
            }
        }
        return out
    }

    private fun text(value: JsonElement, name: String): String {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive is JsonNull || !primitive.isString) throw CardPatchException("$name must be a string")
        if (primitive.content.length > MAX_FIELD_CHARS) throw CardPatchException("$name is too long")
        return primitive.content
    }

    private fun bool(value: JsonElement, name: String): Boolean =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw CardPatchException("$name must be a boolean")

    private fun int(value: JsonElement, name: String): Int =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw CardPatchException("$name must be an integer")

    /** An array of strings, or one comma separated string (what the tag box takes); trimmed, blanks and repeats dropped. */
    private fun tags(value: JsonElement): List<String> {
        val raw = when (value) {
            is JsonArray -> value.map { text(it, "tags[]") }
            is JsonNull -> throw CardPatchException("tags must be an array of strings")
            is JsonPrimitive -> text(value, "tags").split(',', '，')
            else -> throw CardPatchException("tags must be an array of strings")
        }
        val tags = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (tags.size > MAX_TAGS) throw CardPatchException("Too many tags")
        if (tags.any { it.length > MAX_TAG_CHARS }) throw CardPatchException("A tag is too long")
        return tags
    }

    private fun greetings(value: JsonElement): List<String> {
        val array = value as? JsonArray ?: throw CardPatchException("alternate_greetings must be an array of strings")
        if (array.size > MAX_GREETINGS) throw CardPatchException("Too many alternate greetings")
        return array.map { text(it, "alternate_greetings[]") }
    }

    private fun withDepthPrompt(card: CharacterCard, value: JsonElement): CharacterCard {
        val patch = value as? JsonObject ?: throw CardPatchException("depth_prompt must be an object")
        patch.keys.firstOrNull { it !in setOf("prompt", "depth", "role") }?.let { throw CardPatchException("Unknown field: depth_prompt.$it") }
        val current = card.depthPrompt
        val prompt = patch["prompt"]?.let { text(it, "depth_prompt.prompt") } ?: current.prompt
        val depth = patch["depth"]?.let {
            int(it, "depth_prompt.depth").also { n -> if (n !in 0..MAX_DEPTH) throw CardPatchException("depth_prompt.depth must be 0..$MAX_DEPTH") }
        } ?: current.depth
        val role = patch["role"]?.let {
            when (text(it, "depth_prompt.role").lowercase()) {
                "system" -> PromptRole.SYSTEM
                "user" -> PromptRole.USER
                "assistant" -> PromptRole.ASSISTANT
                else -> throw CardPatchException("depth_prompt.role must be system, user or assistant")
            }
        } ?: current.role
        // keys of the card's own depth_prompt that this edit does not know stay
        val merged = LinkedHashMap<String, JsonElement>((card.extensions["depth_prompt"] as? JsonObject) ?: JsonObject(emptyMap()))
        merged["prompt"] = JsonPrimitive(prompt)
        merged["depth"] = JsonPrimitive(depth)
        merged["role"] = JsonPrimitive(role.name.lowercase())
        return card.withExtension("depth_prompt", JsonObject(merged))
    }
}
