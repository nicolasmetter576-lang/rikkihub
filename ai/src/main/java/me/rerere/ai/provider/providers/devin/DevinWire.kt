package me.rerere.ai.provider.providers.devin

import me.rerere.ai.provider.providers.devin.ProtoWire.ProtoWireException
import me.rerere.ai.provider.providers.devin.ProtoWire.appendBytes
import me.rerere.ai.provider.providers.devin.ProtoWire.appendFixed32
import me.rerere.ai.provider.providers.devin.ProtoWire.appendFixed64
import me.rerere.ai.provider.providers.devin.ProtoWire.appendString
import me.rerere.ai.provider.providers.devin.ProtoWire.appendTag
import me.rerere.ai.provider.providers.devin.ProtoWire.appendVarint
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.util.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

// =============================================================================
// Devin (Codeium) upstream Connect-RPC wire protocol
// Ported from CLIProxyAPI internal/runtime/executor/helps/devin_wire.go
// =============================================================================

const val CONNECT_FLAG_DATA: Int = 0x00
const val CONNECT_FLAG_COMPRESSED: Int = 0x01
const val CONNECT_FLAG_END_STREAM: Int = 0x02

const val DEVIN_DEFAULT_BASE_URL = "https://server.codeium.com"
const val DEVIN_CHAT_PATH = "/exa.api_server_pb.ApiServerService/GetChatMessage"
const val DEVIN_GET_USER_STATUS_PATH = "/exa.seat_management_pb.SeatManagementService/GetUserStatus"
const val DEVIN_GET_CLI_MODEL_CONFIGS_PATH = "/exa.api_server_pb.ApiServerService/GetCliModelConfigs"

const val DEVIN_DEFAULT_CLIENT_NAME = "chisel"
const val DEVIN_DEFAULT_CLIENT_VERSION = "3000.10.21"

/** metadata #31 必须是 732 个 hex 字符 */
const val DEVIN_FINGERPRINT_HEX_LEN = 732

const val DEVIN_DEFAULT_MAX_TOKENS = 128000

const val MAX_CONNECT_FRAME_SIZE = 16 * 1024 * 1024
const val MAX_DECOMPRESSED_FRAME_SIZE = 64 * 1024 * 1024

private val devinSecureRandom = SecureRandom()

private fun ByteArray.toHexString(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
    }
    return out.toString()
}

private fun randomUuidString(): String = UUID.randomUUID().toString()

/**
 * Go `runtime.GOOS` 的等价物: 供 ClientMetadata #5 使用.
 * devin-cli 只跑在桌面平台; Android 上按 linux 上报.
 */
internal fun detectDevinOsName(): String {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "darwin"
        else -> "linux"
    }
}

// =============================================================================
// Device fingerprint / sentry trace / session turn counter
// =============================================================================

/**
 * 生成 732 字符 hex 设备指纹.
 * seed 为空时每请求生成随机指纹 (同 devin-cli); 否则 sha256("$seed-$counter") 拼接截断.
 */
fun generateDevinDeviceFingerprint(seed: String): String {
    if (seed.isEmpty()) {
        val bytes = ByteArray(DEVIN_FINGERPRINT_HEX_LEN / 2)
        runCatching { devinSecureRandom.nextBytes(bytes) }
            .onSuccess { return bytes.toHexString() }
        return generateDevinDeviceFingerprint(randomUuidString())
    }
    val sha = MessageDigest.getInstance("SHA-256")
    val sb = StringBuilder()
    var counter = 0
    while (sb.length < DEVIN_FINGERPRINT_HEX_LEN) {
        val digest = sha.digest("$seed-$counter".toByteArray(Charsets.UTF_8))
        sb.append(digest.toHexString())
        counter++
    }
    return sb.substring(0, DEVIN_FINGERPRINT_HEX_LEN)
}

/**
 * Sentry 分布式追踪头: "<32-hex-trace-id>-<16-hex-span-id>-1"
 */
fun generateDevinSentryTrace(): String {
    val bytes = ByteArray(24)
    val ok = runCatching { devinSecureRandom.nextBytes(bytes) }.isSuccess
    if (!ok) {
        val u1 = randomUuidString().replace("-", "")
        val u2 = randomUuidString().replace("-", "").substring(0, 16)
        return "$u1-$u2-1"
    }
    val traceId = bytes.copyOfRange(0, 16).toHexString()
    val spanId = bytes.copyOfRange(16, 24).toHexString()
    return "$traceId-$spanId-1"
}

private const val MAX_SESSION_TURN_COUNTERS = 5000

/**
 * 进程内 session → 请求序号计数器, 等价 CPA 的 BoundedLRU[sessionID]*atomic.Uint64.
 * LinkedHashMap(accessOrder) + 容量上限充当简单 LRU.
 */
private val sessionTurnCounters = object : LinkedHashMap<String, AtomicLong>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AtomicLong>?): Boolean {
        return size > MAX_SESSION_TURN_COUNTERS
    }
}

/**
 * 返回该 session 下一个 0 起始的请求序号 (request Field 15.2).
 * 与 devin-cli 一致: 首个请求返回 0 (线上省略), 后续 1,2,3 递增.
 * sessionID 为空(去空白后)时恒返回 0.
 */
fun nextDevinSessionTurnIndex(sessionId: String): Int {
    val cleanId = sessionId.trim()
    if (cleanId.isEmpty()) return 0
    val counter = synchronized(sessionTurnCounters) {
        sessionTurnCounters.getOrPut(cleanId) { AtomicLong(0) }
    }
    return (counter.incrementAndGet() - 1).toInt()
}

/** 清除 session 计数器 (测试或显式重置) */
fun resetDevinSessionTurnIndex(sessionId: String) {
    synchronized(sessionTurnCounters) {
        sessionTurnCounters.remove(sessionId.trim())
    }
}

// =============================================================================
// Connect-RPC envelope framing
// =============================================================================

/**
 * 用标准 5 字节 Connect 头包装 payload: [1B flag:0x00][4B BE len][payload]
 */
fun wrapConnectEnvelope(protoBytes: ByteArray): ByteArray {
    return wrapConnectEnvelopeWithFlag(CONNECT_FLAG_DATA, protoBytes)
}

fun wrapConnectEnvelopeWithFlag(flag: Int, protoBytes: ByteArray): ByteArray {
    val out = ByteArray(5 + protoBytes.size)
    out[0] = flag.toByte()
    val len = protoBytes.size
    out[1] = (len ushr 24).toByte()
    out[2] = (len ushr 16).toByte()
    out[3] = (len ushr 8).toByte()
    out[4] = len.toByte()
    protoBytes.copyInto(out, 5)
    return out
}

