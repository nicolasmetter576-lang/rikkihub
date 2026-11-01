package me.rerere.ai.provider.providers.google

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 离线测试：Google/Vertex 工具 schema 的 `propertyNames` 兼容过滤。
 *
 * `propertyNames` 是 JSON Schema keyword，Gemini/Vertex 收到后可能返回 400，
 * 需要在请求构建期递归删除。但删除必须是 schema 感知的：
 *  - `properties`/`patternProperties`/`$defs`/`definitions`/`dependentSchemas`
 *    是名映射，映射里名为 "propertyNames" 的**字段**是合法字段名，必须保留；
 *  - `enum`/`default`/`examples` 等 literal data 不能当 schema 递归，不能误删
 *    其中的 "propertyNames" 键；
 *  - 其余键（type/description/properties/required/items/allOf/not 等）原样保留，
 *    既有 removeElements 过滤（const/format/additionalProperties/enum/…）行为不变。
 *
 * 验证走真实构建路径：`generateText` → `buildCompletionRequestBody` → loopback
 * HttpServer 抓包；Vertex 分支（`vertexAI=true`）用 OkHttp 拦截器短路响应，
 * 因 Vertex URL 固定指向 aiplatform.googleapis.com，无法重定向到 loopback。
 */
class GoogleToolSchemaCompatTest {

    /** 最小 loopback：记录请求体，返回脚本化响应。 */
    private class FakeEndpoint(private val path: String, private val responseBody: String) :
        AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val bodies = ConcurrentLinkedQueue<String>()

