package me.rerere.ai.registry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import java.io.InputStream

class ModelInfo(
    val inputModalities: Set<Modality>,
    val outputModalities: Set<Modality>,
    val abilities: Set<ModelAbility>,
    val contextLength: Int?,
)

/**
 * What https://models.dev knows about each model: image input/output, tool calls, reasoning and the
 * context window. The app ships a snapshot of it (`scripts/models-dev/update.py` builds it, one entry per
 * model id with the providers' records already merged) and never downloads it.
 *
 * It only fills in what the hand-written rules in [ModelRegistry] do not cover; text is always assumed.
 */
class ModelsDevCatalog private constructor(
    private val entries: Map<String, ModelInfo>,
) {
    val size: Int get() = entries.size

    fun find(modelId: String): ModelInfo? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        val name = id.substringAfterLast('/')
        // OpenRouter and friends use `:free`, `:thinking` and similar to tell channels of one model apart
        return entries[id]
            ?: entries[id.substringBefore(':')]
            ?: entries[name]
            ?: entries[name.substringBefore(':')]
    }

    companion object {
        const val FORMAT_VERSION = 1

        private const val IMAGE_INPUT = 1
        private const val IMAGE_OUTPUT = 2
        private const val TOOL_CALL = 4
        private const val REASONING = 8

        fun decode(input: InputStream): ModelsDevCatalog = decode(input.bufferedReader(Charsets.UTF_8).readText())

        fun decode(text: String): ModelsDevCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            val version = root["version"]?.jsonPrimitive?.int
            require(version == FORMAT_VERSION) { "Unsupported models.dev snapshot version: $version" }
            val entries = (root["entries"] as? JsonObject).orEmpty()
            val parsed = HashMap<String, ModelInfo>(entries.size * 2)
            for ((id, value) in entries) {
                val info = (value as? JsonArray)?.let(::decodeEntry) ?: continue
                parsed[id] = info
            }
            return ModelsDevCatalog(parsed)
        }

        private fun decodeEntry(entry: JsonArray): ModelInfo? {
            if (entry.size < 2) return null
            val flags = runCatching { entry[0].jsonPrimitive.int }.getOrNull() ?: return null
            val context = runCatching { entry[1].jsonPrimitive.int }.getOrNull() ?: 0
            return ModelInfo(
                inputModalities = modalities(imageSupported = flags and IMAGE_INPUT != 0),
                outputModalities = modalities(imageSupported = flags and IMAGE_OUTPUT != 0),
                abilities = buildSet {
                    if (flags and TOOL_CALL != 0) add(ModelAbility.TOOL)
                    if (flags and REASONING != 0) add(ModelAbility.REASONING)
                },
                contextLength = context.takeIf { it > 0 },
            )
        }

        private fun modalities(imageSupported: Boolean): Set<Modality> =
            if (imageSupported) setOf(Modality.TEXT, Modality.IMAGE) else setOf(Modality.TEXT)
    }
}