/** 一帧解码结果: flag 为原始标志位, payload 已按需解压 */
data class DevinConnectFrame(
    val flag: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is DevinConnectFrame && flag == other.flag && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * flag + payload.contentHashCode()
}

private fun readFullyOrEof(input: InputStream, buf: ByteArray): Int {
    var read = 0
    while (read < buf.size) {
        val n = input.read(buf, read, buf.size - read)
        if (n < 0) break
        read += n
    }
    return read
}

/**
 * 从 Connect-proto 流中读取一帧.
 * 返回 null 表示流在第一字节前就干净结束 (EOF); 中途截断抛 [EOFException],
 * 其它非法帧抛 [ProtoWireException].
 */
fun readConnectFrame(input: InputStream): DevinConnectFrame? {
    val header = ByteArray(5)
    val headerRead = readFullyOrEof(input, header)
    if (headerRead == 0) return null
    if (headerRead < 5) {
        throw EOFException("truncated connect frame header ($headerRead/5 bytes)")
    }
    val flag = header[0].toInt() and 0xFF
    if (flag != CONNECT_FLAG_DATA &&
        flag != CONNECT_FLAG_COMPRESSED &&
        flag != CONNECT_FLAG_END_STREAM &&
        flag != (CONNECT_FLAG_COMPRESSED or CONNECT_FLAG_END_STREAM)
    ) {
        throw ProtoWireException("invalid connect frame flag: 0x%02x".format(flag))
    }
    val length = ((header[1].toLong() and 0xFF) shl 24) or
        ((header[2].toLong() and 0xFF) shl 16) or
        ((header[3].toLong() and 0xFF) shl 8) or
        (header[4].toLong() and 0xFF)
    if (length > MAX_CONNECT_FRAME_SIZE) {
        throw ProtoWireException(
            "connect frame length $length exceeds maximum limit ($MAX_CONNECT_FRAME_SIZE)"
        )
    }
    var payload = ByteArray(length.toInt())
    if (readFullyOrEof(input, payload) < payload.size) {
        throw EOFException("truncated connect frame payload")
    }

    if (flag and CONNECT_FLAG_COMPRESSED != 0) {
        val decompressed = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(payload)).use { gz ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = gz.read(chunk)
                if (n < 0) break
                decompressed.write(chunk, 0, n)
                if (decompressed.size() > MAX_DECOMPRESSED_FRAME_SIZE) {
                    throw ProtoWireException(
                        "decompressed frame size exceeds maximum limit ($MAX_DECOMPRESSED_FRAME_SIZE)"
                    )
                }
            }
        }
        payload = decompressed.toByteArray()
    }
    return DevinConnectFrame(flag, payload)
}

// =============================================================================
// GetChatMessage request encoding
// =============================================================================

/**
 * 构造 Field 1 (ClientMetadata) 的序列化字节 — GetChatMessage/GetCliModelConfigs 变体:
 * f1=f12="chisel", f2=f7=clientVersion, f3=sessionToken, f4="en", f5=osName,
 * f31=732hex 指纹 (与 CLIProxyAPI 现行实现一致: 真实 devin-cli 抓包里 f1 是
 * "chisel", 且不发 f28).
 * (GetUserStatus 用另一个变体, 见 [buildDevinGetUserStatusRequest].)
 */
fun buildDevinClientMetadataBytes(
    sessionToken: String,
    deviceSeed: String,
    osName: String = detectDevinOsName(),
    clientVersion: String = DEVIN_DEFAULT_CLIENT_VERSION,
): ByteArray {
    val deviceFingerprint = generateDevinDeviceFingerprint(deviceSeed)
    val out = ByteArrayOutputStream()
    with(ProtoWire) {
        out.appendTag(1, ProtoWire.BYTES)
        out.appendString(DEVIN_DEFAULT_CLIENT_NAME)
        out.appendTag(2, ProtoWire.BYTES)
        out.appendString(clientVersion)
        out.appendTag(3, ProtoWire.BYTES)
        out.appendString(sessionToken)
        out.appendTag(4, ProtoWire.BYTES)
        out.appendString("en")
        out.appendTag(5, ProtoWire.BYTES)
        out.appendString(osName)
        out.appendTag(7, ProtoWire.BYTES)
        out.appendString(clientVersion)
        out.appendTag(12, ProtoWire.BYTES)
        out.appendString(DEVIN_DEFAULT_CLIENT_NAME)
        out.appendTag(31, ProtoWire.BYTES)
        out.appendString(deviceFingerprint)
    }
    return out.toByteArray()
}

/**
 * 编码整个 GetChatMessageRequest protobuf payload.
 * 与 Go BuildDevinGetChatMessageRequest 逐字节等价; session turnIndex 走进程内计数器.
 */
