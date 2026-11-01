package me.rerere.rikkihub.data.datastore

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.mutation.ExpectedVersions
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.mutation.MutationDomain
import me.rerere.rikkihub.data.mutation.VersionToken

/** Trusted host read snapshot; private settings never become a model DTO. */
internal data class SettingsReadSnapshot(
    val settings: Settings,
    val epoch: Long,
    val objects: Map<String, VersionToken>,
    val collections: Map<String, Long>,
)

/** Lives in the SAME Preferences transaction as the business data. */
@Serializable
internal data class SettingsLedger(
    val objects: Map<String, SettingsLedgerEntry> = emptyMap(),
    val collections: Map<String, Long> = emptyMap(),
    val commitRevision: Long = 0,
    val receipts: Map<String, SettingsOperationReceipt> = emptyMap(),
) {
    fun token(key: String, epoch: Long): VersionToken? = objects[key]
        ?.takeUnless { it.deleted }
        ?.let { VersionToken(MutationDomain.SETTINGS, key, epoch, it.generation, it.revision) }

    fun assertExpected(epoch: Long, expected: ExpectedVersions) {
        expected.objects.forEach { (key, wanted) ->
            if (wanted.domain != MutationDomain.SETTINGS || wanted.objectKey != key || token(key, epoch) != wanted) {
                throw MutationConflictException("settings object version changed: $key")
            }
        }
        expected.collections.forEach { (key, wanted) ->
            if ((collections[key] ?: 0) != wanted) {
                throw MutationConflictException("settings collection version changed: $key")
            }
        }
    }

    /** Existing legacy objects have a stable revision-zero baseline, not a missing token. */
    fun seed(settings: Settings): SettingsLedger = copy(
        objects = objects + settingsObjects(settings).keys.filterNot(objects::containsKey)
            .associateWith { SettingsLedgerEntry() },
        collections = collections + settingsCollections(settings).keys.filterNot(collections::containsKey)
            .associateWith { 0L },
    )

    fun changed(before: Settings, after: Settings): SettingsLedger {
        val oldObjects = settingsObjects(before)
        val newObjects = settingsObjects(after)
        val nextObjects = objects.toMutableMap()
        for (key in oldObjects.keys + newObjects.keys) {
            val old = oldObjects[key]
            val new = newObjects[key]
            val entry = nextObjects[key] ?: SettingsLedgerEntry()
            nextObjects[key] = when {
                old == new -> entry
                new == null -> entry.copy(generation = entry.generation + 1, revision = 0, deleted = true)
                old == null -> SettingsLedgerEntry(
                    generation = entry.generation + if (entry.deleted) 1 else 0,
                    revision = 1,
                )
                else -> entry.copy(revision = entry.revision + 1, deleted = false)
            }
        }
        val oldCollections = settingsCollections(before)
        val newCollections = settingsCollections(after)
        val nextCollections = collections.toMutableMap()
        for ((key, value) in newCollections) {
            if (oldCollections[key] != value) nextCollections[key] = (nextCollections[key] ?: 0) + 1
        }
        return copy(objects = nextObjects, collections = nextCollections, commitRevision = commitRevision + 1)
    }
}

@Serializable
internal data class SettingsLedgerEntry(
    val generation: Long = 0,
    val revision: Long = 0,
    val deleted: Boolean = false,
)

@Serializable
internal data class SettingsOperationReceipt(
    val operationId: String,
    val requestFingerprint: String,
    val appliedAtMillis: Long,
    val epoch: Long,
    val afterTokens: Map<String, String>,
    val afterCollections: Map<String, Long> = emptyMap(),
)

internal fun settingsObjects(settings: Settings): Map<String, Any?> = buildMap {
    settings.assistants.forEach { put("assistant:${it.id}", it) }
    settings.providers.forEach { provider ->
        put("provider:${provider.id}", provider)
        provider.models.forEach { put("model:${it.id}", it) }
    }
    settings.lorebooks.forEach { put("lorebook:${it.id}", it) }
    settings.modeInjections.forEach { put("modeInjection:${it.id}", it) }
    settings.quickMessages.forEach { put("quickMessage:${it.id}", it) }
    settings.mcpServers.forEach { put("mcpServer:${it.id}", it) }
    settings.assistantTags.forEach { put("assistantTag:${it.id}", it) }
    settings.searchServices.forEach { put("searchService:${it.id}", it) }
    settings.asrProviders.forEach { put("asrProvider:${it.id}", it) }
    settings.ttsProviders.forEach { put("ttsProvider:${it.id}", it) }
    put("setting:chatModelId", settings.chatModelId)
    put("setting:fastModelId", settings.fastModelId)
    put("setting:translateModeId", settings.translateModeId)
    put("setting:imageGenerationModelId", settings.imageGenerationModelId)
    put("setting:ocrModelId", settings.ocrModelId)
    put("setting:titlePrompt", settings.titlePrompt)
    put("setting:translatePrompt", settings.translatePrompt)
    put("setting:suggestionPrompt", settings.suggestionPrompt)
    put("setting:ocrPrompt", settings.ocrPrompt)
    put("setting:toolOverrides", settings.toolOverrides)
    put("setting:assistantId", settings.assistantId)
    put("setting:favoriteModels", settings.favoriteModels)
    put("setting:deletedAssistantIds", settings.deletedAssistantIds)
}

/** Predicate revisions also change when a binding changes without adding/removing an object. */
internal fun settingsCollections(settings: Settings): Map<String, List<Any?>> = buildMap {
    put("assistants", settings.assistants)
    put("providers", settings.providers)
    put("lorebooks", settings.lorebooks)
    put("modeInjections", settings.modeInjections)
    put("quickMessages", settings.quickMessages)
    put("mcpServers", settings.mcpServers)
    put("assistantTags", settings.assistantTags)
    put("searchServices", settings.searchServices)
    put("asrProviders", settings.asrProviders)
    put("ttsProviders", settings.ttsProviders)
    put("favoriteModels", settings.favoriteModels)
    put("assistants[].quickMessageIds", settings.assistants.map { it.id to it.quickMessageIds })
    put("assistants[].modeInjectionIds", settings.assistants.map { it.id to it.modeInjectionIds })
    put("assistants[].lorebookIds", settings.assistants.map { it.id to it.lorebookIds })
    put("assistants[].enabledSkills", settings.assistants.map { it.id to it.enabledSkills })
    put("assistants[].chatModelId", settings.assistants.map { it.id to it.chatModelId })
    put("assistants[].workspaceId", settings.assistants.map { it.id to it.workspaceId })
    put("defaults.modelSlots", listOf(settings.chatModelId, settings.fastModelId, settings.translateModeId,
        settings.imageGenerationModelId, settings.ocrModelId))
    put("defaults.prompts", listOf(settings.titlePrompt, settings.translatePrompt, settings.suggestionPrompt,
        settings.ocrPrompt))
    settings.providers.forEach { put("providers.${it.id}.models", it.models) }
}
