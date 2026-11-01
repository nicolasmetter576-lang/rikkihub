package me.rerere.ai.provider.providers.devin

import me.rerere.ai.provider.providers.devin.DevinTestGolden.get
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 与 Go (CLIProxyAPI internal/runtime/executor/helps) 输出逐字节对拍.
 * golden.txt 由临时 Go test 生成, 常量与测试输入在两侧完全固定.
 * request/envelope/fingerprint 于 2026-10-10 用 CLIProxyAPI main `d318bcc` 重新生成
 * (ClientMetadata f1="chisel"、无 f28); frame_* 与 eos_* 沿用旧版生成结果.
 */
class DevinWireGoldenTest {

    private val sessionId = "55555555-5555-5555-5555-555555555555"
    private val cascadeId = "66666666-6666-6666-6666-666666666666"

    private fun goldenPrompts() = listOf(
        DevinPrompt(
            messageId = "11111111-1111-1111-1111-111111111111",
            source = 1,
            content = "hello",
            images = listOf(DevinImage(base64Data = "aGVsbG8=", mimeType = "image/png")),
        ),
        DevinPrompt(
            messageId = "22222222-2222-2222-2222-222222222222",
            source = 2,
            content = "hi there",
            toolCalls = listOf(
                DevinToolCall(id = "call-1", name = "get_weather", arguments = """{"city":"sf"}""")
            ),
            thinking = "thinking step",
            signature = "sealed.v1.test".toByteArray(),
            signatureType = "sealed",
        ),
        DevinPrompt(
            messageId = "33333333-3333-3333-3333-333333333333",
            source = 4,
            content = """{"result":"ok"}""",
            toolCallId = "call-1",
        ),
        DevinPrompt(
            messageId = "44444444-4444-4444-4444-444444444444",
            source = 1,
            content = "next question",
        ),
    )

    private fun goldenTools() = listOf(
        DevinTool(
            name = "get_weather",
            description = "Get weather. Takes a task_id parameter identifying the task.",
            parameters = """{"type":"object","properties":{"city":{"type":"string"}}}""".toByteArray(),
        )
    )

    private fun buildGoldenRequest(prompts: List<DevinPrompt>, temperature: Double?) =
        buildDevinGetChatMessageRequest(
            sessionToken = "test-token",
            deviceSeed = "seed",
            chatModelUid = "swe-2-high",
            systemPrompt = "You are a helpful assistant.",
            prompts = prompts,
            tools = goldenTools(),
            temperature = temperature,
            maxTokens = 4000,
            sessionId = sessionId,
            cascadeId = cascadeId,
            osName = "linux",
            clientVersion = "3000.10.21",
        )

    @Test
    fun `golden request bytes match go output`() {
        resetDevinSessionTurnIndex(sessionId)
        // 与 Go golden 生成顺序一致: A 先 (turnIndex=0, 以 user 结尾 → f15.4=14)
        val reqA = buildGoldenRequest(goldenPrompts(), 0.7)
        assertArrayEquals(
            "request_a bytes diverge from Go",
            DevinTestGolden["request_a"], reqA,
        )
        // B 后 (turnIndex=1, 以 tool result 结尾 → 无 f15.4)
        val reqB = buildGoldenRequest(goldenPrompts().take(3), null)
        assertArrayEquals(
            "request_b bytes diverge from Go",
            DevinTestGolden["request_b"], reqB,
        )
    }

    @Test
    fun `golden connect envelope matches go output`() {
        resetDevinSessionTurnIndex(sessionId)
        val reqA = buildGoldenRequest(goldenPrompts(), 0.7)
        assertArrayEquals(DevinTestGolden["envelope_a"], wrapConnectEnvelope(reqA))
        val reqB = buildGoldenRequest(goldenPrompts().take(3), null)
        assertArrayEquals(DevinTestGolden["envelope_b"], wrapConnectEnvelope(reqB))
    }

    @Test
    fun `golden fingerprint matches go output`() {
        assertEquals(
            DevinTestGolden.string("fingerprint"),
            generateDevinDeviceFingerprint("seed"),
        )
    }

    @Test
    fun `golden data frame parses all fields`() {
        val frame = readConnectFrame(ByteArrayInputStream(DevinTestGolden["frame_data"]))
        assertNotNull(frame)
        assertEquals(CONNECT_FLAG_DATA, frame!!.flag)

        val res = parseDevinFrame(frame.payload)
        assertEquals("bot-uuid-123", res.outputId)
        assertEquals(1700000000L, res.timestamp)
        assertEquals("Hello world", res.contentText)
        assertEquals(42L, res.deltaTokens)
        assertEquals(2L, res.stopReason)
        assertEquals("Let me think...", res.thinkingText)
        assertArrayEquals("CAQS-signature-bytes".toByteArray(), res.deltaSignature)
        assertEquals("anthropic", res.deltaSignatureType)
        assertEquals(0.25, res.latency, 0.0)
        assertEquals("msg-uuid-777", res.messageId)
        assertEquals(listOf(99), res.unknownFieldNumbers)

        assertEquals(1, res.toolCallDeltas.size)
        val tc = res.toolCallDeltas[0]
        assertEquals("call-1", tc.id)
        assertEquals("get_weather", tc.name)
        assertEquals("""{"city":"sf"}""", tc.arguments)
        assertEquals("", tc.invalidJsonStr)
        assertEquals(false, tc.isCustomToolCall)
    }