fun buildDevinGetChatMessageRequest(
    sessionToken: String,
    deviceSeed: String,
    chatModelUid: String,
    systemPrompt: String,
    prompts: List<DevinPrompt>,
    tools: List<DevinTool>,
    temperature: Double?,
    maxTokens: Int,
    sessionId: String,
    cascadeId: String,
    osName: String = detectDevinOsName(),
    clientVersion: String = DEVIN_DEFAULT_CLIENT_VERSION,
): ByteArray {
    val effectiveMaxTokens = if (maxTokens <= 0) DEVIN_DEFAULT_MAX_TOKENS else maxTokens
    val effectiveSessionId = sessionId.ifEmpty { randomUuidString() }
    val effectiveCascadeId = cascadeId.ifEmpty { effectiveSessionId }

    val req = ByteArrayOutputStream()
    with(ProtoWire) {
        // 1. ClientMetadata (Field 1)
        val f1Bytes = buildDevinClientMetadataBytes(sessionToken, deviceSeed, osName, clientVersion)
        req.appendTag(1, ProtoWire.BYTES)
        req.appendBytes(f1Bytes)

        // 2. System prompt (Field 2)
        if (systemPrompt.isNotEmpty()) {
            val sanitized = sanitizeDevinSystemPrompt(systemPrompt)
            if (sanitized.isNotEmpty()) {
                req.appendTag(2, ProtoWire.BYTES)
                req.appendString(sanitized)
            }
        }

        // 3. Repeated History Prompts (Field 3)
        for (p in prompts) {
            val pBytes = ByteArrayOutputStream()

            val msgId = p.messageId.ifEmpty { randomUuidString() }
            pBytes.appendTag(1, ProtoWire.BYTES)
            pBytes.appendString(msgId)

            val source = if (p.source <= 0) 1 else p.source
            pBytes.appendTag(2, ProtoWire.VARINT)
            pBytes.appendVarint(source.toLong())

            pBytes.appendTag(3, ProtoWire.BYTES)
            pBytes.appendString(p.content)

            for (tc in p.toolCalls) {
                val tcBytes = ByteArrayOutputStream()
                if (tc.id.isNotEmpty()) {
                    tcBytes.appendTag(1, ProtoWire.BYTES)
                    tcBytes.appendString(tc.id)
                }
                if (tc.name.isNotEmpty()) {
                    tcBytes.appendTag(2, ProtoWire.BYTES)
                    tcBytes.appendString(tc.name)
                }
                if (tc.arguments.isNotEmpty()) {
                    tcBytes.appendTag(3, ProtoWire.BYTES)
                    tcBytes.appendString(tc.arguments)
                }
                pBytes.appendTag(6, ProtoWire.BYTES)
                pBytes.appendBytes(tcBytes.toByteArray())
            }

            if (p.toolCallId.isNotEmpty()) {
                pBytes.appendTag(7, ProtoWire.BYTES)
                pBytes.appendString(p.toolCallId)
            }

            for (img in p.images) {
                val data = img.base64Data.trim()
                if (data.isEmpty()) continue
                val imgBytes = ByteArrayOutputStream()
                imgBytes.appendTag(1, ProtoWire.BYTES)
                imgBytes.appendString(data)
                val mime = img.mimeType.trim().ifEmpty { "image/png" }
                imgBytes.appendTag(2, ProtoWire.BYTES)
                imgBytes.appendString(mime)
                pBytes.appendTag(10, ProtoWire.BYTES)
                pBytes.appendBytes(imgBytes.toByteArray())
            }

            if (p.thinking.isNotEmpty()) {
                pBytes.appendTag(11, ProtoWire.BYTES)
                pBytes.appendString(p.thinking)
            }

            if (p.signature.isNotEmpty()) {
                pBytes.appendTag(12, ProtoWire.BYTES)
                pBytes.appendBytes(p.signature)
            }

            if (p.signatureType.isNotEmpty()) {
                pBytes.appendTag(18, ProtoWire.BYTES)
                pBytes.appendString(p.signatureType)
            }

            req.appendTag(3, ProtoWire.BYTES)
            req.appendBytes(pBytes.toByteArray())
        }

        // 4. Fixed flags (Field 7: Varint 5)
        req.appendTag(7, ProtoWire.VARINT)
        req.appendVarint(5)

        // 5. Completion config (Field 8)
        val f8Bytes = ByteArrayOutputStream()
        f8Bytes.appendTag(1, ProtoWire.VARINT)
        f8Bytes.appendVarint(1)
        f8Bytes.appendTag(2, ProtoWire.VARINT)
        f8Bytes.appendVarint(effectiveMaxTokens.toLong())
        f8Bytes.appendTag(3, ProtoWire.VARINT)
        f8Bytes.appendVarint(400)
        f8Bytes.appendTag(5, ProtoWire.FIXED64)
        f8Bytes.appendFixed64(java.lang.Double.doubleToRawLongBits(temperature ?: 1.0))
        f8Bytes.appendTag(7, ProtoWire.VARINT)
        f8Bytes.appendVarint(40)
        f8Bytes.appendTag(8, ProtoWire.FIXED64)
        // Go: float64(float32(0.95)) — 先转 float32 再转回 double
        f8Bytes.appendFixed64(java.lang.Double.doubleToRawLongBits(0.95f.toDouble()))
        req.appendTag(8, ProtoWire.BYTES)
        req.appendBytes(f8Bytes.toByteArray())

        // 6. Repeated Tools (Field 10)
        for (tool in tools) {
            val tBytes = ByteArrayOutputStream()
            if (tool.name.isNotEmpty()) {
                tBytes.appendTag(1, ProtoWire.BYTES)
                tBytes.appendString(tool.name)
            }
            var desc = tool.description
            // Claude Code 子代理工具描述里的 snake_case task_id 需要改写为上游要求的 camelCase taskId
            if (desc.contains("Takes a task_id parameter identifying the task")) {
                desc = desc.replace(
                    "Takes a task_id parameter identifying the task",
                    "Takes a taskId parameter identifying the task",
                )
            }
            if (desc.isNotEmpty()) {
                tBytes.appendTag(2, ProtoWire.BYTES)
                tBytes.appendString(desc)
            }
            if (tool.parameters.isNotEmpty()) {
                tBytes.appendTag(3, ProtoWire.BYTES)
                tBytes.appendBytes(tool.parameters)
            }
            req.appendTag(10, ProtoWire.BYTES)
            req.appendBytes(tBytes.toByteArray())
        }

        // 7. Thread session metadata (Field 15)
        //   Field 1: sessionID (UUID string)
        //   Field 2: turnIndex (每 session 请求序号, 0 时省略)
        //   Field 3: 4 (varint)
        //   Field 4: 14 (user-turn 边界才发出)
        val turnIndex = nextDevinSessionTurnIndex(effectiveSessionId)

        val f15Bytes = ByteArrayOutputStream()
        f15Bytes.appendTag(1, ProtoWire.BYTES)
        f15Bytes.appendString(effectiveSessionId)
        if (turnIndex > 0) {
            f15Bytes.appendTag(2, ProtoWire.VARINT)
            f15Bytes.appendVarint(turnIndex.toLong())
        }
        f15Bytes.appendTag(3, ProtoWire.VARINT)
        f15Bytes.appendVarint(4)

        // devin-cli 在 user-turn 边界发出 Field 15.4 = 14
        if (prompts.isNotEmpty() && prompts.last().source == 1) {
            if (turnIndex == 0 || prompts.size < 2 || prompts[prompts.size - 2].source != 1) {
                f15Bytes.appendTag(4, ProtoWire.VARINT)
                f15Bytes.appendVarint(14)
            }
        }

        req.appendTag(15, ProtoWire.BYTES)
        req.appendBytes(f15Bytes.toByteArray())

        // 8. Cascade ID (Field 16: session 稳定的 prompt 缓存 key)
        req.appendTag(16, ProtoWire.BYTES)
        req.appendString(effectiveCascadeId)

        // 9. Fixed flag (Field 20: Varint 1)
        req.appendTag(20, ProtoWire.VARINT)
        req.appendVarint(1)

        // 10. Model UID (Field 21)
        req.appendTag(21, ProtoWire.BYTES)
        req.appendString(chatModelUid)
    }
    return req.toByteArray()
}

/**
 * GetUserStatus 的 ClientMetadata: 字段与 [buildDevinClientMetadataBytes] 相同
 * (f1=f12="chisel", f2=f7=clientVersion, f3=sessionToken, f4="en", f5=osName,
 * f31=指纹), 指纹种子不同.
 * 顶层仅含 Field 1 = ClientMetadata.
 */