        init {
            server.createContext(path) { exchange ->
                try {
                    bodies += exchange.requestBody.readBytes().decodeToString()
                    val bytes = responseBody.toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private val okResponseBody = """{"candidates":[{"role":"model","content":{"parts":[{"text":"ok"}]}}]}"""

    /** 拦截器短路客户端：捕获请求并直接返回脚本响应（Vertex 路径用）。 */
    private fun capturingClient(captured: ConcurrentLinkedQueue<okhttp3.Request>, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                captured += request
                okhttp3.Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    private fun chatModel() = Model(
        modelId = "gemini-test",
        type = me.rerere.ai.provider.ModelType.CHAT,
        abilities = listOf(ModelAbility.TOOL),
    )

    private fun textParams(tools: List<Tool>) = TextGenerationParams(
        model = chatModel(),
        tools = tools,
    )

    private fun toolWithSchema(schema: InputSchema?) = Tool(
        name = "demo_tool",
        description = "demo",
        parameters = { schema },
        execute = { emptyList() },
    )

    /** 从抓包 body 中取出 tools[0].functionDeclarations[0].parameters。 */
    private fun parametersOf(requestBody: String): JsonObject =
        me.rerere.ai.util.json.parseToJsonElement(requestBody).jsonObject
            .getValue("tools").jsonArray[0].jsonObject
            .getValue("functionDeclarations").jsonArray[0].jsonObject
            .getValue("parameters").jsonObject

    /** 经反射直接调私有 buildCompletionRequestBody（与 generateText/streamText 共用）。 */
    private fun buildBody(provider: GoogleProvider, params: TextGenerationParams): JsonObject {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        return method.invoke(provider, listOf(UIMessage.user("hi")), params) as JsonObject
    }

    private fun schemaPropertyNamesOf(body: JsonObject): JsonObject =
        body.getValue("tools").jsonArray[0].jsonObject
            .getValue("functionDeclarations").jsonArray[0].jsonObject
            .getValue("parameters").jsonObject

    // ==================== generateText 全路径（Gemini Developer API） ====================

    @Test
    fun `generateText strips propertyNames keyword recursively but keeps same-named fields`() =
        runBlocking {
            FakeEndpoint("/generate/models/gemini-test:generateContent", okResponseBody).use { ep ->
                val provider = GoogleProvider(OkHttpClient())
                val setting = ProviderSetting.Google(
                    baseUrl = "${ep.baseUrl}/generate",
                    apiKey = "test-key",
                )
                // InputSchema.Obj.properties 是 JsonObject：可在属性值内塞任意深层 schema
                val schema = InputSchema.Obj(
                    properties = buildJsonObject {
                        // 用户合法字段名恰好叫 propertyNames —— 不得误删
                        put("propertyNames", buildJsonObject {
                            put("type", "string")
                            put("description", "a user-defined property")
                        })
                        // 嵌套 schema 节点上的 keyword：应被删除
                        put("nested", buildJsonObject {
                            put("type", "object")
                            put("propertyNames", buildJsonObject { put("maxLength", 5) })
                            put("properties", buildJsonObject {
                                put("leaf", buildJsonObject {
                                    put("type", "string")
                                    put("propertyNames", buildJsonObject { put("minLength", 1) })
                                    put("default", buildJsonObject {
                                        // literal data 里的同名键必须保留
                                        put("propertyNames", "literal-value")
                                    })
                                })
                                // 名映射里合法字段名叫 propertyNames —— 保留
                                put("propertyNames", buildJsonObject {
                                    put("type", "number")
                                    put("propertyNames", buildJsonObject { put("minimum", 0) })
                                })
                            })
                            // Definitions belong to a schema node, not the user's properties name map.
                            put("\$defs", buildJsonObject {
                                put("propertyNames", buildJsonObject {
                                    put("type", "string")
                                    put("propertyNames", buildJsonObject { put("maxLength", 9) })
                                })
                            })
                            // 数组项 schema 上的 keyword：应被删除
                            put("items", buildJsonObject {
                                put("type", "string")
                                put("propertyNames", buildJsonObject { put("maxLength", 3) })
                            })
                        })
                    },
                    required = listOf("nested"),
                )

                provider.generateText(setting, listOf(UIMessage.user("hi")), textParams(
                    listOf(toolWithSchema(schema))
                ))

                val parameters = parametersOf(ep.bodies.single())

                // 顶层结构保留（InputSchema 编码产物：type/properties/required）
                assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
                assertTrue(parameters.containsKey("required"))
                assertTrue(parameters.containsKey("properties"))
                assertFalse(parameters.containsKey("propertyNames"))

                val props = parameters.getValue("properties").jsonObject

                // properties 里名为 propertyNames 的字段保留
                assertTrue("user property named propertyNames must survive",
                    props.containsKey("propertyNames"))
                assertEquals("string",
                    props.getValue("propertyNames").jsonObject["type"]?.jsonPrimitive?.content)

                // 嵌套 schema 上 keyword 全删
                val nested = props.getValue("nested").jsonObject
                assertFalse(nested.containsKey("propertyNames"))
                assertTrue(nested.containsKey("properties"))
                assertTrue(nested.containsKey("items"))

                val leaf = nested.getValue("properties").jsonObject.getValue("leaf").jsonObject
                assertFalse(leaf.containsKey("propertyNames"))
                assertEquals("string", leaf["type"]?.jsonPrimitive?.content)
                // default 是 literal data，其中同名键保留
                assertEquals("literal-value",
                    leaf.getValue("default").jsonObject
                        .getValue("propertyNames").jsonPrimitive.content)

                // properties 映射内名为 propertyNames 的字段：自身保留，但其值是 schema，
                // 值里的 keyword 仍应删除
                val mappedField = nested.getValue("properties").jsonObject
                    .getValue("propertyNames").jsonObject
                assertEquals("number", mappedField["type"]?.jsonPrimitive?.content)
                assertFalse(mappedField.containsKey("propertyNames"))

                // 数组 items 上的 keyword 删除
                val items = nested.getValue("items").jsonObject
                assertFalse(items.containsKey("propertyNames"))
                assertEquals("string", items["type"]?.jsonPrimitive?.content)

                // $defs 映射键保留、值递归
                val defs = nested.getValue("\$defs").jsonObject
                assertTrue(defs.containsKey("propertyNames"))
                val defValue = defs.getValue("propertyNames").jsonObject
                assertEquals("string", defValue["type"]?.jsonPrimitive?.content)
                assertFalse(defValue.containsKey("propertyNames"))
            }
        }

    @Test
    fun `generateText keeps existing incompatible-key filtering behavior`() = runBlocking {
        FakeEndpoint("/generate/models/gemini-test:generateContent", okResponseBody).use { ep ->
            val provider = GoogleProvider(OkHttpClient())
            val setting = ProviderSetting.Google(
                baseUrl = "${ep.baseUrl}/generate",
                apiKey = "test-key",
            )
            val schema = InputSchema.Obj(
                properties = buildJsonObject {
                    put("legacy", buildJsonObject {
                        put("type", "string")
                        put("format", "date-time")
                        put("additionalProperties", buildJsonObject { put("type", "string") })
                        put("exclusiveMinimum", 0)
                        put("exclusiveMaximum", 10)
                        put("const", "x")
                        put("enum", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("a"))
                        })
                    })
                }
            )

            provider.generateText(setting, listOf(UIMessage.user("hi")), textParams(
                listOf(toolWithSchema(schema))
            ))

            val parameters = parametersOf(ep.bodies.single())
            val legacy = parameters.getValue("properties").jsonObject
                .getValue("legacy").jsonObject

            // 既有 removeElements 过滤清单保持不变
            assertFalse(legacy.containsKey("format"))
            assertFalse(legacy.containsKey("additionalProperties"))
            assertFalse(legacy.containsKey("exclusiveMinimum"))
            assertFalse(legacy.containsKey("exclusiveMaximum"))
            assertFalse(legacy.containsKey("const"))
            assertFalse(legacy.containsKey("enum"))
            // 其余键原样
            assertEquals("string", legacy["type"]?.jsonPrimitive?.content)
        }
    }

    // ==================== Vertex 分支（拦截器短路，真实构建路径） ====================

    @Test
    fun `vertex generateText applies the same propertyNames filtering`() = runBlocking {
        val captured = ConcurrentLinkedQueue<okhttp3.Request>()
        val provider = GoogleProvider(capturingClient(captured, okResponseBody))
        val setting = ProviderSetting.Google(
            vertexAI = true,
            useServiceAccount = false,
            projectId = "test-project",
            location = "us-central1",
            apiKey = "vertex-key",
        )
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("dict", buildJsonObject {
                    put("type", "object")
                    put("propertyNames", buildJsonObject { put("maxLength", 4) })
                    put("properties", buildJsonObject {
                        put("propertyNames", buildJsonObject { put("type", "string") })
                    })
                })
            }
        )

        provider.generateText(setting, listOf(UIMessage.user("hi")), textParams(
            listOf(toolWithSchema(schema))
        ))

        val request = captured.single()
        // Vertex URL 走 aiplatform.googleapis.com + key query 参数
        assertEquals("aiplatform.googleapis.com", request.url.host)
        assertTrue(request.url.toString().contains(
            "publishers/google/models/gemini-test:generateContent"))
        assertEquals("vertex-key", request.url.queryParameter("key"))

        val bodyText = requireNotNull(request.body).let { body ->
            okio.Buffer().also { body.writeTo(it) }.readUtf8()
        }
        val parameters = parametersOf(bodyText)
        val dict = parameters.getValue("properties").jsonObject.getValue("dict").jsonObject
        assertFalse(dict.containsKey("propertyNames"))
        assertTrue(dict.getValue("properties").jsonObject.containsKey("propertyNames"))
    }

