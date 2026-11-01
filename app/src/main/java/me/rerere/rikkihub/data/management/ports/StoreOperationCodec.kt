package me.rerere.rikkihub.data.management.ports

import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.management.adapters.skills.SkillStoreOp
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetStoreOp

/** Closed discriminator table. No reflection/class names supplied by a peer. */
object StoreOperationCodec {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(op: StoreOperation): String {
        val (kind, body) = when (op) {
            is SettingsCollectionOp -> "settings.collection.v1" to json.encodeToJsonElement(SettingsCollectionOp.serializer(), op)
            is SettingsRestoreOp -> "settings.restore.v1" to json.encodeToJsonElement(SettingsRestoreOp.serializer(), op)
            is SettingsTransactionOp -> "settings.transaction.v1" to json.encodeToJsonElement(SettingsTransactionOp.serializer(), op)
            is SettingsFieldOp -> "settings.field.v1" to json.encodeToJsonElement(SettingsFieldOp.serializer(), op)
            is ConversationRefStripOp -> "room.strip-reference.v1" to json.encodeToJsonElement(ConversationRefStripOp.serializer(), op)
            is SkillStoreOp -> "files.skill.v1" to json.encodeToJsonElement(SkillStoreOp.serializer(), op)
            is SubagentPresetStoreOp -> "room.subagent.v1" to json.encodeToJsonElement(SubagentPresetStoreOp.serializer(), op)
            is ConversationCommandOp -> "room.conversation-command.v1" to json.encodeToJsonElement(ConversationCommandOp.serializer(), op)
            else -> throw IllegalArgumentException("Unregistered durable operation")
        }
        return JsonObject(mapOf("kind" to JsonPrimitive(kind), "body" to body)).toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Durable step exceeds budget" }
        }
    }

    fun decode(text: String): StoreOperation {
        require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
        val envelope = json.parseToJsonElement(text).jsonObject
        require(envelope.keys == setOf("kind", "body")) { "Invalid operation envelope" }
        val body = envelope.getValue("body")
        return when (envelope.getValue("kind").jsonPrimitive.content) {
            "settings.collection.v1" -> json.decodeFromJsonElement(SettingsCollectionOp.serializer(), body)
            "settings.restore.v1" -> json.decodeFromJsonElement(SettingsRestoreOp.serializer(), body)
            "settings.transaction.v1" -> json.decodeFromJsonElement(SettingsTransactionOp.serializer(), body).also { transaction ->
                require(transaction.operations.size in 1..64)
                require(transaction.operations.map(::decode).all { it is SettingsCollectionOp || it is SettingsFieldOp })
            }
            "settings.field.v1" -> json.decodeFromJsonElement(SettingsFieldOp.serializer(), body)
            "room.strip-reference.v1" -> json.decodeFromJsonElement(ConversationRefStripOp.serializer(), body)
            "files.skill.v1" -> json.decodeFromJsonElement(SkillStoreOp.serializer(), body)
            "room.subagent.v1" -> json.decodeFromJsonElement(SubagentPresetStoreOp.serializer(), body)
            "room.conversation-command.v1" -> json.decodeFromJsonElement(ConversationCommandOp.serializer(), body)
            else -> throw IllegalArgumentException("Unknown durable operation discriminator")
        }
    }
}