fun buildDevinGetUserStatusRequest(
    sessionToken: String,
    deviceFingerprint: String = "",
    osName: String = detectDevinOsName(),
    clientVersion: String = DEVIN_DEFAULT_CLIENT_VERSION,
): ByteArray {
    val fingerprint = deviceFingerprint.ifEmpty { generateDevinDeviceFingerprint(sessionToken) }
    val f1 = ByteArrayOutputStream()
    with(ProtoWire) {
        f1.appendTag(1, ProtoWire.BYTES)
        f1.appendString(DEVIN_DEFAULT_CLIENT_NAME)
        f1.appendTag(2, ProtoWire.BYTES)
        f1.appendString(clientVersion)
        f1.appendTag(3, ProtoWire.BYTES)
        f1.appendString(sessionToken)
        f1.appendTag(4, ProtoWire.BYTES)
        f1.appendString("en")
        f1.appendTag(5, ProtoWire.BYTES)
        f1.appendString(osName)
        f1.appendTag(7, ProtoWire.BYTES)
        f1.appendString(clientVersion)
        f1.appendTag(12, ProtoWire.BYTES)
        f1.appendString(DEVIN_DEFAULT_CLIENT_NAME)
        f1.appendTag(31, ProtoWire.BYTES)
        f1.appendString(fingerprint)
    }
    val req = ByteArrayOutputStream()
    with(ProtoWire) {
        req.appendTag(1, ProtoWire.BYTES)
        req.appendBytes(f1.toByteArray())
    }
    return req.toByteArray()
}

/**
 * 剥离 Claude Code 署名/CLI 身份相关的固定行.
 * (只移植固定规则; CPA 的敏感词 matcher 不在此实现.)
 */
fun sanitizeDevinSystemPrompt(prompt: String): String {
    if (prompt.isEmpty()) return ""
    val normalized = prompt.replace("\r\n", "\n")
    val kept = normalized.split("\n").filter { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("x-anthropic-billing-header:") -> false
            trimmed.startsWith("You are Claude Code") -> false
            trimmed.contains("authorized security testing") -> false
            trimmed.contains("destructive techniques, DoS attacks") -> false
            trimmed.contains("Claude Code is available as a CLI") -> false
            trimmed.contains("Fast mode for Claude Code") -> false
            else -> true
        }
    }
    return kept.joinToString("\n").trim()
}

// =============================================================================
// Wire data types
// =============================================================================

/** GetChatMessageRequest 中的工具定义 (request Field 10) */
data class DevinTool(
    val name: String = "",
    val description: String = "",
    /** 序列化好的 JSON schema 字节 */
    val parameters: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is DevinTool && name == other.name &&
            description == other.description && parameters.contentEquals(other.parameters)

    override fun hashCode(): Int {
        var r = name.hashCode()
        r = 31 * r + description.hashCode()
        r = 31 * r + parameters.contentHashCode()
        return r
    }
}

/** ChatMessagePrompt 中的工具调用 (prompt Field 6) */
data class DevinToolCall(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
)

/**
 * 响应 Field 6 的流式工具调用增量 (ChatToolCall):
 * 1=id, 2=name, 3=arguments_json, 4=invalid_json_str, 5=invalid_json_err,
 * 6=is_custom_tool_call. wire 上**没有 index**: 一个调用的增量连续到达,
 * 首帧带 id/name, 后续帧只带参数片段; 调用身份按 id 区分
 * (见 DevinToolCallAssembler).
 */
data class DevinToolCallDelta(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
    /** 模型参数不是合法 JSON 时上游把原文放这里, arguments 为空 */
    val invalidJsonStr: String = "",
    val invalidJsonErr: String = "",
    val isCustomToolCall: Boolean = false,
)

/** DevinPrompt 的图片附件 (prompt Field 10) */
data class DevinImage(
    val base64Data: String = "",
    val mimeType: String = "",
)

/** 请求历史中的一轮 (repeated Field 3). source: 1=user, 2=assistant, 4=tool */
data class DevinPrompt(
    val messageId: String = "",
    val source: Int = 1,
    val content: String = "",
    val images: List<DevinImage> = emptyList(),
    val toolCalls: List<DevinToolCall> = emptyList(),
    /** source=4 (tool result) 时的调用 ID */
    val toolCallId: String = "",
    val thinking: String = "",
    val signature: ByteArray = ByteArray(0),
    val signatureType: String = "",
) {
    override fun equals(other: Any?): Boolean =
        other is DevinPrompt && messageId == other.messageId && source == other.source &&
            content == other.content && images == other.images && toolCalls == other.toolCalls &&
            toolCallId == other.toolCallId && thinking == other.thinking &&
            signature.contentEquals(other.signature) && signatureType == other.signatureType

    override fun hashCode(): Int {
        var r = messageId.hashCode()
        r = 31 * r + source
        r = 31 * r + content.hashCode()
        r = 31 * r + images.hashCode()
        r = 31 * r + toolCalls.hashCode()
        r = 31 * r + toolCallId.hashCode()
        r = 31 * r + thinking.hashCode()
        r = 31 * r + signature.contentHashCode()
        r = 31 * r + signatureType.hashCode()
        return r
    }
}

/** 响应 Field 7 的 token 计量与上游响应头 */
data class DevinUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** cache write tokens (usage f4); promptTokens 只含 f2 的未缓存输入 */
    val cacheWriteTokens: Long = 0,
    val statusCode: Long = 0,
    val requestId: String = "",
    val modelName: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 把后到帧的 usage 逐字段并进已累计的值 (与 CLIProxyAPI 一致: 计数字段取后到的
 * 非零值, 头合并). 各计数可能分散在不同帧里, 必须先逐字段合并再
 * [toTokenUsage] 求和, 不能逐帧求和后再取最新.
 */
fun DevinUsage?.mergeFrom(next: DevinUsage): DevinUsage {
    if (this == null) return next
    return DevinUsage(
        promptTokens = next.promptTokens.takeIf { it > 0 } ?: promptTokens,
        completionTokens = next.completionTokens.takeIf { it > 0 } ?: completionTokens,
        cachedTokens = next.cachedTokens.takeIf { it > 0 } ?: cachedTokens,
        cacheWriteTokens = next.cacheWriteTokens.takeIf { it > 0 } ?: cacheWriteTokens,
        statusCode = next.statusCode.takeIf { it > 0 } ?: statusCode,
        requestId = next.requestId.ifEmpty { requestId },
        modelName = next.modelName.ifEmpty { modelName },
        headers = headers + next.headers,
    )
}

/**
 * 转成 App 通用口径 (与 Claude 一致): prompt = 未缓存输入 + cache read +
 * cache write, cached = cache read.
 */
fun DevinUsage.toTokenUsage(): TokenUsage {
    val prompt = promptTokens + cachedTokens + cacheWriteTokens
    return TokenUsage(
        promptTokens = prompt.toInt(),
        completionTokens = completionTokens.toInt(),
        cachedTokens = cachedTokens.toInt(),
        totalTokens = (prompt + completionTokens).toInt(),
    )
}

