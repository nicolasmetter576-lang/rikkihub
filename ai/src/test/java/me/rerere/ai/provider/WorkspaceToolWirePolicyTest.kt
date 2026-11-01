package me.rerere.ai.provider

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.devin.DevinTool
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import java.lang.reflect.InvocationTargetException

/**
 * 工作区禁用工具最终 wire 保护（[WorkspaceToolWirePolicy]）离线单测。
 *
 * 覆盖五个协议的最终请求构建出口：OpenAI Chat Completions / Responses
 * （Codex 复用）、Claude `/messages`（含 CC disguise 后检查）、Google
 * generateContent、Devin GetChatMessage 工具列表。全部构造体级检查，不发
 * 网络请求；provider 私有构造器经反射调用，反射包装的异常先 unwrap。
 */
class WorkspaceToolWirePolicyTest {

    private val forbidden = WorkspaceToolWirePolicy(
        forbiddenWireNames = setOf("workspace_shell", "read_file", "subagent"),
    )

    private val unrelatedPolicy = WorkspaceToolWirePolicy(
        forbiddenWireNames = setOf("unrelated_forbidden_name"),
    )

    private val emptyPolicy = WorkspaceToolWirePolicy()

    private fun tool(name: String) = Tool(
        name = name,
        description = "tool $name",
        parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
        execute = { emptyList() },
    )

    private fun toolModel() = Model(
        modelId = "policy-model",
        abilities = listOf(ModelAbility.TOOL),
    )

    private fun params(
        tools: List<Tool> = emptyList(),
        customBody: List<CustomBody> = emptyList(),
        policy: WorkspaceToolWirePolicy? = null,
        model: Model = toolModel(),
    ) = TextGenerationParams(
        model = model,
        tools = tools,
        customBody = customBody,
        workspaceToolPolicy = policy,
    )

