package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Enum values are stored under their serial names, several of them lower case (`"user"`, `"time_info"`,
 * `"workspace_default"`), while models naturally write `"USER"`. This maps every enum string (and every
 * sealed `type` discriminator) to the serial name it matches case-insensitively, following the
 * descriptor, before strict decoding. Values that match nothing are left for the decoder to reject.
 */
object LenientEnums {
    fun normalize(element: JsonElement, descriptor: SerialDescriptor): JsonElement = when {
        descriptor.kind == SerialKind.ENUM && element is JsonPrimitive && element.isString -> {
            val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
            val raw = element.content
            JsonPrimitive(names.firstOrNull { it == raw } ?: names.firstOrNull { it.equals(raw, true) }
                ?: names.firstOrNull { it.replace("_", "").equals(raw.replace("_", ""), true) } ?: raw)
        }
        descriptor.kind == StructureKind.LIST && element is JsonArray ->
            JsonArray(element.map { normalize(it, descriptor.getElementDescriptor(0)) })
        descriptor.kind == StructureKind.MAP && element is JsonObject ->
            JsonObject(element.mapValues { normalize(it.value, descriptor.getElementDescriptor(1)) })
        descriptor.kind == PolymorphicKind.SEALED && element is JsonObject -> {
            val subtypes = descriptor.getElementDescriptor(1)
            val names = (0 until subtypes.elementsCount).map { subtypes.getElementName(it) }
            val raw = (element["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val type = raw?.let { r ->
                names.firstOrNull { it == r } ?: names.firstOrNull { it.equals(r, true) }
                    ?: names.firstOrNull { it.substringAfterLast('.').equals(r, true) }
            }
            if (type == null) element else {
                val sub = subtypes.getElementDescriptor(names.indexOf(type))
                JsonObject(normalize(JsonObject(element - "type"), sub).let { it as JsonObject } + ("type" to JsonPrimitive(type)))
            }
        }
        (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) && element is JsonObject ->
            JsonObject(element.mapValues { (key, value) ->
                val index = descriptor.getElementIndex(key)
                if (index < 0) value else normalize(value, descriptor.getElementDescriptor(index))
            })
        else -> element
    }
}
