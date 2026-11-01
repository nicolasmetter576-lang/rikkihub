package me.rerere.ai.provider.providers.openai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 发送前工具表预检测试（离线 loopback，真实 OkHttp → 本机 HttpServer）。
 *
 * 预检作用于 `mergeCustomBody` 之后的最终请求体：customBody 删减/重排工具
 * 子集放行；未知工具、schema 冲突、非法 tool_choice、legacy functions、
 * 注册名冲突一律在发 HTTP 前以 [ToolStreamProtocolException] 拒绝。
 */
class ChatCompletionsRequestToolPreflightTest {

    /** 最小 loopback：记录每个请求体，按队列返回脚本化响应。 */
    private class FakeEndpoint : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val responses = ConcurrentLinkedQueue<Pair<Int, String>>()
        val bodies = ConcurrentLinkedQueue<String>()

        init {
            server.createContext("/v1/chat/completions") { exchange ->
                try {
                    bodies += exchange.requestBody.readBytes().decodeToString()
                    val (status, body) = responses.poll()
                        ?: (500 to """{"error":{"message":"no scripted response left"}}""")
                    val bytes = body.toByteArray()
                    exchange.responseHeaders.add(
                        "Content-Type",
                        if (status == 200 && body.startsWith("data:")) "text/event-stream" else "application/json",
                    )
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        val port: Int get() = server.address.port
        val requestCount: Int get() = bodies.size

        fun enqueueJson(body: String) = responses.add(200 to body)
        fun enqueueSse(vararg events: String) = responses.add(
            200 to events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
        )

        override fun close() = server.stop(0)
    }

    private val schema = InputSchema.Obj(
        properties = buildJsonObject {
            put("path", buildJsonObject { put("type", "string") })
        },
        required = null,
    )

    /** 与请求构造同一份编码路径生成的声明 schema JsonElement（exactly-once 比对基准）。 */
    private fun schemaJson(s: InputSchema = schema): JsonElement = json.encodeToJsonElement(s)

    private fun tool(
        name: String,
        calls: AtomicInteger? = null,
    ): Tool = Tool(
        name = name,
        description = "tool $name",
        parameters = {
            calls?.incrementAndGet()
            schema
        },
        execute = { emptyList() },
    )

    /** 与构造端一致的 wire 工具声明 {type:"function", function:{...}}。 */
    private fun wireTool(name: String, parameters: JsonElement = schemaJson()): JsonElement =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", "tool $name")
                put("parameters", parameters)
            })
        }

    private fun params(
        tools: List<Tool> = emptyList(),
        customBody: List<CustomBody> = emptyList(),
    ): TextGenerationParams = TextGenerationParams(
        model = Model(modelId = "preflight-model", abilities = listOf(ModelAbility.TOOL)),
        tools = tools,
        customBody = customBody,
    )

    private fun provider(port: Int) = ProviderSetting.OpenAI(
        apiKey = "preflight-test-key",
        baseUrl = "http://127.0.0.1:$port/v1",
    )

    private fun sseText(text: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            addJsonObject {
                put("delta", buildJsonObject { put("content", text) })
                put("finish_reason", "stop")
            }
        })
    }.toString()

    private fun nonStreamText(text: String): String = buildJsonObject {
        put("id", "resp-1")
        put("model", "preflight-model")
        put("choices", buildJsonArray {
            addJsonObject {
                put("message", buildJsonObject {
                    put("role", "assistant")
                    put("content", text)
                })
                put("finish_reason", "stop")
            }
        })
    }.toString()

    private fun assertPreflightFails(
        endpoint: FakeEndpoint,
        api: ChatCompletionsAPI,
        params: TextGenerationParams,
        expectedCode: String? = null,
    ): ToolStreamProtocolException = runBlocking {
        try {
            api.streamText(provider(endpoint.port), listOf(UIMessage.user("hi")), params).toList()
            // fail() 返回 Unit 会让 try 表达式推断成 Any; 用 throw 保持 Nothing 类型
            throw AssertionError("expected ToolStreamProtocolException")
        } catch (e: ToolStreamProtocolException) {
            assertTrue(e.message.isNullOrBlank().not())
            expectedCode?.let { assertEquals(it, e.code) }
            e
        }
    }

    // ---------- 放行路径 ----------

    @Test
    fun `custom body narrowing and reordering tools is accepted`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(
                    tools = listOf(tool("tool_a"), tool("tool_b")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray { add(wireTool("tool_b")) }),
                    ),
                ),
            ).toList()

            assertEquals(1, endpoint.requestCount)
            val sent = json.parseToJsonElement(endpoint.bodies.single()).jsonObject
            val names = sent["tools"]!!.jsonArray.map {
                it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("tool_b"), names)
        }
    }

    @Test
    fun `custom body reordering tools keeps both entries`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(
                    tools = listOf(tool("tool_a"), tool("tool_b")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(wireTool("tool_b"))
                            add(wireTool("tool_a"))
                        }),
                    ),
                ),
            ).toList()

            val sent = json.parseToJsonElement(endpoint.bodies.single()).jsonObject
            val names = sent["tools"]!!.jsonArray.map {
                it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("tool_b", "tool_a"), names)
        }
    }

    @Test
    fun `empty wire tool table via override is accepted`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(CustomBody("tools", buildJsonArray {})),
                ),
            ).toList()

            assertEquals(1, endpoint.requestCount)
            val sent = json.parseToJsonElement(endpoint.bodies.single()).jsonObject
            assertEquals(0, sent["tools"]!!.jsonArray.size)
        }
    }

    @Test
    fun `plain text request without tools is accepted`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(),
            ).toList()
            assertEquals(1, endpoint.requestCount)
            val sent = json.parseToJsonElement(endpoint.bodies.single()).jsonObject
            assertNull(sent["tools"])
        }
    }

    @Test
    fun `nested custom body key that happens to contain tools is not a wire table`() = runBlocking {
        // 预检针对顶层 wire `tools`；`extra.tools` 只是嵌套 payload，不误伤。
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(
                    customBody = listOf(
                        CustomBody("extra", buildJsonObject {
                            put("tools", buildJsonArray { add(JsonPrimitive("not-a-tool")) })
                        }),
                    ),
                ),
            ).toList()
            assertEquals(1, endpoint.requestCount)
        }
    }

    @Test
    fun `tool_choice string modes are accepted`() = runBlocking {
        for (mode in listOf("auto", "none", "required")) {
            FakeEndpoint().use { endpoint ->
                endpoint.enqueueSse(sseText("ok"))
                val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
                api.streamText(
                    provider(endpoint.port),
                    listOf(UIMessage.user("hi")),
                    params(
                        tools = listOf(tool("tool_a")),
                        customBody = listOf(CustomBody("tool_choice", JsonPrimitive(mode))),
                    ),
                ).toList()
                assertEquals(1, endpoint.requestCount)
            }
        }
    }

    @Test
    fun `named tool_choice referencing final wire tool is accepted`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(
                    tools = listOf(tool("tool_a"), tool("tool_b")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray { add(wireTool("tool_a")) }),
                        CustomBody("tool_choice", buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("name", "tool_a") })
                        }),
                    ),
                ),
            ).toList()
            assertEquals(1, endpoint.requestCount)
        }
    }

    // ---------- 拒绝路径（全部发生在 HTTP 发送前） ----------

    @Test
    fun `custom body injecting unknown tool fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            val e = assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray { add(wireTool("rogue_tool")) }),
                    ),
                ),
                expectedCode = "unknown_tool",
            )
            assertTrue(e.message!!.contains("rogue_tool"))
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `custom body conflicting nested schema fails before send`() = runBlocking {
        // 嵌套字段差异也必须被检出（最终 merged 结构逐字节比对 parameters）。
        val tampered = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("path", buildJsonObject { put("type", "integer") }) // string -> integer
            })
        }
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray { add(wireTool("tool_a", tampered)) }),
                    ),
                ),
                expectedCode = "tool_schema_conflict",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `custom body wire table with duplicate names fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(wireTool("tool_a"))
                            add(wireTool("tool_a"))
                        }),
                    ),
                ),
                expectedCode = "invalid_tools_override",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `duplicate registered tool names fail instead of picking the first`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(tools = listOf(tool("dup"), tool("dup"))),
                expectedCode = "duplicate_tool_registration",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `named tool_choice referencing narrowed-out tool fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a"), tool("tool_b")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray { add(wireTool("tool_a")) }),
                        CustomBody("tool_choice", buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("name", "tool_b") })
                        }),
                    ),
                ),
                expectedCode = "unknown_tool_choice",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `invalid tool_choice mode fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(CustomBody("tool_choice", JsonPrimitive("parrot"))),
                ),
                expectedCode = "invalid_tool_choice",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `legacy functions override fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(CustomBody("functions", buildJsonArray {})),
                ),
                expectedCode = "unsupported_legacy_functions",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `legacy function_call override fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(
                        CustomBody("function_call", buildJsonObject { put("name", "tool_a") }),
                    ),
                ),
                expectedCode = "unsupported_legacy_functions",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `non array tools override fails before send`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            assertPreflightFails(
                endpoint, api,
                params(
                    tools = listOf(tool("tool_a")),
                    customBody = listOf(CustomBody("tools", JsonPrimitive("x"))),
                ),
                expectedCode = "invalid_tools_override",
            )
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `tool schema lambda is materialized exactly once`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueSse(sseText("ok"))
            val calls = AtomicInteger(0)
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            api.streamText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(
                    tools = listOf(tool("tool_a", calls), tool("tool_b", calls)),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray { add(wireTool("tool_b")) }),
                    ),
                ),
            ).toList()
            assertEquals(1, endpoint.requestCount)
            assertEquals(2, calls.get()) // 每个参数 lambda 恰一次
        }
    }

    @Test
    fun `non streaming request shares the same preflight`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            try {
                api.generateText(
                    provider(endpoint.port),
                    listOf(UIMessage.user("hi")),
                    params(
                        tools = listOf(tool("tool_a")),
                        customBody = listOf(
                            CustomBody("tools", buildJsonArray { add(wireTool("rogue_tool")) }),
                        ),
                    ),
                )
                org.junit.Assert.fail("expected ToolStreamProtocolException")
            } catch (e: ToolStreamProtocolException) {
                assertEquals("unknown_tool", e.code)
            }
            assertEquals(0, endpoint.requestCount)
        }
    }

    @Test
    fun `non streaming request sends after preflight passes`() = runBlocking {
        FakeEndpoint().use { endpoint ->
            endpoint.enqueueJson(nonStreamText("done"))
            val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
            val result = api.generateText(
                provider(endpoint.port),
                listOf(UIMessage.user("hi")),
                params(tools = listOf(tool("tool_a"))),
            )
            assertEquals(1, endpoint.requestCount)
            assertNotNull(result)
        }
    }
}