/** 单个响应帧解码出的全部内容 */
data class DevinFrameResult(
    val outputId: String = "",
    val timestamp: Long = 0,
    val contentText: String = "",
    val deltaTokens: Long = 0,
    /** 2/4=stop, 10=tool_calls */
    val stopReason: Long = 0,
    val toolCallDeltas: List<DevinToolCallDelta> = emptyList(),
    val thinkingText: String = "",
    val deltaSignature: ByteArray = ByteArray(0),
    val deltaSignatureType: String = "",
    val latency: Double = 0.0,
    val messageId: String = "",
    val usage: DevinUsage? = null,
    val responseDimensionGroups: List<ByteArray> = emptyList(),
    val unknownFieldNumbers: List<Int> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is DevinFrameResult && outputId == other.outputId && timestamp == other.timestamp &&
            contentText == other.contentText && deltaTokens == other.deltaTokens &&
            stopReason == other.stopReason && toolCallDeltas == other.toolCallDeltas &&
            thinkingText == other.thinkingText && deltaSignature.contentEquals(other.deltaSignature) &&
            deltaSignatureType == other.deltaSignatureType && latency == other.latency &&
            messageId == other.messageId && usage == other.usage &&
            responseDimensionGroups.size == other.responseDimensionGroups.size &&
            responseDimensionGroups.indices.all {
                responseDimensionGroups[it].contentEquals(other.responseDimensionGroups[it])
            } &&
            unknownFieldNumbers == other.unknownFieldNumbers

    override fun hashCode(): Int {
        var r = outputId.hashCode()
        r = 31 * r + timestamp.hashCode()
        r = 31 * r + contentText.hashCode()
        r = 31 * r + deltaTokens.hashCode()
        r = 31 * r + stopReason.hashCode()
        r = 31 * r + toolCallDeltas.hashCode()
        r = 31 * r + thinkingText.hashCode()
        r = 31 * r + deltaSignature.contentHashCode()
        r = 31 * r + deltaSignatureType.hashCode()
        r = 31 * r + latency.hashCode()
        r = 31 * r + messageId.hashCode()
        r = 31 * r + (usage?.hashCode() ?: 0)
        r = 31 * r + responseDimensionGroups.fold(0) { acc, b -> 31 * acc + b.contentHashCode() }
        r = 31 * r + unknownFieldNumbers.hashCode()
        return r
    }
}

// =============================================================================
// Response frame decoding
// =============================================================================

/**
 * 从单个响应帧 payload 提取 delta / tool call / thinking / signature / usage.
 * 逐字段等价 Go ParseDevinFrame; 不支持的 wire type 抛异常, 未知 bytes 字段记入
 * [DevinFrameResult.unknownFieldNumbers].
 */
fun parseDevinFrame(payload: ByteArray): DevinFrameResult {
    var outputId = ""
    var timestamp = 0L
    var deltaTokens = 0L
    var stopReason = 0L
    var latency = 0.0
    var messageId = ""
    var deltaSignatureType = ""
    var usage: DevinUsage? = null
    val textParts = mutableListOf<String>()
    val thinkingParts = mutableListOf<String>()
    val signature = ByteArrayOutputStream()
    val toolCallDeltas = mutableListOf<DevinToolCallDelta>()
    val dimensionGroups = mutableListOf<ByteArray>()
    val unknownFields = mutableListOf<Int>()

    val reader = ProtoWire.Reader(payload)
    while (!reader.exhausted) {
        val offset = reader.offset
        val (num, typ) = reader.consumeTag()
        when (typ) {
            ProtoWire.VARINT -> {
                val v = reader.consumeVarint()
                when (num) {
                    2 -> timestamp = v
                    4 -> deltaTokens = v
                    5 -> stopReason = v
                }
            }

            ProtoWire.FIXED64 -> {
                val v = reader.consumeFixed64()
                if (num == 12) {
                    latency = java.lang.Double.longBitsToDouble(v)
                }
            }

            ProtoWire.FIXED32 -> {
                reader.consumeFixed32()
            }

            ProtoWire.BYTES -> {
                val v = reader.consumeBytes()
                when (num) {
                    1 -> outputId = v.toString(Charsets.UTF_8)
                    2 -> timestamp = parseDevinTimestamp(v)
                    3 -> textParts.add(v.toString(Charsets.UTF_8))
                    6 -> parseDevinToolCallDelta(v)?.let { toolCallDeltas.add(it) }
                    7 -> usage = parseDevinUsageField(v)
                    9 -> thinkingParts.add(v.toString(Charsets.UTF_8))
                    10 -> signature.write(v, 0, v.size)
                    17 -> messageId = v.toString(Charsets.UTF_8)
                    21 -> deltaSignatureType = v.toString(Charsets.UTF_8)
                    28 -> dimensionGroups.add(v)
                    else -> unknownFields.add(num)
                }
            }

            else -> throw ProtoWireException("unsupported wire type $typ at offset $offset")
        }
    }

    return DevinFrameResult(
        outputId = outputId,
        timestamp = timestamp,
        contentText = if (textParts.isNotEmpty()) textParts.joinToString("") else "",
        deltaTokens = deltaTokens,
        stopReason = stopReason,
        toolCallDeltas = toolCallDeltas,
        thinkingText = if (thinkingParts.isNotEmpty()) thinkingParts.joinToString("") else "",
        deltaSignature = signature.toByteArray(),
        deltaSignatureType = deltaSignatureType,
        latency = latency,
        messageId = messageId,
        usage = usage,
        responseDimensionGroups = dimensionGroups,
        unknownFieldNumbers = unknownFields,
    )
}

private fun parseDevinToolCallDelta(data: ByteArray): DevinToolCallDelta? {
    var id = ""
    var name = ""
    var arguments = ""
    var invalidJsonStr = ""
    var invalidJsonErr = ""
    var isCustomToolCall = false
    val reader = ProtoWire.Reader(data)
    try {
        while (!reader.exhausted) {
            val (num, typ) = reader.consumeTag()
            when (typ) {
                ProtoWire.VARINT -> {
                    val v = reader.consumeVarint()
                    if (num == 6) isCustomToolCall = v != 0L
                }

                ProtoWire.BYTES -> {
                    val v = reader.consumeBytes()
                    when (num) {
                        1 -> id = v.toString(Charsets.UTF_8)
                        2 -> name = v.toString(Charsets.UTF_8)
                        3 -> arguments = v.toString(Charsets.UTF_8)
                        4 -> invalidJsonStr = v.toString(Charsets.UTF_8)
                        5 -> invalidJsonErr = v.toString(Charsets.UTF_8)
                    }
                }

                else -> reader.consumeFieldValue(num, typ)
            }
        }
    } catch (e: ProtoWireException) {
        return null
    }
    return DevinToolCallDelta(
        id = id,
        name = name,
        arguments = arguments,
        invalidJsonStr = invalidJsonStr,
        invalidJsonErr = invalidJsonErr,
        isCustomToolCall = isCustomToolCall,
    )
}

