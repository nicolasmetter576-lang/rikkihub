package me.rerere.tavern.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal object CardSupport {
    val json = Json { prettyPrint = false }

    fun text(name: String): String =
        CardSupport::class.java.getResource("/cards/$name")!!.readText()

    fun obj(name: String): JsonObject = json.parseToJsonElement(text(name)) as JsonObject

    fun parse(name: String): CharacterCard = CardCodec.parse(text(name)).card
}
