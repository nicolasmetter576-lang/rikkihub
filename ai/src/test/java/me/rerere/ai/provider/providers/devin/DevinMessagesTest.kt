package me.rerere.ai.provider.providers.devin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DevinReasoningMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class DevinMessagesTest {

    private fun textMsg(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun fakeTool(name: String, desc: String = "", schema: InputSchema? = null) = Tool(
        name = name,
        description = desc,
        parameters = { schema },
        execute = { emptyList() },
    )

    @Test
    fun `system messages merge into systemPrompt`() {
        val bundle = buildDevinMessages(
            listOf(
                textMsg(MessageRole.SYSTEM, "first"),
                textMsg(MessageRole.SYSTEM, "second"),
                textMsg(MessageRole.USER, "hello"),
            ),
            emptyList(),
        )
        assertEquals("first\nsecond", bundle.systemPrompt)
        assertEquals(1, bundle.prompts.size)
        assertEquals(1, bundle.prompts[0].source)
        assertEquals("hello", bundle.prompts[0].content)
    }

    @Test
    fun `user message maps text and data url images`() {
        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("look"),
                UIMessagePart.Image("data:image/png;base64,aGVsbG8="),
                UIMessagePart.Image("https://example.com/x.png"), // 非 data: 跳过
            ),
        )
        val bundle = buildDevinMessages(listOf(msg), emptyList())
        val p = bundle.prompts.single()
        assertEquals(1, p.source)
        assertEquals("look", p.content)
        assertEquals(msg.id.toString(), p.messageId)
        assertEquals(1, p.images.size)
        assertEquals("aGVsbG8=", p.images[0].base64Data)
        assertEquals("image/png", p.images[0].mimeType)
    }

    @Test
    fun `assistant unexecuted tool goes to f6 in same prompt`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("calling tool"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "get_weather",
                    input = """{"city":"sf"}""",
                    output = emptyList(),
                ),
            ),
        )
        val bundle = buildDevinMessages(listOf(msg), emptyList())
        assertEquals(1, bundle.prompts.size)
        val p = bundle.prompts[0]
        assertEquals(2, p.source)
        assertEquals("calling tool", p.content)
        assertEquals(1, p.toolCalls.size)
        assertEquals("call-1", p.toolCalls[0].id)
        assertEquals("get_weather", p.toolCalls[0].name)
        assertEquals("""{"city":"sf"}""", p.toolCalls[0].arguments)
    }

    @Test
    fun `assistant executed tool emits call then source4 result`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("calling"),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "get_weather",
                    input = """{"city":"sf"}""",
                    output = listOf(UIMessagePart.Text("sunny")),
                ),
                UIMessagePart.Tool(
                    toolCallId = "call-2",
                    toolName = "get_time",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("noon")),
                ),
            ),
        )
        val bundle = buildDevinMessages(listOf(msg), emptyList())
        assertEquals(3, bundle.prompts.size)

        val assistant = bundle.prompts[0]
        assertEquals(2, assistant.source)
        assertEquals("calling", assistant.content)
        assertEquals(listOf("call-1", "call-2"), assistant.toolCalls.map { it.id })

        val r1 = bundle.prompts[1]
        assertEquals(4, r1.source)
        assertEquals("call-1", r1.toolCallId)
        assertEquals("sunny", r1.content)

        val r2 = bundle.prompts[2]
        assertEquals(4, r2.source)
        assertEquals("call-2", r2.toolCallId)
        assertEquals("noon", r2.content)
    }

    @Test
    fun `content after tool group starts new assistant prompt`() {
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1", toolName = "t", input = "{}",
                    output = listOf(UIMessagePart.Text("done")),
                ),
                UIMessagePart.Text("after result"),
            ),
        )
        val bundle = buildDevinMessages(listOf(msg), emptyList())
        assertEquals(3, bundle.prompts.size)
        assertEquals(2, bundle.prompts[0].source)
        assertEquals(listOf("call-1"), bundle.prompts[0].toolCalls.map { it.id })
        assertEquals(4, bundle.prompts[1].source)
        assertEquals("done", bundle.prompts[1].content)
        assertEquals(2, bundle.prompts[2].source)
        assertEquals("after result", bundle.prompts[2].content)
    }

    @Test
    fun `assistant reasoning merges thinking signature and type`() {
        val sigBytes = "sig-bytes-123".toByteArray()
        val msg = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "deep thought",
                    metadata = DevinReasoningMetadata(
                        signature = DevinTestGolden.b64encode(sigBytes),
                        signatureType = "sealed",
                    ).toMetadata(),
                ),
                UIMessagePart.Text("answer"),
            ),
        )
        val bundle = buildDevinMessages(listOf(msg), emptyList())
        val p = bundle.prompts.single()
        assertEquals(2, p.source)
        assertEquals("answer", p.content)
        assertEquals("deep thought", p.thinking)
        assertArrayEquals(sigBytes, p.signature)
        assertEquals("sealed", p.signatureType)
    }

    @Test
    fun `legacy tool role message maps to source4`() {
        @Suppress("DEPRECATION")
        val msg = UIMessage(
            role = MessageRole.TOOL,
            parts = listOf(
                UIMessagePart.ToolResult(
                    toolCallId = "call-9",
                    toolName = "t",
                    content = JsonPrimitive("the output"),
                    arguments = JsonPrimitive("{}"),
                ),
            ),
        )
        val bundle = buildDevinMessages(listOf(msg), emptyList())
        val p = bundle.prompts.single()
        assertEquals(4, p.source)
        assertEquals("call-9", p.toolCallId)
        assertEquals("the output", p.content)
    }

    @Test
    fun `tools convert with json schema bytes`() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("city", buildJsonObject {
                    put("type", "string")
                })
            },
            required = listOf("city"),
        )
        val bundle = buildDevinMessages(
            emptyList(),
            listOf(fakeTool("get_weather", "desc", schema)),
        )
        assertEquals(1, bundle.tools.size)
        val t = bundle.tools[0]
        assertEquals("get_weather", t.name)
        assertEquals("desc", t.description)
        val params = t.parameters.toString(Charsets.UTF_8)
        assertTrue(params.contains("\"type\":\"object\""))
        assertTrue(params.contains("\"city\""))
    }

    @Test
    fun `task_id description rewrite happens on wire`() {
        val req = buildDevinGetChatMessageRequest(
            sessionToken = "t", deviceSeed = "s", chatModelUid = "swe-2-high",
            systemPrompt = "", prompts = emptyList(),
            tools = listOf(
                DevinTool(name = "agent", description = "Spawn. Takes a task_id parameter identifying the task.", parameters = ByteArray(0))
            ),
            temperature = null, maxTokens = 100,
            sessionId = "wire-rewrite-${System.nanoTime()}", cascadeId = "c",
            osName = "linux", clientVersion = "3000.10.21",
        )
        val s = req.toString(Charsets.UTF_8)
        assertTrue(s.contains("Takes a taskId parameter identifying the task"))
        assertTrue(!s.contains("Takes a task_id parameter"))
    }

    @Test
    fun `empty message id falls back to uuid on wire`() {
        val req = buildDevinGetChatMessageRequest(
            sessionToken = "t", deviceSeed = "s", chatModelUid = "swe-2-high",
            systemPrompt = "",
            prompts = listOf(DevinPrompt(messageId = "", source = 1, content = "hi")),
            tools = emptyList(),
            temperature = null, maxTokens = 100,
            sessionId = "uuid-fallback-${System.nanoTime()}", cascadeId = "c",
            osName = "linux", clientVersion = "3000.10.21",
        )
        // prompt f1 应包含一个生成的 UUID (36 字符, 4 个 '-')
        val reader = ProtoWire.Reader(req)
        var promptBytes: ByteArray? = null
        while (!reader.exhausted) {
            val (num, typ) = reader.consumeTag()
            if (num == 3 && typ == ProtoWire.BYTES) {
                promptBytes = reader.consumeBytes()
            } else {
                reader.consumeFieldValue(num, typ)
            }
        }
        val pReader = ProtoWire.Reader(promptBytes!!)
        assertEquals(1 to ProtoWire.BYTES, pReader.consumeTag())
        val msgId = pReader.consumeBytes().toString(Charsets.UTF_8)
        assertEquals(36, msgId.length)
        assertEquals(4, msgId.count { it == '-' })
        assertTrue(Uuid.parse(msgId).toString() == msgId)
    }
}