    @Test
    fun `golden usage frame parses usage and dimension groups`() {
        val frame = readConnectFrame(ByteArrayInputStream(DevinTestGolden["frame_usage"]))
        assertNotNull(frame)
        val res = parseDevinFrame(frame!!.payload)

        val usage = res.usage
        assertNotNull(usage)
        usage!!
        assertEquals(3L, usage.promptTokens) // f2: 未缓存输入
        assertEquals(58L, usage.cacheWriteTokens) // f4
        assertEquals(39L, usage.completionTokens)
        assertEquals(19179L, usage.cachedTokens) // f5: cache read
        // App 口径: prompt = 未缓存 + cache read + cache write
        val tokenUsage = usage.toTokenUsage()
        assertEquals(3 + 19179 + 58, tokenUsage.promptTokens)
        assertEquals(19179, tokenUsage.cachedTokens)
        assertEquals(39, tokenUsage.completionTokens)
        assertEquals(3 + 19179 + 58 + 39, tokenUsage.totalTokens)
        assertEquals(66L, usage.statusCode)
        assertEquals("req_5bb00ad48ae048119e3420bddf36257f", usage.requestId)
        assertEquals("gpt-5-6-luna-low", usage.modelName)
        assertEquals("419", usage.headers["openai-processing-ms"])
        assertEquals("2020-10-01", usage.headers["openai-version"])

        assertEquals(1, res.responseDimensionGroups.size)
        val dims = parseDevinResponseDimensionGroups(*res.responseDimensionGroups.toTypedArray())
        assertTrue(dims.found)
        assertEquals(575L, dims.promptTokens)
        assertEquals(5L, dims.completionTokens)
        assertEquals(128L, dims.cachedTokens)
    }

    @Test
    fun `dimension groups accept outer tag28 envelope`() {
        val frame = readConnectFrame(ByteArrayInputStream(DevinTestGolden["frame_usage"]))
        val res = parseDevinFrame(frame!!.payload)
        val group = res.responseDimensionGroups[0]
        // 重新包一层 tag28, 应能被解包解析
        val rewrapped = java.io.ByteArrayOutputStream().apply {
            with(ProtoWire) {
                appendTag(28, ProtoWire.BYTES)
                appendBytes(group)
            }
        }.toByteArray()
        val dims = parseDevinResponseDimensionGroups(rewrapped)
        assertTrue(dims.found)
        assertEquals(575L, dims.promptTokens)
    }

    @Test
    fun `gzip frame decompresses to same payload`() {
        val frame = readConnectFrame(ByteArrayInputStream(DevinTestGolden["frame_gzip"]))
        assertNotNull(frame)
        assertEquals(CONNECT_FLAG_COMPRESSED, frame!!.flag)
        val parsed = parseDevinFrame(frame.payload)
        assertEquals("bot-uuid-123", parsed.outputId)
        assertEquals("Hello world", parsed.contentText)
    }

    @Test
    fun `eos frames decode and error maps`() {
        val ok = readConnectFrame(ByteArrayInputStream(DevinTestGolden["eos_ok"]))
        assertNotNull(ok)
        assertEquals(CONNECT_FLAG_END_STREAM, ok!!.flag)
        assertNull(parseDevinTrailerError(ok.payload))

        val err = readConnectFrame(ByteArrayInputStream(DevinTestGolden["eos_error"]))
        assertNotNull(err)
        assertEquals(CONNECT_FLAG_END_STREAM, err!!.flag)
        val mapped = parseDevinTrailerError(err.payload)
        assertNotNull(mapped)
        assertEquals(429, mapped!!.first)
        assertTrue(mapped.second.contains("failed_precondition"))
    }

    @Test
    fun `readConnectFrame returns null on clean eof and errors on truncation`() {
        // 空流 → null
        assertNull(readConnectFrame(ByteArrayInputStream(ByteArray(0))))
        // 截断的 header → EOFException
        try {
            readConnectFrame(ByteArrayInputStream(byteArrayOf(0x00, 0x00)))
            fail("expected EOFException")
        } catch (e: java.io.EOFException) {
            // expected
        }
        // 非法 flag
        try {
            readConnectFrame(ByteArrayInputStream(byteArrayOf(0x7F, 0, 0, 0, 0)))
            fail("expected ProtoWireException")
        } catch (e: ProtoWire.ProtoWireException) {
            // expected
        }
    }

    @Test
    fun `request bytes round trip through frame reader`() {
        resetDevinSessionTurnIndex(sessionId)
        val req = buildGoldenRequest(goldenPrompts(), 0.7)
        val frame = readConnectFrame(ByteArrayInputStream(wrapConnectEnvelope(req)))
        assertNotNull(frame)
        assertEquals(CONNECT_FLAG_DATA, frame!!.flag)
        assertArrayEquals(req, frame.payload)
    }
}