private fun parseDevinTimestamp(data: ByteArray): Long {
    var secs = 0L
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            break
        }
        if (typ == ProtoWire.VARINT) {
            val v = try {
                reader.consumeVarint()
            } catch (e: ProtoWireException) {
                break
            }
            if (num == 1) secs = v
        } else {
            break
        }
    }
    return secs
}

/** 解析 usage Field 7 内 Field 8 的 header 子消息: 1=name, 2=value */
private fun parseDevinHeaderField(data: ByteArray): Pair<String, String> {
    var key = ""
    var value = ""
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            break
        }
        when (typ) {
            ProtoWire.BYTES -> {
                val b = try {
                    reader.consumeBytes()
                } catch (e: ProtoWireException) {
                    return key to value
                }
                when (num) {
                    1 -> key = b.toString(Charsets.UTF_8)
                    2 -> value = b.toString(Charsets.UTF_8)
                }
            }

            else -> try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                return key to value
            }
        }
    }
    return key to value
}

private fun isPrintableAscii(bytes: ByteArray): Boolean {
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        if (v < 32 || v > 126) return false
    }
    return true
}

internal fun parseDevinUsageField(data: ByteArray): DevinUsage {
    var promptTokens = 0L
    var completionTokens = 0L
    var cachedTokens = 0L
    var cacheWriteTokens = 0L
    var statusCode = 0L
    var requestId = ""
    var modelName = ""
    var headers: MutableMap<String, String>? = null

    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            break
        }
        when (typ) {
            ProtoWire.VARINT -> {
                val v = try {
                    reader.consumeVarint()
                } catch (e: ProtoWireException) {
                    break
                }
                when (num) {
                    // turn 消息里的未缓存输入
                    2 -> promptTokens += v
                    // 输出 tokens
                    3 -> completionTokens = v
                    // cache write tokens
                    4 -> cacheWriteTokens += v
                    // cache read tokens
                    5 -> cachedTokens = v
                    // 状态码
                    6 -> statusCode = v
                }
            }

            ProtoWire.BYTES -> {
                val v = try {
                    reader.consumeBytes()
                } catch (e: ProtoWireException) {
                    break
                }
                when (num) {
                    8 -> {
                        val (k, hv) = parseDevinHeaderField(v)
                        if (k.isNotEmpty()) {
                            if (headers == null) headers = mutableMapOf()
                            headers[k] = hv
                            if ((k.equals("x-request-id", true) || k.equals("request-id", true)) &&
                                hv.isNotEmpty()
                            ) {
                                requestId = hv
                            }
                        } else if (v.isNotEmpty() && isPrintableAscii(v) && requestId.isEmpty()) {
                            requestId = v.toString(Charsets.UTF_8)
                        }
                    }

                    9 -> modelName = v.toString(Charsets.UTF_8)
                }
            }

            ProtoWire.FIXED64 -> try {
                reader.consumeFixed64()
            } catch (e: ProtoWireException) {
                break
            }

            ProtoWire.FIXED32 -> try {
                reader.consumeFixed32()
            } catch (e: ProtoWireException) {
                break
            }

            else -> try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                break
            }
        }
    }
    return DevinUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cachedTokens = cachedTokens,
        cacheWriteTokens = cacheWriteTokens,
        statusCode = statusCode,
        requestId = requestId,
        modelName = modelName,
        headers = headers ?: emptyMap(),
    )
}

/** Field 28 (ResponseDimensionGroups) 解析出的 Token Usage 指标 */
data class DevinDimensionTokens(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val found: Boolean = false,
)

/**
 * 解析 Field 28 (ResponseDimensionGroups) 条目, 提取 Token Usage:
 * input_tokens / output_tokens / cached_input_tokens.
 * 入参可为一个或多个 group payload (Field 28 的值), 也可以是包含 Tag 28 的外层封装.
 */
