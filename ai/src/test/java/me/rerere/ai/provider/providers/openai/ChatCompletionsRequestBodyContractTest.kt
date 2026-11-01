package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ChatCompletions 请求体字段契约测试（离线，经反射调私有
 * `buildChatCompletionRequest`，不发网络请求）：
 *
 * - `role=tool` 结果消息只带 `role`/`tool_call_id`/`content`，无 `name`；
 *   assistant `tool_calls[].function.name` 与顶层 `tools[].function.name`
 *   声明名保持不变。
 * - 通用分支 OFF 直发 `reasoning_effort:"none"`（不再改写 low）；
 *   DashScope 统一改发 `reasoning_effort`（AUTO 不发），不再使用
 *   `enable_thinking`/`thinking_budget`；customBody 对最终 body 拥有
 *   最后覆写权；其余服务专项分支（volces/bigmodel/mistral/siliconflow/
 *   opencode/moonshot/deepseek/nvidia）保持既有行为。
 */
class ChatCompletionsRequestBodyContractTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // 与 ChatCompletionsMoonshotRequestTest 相同的反射入口（保留的历史兼容签名）
    private fun buildRequest(
        baseUrl: String = "https://api.openai.com/v1",
        reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
        reasoningAbility: Boolean = true,
        modelId: String = "test-model",
        customBody: List<CustomBody> = emptyList(),
        tools: List<Tool> = emptyList(),
        messages: List<UIMessage> = listOf(UIMessage.user("hi")),
        maxTokens: Int? = null,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        val abilities = mutableListOf<ModelAbility>()
        if (reasoningAbility) abilities += ModelAbility.REASONING
        if (tools.isNotEmpty()) abilities += ModelAbility.TOOL
        val params = TextGenerationParams(
            model = Model(modelId = modelId, abilities = abilities),
            reasoningLevel = reasoningLevel,
            customBody = customBody,
            tools = tools,
            maxTokens = maxTokens,
        )
        return method.invoke(
            api,
            messages,
            params,
            ProviderSetting.OpenAI(baseUrl = baseUrl),
            true
        ) as JsonObject
    }

    private fun effort(body: JsonObject): String? =
        body["reasoning_effort"]?.jsonPrimitive?.content

    // ==================== 通用 else 分支（OpenAI 官方及其他未列名 host） ====================

    @Test
    fun `generic host OFF sends reasoning_effort none without rewriting to low`() {
        val body = buildRequest(reasoningLevel = ReasoningLevel.OFF)
        assertEquals("none", effort(body))
    }

    @Test
    fun `generic host AUTO sends no reasoning fields`() {
        val body = buildRequest(reasoningLevel = ReasoningLevel.AUTO)
        assertNull(body["reasoning_effort"])
        assertNull(body["enable_thinking"])
        assertNull(body["thinking_budget"])
        assertNull(body["thinking"])
    }

    @Test
    fun `generic host LOW and HIGH pass effort through verbatim`() {
        assertEquals("low", effort(buildRequest(reasoningLevel = ReasoningLevel.LOW)))
        assertEquals("high", effort(buildRequest(reasoningLevel = ReasoningLevel.HIGH)))
    }

    @Test
    fun `model without reasoning ability emits no reasoning fields at all`() {
        val body = buildRequest(
            reasoningLevel = ReasoningLevel.HIGH,
            reasoningAbility = false,
        )
        assertNull(body["reasoning_effort"])
        assertNull(body["enable_thinking"])
        assertNull(body["thinking_budget"])
        assertNull(body["thinking"])
        assertNull(body["reasoning"])
        assertNull(body["thinking_mode"])
    }

    // ==================== DashScope（阿里云百炼） ====================

    @Test
    fun `dashscope OFF sends reasoning_effort none and no legacy thinking fields`() {
        val body = buildRequest(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            reasoningLevel = ReasoningLevel.OFF,
        )
        assertEquals("none", effort(body))
        assertNull(body["enable_thinking"])
        assertNull(body["thinking_budget"])
    }

    @Test
    fun `dashscope LOW sends reasoning_effort low and no legacy thinking fields`() {
        val body = buildRequest(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            reasoningLevel = ReasoningLevel.LOW,
        )
        assertEquals("low", effort(body))
        assertNull(body["enable_thinking"])
        assertNull(body["thinking_budget"])
    }

    @Test
    fun `dashscope HIGH sends reasoning_effort high and no legacy thinking fields`() {
        val body = buildRequest(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertEquals("high", effort(body))
        assertNull(body["enable_thinking"])
        assertNull(body["thinking_budget"])
    }

    @Test
    fun `dashscope AUTO sends nothing at all`() {
        val body = buildRequest(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            reasoningLevel = ReasoningLevel.AUTO,
        )
        assertNull(body["reasoning_effort"])
        assertNull(body["enable_thinking"])
        assertNull(body["thinking_budget"])
    }

    // ==================== customBody 最后覆写权 ====================

    @Test
    fun `custom body overrides generic reasoning_effort and unrelated fields`() {
        val body = buildRequest(
            reasoningLevel = ReasoningLevel.HIGH,
            customBody = listOf(
                CustomBody("reasoning_effort", JsonPrimitive("minimal")),
                CustomBody("parallel_tool_calls", JsonPrimitive(false)),
            ),
        )
        assertEquals("minimal", effort(body))
        assertEquals(false, body["parallel_tool_calls"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `custom body overrides dashscope wire fields as last writer`() {
        val body = buildRequest(
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            reasoningLevel = ReasoningLevel.HIGH,
            customBody = listOf(
                CustomBody("reasoning_effort", JsonPrimitive("low")),
                CustomBody("enable_thinking", JsonPrimitive(true)),
            ),
        )
        assertEquals("low", effort(body))
        // customBody 最后覆写：用户显式注入的字段按原值保留
        assertEquals(true, body["enable_thinking"]?.jsonPrimitive?.booleanOrNull)
    }

    // ==================== 其他服务专项分支不变 ====================

    @Test
    fun `volces keeps thinking type enabled disabled contract`() {
        val off = buildRequest(
            baseUrl = "https://ark.cn-beijing.volces.com/v1",
            reasoningLevel = ReasoningLevel.OFF,
        )
        assertEquals("disabled", off["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(off["reasoning_effort"])

        val high = buildRequest(
            baseUrl = "https://ark.cn-beijing.volces.com/v1",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertEquals("enabled", high["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(high["reasoning_effort"])
    }

    @Test
    fun `zhipu keeps thinking type enabled disabled contract`() {
        val off = buildRequest(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            reasoningLevel = ReasoningLevel.OFF,
        )
        assertEquals("disabled", off["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)

        val high = buildRequest(
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertEquals("enabled", high["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `mistral emits no reasoning fields and no stream options`() {
        val body = buildRequest(
            baseUrl = "https://api.mistral.ai/v1",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertNull(body["reasoning_effort"])
        assertNull(body["thinking"])
        assertNull(body["enable_thinking"])
        assertNull(body["stream_options"]) // mistral 不支持 stream_options（既有保护）
    }

    @Test
    fun `siliconflow only sends enable_thinking for whitelisted models`() {
        val listed = buildRequest(
            baseUrl = "https://api.siliconflow.cn/v1",
            reasoningLevel = ReasoningLevel.HIGH,
            modelId = "zai-org/GLM-4.6",
        )
        assertEquals(true, listed["enable_thinking"]?.jsonPrimitive?.booleanOrNull)

        val unlisted = buildRequest(
            baseUrl = "https://api.siliconflow.cn/v1",
            reasoningLevel = ReasoningLevel.HIGH,
            modelId = "some/other-model",
        )
        assertNull(unlisted["enable_thinking"])
        assertNull(unlisted["reasoning_effort"])
    }

    @Test
    fun `opencode passes effort through except AUTO`() {
        val high = buildRequest(
            baseUrl = "https://opencode.ai/v1",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertEquals("high", effort(high))
        val auto = buildRequest(
            baseUrl = "https://opencode.ai/v1",
            reasoningLevel = ReasoningLevel.AUTO,
        )
        assertNull(auto["reasoning_effort"])
    }

    @Test
    fun `moonshot keeps thinking type contract without effort`() {
        val body = buildRequest(
            baseUrl = "https://api.moonshot.cn/v1",
            reasoningLevel = ReasoningLevel.OFF,
            modelId = "some-model",
        )
        assertEquals("disabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun `deepseek keeps thinking plus mapped reasoning_effort`() {
        val off = buildRequest(
            baseUrl = "https://api.deepseek.com/v1",
            reasoningLevel = ReasoningLevel.OFF,
        )
        assertEquals("disabled", off["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertNull(off["reasoning_effort"]) // 关闭思考时不再附带 effort

        val high = buildRequest(
            baseUrl = "https://api.deepseek.com/v1",
            reasoningLevel = ReasoningLevel.HIGH,
        )
        assertEquals("enabled", high["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("high", effort(high))
    }

    @Test
    fun `nvidia non deepseek v4 still rewrites none to low`() {
        // 仅通用 else 分支改为直发 none；nvidia 专项分支保留旧改写
        val body = buildRequest(
            baseUrl = "https://integrate.api.nvidia.com/v1",
            reasoningLevel = ReasoningLevel.OFF,
            modelId = "meta/llama-3.1",
        )
        assertEquals("low", effort(body))
    }

    // ==================== maxTokens 直通（缓存保活 maxTokens=1 专项路径共用） ====================

    @Test
    fun `maxTokens one passes through as max_tokens unchanged`() {
        val body = buildRequest(maxTokens = 1)
        assertEquals(1, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
    }

    // ==================== tool 结果消息无 name ====================

    private val weatherTool = Tool(
        name = "get_weather",
        description = "get weather",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { emptyList() },
    )

    private fun executedTool(callId: String, name: String, output: String) = UIMessagePart.Tool(
        toolCallId = callId,
        toolName = name,
        input = """{"city":"sh"}""",
        output = listOf(UIMessagePart.Text(output)),
    )

    @Test
    fun `tool result message has tool_call_id and content but no name`() {
        val body = buildRequest(
            tools = listOf(weatherTool),
            messages = listOf(
                UIMessage.user("weather?"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Text("checking"),
                        executedTool("call_42", "get_weather", "sunny 25C"),
                    ),
                ),
            ),
        )

        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        val toolResult = msgs.single { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("call_42", toolResult["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("sunny 25C", toolResult["content"]?.jsonPrimitive?.content)
        assertFalse("tool result message must not contain name", toolResult.containsKey("name"))

        // assistant tool_calls 的声明名不变
        val assistant = msgs.single { it["role"]?.jsonPrimitive?.content == "assistant" }
        val call = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_42", call["id"]?.jsonPrimitive?.content)
        assertEquals("function", call["type"]?.jsonPrimitive?.content)
        assertEquals(
            "get_weather",
            call["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )

        // 顶层 tools 声明名不变
        val decl = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals(
            "get_weather",
            decl["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `parallel tool results each omit name while keeping ids and contents`() {
        val body = buildRequest(
            tools = listOf(weatherTool),
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        executedTool("call_1", "get_weather", "r1"),
                        executedTool("call_2", "get_weather", "r2"),
                    ),
                ),
            ),
        )
        val toolResults = body["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(2, toolResults.size)
        assertEquals(listOf("call_1", "call_2"),
            toolResults.map { it["tool_call_id"]?.jsonPrimitive?.content })
        assertEquals(listOf("r1", "r2"),
            toolResults.map { it["content"]?.jsonPrimitive?.content })
        assertTrue(toolResults.none { it.containsKey("name") })
    }
}
