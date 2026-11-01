package me.rerere.rikkihub.ui.components.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

internal const val MODEL_LIST_COLLAPSED_PROVIDERS_KEY = "model_list_collapsed_providers"

internal fun decodeModelListCollapsedProviders(value: String?): Set<Uuid> =
    value.orEmpty().split(',').mapNotNull { entry ->
        runCatching { Uuid.parse(entry.trim()) }.getOrNull()
    }.toSet()

internal fun encodeModelListCollapsedProviders(ids: Set<Uuid>): String =
    ids.map { it.toString() }.sorted().joinToString(",")

// Only this UI preference's read-modify-write is serialized. apply() publishes to
// SharedPreferences memory before returning, so even a lagging UI flow is safe.
private val modelListCollapseLock = Any()

internal fun toggleModelListProviderCollapse(
    providerId: Uuid,
    searchKeywords: String,
    readLatest: () -> String?,
    write: (String) -> Unit,
) {
    if (searchKeywords.isNotBlank()) return
    synchronized(modelListCollapseLock) {
        val latest = decodeModelListCollapsedProviders(readLatest())
        write(encodeModelListCollapsedProviders(
            if (providerId in latest) latest - providerId else latest + providerId
        ))
    }
}

internal data class ModelListProviderProjection(
    val providerId: Uuid,
    val headerIndex: Int,
    val collapsed: Boolean,
    val matchingModels: List<Model>,
    val visibleModels: List<Model>,
)

internal data class ModelListVisibilityProjection(
    val sections: List<ModelListProviderProjection>,
    val favoriteModelIds: List<Uuid>,
    val prefixItemCount: Int,
) {
    val providerPositions: Map<Uuid, Int> = sections.associate { it.providerId to it.headerIndex }
    val favoriteStartIndex: Int = prefixItemCount + if (favoriteModelIds.isEmpty()) 0 else 1

    fun selectedIndex(modelId: Uuid?): Int {
        if (modelId == null) return 0
        val favoriteIndex = favoriteModelIds.indexOf(modelId)
        if (favoriteIndex >= 0) return favoriteStartIndex + favoriteIndex
        sections.forEach { section ->
            val index = section.matchingModels.indexOfFirst { it.id == modelId }
            if (index >= 0) return section.headerIndex + if (section.collapsed) 0 else 1 + index
        }
        return 0
    }
}

// The input models have already passed the fork's type/displayName search filters.
// All rendered rows, selection and badges use this one visibility projection.
internal fun projectModelListVisibility(
    providers: List<ProviderSetting>,
    matchingModelsByProvider: Map<Uuid, List<Model>>,
    collapsedProviders: Set<Uuid>,
    searchKeywords: String,
    favoriteModelIds: List<Uuid>,
    prefixItemCount: Int = 0,
): ModelListVisibilityProjection {
    var index = prefixItemCount + if (favoriteModelIds.isEmpty()) 0 else 1 + favoriteModelIds.size
    val sections = providers.map { provider ->
        val matching = matchingModelsByProvider[provider.id].orEmpty()
        val collapsed = searchKeywords.isBlank() && provider.id in collapsedProviders
        val visible = if (collapsed) emptyList() else matching
        ModelListProviderProjection(provider.id, index, collapsed, matching, visible).also {
            index += 1 + visible.size
        }
    }
    return ModelListVisibilityProjection(sections, favoriteModelIds, prefixItemCount)
}