fun parseDevinResponseDimensionGroups(vararg groups: ByteArray): DevinDimensionTokens {
    for (raw in groups) {
        if (raw.isEmpty()) continue
        var gBytes = raw
        // 外层封装带 Tag 28 时解出内部 group 字节
        val outer = ProtoWire.Reader(gBytes)
        val head = try {
            outer.consumeTag()
        } catch (e: ProtoWireException) {
            null
        }
        if (head != null && head.first == 28 && head.second == ProtoWire.BYTES) {
            gBytes = try {
                outer.consumeBytes()
            } catch (e: ProtoWireException) {
                continue
            }
        }

        var title = ""
        val metrics = mutableListOf<Pair<String, Float>>()
        val gReader = ProtoWire.Reader(gBytes)
        groupLoop@ while (!gReader.exhausted) {
            val (gNum, gTyp) = try {
                gReader.consumeTag()
            } catch (e: ProtoWireException) {
                break
            }
            if (gTyp != ProtoWire.BYTES) {
                try {
                    gReader.consumeFieldValue(gNum, gTyp)
                } catch (e: ProtoWireException) {
                    break
                }
                continue
            }
            val gb = try {
                gReader.consumeBytes()
            } catch (e: ProtoWireException) {
                break
            }
            when (gNum) {
                1 -> title = gb.toString(Charsets.UTF_8)
                2 -> {
                    var mKey = ""
                    var mVal = 0f
                    val mReader = ProtoWire.Reader(gb)
                    metricLoop@ while (!mReader.exhausted) {
                        val (mNum, mTyp) = try {
                            mReader.consumeTag()
                        } catch (e: ProtoWireException) {
                            break
                        }
                        if (mTyp != ProtoWire.BYTES) {
                            try {
                                mReader.consumeFieldValue(mNum, mTyp)
                            } catch (e: ProtoWireException) {
                                break
                            }
                            continue
                        }
                        val mb = try {
                            mReader.consumeBytes()
                        } catch (e: ProtoWireException) {
                            break
                        }
                        when (mNum) {
                            5 -> mKey = mb.toString(Charsets.UTF_8)
                            4 -> {
                                val dReader = ProtoWire.Reader(mb)
                                dimLoop@ while (!dReader.exhausted) {
                                    val (dNum, dTyp) = try {
                                        dReader.consumeTag()
                                    } catch (e: ProtoWireException) {
                                        break
                                    }
                                    if (dTyp == ProtoWire.FIXED32) {
                                        val dv = try {
                                            dReader.consumeFixed32()
                                        } catch (e: ProtoWireException) {
                                            break
                                        }
                                        if (dNum == 2) {
                                            mVal = java.lang.Float.intBitsToFloat(dv.toInt())
                                        }
                                    } else {
                                        try {
                                            dReader.consumeFieldValue(dNum, dTyp)
                                        } catch (e: ProtoWireException) {
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (mKey.isNotEmpty()) metrics.add(mKey to mVal)
                }
            }
        }

        if (title.equals("Token Usage", ignoreCase = true)) {
            var prompt = 0L
            var completion = 0L
            var cached = 0L
            var found = false
            for ((key, v) in metrics) {
                when (key) {
                    "input_tokens" -> {
                        prompt = v.toLong()
                        found = true
                    }

                    "output_tokens" -> {
                        completion = v.toLong()
                        found = true
                    }

                    "cached_input_tokens" -> {
                        cached = v.toLong()
                        found = true
                    }
                }
            }
            if (found) {
                return DevinDimensionTokens(prompt, completion, cached, true)
            }
        }
    }
    return DevinDimensionTokens()
}

// =============================================================================
// GetUserStatus response decoding (internal/auth/devin/user_status.go)
// =============================================================================

/** GetUserStatus 返回的套餐/配额/账户信息 */
data class DevinUserStatus(
    val email: String = "",
    val userName: String = "",
    val userId: String = "",
    val teamId: String = "",
    val orgId: String = "",
    val orgName: String = "",
    val plan: String = "",
    val dailyQuotaRemainingPercent: Long = 0,
    val weeklyQuotaRemainingPercent: Long = 0,
    /** epoch 秒, 0 = 未提供 */
    val dailyQuotaResetAt: Long = 0,
    val weeklyQuotaResetAt: Long = 0,
    val planStart: Long = 0,
    val planEnd: Long = 0,
)

/** 解码 GetUserStatus 的二进制响应 */
fun parseDevinGetUserStatusResponse(data: ByteArray): DevinUserStatus {
    if (data.isEmpty()) {
        throw ProtoWireException("empty GetUserStatus response")
    }
    val status = DevinUserStatusBuilder()
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = reader.consumeTag()
        if (num == 1 && typ == ProtoWire.BYTES) {
            parseDevinUserStatusField(reader.consumeBytes(), status)
        } else {
            reader.consumeFieldValue(num, typ)
        }
    }
    return status.build()
}

private class DevinUserStatusBuilder {
    var email = ""
    var userName = ""
    var userId = ""
    var teamId = ""
    var orgId = ""
    var orgName = ""
    var plan = ""
    var dailyQuotaRemainingPercent = 0L
    var weeklyQuotaRemainingPercent = 0L
    var dailyQuotaResetAt = 0L
    var weeklyQuotaResetAt = 0L
    var planStart = 0L
    var planEnd = 0L

    fun build() = DevinUserStatus(
        email, userName, userId, teamId, orgId, orgName, plan,
        dailyQuotaRemainingPercent, weeklyQuotaRemainingPercent,
        dailyQuotaResetAt, weeklyQuotaResetAt, planStart, planEnd,
    )
}

private fun parseDevinUserStatusField(data: ByteArray, status: DevinUserStatusBuilder) {
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            return
        }
        if (typ == ProtoWire.BYTES) {
            val v = try {
                reader.consumeBytes()
            } catch (e: ProtoWireException) {
                return
            }
            when (num) {
                3 -> status.userName = v.toString(Charsets.UTF_8)
                5 -> status.teamId = v.toString(Charsets.UTF_8)
                7 -> status.email = v.toString(Charsets.UTF_8)
                13 -> parseDevinPlanStatus(v, status)
                36 -> status.userId = v.toString(Charsets.UTF_8)
            }
        } else {
            try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                return
            }
        }
    }
}

private fun parseDevinPlanStatus(data: ByteArray, status: DevinUserStatusBuilder) {
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            return
        }
        when (typ) {
            ProtoWire.BYTES -> {
                val v = try {
                    reader.consumeBytes()
                } catch (e: ProtoWireException) {
                    return
                }
                when (num) {
                    1 -> parseDevinPlanInfo(v, status)
                    2 -> parseDevinSecondsSubfield(v).takeIf { it > 0 }
                        ?.let { status.planStart = it }

                    3 -> parseDevinSecondsSubfield(v).takeIf { it > 0 }
                        ?.let { status.planEnd = it }
                }
            }

            ProtoWire.VARINT -> {
                val v = try {
                    reader.consumeVarint()
                } catch (e: ProtoWireException) {
                    return
                }
                when (num) {
                    14 -> status.dailyQuotaRemainingPercent = v
                    15 -> status.weeklyQuotaRemainingPercent = v
                    17 -> if (v > 0) status.dailyQuotaResetAt = v
                    18 -> if (v > 0) status.weeklyQuotaResetAt = v
                }
            }

            else -> try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                return
            }
        }
    }
}

private fun parseDevinPlanInfo(data: ByteArray, status: DevinUserStatusBuilder) {
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            return
        }
        if (typ == ProtoWire.BYTES) {
            val v = try {
                reader.consumeBytes()
            } catch (e: ProtoWireException) {
                return
            }
            when (num) {
                2 -> status.plan = v.toString(Charsets.UTF_8)
                33 -> parseDevinPlanInfoOrg(v, status)
            }
        } else {
            try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                return
            }
        }
    }
}

private fun parseDevinPlanInfoOrg(data: ByteArray, status: DevinUserStatusBuilder) {
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            return
        }
        if (typ == ProtoWire.BYTES) {
            val v = try {
                reader.consumeBytes()
            } catch (e: ProtoWireException) {
                return
            }
            when (num) {
                4 -> status.orgId = v.toString(Charsets.UTF_8)
                8 -> status.orgName = v.toString(Charsets.UTF_8)
            }
        } else {
            try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                return
            }
        }
    }
}

private fun parseDevinSecondsSubfield(data: ByteArray): Long {
    val reader = ProtoWire.Reader(data)
    while (!reader.exhausted) {
        val (num, typ) = try {
            reader.consumeTag()
        } catch (e: ProtoWireException) {
            return 0
        }
        if (typ == ProtoWire.VARINT) {
            val v = try {
                reader.consumeVarint()
            } catch (e: ProtoWireException) {
                return 0
            }
            if (num == 1) return v
        } else {
            try {
                reader.consumeFieldValue(num, typ)
            } catch (e: ProtoWireException) {
                return 0
            }
        }
    }
    return 0
}

// =============================================================================
// EOS trailer error mapping
// =============================================================================

/**
 * 解析 Connect-RPC EOS trailer JSON, 返回 (httpStatus, errorMessage).
 * 无错误 (空 / "{}" / 无 error 对象) 返回 null.
 */