    // ==================== 私有构建器直接验证（与 generateText 同一实现） ====================

    @Test
    fun `buildCompletionRequestBody removes propertyNames under dependentSchemas and dependentRequired`() {
        val provider = GoogleProvider(OkHttpClient())
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("root", buildJsonObject {
                    put("type", "object")
                    put("propertyNames", buildJsonObject { put("maxLength", 2) })
                    put("dependencies", buildJsonObject {
                        put("propertyNames", buildJsonObject {
                            put("type", "object")
                            put("propertyNames", buildJsonObject { put("maxLength", 6) })
                        })
                    })
                    put("dependentSchemas", buildJsonObject {
                        // dependentSchemas 的值是 schema：递归删 keyword，映射键保留
                        put("propertyNames", buildJsonObject {
                            put("type", "object")
                            put("propertyNames", buildJsonObject { put("minLength", 1) })
                        })
                    })
                    put("dependentRequired", buildJsonObject {
                        // dependentRequired 是名映射（值为字符串数组），键保留
                        put("propertyNames", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("a"))
                        })
                    })
                    put("patternProperties", buildJsonObject {
                        // 正则键保留，值递归
                        put("^propertyNames$", buildJsonObject {
                            put("type", "string")
                            put("propertyNames", buildJsonObject { put("maxLength", 7) })
                        })
                    })
                })
            }
        )

        val body = buildBody(provider, textParams(listOf(toolWithSchema(schema))))
        val parameters = schemaPropertyNamesOf(body)
        val root = parameters.getValue("properties").jsonObject.getValue("root").jsonObject

        assertFalse(root.containsKey("propertyNames"))

        val dependencies = root.getValue("dependencies").jsonObject
        assertTrue(dependencies.containsKey("propertyNames"))
        assertFalse(dependencies.getValue("propertyNames").jsonObject.containsKey("propertyNames"))
        val depSchemas = root.getValue("dependentSchemas").jsonObject
        assertTrue(depSchemas.containsKey("propertyNames"))
        val depSchemaValue = depSchemas.getValue("propertyNames").jsonObject
        assertEquals("object", depSchemaValue["type"]?.jsonPrimitive?.content)
        assertFalse(depSchemaValue.containsKey("propertyNames"))

        val depRequired = root.getValue("dependentRequired").jsonObject
        assertTrue(depRequired.containsKey("propertyNames"))

        val patternProps = root.getValue("patternProperties").jsonObject
        val patternValue = patternProps.getValue("^propertyNames$").jsonObject
        assertFalse(patternValue.containsKey("propertyNames"))
        assertEquals("string", patternValue["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `buildCompletionRequestBody keeps tools declarations and other schema keys intact`() {
        val provider = GoogleProvider(OkHttpClient())
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
            },
            required = listOf("name"),
        )
        val body = buildBody(provider, textParams(listOf(toolWithSchema(schema))))
        val tool = body.getValue("tools").jsonArray[0].jsonObject
            .getValue("functionDeclarations").jsonArray[0].jsonObject

        // 工具 wire 声明结构不变：name/description/parameters 保留
        assertEquals("demo_tool", tool["name"]?.jsonPrimitive?.content)
        assertEquals("demo", tool["description"]?.jsonPrimitive?.content)
        val parameters = tool.getValue("parameters").jsonObject
        assertEquals("object", parameters["type"]?.jsonPrimitive?.content)
        assertTrue(parameters.containsKey("properties"))
        assertTrue(parameters.containsKey("required"))
    }
}
