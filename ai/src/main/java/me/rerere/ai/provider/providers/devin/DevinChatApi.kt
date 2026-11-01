package me.rerere.ai.provider.providers.devin

import me.rerere.ai.provider.providers.devin.ProtoWire.ProtoWireException
import me.rerere.ai.provider.providers.devin.ProtoWire.appendBytes
import me.rerere.ai.provider.providers.devin.ProtoWire.appendTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.EOFException

// =============================================================================
// Devin (Codeium) Connect-RPC OkHttp 传输层
// =============================================================================

/** GetChatMessage 请求参数 */
data class DevinChatRequest(
    val sessionToken: String,
    val deviceSeed: String = "",
    val clientVersion: String = DEVIN_DEFAULT_CLIENT_VERSION,
    val osName: String = "",
    val chatModelUid: String,
    val systemPrompt: String = "",
    val prompts: List<DevinPrompt> = emptyList(),
    val tools: List<DevinTool> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int = 0,
    val sessionId: String = "",
    val cascadeId: String = "",
    val customHeaders: Headers = Headers.Builder().build(),
) {
    /** 编码为 GetChatMessageRequest protobuf 字节 */
    fun buildRequestBytes(): ByteArray = buildDevinGetChatMessageRequest(
        sessionToken = sessionToken,
        deviceSeed = deviceSeed,
        chatModelUid = chatModelUid,
        systemPrompt = systemPrompt,
        prompts = prompts,
        tools = tools,
        temperature = temperature,
        maxTokens = maxTokens,
        sessionId = sessionId,
        cascadeId = cascadeId,
        osName = osName.ifEmpty { detectDevinOsName() },
        clientVersion = clientVersion,
    )
}

/** GetCliModelConfigs 返回的单个远端模型配置 */
data class DevinRemoteModel(
    val label: String = "",
    val multimodal: Boolean = false,
    val vendorId: Long = 0,
    val contextLength: Int = 0,
    val chatModelUid: String = "",
)