fun parseDevinTrailerError(payload: ByteArray): Pair<Int, String>? {
    val trimmed = String(payload, Charsets.UTF_8).trim()
    if (trimmed.isEmpty() || trimmed == "{}") return null

    val errorObj = runCatching {
        json.parseToJsonElement(trimmed).jsonObject["error"]?.jsonObject
    }.getOrNull() ?: return null

    // Go: error 对象存在但缺 code 时按默认分支映射 502 (codeStr="" 无匹配)
    val code = errorObj["code"]?.jsonPrimitive?.content ?: ""
    val message = errorObj["message"]?.jsonPrimitive?.content ?: ""

    val codeStr = code.lowercase()
    val msgLower = message.lowercase()

    val httpCode = when (codeStr) {
        "invalid_argument" -> if (msgLower.contains("internal error")) 502 else 400
        "internal" -> 502
        "unauthenticated" -> 401
        "permission_denied" -> 403
        "resource_exhausted" -> 429
        "unavailable" -> 503
        "canceled" -> 499
        "deadline_exceeded" -> 504
        "failed_precondition" -> if (
            msgLower.contains("quota") || msgLower.contains("credit") ||
            msgLower.contains("acu") || msgLower.contains("exhausted") ||
            msgLower.contains("limit")
        ) {
            429
        } else {
            400
        }

        else -> 502
    }
    return httpCode to "devin upstream error ($code): $message"
}

// =============================================================================
// UTF-8 split buffer
// =============================================================================

private data class DecodedRune(val codePoint: Int, val size: Int)

// Go unicode/utf8 的 acceptRanges 表 (索引 5-15 为 {0,0} 的零值, 与 Go [16]acceptRange 一致).
private val UTF8_ACCEPT_RANGES = Array(16) { 0 to 0 }.apply {
    this[0] = 0x80 to 0xBF
    this[1] = 0xA0 to 0xBF
    this[2] = 0x80 to 0x9F
    this[3] = 0x90 to 0xBF
    this[4] = 0x80 to 0x8F
}

/**
 * 等价 Go utf8 的 `first` 表: 高 4 位 = acceptRange 索引, 低 3 位 = size (ASCII 为 1), -1 = 非法起始字节.
 */
private fun utf8First(b0: Int): Int {
    return when {
        b0 < 0x80 -> 1 // ASCII
        b0 < 0xC2 -> -1 // 0x80-0xC1: 孤立 continuation 或 overlong 起始
        b0 < 0xE0 -> (0 shl 4) or 2
        b0 == 0xE0 -> (1 shl 4) or 3
        b0 < 0xED -> (0 shl 4) or 3
        b0 == 0xED -> (2 shl 4) or 3
        b0 < 0xF0 -> (0 shl 4) or 3
        b0 == 0xF0 -> (3 shl 4) or 4
        b0 < 0xF4 -> (0 shl 4) or 4
        b0 == 0xF4 -> (4 shl 4) or 4
        else -> -1 // 0xF5-0xFF
    }
}

/**
 * 等价 Go utf8.DecodeRune: 返回首个 rune 及其字节长度.
 * 非法序列与"合法前缀但长度不足"都返回 (RuneError=-1, 1).
 */
private fun decodeRune(buf: ByteArray, start: Int): DecodedRune {
    val n = buf.size - start
    if (n < 1) return DecodedRune(-1, 0)
    val b0 = buf[start].toInt() and 0xFF
    if (b0 < 0x80) return DecodedRune(b0, 1)
    val x = utf8First(b0)
    if (x < 0) return DecodedRune(-1, 1)
    val sz = x and 7
    val accept = UTF8_ACCEPT_RANGES[x shr 4]
    if (n < sz) return DecodedRune(-1, 1) // 截断但可能是合法前缀
    val b1 = buf[start + 1].toInt() and 0xFF
    if (b1 < accept.first || b1 > accept.second) return DecodedRune(-1, 1)
    if (sz == 2) {
        return DecodedRune(((b0 and 0x1F) shl 6) or (b1 and 0x3F), 2)
    }
    val b2 = buf[start + 2].toInt() and 0xFF
    if (b2 < 0x80 || b2 > 0xBF) return DecodedRune(-1, 1)
    if (sz == 3) {
        return DecodedRune(((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F), 3)
    }
    val b3 = buf[start + 3].toInt() and 0xFF
    if (b3 < 0x80 || b3 > 0xBF) return DecodedRune(-1, 1)
    return DecodedRune(
        ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F),
        4,
    )
}

/**
 * 等价 Go utf8.FullRune: buf 是否含至少一个完整 rune.
 * 非法编码视为完整 rune (解码时产出宽度 1 的 RuneError); 只有"合法前缀被截断"才返回 false.
 */
private fun fullRune(buf: ByteArray, start: Int): Boolean {
    val n = buf.size - start
    if (n < 1) return false
    val x = utf8First(buf[start].toInt() and 0xFF)
    // Go 的 xx 表项为 0xF1: x&7=1, x>>4=15 → 非法起始字节 size 视为 1
    val sz = if (x < 0) 1 else (x and 7)
    if (n >= sz) return true // ASCII, invalid or valid
    // 序列被截断: 若后续字节已能判定非法, 仍算 full (按错误 rune 处理)
    val accept = UTF8_ACCEPT_RANGES[(if (x < 0) 0xF1 else x) shr 4]
    if (n > 1) {
        val b1 = buf[start + 1].toInt() and 0xFF
        if (b1 < accept.first || b1 > accept.second) return true
    }
    if (n > 2) {
        val b2 = buf[start + 2].toInt() and 0xFF
        if (b2 < 0x80 || b2 > 0xBF) return true
    }
    return false
}

/**
 * 跨 chunk 缓冲不完整 UTF-8 字节序列, 等价 Go UTF8SplitBuffer.
 * 每次 [feed] 返回所有完整的 UTF-8 文本; 不完整尾部留到下次.
 */
class Utf8SplitBuffer {
    private var remainder: ByteArray = ByteArray(0)

    fun feed(chunk: ByteArray): String {
        val combined = remainder + chunk
        remainder = ByteArray(0)
        if (combined.isEmpty()) return ""

        var validUntil = 0
        while (validUntil < combined.size) {
            val r = decodeRune(combined, validUntil)
            if (r.codePoint == -1 && r.size == 1) {
                val trailingLen = combined.size - validUntil
                if (trailingLen < 4 && !fullRune(combined, validUntil)) {
                    break
                }
                validUntil++
                continue
            }
            validUntil += r.size
        }

        val validBytes = combined.copyOfRange(0, validUntil)
        remainder = combined.copyOfRange(validUntil, combined.size)
        return String(validBytes, Charsets.UTF_8)
    }

    /** 流结束时取剩余字节 (不完整序列按 UTF-8 默认替换规则解码) */
    fun flush(): String {
        val out = String(remainder, Charsets.UTF_8)
        remainder = ByteArray(0)
        return out
    }
}
