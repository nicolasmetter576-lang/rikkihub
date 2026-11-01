package me.rerere.rikkihub.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting

/** Null means no mutation. Keys are resolved against the latest list, never filtered UI indices. */
internal fun reorderProvidersByKey(
    providers: List<ProviderSetting>,
    searchQuery: String,
    fromKey: Any?,
    toKey: Any?,
): List<ProviderSetting>? {
    if (searchQuery.isNotBlank() || fromKey == toKey) return null
    val from = providers.indexOfFirst { it.id == fromKey }
    val to = providers.indexOfFirst { it.id == toKey }
    if (from < 0 || to < 0 || providers.map { it.id }.distinct().size != providers.size) return null
    return providers.toMutableList().apply { add(to, removeAt(from)) }
}
