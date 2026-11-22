package me.rerere.rikkihub.ui.pages.setting.components

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.registry.ModelRegistry
import me.rerere.rikkihub.data.datastore.Settings
import kotlin.uuid.Uuid

/** 在 SettingsStore 的最新值上执行，不能在网络请求结束后写回登录开始时的提供商快照。 */
internal fun Settings.withRefreshedOAuthModels(providerId: Uuid, refreshed: List<Model>): Settings = copy(
    providers = providers.map { provider ->
        if (provider.id == providerId) {
            provider.copyProvider(models = mergeOAuthModels(provider.models, refreshed))
        } else provider
    },
)

internal fun mergeOAuthModels(existing: List<Model>, refreshed: List<Model>): List<Model> {
    val refreshedByModelId = refreshed.associateBy(Model::modelId)
    val merged = existing.map { model ->
        refreshedByModelId[model.modelId]?.let { refreshedModel ->
            model.copy(
                inputModalities = refreshedModel.inputModalities,
                outputModalities = refreshedModel.outputModalities,
                abilities = refreshedModel.abilities,
            )
        } ?: model
    }
    val existingModelIds = existing.mapTo(mutableSetOf(), Model::modelId)
    return merged + refreshed.distinctBy(Model::modelId).filterNot { it.modelId in existingModelIds }
}

/** 用户点击批量填价时才调用；不覆盖手填、部分已填或明确为 0 的价格。 */
internal fun Settings.withProviderReferencePrices(providerId: Uuid): Settings = copy(
    providers = providers.map { provider ->
        if (provider.id == providerId) {
            provider.copyProvider(models = provider.models.map { model ->
                if (model.type == ModelType.CHAT && model.pricing?.isEmpty != false) {
                    ModelRegistry.referencePrice(model.modelId)?.let { model.copy(pricing = it) } ?: model
                } else model
            })
        } else provider
    },
)
