package me.rerere.ai.provider.providers.google

import me.rerere.ai.util.HttpException
import me.rerere.ai.util.httpFailure
import me.rerere.ai.util.parseRetryAfterMillis
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.accumulateUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.VertexServiceAccount
import me.rerere.ai.provider.providers.LEADING_USER_PLACEHOLDER
import me.rerere.ai.provider.providers.splitSystemPrompt
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.resolvedLocation
import me.rerere.ai.provider.rotationStrategyOrDefault
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.CredentialLogTag
import me.rerere.ai.util.CredentialPool
import me.rerere.ai.util.PooledCredential
import me.rerere.ai.util.apiKeyCandidates
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.util.vertexServiceAccountCandidates
import me.rerere.common.http.await
import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.apache.commons.text.StringEscapeUtils
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"
private val GEMINI_PRO_REGEX = Regex("2\\.5.*pro", RegexOption.IGNORE_CASE)
private val SWITCHABLE_LIST_MODEL_CODES = setOf(401, 403, 429, 503)

// Gemini/Vertex 工具 schema 兼容。`propertyNames` 是 JSON Schema 2020-12
// 的校验 keyword，generateContent 拒绝该字段会返回 400。但它只在「schema 节点」
// 上是 keyword；`properties`/`patternProperties`/`$defs`/`definitions`/
// `dependentSchemas`/`dependentRequired` 这类键是名映射而非 schema，映射里名为
// "propertyNames" 的字段是合法字段名，必须保留并把值继续当 schema 处理。
// `enum`/`const`/`default`/`examples`/`title`/`description` 的值是 literal data，
// 不能当 schema 递归，避免误删其中的 "propertyNames" 键。
// 注意 upstream 曾用通用 removeElements() 加 "propertyNames" 一并删，它会按任意
// 键名递归删除，正好踩中上面两类误删，这里改为上下文感知遍历。
private val SCHEMA_NAME_MAP_KEYWORDS = setOf(
    "properties",
    "patternProperties",
    "\$defs",
    "definitions",
    "dependencies",
    "dependentSchemas",
    "dependentRequired",
)

private val SCHEMA_LITERAL_KEYWORDS = setOf(
    "enum",
    "const",
    "default",
    "examples",
    "title",
    "description",
)

/** 递归移除 JSON Schema 节点上的 `propertyNames` keyword，其余键与结构保持原样。 */
private fun JsonElement.withoutPropertyNamesKeyword(): JsonElement = when (this) {
    is JsonObject -> JsonObject(buildMap {
        for ((key, value) in this@withoutPropertyNamesKeyword) {
            when {
                key == "propertyNames" -> Unit // schema keyword，删除
                key in SCHEMA_LITERAL_KEYWORDS -> put(key, value) // literal data，不递归
                key in SCHEMA_NAME_MAP_KEYWORDS && value is JsonObject -> put(
                    key,
                    // 名映射：键（含 "propertyNames" 字段名）原样保留，值按 schema 递归
                    JsonObject(value.mapValues { (_, v) -> v.withoutPropertyNamesKeyword() })
                )
                else -> put(key, value.withoutPropertyNamesKeyword())
            }
        }
    })

    is JsonArray -> JsonArray(map { it.withoutPropertyNamesKeyword() })
    else -> this
}

