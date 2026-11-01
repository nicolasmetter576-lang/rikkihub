package me.rerere.rikkihub.data.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.antigravity.AntigravityChatApi
import me.rerere.ai.provider.providers.antigravity.AntigravityStreamRequest
import me.rerere.ai.provider.providers.antigravity.schemaOf
import me.rerere.ai.provider.providers.antigravity.toModel
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.rikkihub.AppScope

/**
 * Antigravity provider：每次请求轮询取一个账户，经 [AntigravityChatApi] 发 Antigravity 包装的
 * Gemini 请求。401 作废账户；429 按响应里的等待时间（没有则 60 秒）让该账户冷却。
 * 主对话不在这里重试（GenerationLoop 的限流退避只对子代理生效，现行契约）。
 */
class AntigravityProvider(
    private val api: AntigravityChatApi,
    private val repository: AntigravityAccountRepository,
    private val scope: AppScope,
) : Provider<ProviderSetting.Antigravity> {

    override suspend fun listModels(providerSetting: ProviderSetting.Antigravity): List<Model> =
        withContext(Dispatchers.IO) {
            refreshVersionInBackground()
            val account = repository.acquireAccount()
            val catalog = try {
                api.fetchAvailableModels(
                    accessToken = account.accessToken,
                    projectId = account.projectId,
                    customHeaders = providerSetting.mergeCustomHeaders(),
                    userAgentVersion = providerSetting.clientVersion,
                )
            } catch (error: HttpException) {
                handleFailure(account.id, error)
                throw error
            }
            repository.updateQuotas(account.id, catalog)
            catalog.models.map { it.toModel() }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var response = listOf(UIMessage.user(""))
        var finish: StreamChunk.Finish? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            if (chunk is StreamChunk.Finish) finish = chunk
            response = handler.handle(response, chunk)
        }
        val message = response.last()
        return TextGenerationResult(
            id = finish?.responseId ?: message.id.toString(),
            model = params.model.modelId,
            message = message,
            finishReason = finish?.finishReason,
            usage = message.usage,
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Antigravity,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        refreshVersionInBackground()
        val modelId = params.model.modelId
        val account = repository.acquireAccount(modelId)
        val modelIdsByUuid = providerSetting.models.associate { it.id to it.modelId }
        val body = api.buildGenerateBody(messages, params, account.projectId) { message ->
            message.modelId?.let(modelIdsByUuid::get)
        }
        return api.streamGenerate(
            AntigravityStreamRequest(
                accessToken = account.accessToken,
                body = body,
                modelId = modelId,
                customHeaders = providerSetting.mergeCustomHeaders(params.customHeaders),
                userAgentVersion = providerSetting.clientVersion,
                toolSchemas = params.tools.associate { it.name to schemaOf(it) },
            )
        ).catch { error ->
            (error as? HttpException)?.let { handleFailure(account.id, it) }
            throw error
        }
    }

    private suspend fun handleFailure(accountId: String, error: HttpException) {
        when (error.statusCode) {
            401 -> repository.markInvalid(accountId)
            429 -> repository.markCooldown(
                accountId,
                System.currentTimeMillis() + (error.retryAfterMillis ?: DEFAULT_COOLDOWN_MS),
            )
        }
    }

    private fun refreshVersionInBackground() {
        if (api.version.isStale()) scope.launch(Dispatchers.IO) { api.refreshVersionIfStale() }
    }

    private companion object {
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
