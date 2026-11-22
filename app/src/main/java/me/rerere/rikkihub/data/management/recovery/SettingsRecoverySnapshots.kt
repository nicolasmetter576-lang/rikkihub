package me.rerere.rikkihub.data.management.recovery

import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.*
import kotlin.uuid.Uuid

/** PRIVATE raw images. Never use this helper for model/read/diff/Web projection. */
internal object SettingsRecoverySnapshots {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val collections = mapOf(
        "settings.quickMessages" to "quickMessages", "settings.modeInjections" to "promptLibrary",
        "settings.lorebooks" to "lorebooks", "settings.assistants" to "assistants",
        "settings.assistantTags" to "assistantTags")
    private val scalars = mapOf(
        "settings.defaults.slot.CHAT" to "chatModelId", "settings.defaults.slot.FAST" to "fastModelId",
        "settings.defaults.slot.TRANSLATE" to "translateModeId", "settings.defaults.slot.IMAGE_GENERATION" to "imageGenerationModelId",
        "settings.defaults.slot.OCR" to "ocrModelId",
        "settings.defaults.prompt.TITLE" to "titlePrompt", "settings.defaults.prompt.TRANSLATE" to "translatePrompt",
        "settings.defaults.prompt.SUGGESTION" to "suggestionPrompt", "settings.defaults.prompt.OCR" to "ocrPrompt")

    private val providerList = kotlinx.serialization.builtins.ListSerializer(ProviderSetting.serializer())

    /** 嵌套集合（世界书条目、助手预设条目与正则、全局正则、全局酒馆设置）的镜像取父集合：条目在世界书里、预设条目在助手里、全局设置在 `tavern`。 */
    private val nested = Regex("^settings\\.(lorebooks|assistants)\\.[0-9a-fA-F-]{36}\\.(entries|presetEntries|regexScripts)$")
    private fun parentOf(target: String): String? = when {
        target == "settings.tavern" || target == "settings.tavern.regexScripts" -> "tavern"
        else -> nested.matchEntire(target)?.let { if (it.groupValues[1] == "lorebooks") "lorebooks" else "assistants" }
    }

    fun capture(settings: Settings, targets: Set<String>): JsonObject {
        val raw = json.encodeToJsonElement(Settings.serializer(), settings).jsonObject
        return JsonObject(targets.associateWith { target ->
            when {
                target in collections -> raw.getValue(collections.getValue(target))
                parentOf(target) != null -> raw.getValue(parentOf(target)!!)
                target in scalars -> raw.getValue(scalars.getValue(target))
                target == PROVIDERS -> json.encodeToJsonElement(providerList, settings.providers)
                else -> {
                    val id = modelProviderId(target)
                    // The models of a provider created in the same transaction: the provider list restores them.
                    val provider = settings.providers.firstOrNull { it.id == id } ?: return@associateWith JsonNull
                    json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Model.serializer()), provider.models)
                }
            }
        })
    }

    fun restore(current: Settings, snapshot: JsonObject): Settings {
        val raw = json.encodeToJsonElement(Settings.serializer(), current).jsonObject.toMutableMap()
        var providers = snapshot[PROVIDERS]?.let { json.decodeFromJsonElement(providerList, it) } ?: current.providers
        snapshot.forEach { (target, value) -> when {
            target in collections -> raw[collections.getValue(target)] = value
            parentOf(target) != null -> raw[parentOf(target)!!] = value
            target in scalars -> raw[scalars.getValue(target)] = value
            target == PROVIDERS || value is JsonNull -> Unit
            else -> {
                val id = modelProviderId(target)
                require(providers.any { it.id == id }) { "Original provider no longer exists" }
                val models = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Model.serializer()), value)
                providers = providers.map { if (it.id == id) it.copyProvider(models = models) else it }
            }
        } }
        return json.decodeFromJsonElement(Settings.serializer(), JsonObject(raw)).copy(providers = providers,
            readEpoch = current.readEpoch, readRevision = current.readRevision)
    }

    private const val PROVIDERS = "settings.providers"

    private fun modelProviderId(target: String): Uuid = Uuid.parse(
        Regex("settings\\.providers\\.([0-9a-fA-F-]{36})\\.models").matchEntire(target)
            ?.groupValues?.get(1) ?: throw IllegalArgumentException("Unknown recovery target"))
}
