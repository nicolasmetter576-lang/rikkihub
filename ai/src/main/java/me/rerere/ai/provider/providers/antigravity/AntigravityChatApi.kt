package me.rerere.ai.provider.providers.antigravity

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.google.GoogleStreamDecoder
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.HttpException
import me.rerere.ai.util.json
import me.rerere.ai.util.parseRetryAfterMillis
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.uuid.Uuid

private const val TAG = "AntigravityChatApi"

/** OAuth token 响应（交换与刷新共用；刷新时 refresh_token 可能缺省）。 */
data class AntigravityTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long,
)

/** 一次生成请求。[toolSchemas] 是工具原始 schema（按名字），Claude 目标用来剥离占位参数。 */
data class AntigravityStreamRequest(
    val accessToken: String,
    val body: JsonObject,
    val modelId: String,
    val customHeaders: Headers = Headers.headersOf(),
    /** 用户在设置里填的版本；空/null = 自动。 */
    val userAgentVersion: String? = null,
    val toolSchemas: Map<String, JsonElement> = emptyMap(),
)

/**
 * Antigravity（Google Cloud Code 私有接口）HTTP 层，移植自 CPA：
 * `internal/auth/antigravity`、`sdk/auth/antigravity.go`、`internal/runtime/executor/antigravity_*`。
 * 只管协议，不管账户与轮询（在 app 侧 `AntigravityProvider`/仓库里）。
 */
