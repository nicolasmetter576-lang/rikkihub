package me.rerere.ai.provider.providers.devin

import me.rerere.ai.provider.providers.devin.ProtoWire.appendBytes
import me.rerere.ai.provider.providers.devin.ProtoWire.appendString
import me.rerere.ai.provider.providers.devin.ProtoWire.appendTag
import me.rerere.ai.provider.providers.devin.ProtoWire.appendVarint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DevinWireTest {

    // ======================= parseDevinTrailerError =======================

    @Test
    fun `trailer error mapping covers all branches`() {
        fun code(json: String): Pair<Int, String>? =
            parseDevinTrailerError(json.toByteArray())

        assertNull(code(""))
        assertNull(code("   "))
        assertNull(code("{}"))
        assertNull(code("not json"))
        assertNull(code("""{"foo":1}"""))

        assertEquals(429, code("""{"error":{"code":"failed_precondition","message":"User monthly ACU quota exhausted (trace ID: 12345)"}}""")!!.first)
        assertEquals(429, code("""{"error":{"code":"failed_precondition","message":"credit balance too low"}}""")!!.first)
        assertEquals(429, code("""{"error":{"code":"failed_precondition","message":"rate limit reached"}}""")!!.first)
        assertEquals(400, code("""{"error":{"code":"failed_precondition","message":"Client version 3000.1.0 is no longer supported"}}""")!!.first)

        assertEquals(401, code("""{"error":{"code":"unauthenticated","message":"Invalid or expired session token"}}""")!!.first)
        assertEquals(403, code("""{"error":{"code":"permission_denied","message":"nope"}}""")!!.first)
        assertEquals(429, code("""{"error":{"code":"resource_exhausted","message":"too fast"}}""")!!.first)
        assertEquals(503, code("""{"error":{"code":"unavailable","message":"try later"}}""")!!.first)
        assertEquals(499, code("""{"error":{"code":"canceled","message":"client canceled"}}""")!!.first)
        assertEquals(504, code("""{"error":{"code":"deadline_exceeded","message":"upstream deadline exceeded"}}""")!!.first)
        assertEquals(502, code("""{"error":{"code":"internal","message":"database timeout"}}""")!!.first)
        assertEquals(502, code("""{"error":{"code":"invalid_argument","message":"an internal error occurred (trace ID: fa43c6393b805646b66997cc46c6f4af)"}}""")!!.first)
        assertEquals(400, code("""{"error":{"code":"invalid_argument","message":"field 'model' cannot be empty"}}""")!!.first)
        assertEquals(502, code("""{"error":{"code":"unknown_future_error","message":"something unusual"}}""")!!.first)

        val mapped = code("""{"error":{"code":"unauthenticated","message":"bad token"}}""")
        assertNotNull(mapped)
        assertTrue(mapped!!.second.contains("devin upstream error (unauthenticated): bad token"))
    }

    // ======================= fingerprint =======================

    @Test
    fun `fingerprint is 732 hex and deterministic per seed`() {
        val fp1 = generateDevinDeviceFingerprint("seed-1")
        assertEquals(DEVIN_FINGERPRINT_HEX_LEN, fp1.length)
        assertTrue(fp1.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(fp1, generateDevinDeviceFingerprint("seed-1"))

        // 空 seed → 每次随机
        val r1 = generateDevinDeviceFingerprint("")
        val r2 = generateDevinDeviceFingerprint("")
        assertEquals(DEVIN_FINGERPRINT_HEX_LEN, r1.length)
        assertEquals(DEVIN_FINGERPRINT_HEX_LEN, r2.length)
        assertTrue(r1 != r2)
    }

    @Test
    fun `sentry trace format`() {
        val t1 = generateDevinSentryTrace()
        val t2 = generateDevinSentryTrace()
        assertTrue(t1 != t2)
        val parts = t1.split('-')
        assertEquals(3, parts.size)
        assertEquals(32, parts[0].length)
        assertEquals(16, parts[1].length)
        assertEquals("1", parts[2])
    }

    // ======================= sanitizeDevinSystemPrompt =======================

    @Test
    fun `sanitize strips fixed attribution lines`() {
        val raw = "x-anthropic-billing-header: cc_version=2.1.260;\n" +
            "You are Claude Code, Anthropic's official CLI for Claude.\n" +
            "Do authorized security testing on this repo\n" +
            "Uses destructive techniques, DoS attacks are banned\n" +
            "Claude Code is available as a CLI tool\n" +
            "Fast mode for Claude Code users\n" +
            "Keep this line"
        val out = sanitizeDevinSystemPrompt(raw)
        assertTrue(!out.contains("x-anthropic-billing-header"))
        assertTrue(!out.contains("You are Claude Code"))
        assertTrue(!out.contains("authorized security testing"))
        assertTrue(!out.contains("destructive techniques, DoS attacks"))
        assertTrue(!out.contains("Claude Code is available as a CLI"))
        assertTrue(!out.contains("Fast mode for Claude Code"))
        assertTrue(out.contains("Keep this line"))
    }

    @Test
    fun `sanitize normalizes crlf and trims`() {
        assertEquals("a\nb", sanitizeDevinSystemPrompt("a\r\nb"))
        assertEquals("x", sanitizeDevinSystemPrompt("  x  "))
        assertEquals("", sanitizeDevinSystemPrompt(""))
        // 全部剥离 → 空
        assertEquals("", sanitizeDevinSystemPrompt("You are Claude Code"))
    }

    // ======================= session turn index =======================

    @Test
    fun `turn index counts per session and resets`() {
        val s = "sess-test-${System.nanoTime()}"
        try {
            assertEquals(0, nextDevinSessionTurnIndex(s))
            assertEquals(1, nextDevinSessionTurnIndex(s))
            assertEquals(2, nextDevinSessionTurnIndex(s))
            resetDevinSessionTurnIndex(s)
            assertEquals(0, nextDevinSessionTurnIndex(s))
        } finally {
            // 不在此处 reset: 下面还要验证 s 已消耗过的计数延续
        }
        // 空/空白 sessionId → 恒 0
        assertEquals(0, nextDevinSessionTurnIndex(""))
        assertEquals(0, nextDevinSessionTurnIndex("   "))
        // 不同 session 独立计数; s 在 reset 后已消耗过一次 → 下一个为 1
        val other = "sess-other-${System.nanoTime()}"
        try {
            assertEquals(0, nextDevinSessionTurnIndex(other))
            assertEquals(1, nextDevinSessionTurnIndex(s))
        } finally {
            resetDevinSessionTurnIndex(other)
            resetDevinSessionTurnIndex(s)
        }
    }

    @Test
    fun `field15 turn boundary rules`() {
        val s = "sess-f15-${System.nanoTime()}"
        try {
            fun f15(prompts: List<DevinPrompt>): ByteArray {
                val req = buildDevinGetChatMessageRequest(
                    sessionToken = "t", deviceSeed = "s", chatModelUid = "swe-2-high",
                    systemPrompt = "", prompts = prompts, tools = emptyList(),
                    temperature = null, maxTokens = 1000,
                    sessionId = s, cascadeId = "c",
                    osName = "linux", clientVersion = "3000.10.21",
                )
                val reader = ProtoWire.Reader(req)
                while (!reader.exhausted) {
                    val (num, typ) = reader.consumeTag()
                    if (num == 15 && typ == ProtoWire.BYTES) {
                        return reader.consumeBytes()
                    }
                    reader.consumeFieldValue(num, typ)
                }
                error("field 15 missing")
            }

            fun f15Varints(bytes: ByteArray): Map<Int, Long> {
                val out = mutableMapOf<Int, Long>()
                val reader = ProtoWire.Reader(bytes)
                while (!reader.exhausted) {
                    val (num, typ) = reader.consumeTag()
                    when (typ) {
                        ProtoWire.VARINT -> out[num] = reader.consumeVarint()
                        else -> reader.consumeFieldValue(num, typ)
                    }
                }
                return out
            }

            // turn0 + 末位 user → f2 省略, f4=14
            var sub = f15Varints(f15(listOf(DevinPrompt(messageId = "u1", source = 1, content = "hi"))))
            assertNull(sub[2])
            assertEquals(4L, sub[3])
            assertEquals(14L, sub[4])

            // turn1 + 末位 user (且前一 prompt 也是 user → 不发 14)
            sub = f15Varints(f15(listOf(
                DevinPrompt(messageId = "u1", source = 1, content = "a"),
                DevinPrompt(messageId = "u2", source = 1, content = "b"),
            )))
            assertEquals(1L, sub[2])
            assertEquals(4L, sub[3])
            assertNull(sub[4])

            // turn2 + 末位 tool result → 无 f4
            sub = f15Varints(f15(listOf(
                DevinPrompt(messageId = "u1", source = 1, content = "a"),
                DevinPrompt(messageId = "a1", source = 2, content = "b"),
                DevinPrompt(messageId = "t1", source = 4, content = "c", toolCallId = "call_1"),
            )))
            assertEquals(2L, sub[2])
            assertEquals(4L, sub[3])
            assertNull(sub[4])

            // turn3 + 末位 user 且前一条非 user → 发 14
            sub = f15Varints(f15(listOf(
                DevinPrompt(messageId = "a1", source = 2, content = "b"),
                DevinPrompt(messageId = "u2", source = 1, content = "a"),
            )))
            assertEquals(3L, sub[2])
            assertEquals(14L, sub[4])
        } finally {
            resetDevinSessionTurnIndex(s)
        }
    }

    // ======================= Utf8SplitBuffer =======================

    @Test
    fun `utf8 split buffer joins multi byte runs`() {
        val buf = Utf8SplitBuffer()
        val c1 = byteArrayOf(0xE4.toByte(), 0xBD.toByte())
        val c2 = byteArrayOf(0xA0.toByte(), 0xE5.toByte(), 0xA5.toByte())
        val c3 = byteArrayOf(0xBD.toByte())
        assertEquals("", buf.feed(c1))
        assertEquals("你", buf.feed(c2))
        assertEquals("好", buf.feed(c3))
    }

    @Test
    fun `utf8 split buffer passes through invalid bytes`() {
        val buf = Utf8SplitBuffer()
        val out = buf.feed(byteArrayOf(0x61, 0xFF.toByte(), 0x62))
        // 'a' + 非法字节按错误 rune 处理 + 'b'
        assertEquals(3, out.length)
        assertEquals('a', out[0])
        assertEquals('b', out[2])
    }

    @Test
    fun `utf8 split buffer flush`() {
        val buf = Utf8SplitBuffer()
        assertEquals("", buf.feed(byteArrayOf(0xE4.toByte(), 0xBD.toByte())))
        // 流结束时 flush 拿残留
        val flushed = buf.flush()
        assertTrue(flushed.isNotEmpty())
    }

    // ======================= ChatToolCall (response field 6) =======================

    private fun frameWithToolCall(build: java.io.ByteArrayOutputStream.() -> Unit): DevinFrameResult {
        val toolCall = java.io.ByteArrayOutputStream().apply(build).toByteArray()
        val payload = java.io.ByteArrayOutputStream().apply {
            appendTag(6, ProtoWire.BYTES)
            appendBytes(toolCall)
        }.toByteArray()
        return parseDevinFrame(payload)
    }

    @Test
    fun `tool call delta decodes every wire field`() {
        // ChatToolCall: 1=id, 2=name, 3=arguments_json, 4=invalid_json_str,
        // 5=invalid_json_err, 6=is_custom_tool_call; 没有 index.
        val tc = frameWithToolCall {
            appendTag(1, ProtoWire.BYTES); appendString("call_1")
            appendTag(2, ProtoWire.BYTES); appendString("apply_patch")
            appendTag(4, ProtoWire.BYTES); appendString("*** Begin Patch\n+hello")
            appendTag(5, ProtoWire.BYTES); appendString("invalid character")
            appendTag(6, ProtoWire.VARINT); appendVarint(1)
        }.toolCallDeltas.single()
        assertEquals("call_1", tc.id)
        assertEquals("apply_patch", tc.name)
        assertEquals("", tc.arguments)
        assertEquals("*** Begin Patch\n+hello", tc.invalidJsonStr)
        assertEquals("invalid character", tc.invalidJsonErr)
        assertTrue(tc.isCustomToolCall)
    }

    @Test
    fun `tool call delta with arguments json only`() {
        val tc = frameWithToolCall {
            appendTag(1, ProtoWire.BYTES); appendString("call_9")
            appendTag(3, ProtoWire.BYTES); appendString("""{"command":"ls"}""")
        }.toolCallDeltas.single()
        assertEquals("""{"command":"ls"}""", tc.arguments)
        assertEquals("", tc.invalidJsonStr)
        assertFalse(tc.isCustomToolCall)
    }

    // ======================= ClientMetadata =======================

    @Test
    fun `client metadata identifies as chisel without field 28`() {
        val meta = buildDevinClientMetadataBytes(
            sessionToken = "tok-123",
            deviceSeed = "seed-1",
            osName = "linux",
        )
        val fields = mutableMapOf<Int, String>()
        val reader = ProtoWire.Reader(meta)
        while (!reader.exhausted) {
            val (num, typ) = reader.consumeTag()
            assertEquals(ProtoWire.BYTES, typ)
            fields[num] = reader.consumeBytes().toString(Charsets.UTF_8)
        }
        assertEquals(DEVIN_DEFAULT_CLIENT_NAME, fields[1])
        assertEquals(DEVIN_DEFAULT_CLIENT_NAME, fields[12])
        assertFalse(28 in fields)
        assertEquals(DEVIN_FINGERPRINT_HEX_LEN, fields[31]?.length)
    }

    // ======================= usage =======================

    @Test
    fun `usage split across frames merges per field before summing`() {
        // f2/f3 在一帧, f5/f4 在后一帧: 逐帧求和会丢掉一半; 必须先逐字段合并.
        val first = DevinUsage(promptTokens = 3, completionTokens = 39, modelName = "m")
        val second = DevinUsage(cachedTokens = 19179, cacheWriteTokens = 58, requestId = "req")
        val merged = null.mergeFrom(first).mergeFrom(second)
        assertEquals(3L, merged.promptTokens)
        assertEquals(39L, merged.completionTokens)
        assertEquals(19179L, merged.cachedTokens)
        assertEquals(58L, merged.cacheWriteTokens)
        assertEquals("m", merged.modelName)
        assertEquals("req", merged.requestId)
        val usage = merged.toTokenUsage()
        assertEquals(3 + 19179 + 58, usage.promptTokens)
        assertEquals(19179, usage.cachedTokens)
        assertEquals(3 + 19179 + 58 + 39, usage.totalTokens)
        // 后到的 0 不覆盖已有值
        assertEquals(39L, merged.mergeFrom(DevinUsage(completionTokens = 0)).completionTokens)
    }
}