/** 上游返回的业务错误 (EOS trailer 或非 2xx), 带映射后的 HTTP 状态码 */
open class DevinApiException(
    /** 映射的 HTTP 状态码; -1 表示无对应状态码 (如流截断) */
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 响应流在 EOS trailer 前被截断 */
class DevinStreamTruncatedException(
    message: String = "devin stream terminated prematurely before EOS trailer",
    cause: Throwable? = null,
) : DevinApiException(-1, message, cause)

/** streamChat 发出的事件 */
sealed class DevinStreamEvent {
    /** 一个解出的数据帧 */
    data class Frame(val result: DevinFrameResult) : DevinStreamEvent()

    /** 收到 EOS trailer (无错误); payload 为 trailer 原始字节 */
    data class Eos(val payload: ByteArray) : DevinStreamEvent() {
        override fun equals(other: Any?): Boolean =
            other is Eos && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()
    }
}

/**
 * Devin Connect-RPC 客户端.
 *
 * [client] 的 UA 剥离约定: 请求里 `User-Agent` 为空值时, 由内部 network interceptor
 * 在 BridgeInterceptor 注入 okhttp 默认 UA 之后真正摘掉该头 (与 devin-cli 一致,
 * 请求线上完全没有 User-Agent); 非空 UA (如 GetCliModelConfigs 的 connect-go UA) 不受影响.
 */
class DevinChatApi(
    client: OkHttpClient,
    private val baseUrl: String = DEVIN_DEFAULT_BASE_URL,
) {
    private val client: OkHttpClient = client.newBuilder()
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            if (req.headers["User-Agent"].isNullOrEmpty()) {
                chain.proceed(req.newBuilder().removeHeader("User-Agent").build())
            } else {
                chain.proceed(req)
            }
        }
        .build()

    private fun endpoint(path: String): String = baseUrl.trimEnd('/') + path

    // ============================ streaming chat ============================

    /**
     * POST GetChatMessage, 逐帧解析 Connect-proto 响应流.
     * - EOS trailer 带错误 → 抛 [DevinApiException] (statusCode 已映射)
     * - 流在 EOS 前结束 → 抛 [DevinStreamTruncatedException]
     * - 单帧解析失败 → 跳过该帧 (与 Go executor 一致)
     */
    fun streamChat(request: DevinChatRequest): Flow<DevinStreamEvent> = flow {
        val body = wrapConnectEnvelope(request.buildRequestBytes())
            .toRequestBody("application/connect+proto".toMediaType())

        val httpRequest = Request.Builder()
            .url(endpoint(DEVIN_CHAT_PATH))
            .headers(request.customHeaders)
            .post(body)
            .header("Authorization", "Basic ${request.sessionToken}-${request.sessionToken}")
            .header("Content-Type", "application/connect+proto")
            .header("Connect-Protocol-Version", "1")
            .header("Accept", "*/*")
            // 空 UA: 触发 network interceptor 真正移除该头
            .header("User-Agent", "")
            .header("Sentry-Trace", generateDevinSentryTrace())
            .build()

        // invokeOnCompletion 在协程取消/正常结束时都会触发; call.cancel() 会中断正在阻塞的
        // execute()/流读取, 让取消即时生效而不是等下一个 ensureActive 检查点
        val call = client.newCall(httpRequest)
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching {
                    response.body.byteStream().readNBytes(1 shl 20).toString(Charsets.UTF_8)
                }.getOrDefault("")
                throw DevinApiException(
                    response.code,
                    "devin upstream error (status ${response.code}): $errBody",
                )
            }

            val stream = response.body.byteStream()
            var sawEos = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = try {
                    readConnectFrame(stream)
                } catch (e: EOFException) {
                    throw DevinStreamTruncatedException(cause = e)
                } ?: break // 干净 EOF

                if (frame.flag and CONNECT_FLAG_END_STREAM != 0) {
                    parseDevinTrailerError(frame.payload)?.let { (code, msg) ->
                        throw DevinApiException(code, msg)
                    }
                    sawEos = true
                    emit(DevinStreamEvent.Eos(frame.payload))
                    break
                }

                val parsed = runCatching { parseDevinFrame(frame.payload) }.getOrNull()
                if (parsed != null) {
                    emit(DevinStreamEvent.Frame(parsed))
                }
            }

            if (!sawEos) {
                throw DevinStreamTruncatedException()
            }
        }
    }.flowOn(Dispatchers.IO)

    // ============================ unary: GetUserStatus ============================

    /**
     * unary 调用 GetUserStatus. Connect unary 请求/响应都不包 5 字节 Connect 帧,
     * Content-Type 为 application/proto.
     */
    fun getUserStatus(sessionToken: String, deviceSeed: String): DevinUserStatus {
        val token = sessionToken.trim()
        require(token.isNotEmpty()) { "devin session token is required" }

        val body = buildDevinGetUserStatusRequest(
            sessionToken = token,
            deviceFingerprint = generateDevinDeviceFingerprint(deviceSeed),
        ).toRequestBody("application/proto".toMediaType())

        val httpRequest = Request.Builder()
            .url(endpoint(DEVIN_GET_USER_STATUS_PATH))
            .post(body)
            .header("Authorization", "Basic $token-$token")
            .header("Content-Type", "application/proto")
            .header("Connect-Protocol-Version", "1")
            .header("Accept", "*/*")
            .header("User-Agent", "")
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val respBytes = response.body.byteStream().readNBytes(4 shl 20)
            if (!response.isSuccessful) {
                throw DevinApiException(
                    response.code,
                    "devin seat management error (status ${response.code}): " +
                        respBytes.toString(Charsets.UTF_8),
                )
            }
            return parseDevinGetUserStatusResponse(respBytes)
        }
    }

    // ============================ unary: GetCliModelConfigs ============================

    /**
     * unary 调用 GetCliModelConfigs 拉取远端模型目录.
     * 请求 = field1 bytes(ClientMetadata, chat 变体);
     * 响应 = repeated f1{1=label,5=multimodal,10=vendorID,18=context_length,22=chat_model_uid}.
     */
    fun getCliModelConfigs(
        sessionToken: String,
        deviceSeed: String,
        clientVersion: String = DEVIN_DEFAULT_CLIENT_VERSION,
        osName: String = detectDevinOsName(),
        customHeaders: Headers = Headers.Builder().build(),
    ): List<DevinRemoteModel> {
        val token = sessionToken.trim()
        require(token.isNotEmpty()) { "devin session token is required" }

        val reqBytes = ByteArrayOutputStream()
        with(ProtoWire) {
            reqBytes.appendTag(1, ProtoWire.BYTES)
            reqBytes.appendBytes(
                buildDevinClientMetadataBytes(
                    sessionToken = token,
                    deviceSeed = deviceSeed,
                    osName = osName,
                    clientVersion = clientVersion,
                )
            )
        }

        val httpRequest = Request.Builder()
            .url(endpoint(DEVIN_GET_CLI_MODEL_CONFIGS_PATH))
            .headers(customHeaders)
            .post(reqBytes.toByteArray().toRequestBody("application/proto".toMediaType()))
            .header("Authorization", "Basic $token-$token")
            .header("Content-Type", "application/proto")
            .header("Connect-Protocol-Version", "1")
            // 与 CPA fetch_devin_models 一致的原样 UA
            .header("User-Agent", "connect-go/1.19.1 (go1.25.0)")
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val respBytes = response.body.byteStream().readBytes()
            if (!response.isSuccessful) {
                throw DevinApiException(
                    response.code,
                    "devin model configs error (status ${response.code}): " +
                        respBytes.toString(Charsets.UTF_8),
                )
            }
            return parseDevinCliModelConfigs(respBytes)
        }
    }

    private fun parseDevinCliModelConfigs(data: ByteArray): List<DevinRemoteModel> {
        val results = mutableListOf<DevinRemoteModel>()
        val reader = ProtoWire.Reader(data)
        while (!reader.exhausted) {
            val (num, typ) = reader.consumeTag()
            if (num == 1 && typ == ProtoWire.BYTES) {
                val model = parseDevinSingleModelConfig(reader.consumeBytes())
                if (model.chatModelUid.isNotEmpty()) {
                    results.add(model)
                }
            } else {
                reader.consumeFieldValue(num, typ)
            }
        }
        return results
    }

    private fun parseDevinSingleModelConfig(data: ByteArray): DevinRemoteModel {
        var label = ""
        var multimodal = false
        var vendorId = 0L
        var contextLength = 0
        var uid = ""
        val reader = ProtoWire.Reader(data)
        while (!reader.exhausted) {
            val (num, typ) = try {
                reader.consumeTag()
            } catch (e: ProtoWireException) {
                break
            }
            val consumed = when {
                num == 1 && typ == ProtoWire.BYTES -> {
                    label = reader.consumeBytes().toString(Charsets.UTF_8)
                    true
                }

                num == 5 && typ == ProtoWire.VARINT -> {
                    multimodal = reader.consumeVarint() == 1L
                    true
                }

                num == 10 && typ == ProtoWire.VARINT -> {
                    vendorId = reader.consumeVarint()
                    true
                }

                num == 18 && typ == ProtoWire.VARINT -> {
                    contextLength = reader.consumeVarint().toInt()
                    true
                }

                num == 22 && typ == ProtoWire.BYTES -> {
                    uid = reader.consumeBytes().toString(Charsets.UTF_8)
                    true
                }

                else -> false
            }
            if (!consumed) {
                try {
                    reader.consumeFieldValue(num, typ)
                } catch (e: ProtoWireException) {
                    break
                }
            }
        }
        return DevinRemoteModel(
            label = label,
            multimodal = multimodal,
            vendorId = vendorId,
            contextLength = contextLength,
            chatModelUid = uid,
        )
    }
}