    /** Chat Completions wire 工具条目形态：{"type":"function","function":{...}} */
    private fun chatCompletionsWireTool(
        name: String,
        parameters: JsonElement = json.encodeToJsonElement<InputSchema>(
            InputSchema.Obj(properties = JsonObject(emptyMap()))
        ),
    ) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", "tool $name")
            put("parameters", parameters)
        })
    }

    private fun assertConflict(block: () -> Unit): WorkspaceToolPolicyConflictException =
        assertThrows(WorkspaceToolPolicyConflictException::class.java) { block() }

    // ---------- pure seam：各协议名字采集与比对 ----------

    @Test
    fun `chat completions tools and tool_choice names are checked`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(chatCompletionsWireTool("search_web"))
            })
            put("tool_choice", buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "workspace_shell") })
            })
        }
        val e = assertConflict { forbidden.enforceChatCompletionsBody(body) }
        assertEquals(setOf("workspace_shell"), e.names)
        assertEquals("chat_completions", e.protocol)
        assertTrue(e.message!!.contains("workspace_shell"))
    }

    @Test
    fun `chat completions server tool entries with colliding name are skipped`() {
        // typed server/built-in 条目不是客户端函数声明：整项跳过，不比
        // name/server_label/allowed_tools，ServerTool 行为保持不扩大。
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "mcp")
                    put("name", "workspace_shell")
                    put("server_label", "x")
                    put("allowed_tools", buildJsonArray { add("subagent") })
                })
                add(buildJsonObject {
                    put("type", "image_generation")
                    put("name", "read_file")
                })
            })
        }
        forbidden.enforceChatCompletionsBody(body) // no throw
    }

    @Test
    fun `chat completions bare name entry without function container is skipped`() {
        // 裸 {"name":...} 不是 type=function/custom 声明，也不是带
        // function 容器的缺省 type 形态：不采集（形状由 preflight 判）。
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", "workspace_shell") })
            })
        }
        forbidden.enforceChatCompletionsBody(body) // no throw
    }

    @Test
    fun `chat completions absent type function container is treated as function`() {
        // 与 preflight 一致：无 type 但带 function 容器按 function 声明采集。
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("function", buildJsonObject { put("name", "workspace_shell") })
                })
            })
        }
        assertConflict { forbidden.enforceChatCompletionsBody(body) }
    }

    @Test
    fun `chat completions custom tool type names are checked`() {
        val tools = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "custom")
                    put("custom", buildJsonObject { put("name", "workspace_shell") })
                })
            })
        }
        assertConflict { forbidden.enforceChatCompletionsBody(tools) }

        val choice = buildJsonObject {
            put("tool_choice", buildJsonObject {
                put("type", "custom")
                put("custom", buildJsonObject { put("name", "subagent") })
            })
        }
        assertConflict { forbidden.enforceChatCompletionsBody(choice) }
    }

    @Test
    fun `chat completions legacy functions and function_call names are checked`() {
        val functions = buildJsonObject {
            put("functions", buildJsonArray {
                add(buildJsonObject { put("name", "subagent") })
            })
        }
        assertConflict { forbidden.enforceChatCompletionsBody(functions) }

        val functionCall = buildJsonObject {
            put("function_call", buildJsonObject { put("name", "read_file") })
        }
        assertConflict { forbidden.enforceChatCompletionsBody(functionCall) }
    }

    @Test
    fun `chat completions message history tool name field is not scanned`() {
        // 历史 assistant tool_calls / role=tool 结果消息里的 name 不是声明表；
        // 禁用工具的既有调用记录必须保留在 wire 上（政策只挡声明与选择器）。
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "tool")
                    put("name", "workspace_shell")
                    put("tool_call_id", "call_1")
                })
            })
        }
        forbidden.enforceChatCompletionsBody(body) // no throw
    }

    @Test
    fun `chat completions schema subtree name keys are not scanned`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(chatCompletionsWireTool(
                    name = "search_web",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("workspace_shell", buildJsonObject {
                                put("type", "string")
                            })
                        })
                        // schema 自定义字段里的 name 键不参与比对
                        put("name", "subagent")
                    },
                ))
            })
        }
        forbidden.enforceChatCompletionsBody(body) // no throw
    }

    @Test
    fun `responses flat tools and nested tool_choice names are checked`() {
        val toolEntry = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", "workspace_shell")
                })
            })
        }
        assertConflict { forbidden.enforceResponsesApiBody(toolEntry) }

        val toolChoice = buildJsonObject {
            put("tool_choice", buildJsonObject {
                put("type", "allowed_tools")
                put("mode", "required")
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", "subagent")
                    })
                    // 选择器里的字符串点名形态也采集
                    add(JsonPrimitive("workspace_shell"))
                })
            })
        }
        val e2 = assertConflict { forbidden.enforceResponsesApiBody(toolChoice) }
        assertEquals(setOf("subagent", "workspace_shell"), e2.names)
    }

    @Test
    fun `responses server tool entries with colliding names are skipped`() {
        // web_search / mcp / image_generation 等 server 条目整项跳过：
        // 其 name/server_label/allowed_tools 都不比对，ServerTool 不扩大。
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("type", "web_search") })
                add(buildJsonObject {
                    put("type", "mcp")
                    put("server_label", "workspace_shell")
                    put("name", "read_file")
                    put("allowed_tools", buildJsonArray { add("subagent") })
                })
                add(buildJsonObject {
                    put("type", "image_generation")
                    put("name", "workspace_shell")
                })
            })
        }
        forbidden.enforceResponsesApiBody(body) // no throw
    }

    @Test
    fun `claude tools and tool_choice names are checked`() {
        val toolEntry = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("name", "read_file")
                    put("input_schema", buildJsonObject {
                        put("type", "object")
                        // input_schema 容器内的 name 键不采集
                        put("name", "workspace_shell")
                    })
                })
            })
        }
        val e = assertConflict { forbidden.enforceClaudeMessagesBody(toolEntry) }
        assertEquals(setOf("read_file"), e.names)

        val toolChoice = buildJsonObject {
            put("tool_choice", buildJsonObject {
                put("type", "tool")
                put("name", "subagent")
            })
        }
        assertConflict { forbidden.enforceClaudeMessagesBody(toolChoice) }
    }

    @Test
    fun `claude typed server tool entries are skipped`() {
        // web_search_20250305 / code_execution_* 等 typed server tool 跳过；
        // 即使声明了与禁用名相同的 name 也不比对。
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "web_search_20250305")
                    put("name", "web_search")
                })
                add(buildJsonObject {
                    put("type", "code_execution_20250825")
                    put("name", "workspace_shell")
                })
            })
            // tool_choice type=any/auto/none 不携名
            put("tool_choice", buildJsonObject { put("type", "any") })
        }
        forbidden.enforceClaudeMessagesBody(body) // no throw
    }

    @Test
    fun `google function declarations and allowedFunctionNames are checked`() {
        val declaration = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        add(buildJsonObject { put("name", "workspace_shell") })
                    })
                })
            })
        }
        assertConflict { forbidden.enforceGoogleGenerateContentBody(declaration) }

        val toolConfig = buildJsonObject {
            put("toolConfig", buildJsonObject {
                put("functionCallingConfig", buildJsonObject {
                    put("mode", "ANY")
                    put("allowedFunctionNames", buildJsonArray {
                        add("subagent")
                        add("search_web")
                    })
                })
            })
        }
        val e = assertConflict { forbidden.enforceGoogleGenerateContentBody(toolConfig) }
        assertEquals(setOf("subagent"), e.names)
    }

    @Test
    fun `google built-in tool keys without forbidden names pass`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("googleSearch", buildJsonObject {}) })
            })
        }
        forbidden.enforceGoogleGenerateContentBody(body)
    }

    @Test
    fun `google non declaration tool keys are not expanded for names`() {
        // googleSearch/urlContext/mcpServers 等条目没有 functionDeclarations：
        // 其内部 name 键不采集，未知来源 name 重合不误伤。
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("googleSearch", buildJsonObject { put("name", "workspace_shell") })
                })
                add(buildJsonObject {
                    put("urlContext", buildJsonObject { put("name", "subagent") })
                })
            })
            put("toolConfig", buildJsonObject {
                put("retrievalConfig", buildJsonObject { put("name", "read_file") })
            })
        }
        forbidden.enforceGoogleGenerateContentBody(body) // no throw
    }

    @Test
    fun `devin wire tool names are checked`() {
        val tools = listOf(
            DevinTool(name = "search_web"),
            DevinTool(name = "read_file"),
        )
        val e = assertConflict { forbidden.enforceDevinChatTools(tools) }
        assertEquals(setOf("read_file"), e.names)
        assertEquals("devin_get_chat_message", e.protocol)

        forbidden.enforceDevinChatTools(listOf(DevinTool(name = "search_web")))
        forbidden.enforceDevinChatTools(emptyList())
    }

    @Test
    fun `null or empty policy preserves arbitrary custom configuration`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", "workspace_shell")
                })
            })
            put("tool_choice", buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject { put("name", "workspace_shell") })
            })
        }
        emptyPolicy.enforceChatCompletionsBody(body)
        emptyPolicy.enforceResponsesApiBody(body)
        emptyPolicy.enforceClaudeMessagesBody(body)
        emptyPolicy.enforceGoogleGenerateContentBody(body)
        emptyPolicy.enforceDevinChatTools(listOf(DevinTool(name = "workspace_shell")))
    }

    @Test
    fun `policy with unrelated forbidden names does not touch other tools`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", "workspace_shell")
                })
            })
        }
        unrelatedPolicy.enforceChatCompletionsBody(body)
        unrelatedPolicy.enforceResponsesApiBody(body)
        unrelatedPolicy.enforceClaudeMessagesBody(body)
        unrelatedPolicy.enforceGoogleGenerateContentBody(body)
    }

    @Test
    fun `request captured context fields support epoch freshness check`() {
        // observer/保活侧取本请求捕获的 policyEpoch 比 currentEpoch；
        // 纯比较、不依赖 Android、不进 wire。
        val policy = WorkspaceToolWirePolicy(
            forbiddenWireNames = setOf("workspace_shell"),
            workspaceId = "ws-1",
            policyEpoch = 7L,
            viewFingerprint = "fp-abc",
        )
        assertEquals(false, policy.isStale(7L))
        assertEquals(true, policy.isStale(8L))
        assertEquals(true, policy.isStale(null))
        assertEquals(false, WorkspaceToolWirePolicy().isStale(1L))
        assertEquals(false, WorkspaceToolWirePolicy().isStale(null))
        // 本地上下文字段不干扰 enforce 语义
        assertConflict {
            policy.enforceDevinChatTools(listOf(DevinTool(name = "workspace_shell")))
        }
    }

    @Test
    fun `conflict names are sorted and deduplicated`() {
        val body = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", "subagent") })
                add(buildJsonObject { put("name", "workspace_shell") })
                add(buildJsonObject { put("name", "workspace_shell") })
            })
        }
        val e = assertConflict { forbidden.enforceClaudeMessagesBody(body) }
        assertEquals(setOf("subagent", "workspace_shell"), e.names)
        // 只携带工具名与协议标识，不带请求正文
        assertTrue(e.message!!.contains("subagent"))
        assertTrue(e.message!!.contains("workspace_shell"))
        assertTrue(e.message!!.contains("claude_messages"))
    }

    // ---------- provider 构造出口集成（反射私有构造器，不联网） ----------

    private val openAiSetting = ProviderSetting.OpenAI(
        apiKey = "k",
        baseUrl = "http://127.0.0.1:1/v1",
    )

    private fun buildChatCompletionsBody(
        params: TextGenerationParams,
    ): JsonObject {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        method.isAccessible = true
        try {
            return method.invoke(
                api,
                listOf(UIMessage.user("hi")),
                params,
                openAiSetting,
                false,
            ) as JsonObject
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun buildResponseBody(params: TextGenerationParams): JsonObject {
        val api = ResponseAPI(OkHttpClient())
        return api.buildRequestBody(
            providerSetting = openAiSetting,
            messages = listOf(UIMessage.user("hi")),
            params = params,
            stream = false,
        )
    }

    private fun buildClaudeBody(params: TextGenerationParams): JsonObject {
        val provider = ClaudeProvider(OkHttpClient())
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        method.isAccessible = true
        try {
            return method.invoke(
                provider,
                ProviderSetting.Claude(apiKey = "k"),
                listOf(UIMessage.user("hi")),
                params,
                false,
            ) as JsonObject
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun buildGoogleBody(params: TextGenerationParams): JsonObject {
        val provider = GoogleProvider(OkHttpClient())
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
        )
        method.isAccessible = true
        try {
            return method.invoke(
                provider,
                listOf(UIMessage.user("hi")),
                params,
            ) as JsonObject
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    @Test
    fun `chat completions customBody injecting forbidden tool conflicts before send`() {
        val e = assertConflict {
            buildChatCompletionsBody(
                params(
                    tools = listOf(tool("search_web")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(chatCompletionsWireTool("workspace_shell"))
                        }),
                    ),
                    policy = forbidden,
                ),
            )
        }
        assertEquals(setOf("workspace_shell"), e.names)
    }

    @Test
    fun `chat completions customBody named tool_choice conflicts`() {
        assertConflict {
            buildChatCompletionsBody(
                params(
                    tools = listOf(tool("search_web")),
                    customBody = listOf(
                        CustomBody("tool_choice", buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("name", "subagent") })
                        }),
                    ),
                    policy = forbidden,
                ),
            )
        }
    }

    @Test
    fun `chat completions null policy keeps existing preflight freedom`() {
        // null policy：已注册工具的删减子集 + 命名 tool_choice 保持放行，
        // 由既有 preflight（而非本政策）判定合法性。
        val body = buildChatCompletionsBody(
            params(
                tools = listOf(tool("tool_a"), tool("tool_b")),
                customBody = listOf(
                    CustomBody("tools", buildJsonArray {
                        add(chatCompletionsWireTool("tool_b"))
                    }),
                    CustomBody("tool_choice", buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject { put("name", "tool_b") })
                    }),
                ),
            ),
        )
        val names = body["tools"]!!.jsonArray.map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("tool_b"), names)
    }

    @Test
    fun `responses customBody injecting forbidden tool conflicts (codex path)`() {
        // Codex 复用 ResponseAPI.buildRequestBody；customBody 扁平注入命中即拒。
        val e = assertConflict {
            buildResponseBody(
                params(
                    tools = listOf(tool("search_web")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", "read_file")
                            })
                        }),
                    ),
                    policy = forbidden,
                ),
            )
        }
        assertEquals(setOf("read_file"), e.names)
        assertEquals("responses_api", e.protocol)
    }

    @Test
    fun `responses null policy keeps free customBody tool configuration`() {
        // 无政策时 customBody 可自由注入任意 tools 条目（现有宽松行为不变）。
        val body = buildResponseBody(
            params(
                customBody = listOf(
                    CustomBody("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", "arbitrary_custom_tool")
                        })
                    }),
                ),
            ),
        )
        val names = body["tools"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("arbitrary_custom_tool"), names)
    }

    @Test
    fun `claude customBody injecting forbidden tool conflicts`() {
        val e = assertConflict {
            buildClaudeBody(
                params(
                    tools = listOf(tool("search_web")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "workspace_shell")
                                put("input_schema", buildJsonObject {
                                    put("type", "object")
                                })
                            })
                        }),
                    ),
                    policy = forbidden,
                ),
            )
        }
        assertEquals(setOf("workspace_shell"), e.names)
        assertEquals("claude_messages", e.protocol)
    }

    @Test
    fun `claude policy check runs after cc disguise apply`() {
        // CC disguise 开启时检查仍在 applyBody 之后：伪装后的最终 body 里
        // 注入的禁用名同样被拒（且 metadata.user_id 已存在证明 apply 生效）。
        val disguisedModel = toolModel().copy(ccDisguise = true)
        assertConflict {
            buildClaudeBody(
                params(
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "read_file")
                            })
                        }),
                    ),
                    policy = forbidden,
                    model = disguisedModel,
                ),
            )
        }

        // 无冲突时 disguise 结果正常返回（system 身份块 + metadata.user_id）。
        val body = buildClaudeBody(
            params(
                customBody = listOf(
                    CustomBody("tools", buildJsonArray {
                        add(buildJsonObject { put("name", "search_web") })
                    }),
                ),
                policy = forbidden,
                model = disguisedModel,
            ),
        )
        assertNotNull(body["metadata"])
        assertTrue(body["system"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `google customBody injecting forbidden declaration conflicts`() {
        val e = assertConflict {
            buildGoogleBody(
                params(
                    tools = listOf(tool("search_web")),
                    customBody = listOf(
                        CustomBody("tools", buildJsonArray {
                            add(buildJsonObject {
                                put("functionDeclarations", buildJsonArray {
                                    add(buildJsonObject { put("name", "subagent") })
                                })
                            })
                        }),
                    ),
                    policy = forbidden,
                ),
            )
        }
        assertEquals(setOf("subagent"), e.names)
        assertEquals("google_generate_content", e.protocol)
    }

    @Test
    fun `google toolConfig allowedFunctionNames conflicts`() {
        assertConflict {
            buildGoogleBody(
                params(
                    tools = listOf(tool("search_web")),
                    customBody = listOf(
                        CustomBody("toolConfig", buildJsonObject {
                            put("functionCallingConfig", buildJsonObject {
                                put("mode", "ANY")
                                put("allowedFunctionNames", buildJsonArray {
                                    add("workspace_shell")
                                })
                            })
                        }),
                    ),
                    policy = forbidden,
                ),
            )
        }
    }

    @Test
    fun `google null policy keeps customBody tools freedom`() {
        val body = buildGoogleBody(
            params(
                customBody = listOf(
                    CustomBody("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("functionDeclarations", buildJsonArray {
                                add(buildJsonObject { put("name", "arbitrary_custom_tool") })
                            })
                        })
                    }),
                ),
            ),
        )
        val decls = body["tools"]!!.jsonArray.single()
            .jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(
            "arbitrary_custom_tool",
            decls.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `claude null policy keeps customBody tools freedom`() {
        val body = buildClaudeBody(
            params(
                customBody = listOf(
                    CustomBody("tools", buildJsonArray {
                        add(buildJsonObject { put("name", "arbitrary_custom_tool") })
                    }),
                ),
            ),
        )
        assertEquals(
            "arbitrary_custom_tool",
            body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content,
        )
    }
}
