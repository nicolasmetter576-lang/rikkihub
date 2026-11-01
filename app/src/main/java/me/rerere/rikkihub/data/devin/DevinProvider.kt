package me.rerere.rikkihub.data.devin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.devin.DevinApiException
import me.rerere.ai.provider.providers.devin.DevinCatalogModel
import me.rerere.ai.provider.providers.devin.DevinChatApi
import me.rerere.ai.provider.providers.devin.DevinChatRequest
import me.rerere.ai.provider.providers.devin.DevinModelCatalog
import me.rerere.ai.provider.providers.devin.DevinRemoteModel
import me.rerere.ai.provider.providers.devin.DevinStreamEvent
import me.rerere.ai.provider.providers.devin.DevinUsage
import me.rerere.ai.provider.providers.devin.buildDevinMessages
import me.rerere.ai.provider.providers.devin.mergeFrom
import me.rerere.ai.provider.providers.devin.resolveDevinChatModelUid
import me.rerere.ai.provider.providers.devin.toTokenUsage
import me.rerere.ai.ui.DevinReasoningMetadata
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.toMetadata
import java.io.ByteArrayOutputStream
import java.util.Base64

class DevinProvider(
    private val chatApi: DevinChatApi,
    private val repository: DevinAccountRepository,
) : Provider<ProviderSetting.Devin> {

    /**
     * 内置 catalog 为权威目录 (id / display_name / modalities / thinking.levels);
     * 远端 GetCliModelConfigs 只补充 catalog 中没有的 chat_model_uid.
     * 拉目录不耗配额, 配额用尽的账户照样能拉. 无账户或远端失败 → 纯 catalog.
     */
    override suspend fun listModels(providerSetting: ProviderSetting.Devin): List<Model> =
        withContext(Dispatchers.IO) {
            val catalogModels = DevinModelCatalog.models.map { it.toModel() }
            val account = runCatching { repository.acquireAccount() }.getOrNull()
            val remote = if (account != null) {
                runCatching {
                    chatApi.getCliModelConfigs(
                        sessionToken = account.sessionToken,
                        deviceSeed = providerSetting.deviceSeed,
                        clientVersion = providerSetting.clientVersion,
                        customHeaders = providerSetting.mergeCustomHeaders(),
                    )
                }.onFailure {
                    if ((it as? DevinApiException)?.statusCode == 401) {
                        repository.markInvalid(account.id)
                    }
                }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
            disambiguateDevinDisplayNames(catalogModels + remoteCatalogExtras(catalogModels, remote))
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Devin,
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
        providerSetting: ProviderSetting.Devin,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val modelId = params.model.modelId
        val firstAccount = repository.acquireAccount(modelId)
        // params.toolWireRenames：上游保留名（当前实测 `bash`）在装配层已
        // 改名发出，历史里的调用名/结果归属名按同一映射回放，保持声明
        // 与历史一致，避免 MCP 配置校验再次命中保留名。
        val bundle = buildDevinMessages(
            messages,
            params.tools,
            wireToolRenames = params.toolWireRenames,
        )
        val request = DevinChatRequest(
            sessionToken = firstAccount.sessionToken,
            deviceSeed = providerSetting.deviceSeed,
            clientVersion = providerSetting.clientVersion,
            chatModelUid = resolveChatModelUid(modelId, params.reasoningLevel),
            systemPrompt = bundle.systemPrompt,
            prompts = bundle.prompts,
            tools = bundle.tools,
            temperature = params.temperature?.toDouble(),
            maxTokens = params.maxTokens ?: 0,
            sessionId = params.sessionId.orEmpty(),
            cascadeId = params.sessionId.orEmpty(),
            customHeaders = providerSetting.mergeCustomHeaders(params.customHeaders),
        )
        // 工作区禁用工具的最终 wire 保护：Devin 无 JSON body / tool_choice，
        // 这里在 GetChatMessage 编码发出之前检查 Field 10 的工具名；命中禁用
        // 名即抛 WorkspaceToolPolicyConflictException，不修改请求。
        params.workspaceToolPolicy?.enforceDevinChatTools(request.tools)
        return flow {
            var reasoningOpen = false
            var textOpen = false
            var lastOutputId = ""
            var lastStopReason = 0L
            var lastModelName = ""
            var accumulatedUsage: DevinUsage? = null
            val signature = ByteArrayOutputStream()
            var signatureType = ""
            // 工具调用的名称消歧/身份账本收敛在 assembler; 注册表为本轮实际
            // 下发的工具名, streamKey 与上游 outputId 无关、响应内稳定
            val toolCallAssembler = DevinToolCallAssembler(
                knownToolNames = params.tools.mapTo(mutableSetOf()) { it.name },
            )

            suspend fun closeReasoning() {
                if (!reasoningOpen) return
                reasoningOpen = false
                emit(
                    StreamChunk.ReasoningEnd(
                        id = DEVIN_REASONING_ID,
                        metadata = devinReasoningEndMetadata(
                            signature = signature.toByteArray(),
                            signatureType = signatureType,
                        ),
                    )
                )
            }

            suspend fun onEvent(event: DevinStreamEvent) {
                when (event) {
                    is DevinStreamEvent.Frame -> {
                        val frame = event.result
                        if (frame.outputId.isNotEmpty()) lastOutputId = frame.outputId
                        if (frame.stopReason != 0L) lastStopReason = frame.stopReason

                        if (frame.thinkingText.isNotEmpty()) {
                            if (!reasoningOpen) {
                                reasoningOpen = true
                                emit(StreamChunk.ReasoningStart(DEVIN_REASONING_ID))
                            }
                            emit(
                                StreamChunk.ReasoningDelta(
                                    id = DEVIN_REASONING_ID,
                                    text = frame.thinkingText,
                                )
                            )
                        }
                        if (frame.deltaSignature.isNotEmpty()) {
                            signature.write(frame.deltaSignature)
                        }
                        if (frame.deltaSignatureType.isNotEmpty()) {
                            signatureType = frame.deltaSignatureType
                        }

                        if (frame.contentText.isNotEmpty()) {
                            closeReasoning()
                            if (!textOpen) {
                                textOpen = true
                                emit(StreamChunk.TextStart(DEVIN_TEXT_ID))
                            }
                            emit(
                                StreamChunk.TextDelta(
                                    id = DEVIN_TEXT_ID,
                                    text = frame.contentText,
                                )
                            )
                        }

                        if (frame.toolCallDeltas.isNotEmpty()) {
                            closeReasoning()
                            for (chunk in toolCallAssembler.onDeltas(frame.toolCallDeltas)) {
                                emit(chunk)
                            }
                        }

                        frame.usage?.let { usage ->
                            if (usage.modelName.isNotEmpty()) lastModelName = usage.modelName
                            val merged = accumulatedUsage.mergeFrom(usage)
                            accumulatedUsage = merged
                            emit(StreamChunk.Usage(merged.toTokenUsage()))
                        }
                    }

                    is DevinStreamEvent.Eos -> {
                        closeReasoning()
                        if (textOpen) {
                            textOpen = false
                            emit(StreamChunk.TextEnd(DEVIN_TEXT_ID))
                        }
                        for (chunk in toolCallAssembler.finish()) {
                            emit(chunk)
                        }
                        emit(
                            StreamChunk.Finish(
                                finishReason = if (lastStopReason == 10L) {
                                    "tool_calls"
                                } else {
                                    "stop"
                                },
                                responseId = lastOutputId.ifBlank { null },
                                model = lastModelName.ifBlank { null },
                            )
                        )
                    }
                }
            }

            // 上游报日/周配额耗尽: 该账户的这个模型记冷却; 还没收到任何帧时
            // 换一个没试过、不在冷却中的账户重发, 没有就把上游原错误抛给用户.
            var account = firstAccount
            val triedAccounts = mutableSetOf<String>()
            var receivedEvent = false
            while (true) {
                triedAccounts += account.id
                try {
                    chatApi.streamChat(request.copy(sessionToken = account.sessionToken))
                        .collect { event ->
                            receivedEvent = true
                            onEvent(event)
                        }
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (error is DevinApiException && error.statusCode == 401) {
                        repository.markInvalid(account.id)
                    }
                    val period = devinQuotaExhaustionPeriod(error) ?: throw error
                    repository.markQuotaExhausted(account.id, modelId, period)
                    if (receivedEvent) throw error
                    account = repository.acquireRetryAccount(modelId, triedAccounts) ?: throw error
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Devin provider")
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported by the Devin provider")
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.Devin,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported by the Devin provider")
    }

    /**
     * modelId → 上游 chat_model_uid.
     * 远端独有的枚举型 UID (不在 catalog, 含大写) 会被 resolve 的小写化破坏,
     * 这类 UID 原样透传; 其余统一走 [resolveDevinChatModelUid].
     */
    internal fun resolveChatModelUid(modelId: String, reasoningLevel: ReasoningLevel): String {
        if (DEVIN_REMOTE_UID_PATTERN.matches(modelId) &&
            DevinModelCatalog.lookup(modelId) == null
        ) {
            return modelId
        }
        return resolveDevinChatModelUid(modelId, reasoningLevel)
    }

    private fun DevinCatalogModel.toModel(): Model {
        return Model(
            modelId = id,
            displayName = displayName.ifBlank { id },
            inputModalities = supportedInputModalities.mapNotNull {
                when (it.lowercase()) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }.ifEmpty { listOf(Modality.TEXT) },
            outputModalities = supportedOutputModalities.mapNotNull {
                when (it.lowercase()) {
                    "text" -> Modality.TEXT
                    "image" -> Modality.IMAGE
                    else -> null
                }
            }.ifEmpty { listOf(Modality.TEXT) },
            abilities = buildList {
                add(ModelAbility.TOOL)
                if (thinking?.levels?.isNotEmpty() == true) {
                    add(ModelAbility.REASONING)
                }
            },
        )
    }

    private fun remoteCatalogExtras(
        catalogModels: List<Model>,
        remote: List<DevinRemoteModel>,
    ): List<Model> {
        val knownIds = catalogModels.mapTo(mutableSetOf()) { it.modelId.lowercase() }
        return remote
            .filter { it.chatModelUid.isNotBlank() }
            .filter { it.chatModelUid.lowercase() !in knownIds }
            .distinctBy { it.chatModelUid.lowercase() }
            .map { remoteModel ->
                Model(
                    modelId = remoteModel.chatModelUid,
                    displayName = remoteModel.label.ifBlank { remoteModel.chatModelUid },
                    inputModalities = buildList {
                        add(Modality.TEXT)
                        if (remoteModel.multimodal) add(Modality.IMAGE)
                    },
                    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                )
            }
    }

    private companion object {
        const val DEVIN_TEXT_ID = "devin-text"
        const val DEVIN_REASONING_ID = "devin-reasoning"

        /** 远端独有 chat_model_uid 形态: MODEL_PRIVATE_3 / MODEL_CHAT_GPT_4_1_… 等 */
        val DEVIN_REMOTE_UID_PATTERN = Regex("[A-Z][A-Z0-9_]+")
    }
}

/**
 * 同名模型 (如目录里 MODEL_PRIVATE_12～15 都叫 "GPT-5.1") 在显示名后附上 id, 列表里才分得清.
 */
internal fun disambiguateDevinDisplayNames(models: List<Model>): List<Model> {
    val counts = models.groupingBy { it.displayName.lowercase() }.eachCount()
    return models.map { model ->
        if ((counts[model.displayName.lowercase()] ?: 0) > 1 && model.displayName != model.modelId) {
            model.copy(displayName = "${model.displayName} (${model.modelId})")
        } else {
            model
        }
    }
}

/** ReasoningEnd 携带 Devin signature (base64) 与 signatureType, 供回传 */
internal fun devinReasoningEndMetadata(
    signature: ByteArray,
    signatureType: String,
) = if (signature.isEmpty() && signatureType.isBlank()) {
    null
} else {
    DevinReasoningMetadata(
        signature = signature.takeIf { it.isNotEmpty() }
            ?.let { Base64.getEncoder().encodeToString(it) },
        signatureType = signatureType.ifBlank { null },
    ).toMetadata()
}