class AntigravityChatApi(
    private val client: OkHttpClient,
    private val baseUrl: String = BASE_URL_DAILY,
    private val controlBaseUrl: String = BASE_URL_PROD,
    private val oauthTokenUrl: String = TOKEN_URL,
    private val userInfoUrl: String = USER_INFO_URL,
    val version: AntigravityVersion = AntigravityVersion(),
) {
    private val google by lazy { GoogleProvider(client) }

    /**
     * 完整请求体：历史签名按模型族预处理 → GoogleProvider 构建 Gemini 请求 → Antigravity 改写；
     * 工作区禁用工具的 wire 保护在改写后的 `request` 上再查一次。
     */
    fun buildGenerateBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
        projectId: String,
        sourceModelIdOf: (UIMessage) -> String?,
    ): JsonObject {
        val modelId = params.model.modelId
        val prepared = prepareAntigravityHistory(messages, modelId, sourceModelIdOf)
        val gemini = google.buildGenerateContentBody(prepared, params)
        val body = buildAntigravityRequest(
            gemini,
            AntigravityRequestContext(
                modelId = modelId,
                projectId = projectId,
                tools = if (params.model.abilities.contains(ModelAbility.TOOL)) params.tools else emptyList(),
                reasoningEnabled = params.model.abilities.contains(ModelAbility.REASONING),
                reasoningLevel = params.reasoningLevel,
                maxTokens = params.maxTokens,
                sessionSeed = params.sessionId,
            ),
        )
        (body["request"] as? JsonObject)?.let { params.workspaceToolPolicy?.enforceGoogleGenerateContentBody(it) }
        return body
    }

    fun userAgent(override: String? = null): String =
        AntigravityVersion.userAgent(override?.takeIf { it.isNotBlank() } ?: version.current())

    suspend fun refreshVersionIfStale() {
        if (version.isStale()) version.refresh(client)
    }

    // ---- OAuth ----

    fun buildAuthUrl(state: String, redirectUri: String): String =
        AUTH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("access_type", "offline")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("prompt", "consent")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("scope", SCOPES.joinToString(" "))
            .addQueryParameter("state", state)
            .build()
            .toString()

    suspend fun exchangeCode(code: String, redirectUri: String): AntigravityTokens =
        postTokenForm(
            FormBody.Builder()
                .add("code", code)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build(),
            userAgent = null,
            what = "token exchange",
        )

    /** 刷新失败抛 [HttpException]（带状态码，调用方据此判断是否作废账户）。 */
    suspend fun refreshToken(refreshToken: String): AntigravityTokens =
        postTokenForm(
            FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .build(),
            // 官方客户端用 Go 默认 UA 刷新 token
            userAgent = "Go-http-client/2.0",
            what = "token refresh",
        )

    private suspend fun postTokenForm(form: FormBody, userAgent: String?, what: String): AntigravityTokens =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(oauthTokenUrl)
                .post(form)
                .apply { userAgent?.let { header("User-Agent", it) } }
                .build()
            client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw HttpException(
                        "Antigravity $what failed: ${response.code} ${body.take(300)}",
                        response.code,
                    )
                }
                parseTokenResponse(body)
            }
        }

    suspend fun fetchUserEmail(accessToken: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(userInfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", userAgent())
            .get()
            .build()
        client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw HttpException("Antigravity userinfo failed: ${response.code} ${body.take(300)}", response.code)
            }
            (json.parseToJsonElement(body) as? JsonObject)?.get("email")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: error("Antigravity userinfo response is missing the email")
        }
    }

    /** loadCodeAssist 取项目；没有就 onboardUser 轮询（CPA `FetchProjectID`）。 */
    suspend fun fetchProjectId(accessToken: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("metadata", buildJsonObject { put("ideType", "ANTIGRAVITY") })
        }
        val request = Request.Builder()
            .url("$controlBaseUrl/v1internal:loadCodeAssist")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "*/*")
            .header("User-Agent", userAgent())
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val loadResponse = client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw HttpException("Antigravity loadCodeAssist failed: ${response.code} ${text.take(300)}", response.code)
            }
            json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
        }
        extractProjectId(loadResponse)?.let { return@withContext it }
        onboardUser(accessToken, defaultTierId(loadResponse))
    }

    private suspend fun onboardUser(accessToken: String, tierId: String): String {
        val agent = userAgent() + " " + NODE_API_CLIENT_UA
        val body = buildJsonObject {
            put("tier_id", tierId)
            put("metadata", buildJsonObject {
                put("ide_type", "ANTIGRAVITY")
                put("ide_version", version.current())
                put("ide_name", "antigravity")
            })
        }.toString()
        repeat(ONBOARD_ATTEMPTS) {
            val request = Request.Builder()
                .url("$baseUrl/v1internal:onboardUser")
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "*/*")
                .header("User-Agent", agent)
                .header("X-Goog-Api-Client", GOOG_API_CLIENT_UA)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val data = withTimeout(30_000) {
                client.newCall(request).await().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) {
                        throw HttpException("Antigravity onboardUser failed: ${response.code} ${text.take(200)}", response.code)
                    }
                    json.parseToJsonElement(text) as? JsonObject ?: JsonObject(emptyMap())
                }
            }
            if ((data["done"] as? JsonPrimitive)?.booleanOrNull == true) {
                return (data["response"] as? JsonObject)?.let(::extractProjectId)
                    ?: error("Antigravity onboardUser returned no project")
            }
            delay(2_000)
        }
        error("Antigravity onboardUser did not complete after $ONBOARD_ATTEMPTS attempts")
    }

    // ---- 模型 ----

    suspend fun fetchAvailableModels(
        accessToken: String,
        projectId: String,
        customHeaders: Headers = Headers.headersOf(),
        userAgentVersion: String? = null,
    ): AntigravityModelCatalog = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("project", projectId) }
        val request = Request.Builder()
            .url("$baseUrl/v1internal:fetchAvailableModels")
            .headers(customHeaders)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent(userAgentVersion))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw antigravityHttpError(response.code, text, response.header("Retry-After"))
            parseAntigravityModelCatalog(text)
        }
    }

    // ---- 生成 ----

    fun streamGenerate(request: AntigravityStreamRequest): Flow<StreamChunk> = callbackFlow {
        val url = "$baseUrl/v1internal:streamGenerateContent?alt=sse"
        val httpRequest = Request.Builder()
            .url(url)
            .headers(request.customHeaders)
            .header("Authorization", "Bearer ${request.accessToken}")
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent(request.userAgentVersion))
            .header("Accept", "text/event-stream")
            .post(request.body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val claude = AntigravityModelFamily.isClaude(request.modelId)
        val decoder = GoogleStreamDecoder(Uuid.random().toString(), request.modelId)

        fun send(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySendBlocking(chunk).onFailure { Log.w(TAG, "chunk dropped (${it?.message})") }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val payload = unwrapAntigravityEvent(data, if (claude) request.toolSchemas else null)
                        ?: return
                    val result = decoder.accept(SseEvent(id = id, event = type, data = payload))
                    send(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to handle stream event", e)
                    close(e)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val error = if (response != null && !response.isSuccessful) {
                    antigravityHttpError(
                        response.code,
                        runCatching { response.body.string() }.getOrNull().orEmpty(),
                        response.header("Retry-After"),
                    )
                } else {
                    t ?: IllegalStateException("Antigravity stream failed")
                }
                close(error)
            }

            override fun onClosed(eventSource: EventSource) {
                send(decoder.onClosed())
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    companion object {
        const val CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val CLIENT_SECRET = "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"
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val USER_INFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"
        const val BASE_URL_DAILY = "https://daily-cloudcode-pa.googleapis.com"
        const val BASE_URL_PROD = "https://cloudcode-pa.googleapis.com"
        const val DEFAULT_CALLBACK_PORT = 51121
        const val NODE_API_CLIENT_UA = "google-api-nodejs-client/10.3.0"
        const val GOOG_API_CLIENT_UA = "gl-node/22.21.1"
        private const val ONBOARD_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal fun parseTokenResponse(body: String): AntigravityTokens {
    val obj = json.parseToJsonElement(body) as? JsonObject ?: error("Invalid token response")
    val access = (obj["access_token"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("Token response is missing access_token")
    return AntigravityTokens(
        accessToken = access,
        refreshToken = (obj["refresh_token"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        expiresInSeconds = (obj["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L,
    )
}

/** CPA `extractCloudaicompanionProject`。 */
internal fun extractProjectId(data: JsonObject): String? {
    for (key in listOf("cloudaicompanionProject", "projectId", "project")) {
        when (val value = data[key]) {
            is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            is JsonObject -> (value["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            else -> Unit
        }
    }
    return null
}

/** CPA `defaultAntigravityTierID`：allowedTiers 里 isDefault 的，否则 currentTier，否则 free-tier。 */
internal fun defaultTierId(loadResponse: JsonObject): String {
    (loadResponse["allowedTiers"] as? JsonArray)?.forEach { tier ->
        val t = tier as? JsonObject ?: return@forEach
        if ((t["isDefault"] as? JsonPrimitive)?.booleanOrNull != true) return@forEach
        (t["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    ((loadResponse["currentTier"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() }?.let { return it }
    return "free-tier"
}

/**
 * 一条 SSE data → 交给 GoogleStreamDecoder 的 Gemini 事件：拆掉外层 `response`；
 * 流内 `error` 抛异常；空 keepalive 返回 null。[toolSchemas] 非空时（Claude 目标）剥离占位参数。
 */
internal fun unwrapAntigravityEvent(data: String, toolSchemas: Map<String, JsonElement>?): String? {
    if (data.isBlank()) return null
    if (data == "[DONE]") return data
    val obj = json.parseToJsonElement(data) as? JsonObject ?: return null
    (obj["error"] as? JsonObject)?.let { error ->
        val code = (error["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 400..599 } ?: 502
        throw antigravityHttpError(code, data, null)
    }
    var inner = obj["response"] as? JsonObject ?: obj
    if (inner["candidates"] == null && inner["usageMetadata"] == null && inner["promptFeedback"] == null) return null
    if (!toolSchemas.isNullOrEmpty()) inner = stripPlaceholdersInCandidates(inner, toolSchemas)
    return inner.toString()
}

private fun stripPlaceholdersInCandidates(response: JsonObject, schemas: Map<String, JsonElement>): JsonObject {
    val candidates = response["candidates"] as? JsonArray ?: return response
    fun JsonObject.put(key: String, value: JsonElement) = JsonObject(LinkedHashMap<String, JsonElement>(this).apply { put(key, value) })
    return response.put("candidates", JsonArray(candidates.map { candidate ->
        val c = candidate as? JsonObject ?: return@map candidate
        val content = c["content"] as? JsonObject ?: return@map candidate
        val parts = content["parts"] as? JsonArray ?: return@map candidate
        c.put("content", content.put("parts", JsonArray(parts.map { part ->
            val p = part as? JsonObject ?: return@map part
            val call = p["functionCall"] as? JsonObject ?: return@map part
            val name = (call["name"] as? JsonPrimitive)?.contentOrNull ?: return@map part
            val args = call["args"] ?: return@map part
            p.put("functionCall", call.put("args", AntigravitySchema.stripPlaceholderArgs(args, schemas[name])))
        })))
    }))
}

/** 非 2xx/流内错误 → [HttpException]：消息取 `error.message`，429 的等待时间取 RetryInfo/quotaResetDelay。 */
internal fun antigravityHttpError(code: Int, body: String, retryAfterHeader: String?): HttpException {
    val error = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: body.take(300).ifBlank { "HTTP $code" }
    val retryAfter = parseRetryAfterMillis(retryAfterHeader) ?: parseAntigravityRetryDelayMillis(error)
    return HttpException("Antigravity $code: $message", code, retryAfter)
}

/** CPA `ParseRetryDelay`：RetryInfo.retryDelay 优先，其次 ErrorInfo.metadata.quotaResetDelay。 */
internal fun parseAntigravityRetryDelayMillis(error: JsonObject?): Long? {
    val details = error?.get("details") as? JsonArray ?: return null
    fun typeOf(detail: JsonObject) = (detail["@type"] as? JsonPrimitive)?.contentOrNull
    details.forEach { detail ->
        val d = detail as? JsonObject ?: return@forEach
        if (typeOf(d) != "type.googleapis.com/google.rpc.RetryInfo") return@forEach
        parseGoDurationMillis((d["retryDelay"] as? JsonPrimitive)?.contentOrNull)?.let { return it }
    }
    details.forEach { detail ->
        val d = detail as? JsonObject ?: return@forEach
        if (typeOf(d) != "type.googleapis.com/google.rpc.ErrorInfo") return@forEach
        val metadata = d["metadata"] as? JsonObject ?: return@forEach
        parseGoDurationMillis((metadata["quotaResetDelay"] as? JsonPrimitive)?.contentOrNull)?.let { return it }
    }
    return null
}

/** Go `time.ParseDuration` 的常见子集：`3.5s`、`1m30s`、`250ms`、`1h2m3.4s`。 */
internal fun parseGoDurationMillis(value: String?): Long? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val pattern = Regex("""(\d+(?:\.\d+)?)(ms|us|µs|ns|h|m|s)""")
    var total = 0.0
    var consumed = 0
    for (match in pattern.findAll(text)) {
        if (match.range.first != consumed) return null
        consumed = match.range.last + 1
        val number = match.groupValues[1].toDouble()
        total += when (match.groupValues[2]) {
            "h" -> number * 3_600_000
            "m" -> number * 60_000
            "s" -> number * 1_000
            "ms" -> number
            "us", "µs" -> number / 1_000
            else -> number / 1_000_000
        }
    }
    if (consumed != text.length) return null
    return total.toLong()
}