class GoogleProvider(
    private val client: OkHttpClient,
    private val credentialPool: CredentialPool = CredentialPool(),
) : Provider<ProviderSetting.Google> {
    private val interactionsApi by lazy { InteractionsAPI(client, credentialPool) }
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }

    // "<provider id>/<model id>" -> true: rejected with HTTP 400, skip streamed arguments for the
    // rest of the process; false: one failed streamed-arguments attempt, skip only the retry.
    private val streamedArgumentsSkips = ConcurrentHashMap<String, Boolean>()

    private fun streamedArgumentsAllowed(key: String): Boolean {
        val sticky = streamedArgumentsSkips[key] ?: return true
        if (!sticky) streamedArgumentsSkips.remove(key, false)
        return false
    }

    private fun buildUrl(
        providerSetting: ProviderSetting.Google,
        path: String,
        projectId: String = providerSetting.projectId,
        location: String = providerSetting.location,
    ): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/$projectId/locations/$location/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private sealed interface GoogleCredential {
        val label: String

        data class ServiceAccount(
            override val label: String,
            val account: VertexServiceAccount,
        ) : GoogleCredential

        data class ApiKey(
            override val label: String,
            val key: String,
        ) : GoogleCredential
    }

    private fun credentialCandidates(providerSetting: ProviderSetting.Google): List<PooledCredential<GoogleCredential>> =
        if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            providerSetting.vertexServiceAccountCandidates().map { cred ->
                PooledCredential(
                    id = cred.id,
                    displayLabel = cred.displayLabel,
                    value = GoogleCredential.ServiceAccount(cred.displayLabel, cred.value),
                )
            }
        } else {
            providerSetting.apiKeyCandidates().map { cred ->
                PooledCredential(
                    id = cred.id,
                    displayLabel = cred.displayLabel,
                    value = GoogleCredential.ApiKey(cred.displayLabel, cred.value),
                )
            }
        }

    private suspend fun buildSignedRequest(
        providerSetting: ProviderSetting.Google,
        credential: GoogleCredential,
        path: String,
        configureUrl: (HttpUrl.Builder) -> Unit = {},
        builderBlock: Request.Builder.(HttpUrl) -> Unit,
    ): Request {
        val baseUrl = when (credential) {
            is GoogleCredential.ServiceAccount -> buildUrl(
                providerSetting = providerSetting,
                path = path,
                projectId = credential.account.projectId.trim(),
                location = credential.account.resolvedLocation(providerSetting.location),
            )
            is GoogleCredential.ApiKey -> buildUrl(providerSetting = providerSetting, path = path)
        }
        val finalUrl = baseUrl.newBuilder().apply(configureUrl).build()
        val requestBuilder = Request.Builder().url(finalUrl)
        requestBuilder.builderBlock(finalUrl)
        if (credential.label.isNotBlank()) {
            requestBuilder.tag(CredentialLogTag::class.java, CredentialLogTag(credential.label))
        }
        return when (credential) {
            is GoogleCredential.ServiceAccount -> {
                val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                    serviceAccountEmail = credential.account.serviceAccountEmail.trim(),
                    privateKeyPem = StringEscapeUtils.unescapeJson(credential.account.privateKey.trim()),
                )
                requestBuilder
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
            }

            is GoogleCredential.ApiKey -> {
                if (providerSetting.vertexAI) {
                    requestBuilder
                        .url(finalUrl.newBuilder().addQueryParameter("key", credential.key).build())
                        .build()
                } else {
                    requestBuilder
                        .addHeader("x-goog-api-key", credential.key)
                        .build()
                }
            }
        }
    }

    suspend fun verifyServiceAccountToken(account: VertexServiceAccount): String =
        serviceAccountTokenProvider.fetchAccessToken(
            serviceAccountEmail = account.serviceAccountEmail.trim(),
            privateKeyPem = StringEscapeUtils.unescapeJson(account.privateKey.trim()),
        )

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val candidates = credentialCandidates(providerSetting)
            credentialPool.executeWithFailover(
                providerId = providerSetting.id.toString(),
                modelId = null,
                candidates = candidates,
                strategy = providerSetting.rotationStrategyOrDefault(),
            ) { cred ->
                val request = buildSignedRequest(
                    providerSetting = providerSetting,
                    credential = cred.value,
                    path = "models?pageSize=100",
                ) {
                    headers(providerSetting.mergeCustomHeaders())
                    get()
                }
                client.newCall(request).await().use { response ->
                    if (!response.isSuccessful) {
                        if (response.code in SWITCHABLE_LIST_MODEL_CODES) {
                            throw HttpException(
                                "Google listModels HTTP ${response.code}",
                                statusCode = response.code,
                                retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                            )
                        }
                        return@use emptyList()
                    }
                    val body = response.body?.string() ?: error("empty body")
                    Log.d(TAG, "listModels: $body")
                    val bodyObject = json.parseToJsonElement(body).jsonObject
                    val models = bodyObject["models"]?.jsonArray ?: return@use emptyList()

                    models.mapNotNull {
                        val modelObject = it.jsonObject

                        // 忽略非chat/embedding模型
                        val supportedGenerationMethods =
                            modelObject["supportedGenerationMethods"]!!.jsonArray
                                .map { method -> method.jsonPrimitive.content }
                        if ("generateContent" !in supportedGenerationMethods && "embedContent" !in supportedGenerationMethods) {
                            return@mapNotNull null
                        }

                        Model(
                            modelId = modelObject["name"]!!.jsonPrimitive.content.substringAfter("/"),
                            displayName = modelObject["displayName"]!!.jsonPrimitive.content,
                            type = if ("generateContent" in supportedGenerationMethods) ModelType.CHAT else ModelType.EMBEDDING,
                        )
                    }
                }
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        if (providerSetting.useInteractionsApi && !providerSetting.vertexAI) {
            return@withContext interactionsApi.generateText(providerSetting, messages, params)
        }
        val requestBody = buildCompletionRequestBody(messages, params)
        val encodedBody = json.encodeToString(requestBody)
        val path = if (providerSetting.vertexAI) {
            "publishers/google/models/${params.model.modelId}:generateContent"
        } else {
            "models/${params.model.modelId}:generateContent"
        }

        val candidates = credentialCandidates(providerSetting)
        credentialPool.executeWithFailover(
            providerId = providerSetting.id.toString(),
            modelId = params.model.modelId,
            candidates = candidates,
            strategy = providerSetting.rotationStrategyOrDefault(),
        ) { cred ->
            val request = buildSignedRequest(
                providerSetting = providerSetting,
                credential = cred.value,
                path = path,
            ) { url ->
                headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                configureSessionHeaders(url.toString(), params.resolvedSessionId(providerSetting))
                post(encodedBody.toRequestBody("application/json".toMediaType()))
                configureReferHeaders(providerSetting.baseUrl)
            }

            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    // Do not read error bodies: upstream errors may echo credentials.
                    throw HttpException(
                        "Google generateContent HTTP ${response.code}",
                        statusCode = response.code,
                        retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                    )
                }

                val bodyStr = response.body?.string() ?: ""
                val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

                val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: error("No candidates in response")
                val usage = GoogleUsageNormalizer().update(bodyJson["usageMetadata"] as? JsonObject).accumulateUsage()
                TextGenerationResult(
                    id = Uuid.random().toString(),
                    model = params.model.modelId,
                    message = parseMessage(candidate),
                    finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
                    usage = usage.snapshot,
                    hasCompleteUsage = usage.hasCompleteUsage,
                )
            }
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        if (providerSetting.useInteractionsApi && !providerSetting.vertexAI) {
            return interactionsApi.streamText(providerSetting, messages, params)
        }
        val streamedArgumentsKey = "${providerSetting.id}/${params.model.modelId}"
        val streamArguments = providerSetting.vertexAI && providerSetting.streamFunctionCallArguments &&
            supportsStreamedFunctionCallArguments(params) && streamedArgumentsAllowed(streamedArgumentsKey)
        val requestBody = buildCompletionRequestBody(messages, params, streamFunctionCallArguments = streamArguments)
        val encodedBody = json.encodeToString(requestBody)
        val path = if (providerSetting.vertexAI) {
            "publishers/google/models/${params.model.modelId}:streamGenerateContent"
        } else {
            "models/${params.model.modelId}:streamGenerateContent"
        }
        Log.i(TAG, "streamText: $encodedBody")

        val candidates = credentialCandidates(providerSetting)
        return credentialPool.streamWithFailover(
            providerId = providerSetting.id.toString(),
            modelId = params.model.modelId,
            candidates = candidates,
            strategy = providerSetting.rotationStrategyOrDefault(),
        ) { cred ->
            val request = buildSignedRequest(
                providerSetting = providerSetting,
                credential = cred.value,
                path = path,
                configureUrl = { it.addQueryParameter("alt", "sse") },
            ) { url ->
                headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                configureSessionHeaders(url.toString(), params.resolvedSessionId(providerSetting))
                post(encodedBody.toRequestBody("application/json".toMediaType()))
                configureReferHeaders(providerSetting.baseUrl)
            }

            streamSingleRequest(
                providerSetting = providerSetting,
                params = params,
                request = request,
                streamedArgumentsKey = streamedArgumentsKey,
                streamArguments = streamArguments,
            )
        }
    }

    private fun streamSingleRequest(
        providerSetting: ProviderSetting.Google,
        params: TextGenerationParams,
        request: Request,
        streamedArgumentsKey: String,
        streamArguments: Boolean,
    ): Flow<StreamChunk> = callbackFlow {
        var receivedEvents = false
        val responseId = Uuid.random().toString()
        val decoder = GoogleStreamDecoder(responseId, params.model.modelId, vertexAI = providerSetting.vertexAI)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            // trySendBlocking 阻塞等待通道可收, 不会在缓冲满时丢弃 delta (#1295)
            chunks.forEach { chunk ->
                trySendBlocking(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                receivedEvents = true
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(
                        TAG,
                        "Stream event failed: " + ((e as? ToolStreamProtocolException)?.code ?: "decode_error"),
                    )
                    val code = (e as? ToolStreamProtocolException)?.code
                    if (streamArguments && (decoder.toolCallRejected || code in STREAMED_ARGUMENTS_SHAPE_CODES)) {
                        // Retry once the classic way: the generation loop restarts the attempt on IOException.
                        streamedArgumentsSkips[streamedArgumentsKey] = false
                        close(StreamedArgumentsFallbackException("${e.message} ($code)", code = code))
                    } else {
                        close(e)
                    }
                    eventSource.cancel()
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                if (isClosedForSend) return
                if (streamArguments && !receivedEvents && response?.code == 400) {
                    // Blind fallback (error bodies stay unread): a real 400 fails again without the field.
                    streamedArgumentsSkips[streamedArgumentsKey] = true
                    close(StreamedArgumentsFallbackException("Vertex rejected streamed tool arguments (HTTP 400)"))
                    return
                }
                // Do not read error bodies: upstream errors may echo credentials.
                val exception = if (t == null && response != null) {
                    httpFailure(null, response) ?: Exception("Google stream failed: HTTP ${response.code}")
                } else {
                    httpFailure(t, response) ?: Exception("Google stream failed")
                }
                close(exception)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "[onClosed] 连接已关闭")
                try {
                    sendChunks(decoder.onClosed())
                    close()
                } catch (e: Throwable) {
                    val code = (e as? ToolStreamProtocolException)?.code
                    if (streamArguments && code in STREAMED_ARGUMENTS_SHAPE_CODES) {
                        streamedArgumentsSkips[streamedArgumentsKey] = false
                        close(StreamedArgumentsFallbackException("${e.message} ($code)", code = code))
                    } else {
                        close(e)
                    }
                }
            }
        }

        val eventSource = EventSources.createFactory(client)
                .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
        // trySendBlocking + 无界 buffer, 保证 SSE 回调线程不会被背压丢弃 delta (#1295)
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    /** Gemini generateContent 请求体；Antigravity（同模块）在此基础上包装改写。 */
    internal fun buildGenerateContentBody(messages: List<UIMessage>, params: TextGenerationParams): JsonObject =
        buildCompletionRequestBody(messages, params)

    @JvmOverloads
    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        streamFunctionCallArguments: Boolean = false,
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessages = messages.splitSystemPrompt().system
        if (systemMessages.isNotEmpty() && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    systemMessages.forEach { systemMessage ->
                        add(buildJsonObject {
                            put(
                                "text",
                                systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                    .joinToString { it.text })
                        })
                    }
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.topK != null) put("topK", params.topK)
            if (params.frequencyPenalty != null) put("frequencyPenalty", params.frequencyPenalty)
            if (params.presencePenalty != null) put("presencePenalty", params.presencePenalty)
            if (params.seed != null) put("seed", params.seed)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(GEMINI_PRO_REGEX)
                    val useThinkingLevel =
                        ModelRegistry.GEMINI_3_SERIES.match(params.model.modelId) ||
                            ModelRegistry.GEMINI_4.match(params.model.modelId)

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (useThinkingLevel) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (useThinkingLevel) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages)
        )

        // Client function tools and model built-in tools share the same array.
        val useFunctionTools =
            params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL)
        val useBuiltInTools = params.model.tools.any {
            it == BuiltInTools.Search || it == BuiltInTools.UrlContext
        }
        if (useFunctionTools || useBuiltInTools) {
            putJsonArray("tools") {
                if (useFunctionTools) {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    put(
                                        key = "parameters",
                                        element = json.encodeToJsonElement(tool.parameters())
                                            .removeElements(
                                                listOf(
                                                    "const",
                                                    "exclusiveMaximum",
                                                    "exclusiveMinimum",
                                                    "format",
                                                    "additionalProperties",
                                                    "enum",
                                                )
                                            )
                                            .withoutPropertyNamesKeyword()
                                    )
                                })
                            }
                        }
                    })
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
        val streamArguments = streamFunctionCallArguments && useFunctionTools
        if ((useFunctionTools && useBuiltInTools) || streamArguments) {
            put("toolConfig", buildJsonObject {
                if (useFunctionTools && useBuiltInTools) put("includeServerSideToolInvocations", true)
                if (streamArguments) {
                    put("functionCallingConfig", buildJsonObject { put("streamFunctionCallArguments", true) })
                }
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(params.customBody).also { body ->
        // 工作区禁用工具的最终 wire 保护：检查 tools[].functionDeclarations
        // 与 toolConfig.functionCallingConfig.allowedFunctionNames 的名字，
        // 命中禁用名即抛出 WorkspaceToolPolicyConflictException，不修改 body。
        params.workspaceToolPolicy?.enforceGoogleGenerateContentBody(body)
    }

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = parseMessageParts(content["parts"]?.jsonArray)

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessageParts(parts: JsonArray?): List<UIMessagePart> = buildList {
        parts.orEmpty().forEachIndexed { index, element ->
            val part = parseMessagePart(element.jsonObject, index)
            if (part !is UIMessagePart.ServerTool) {
                add(part)
                return@forEachIndexed
            }

            val existingIndex = indexOfFirst {
                it is UIMessagePart.ServerTool && it.toolCallId == part.toolCallId
            }
            if (existingIndex < 0) {
                add(part)
            } else {
                val existing = get(existingIndex) as UIMessagePart.ServerTool
                set(
                    existingIndex, existing.copy(
                        toolName = part.toolName.ifBlank { existing.toolName },
                        input = part.input ?: existing.input,
                        output = part.output ?: existing.output,
                        status = if (part.isFinished) part.status else existing.status,
                        metadata = mergeGoogleMetadata(existing.metadata, part.metadata),
                    )
                )
            }
        }
    }

    private fun parseMessagePart(jsonObject: JsonObject, index: Int): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = jsonObject.toGoogleThoughtMetadata(),
                ) else UIMessagePart.Text(
                    text = text,
                    metadata = jsonObject.toGoogleThoughtMetadata(),
                )
            }

            // Signature-only part (Gemini 4 built-in search state), see GoogleStreamDecoder.parsePart.
            jsonObject.keys == setOf("thoughtSignature") -> UIMessagePart.Reasoning(
                reasoning = "",
                createdAt = Clock.System.now(),
                finishedAt = null,
                metadata = jsonObject.toGoogleThoughtMetadata(),
            )

            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"]!!.jsonObject
                val toolCallId = functionCall["id"]?.jsonPrimitive?.contentOrNull
                    ?: Uuid.random().toString()
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = functionCall["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(functionCall["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("toolCall") -> {
                val toolCall = jsonObject["toolCall"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolCall["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = toolCall["args"],
                    status = ServerToolStatus.IN_PROGRESS,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        call = jsonObject,
                        callIndex = index,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("toolResponse") -> {
                val toolResponse = jsonObject["toolResponse"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolResponse["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolResponse["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    output = toolResponse["response"],
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        result = jsonObject,
                        resultIndex = index,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = "data:$mime;base64,$data",
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature)
                        .takeIf { thoughtSignature != null }
                        ?.toMetadata()
                )
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            val sendable = messages.splitSystemPrompt().conversation.filter { it.isValidToUpload() }
            if (sendable.firstOrNull()?.role == MessageRole.ASSISTANT) addUserMessage(UIMessage.user(LEADING_USER_PLACEHOLDER))
            sendable
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message)
                    } else {
                        addUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.flatMap { it.toGoogleParts() }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.flatMap { it.toGoogleParts() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGoogleParts(): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> toGoogleServerToolParts()
        else -> listOfNotNull(toGooglePart())
    }

    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> {
            val thoughtSignature = metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            buildJsonObject {
                put("text", text)
                thoughtSignature?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Reasoning -> {
            val thoughtSignature = metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            buildJsonObject {
                put("text", reasoning)
                put("thought", true)
                thoughtSignature?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Image -> {
            encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
            put("id", toolCallId)
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)
                put("id", toolCallId)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart() }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun UIMessagePart.ServerTool.toGoogleServerToolParts(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.GOOGLE_GENERATE_CONTENT) {
            return emptyList()
        }

        return buildList {
            metadata?.call?.let(::add)
            metadata?.result?.let(::add)
        }
    }

    private fun JsonObject.toGoogleThoughtMetadata() =
        this["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let {
            GoogleThoughtMetadata(thoughtSignature = it).toMetadata()
        }

    private fun mergeGoogleMetadata(first: JsonObject?, second: JsonObject?): JsonObject? = when {
        first == null -> second
        second == null -> first
        else -> JsonObject(first + second)
    }
}

/** Vertex streams function-call arguments for Gemini 3 and later, except the Flash-Lite models. */
// Vertex rejects the field next to built-in tools: "Streaming function call arguments is not
// supported with Built-in tools." (HTTP 400, gemini-4-argon, 2026-11-12).
private fun supportsStreamedFunctionCallArguments(params: TextGenerationParams): Boolean {
    val modelId = params.model.modelId
    return params.tools.isNotEmpty() && params.model.abilities.contains(ModelAbility.TOOL) &&
        (ModelRegistry.GEMINI_3_SERIES.match(modelId) || ModelRegistry.GEMINI_4.match(modelId)) &&
        !modelId.contains("lite", ignoreCase = true) &&
        params.model.tools.none { it == BuiltInTools.Search || it == BuiltInTools.UrlContext }
}

/**
 * Streamed-argument shape anomalies, plus a server-rejected tool call (decoder.toolCallRejected), are
 * retried without streamed arguments. Output-limit stops (MAX_TOKENS) and disconnects are not: a classic
 * retry would hit the same limit.
 */
private val STREAMED_ARGUMENTS_SHAPE_CODES = setOf(
    "google_unsupported_partial_args",
    "google_orphan_partial_args",
    "google_interleaved_tool_call",
    "google_duplicate_partial_args",
    // STOP arrived without the closing functionCall:{} (seen in the field, not reproduced)
    UNTERMINATED_STREAMED_ARGS_CODE,
)

/** An IOException so the generation loop's network retry restarts the attempt without streamed arguments. */
class StreamedArgumentsFallbackException(
    message: String,
    val code: String? = null,
) : IOException("Vertex streamed tool arguments failed, retrying without them: $message")
