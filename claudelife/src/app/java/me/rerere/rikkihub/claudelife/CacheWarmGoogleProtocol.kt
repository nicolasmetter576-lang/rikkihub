package me.rerere.rikkihub.claudelife

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.data.datastore.findProvider

/**
 * Invalidate an old Google baseline only when its effective text protocol changes.
 * Resolve the latest selected model, including its latest providerOverwrite: the
 * captured params.model may still carry an obsolete overwrite. Credentials,
 * headers and baseUrl are deliberately not frozen or compared.
 */
internal fun cacheWarmGoogleProtocolChange(
    captured: ProviderSetting,
    modelId: kotlin.uuid.Uuid,
    settings: Settings,
): String? {
    val latestModel: Model = settings.findModelById(modelId) ?: return "model removed"
    val latest = latestModel.findProvider(settings.providers) ?: return "provider removed"
    if (captured !is ProviderSetting.Google && latest !is ProviderSetting.Google) return null
    if (captured !is ProviderSetting.Google || latest !is ProviderSetting.Google) {
        return "Google text protocol changed"
    }
    val capturedInteractions = captured.useInteractionsApi && !captured.vertexAI
    val latestInteractions = latest.useInteractionsApi && !latest.vertexAI
    return if (capturedInteractions != latestInteractions) "Google text protocol changed" else null
}
