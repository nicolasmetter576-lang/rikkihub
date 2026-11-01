package me.rerere.ai.provider.providers.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Clock
import kotlin.uuid.Uuid

class AntigravityRequestTest {
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun contents(text: String) = json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.request() = this["request"]!!.jsonObject

    private val gemini = obj(
        """{"systemInstruction":{"parts":[{"text":"sys"}]},
            "generationConfig":{"temperature":0.5,"maxOutputTokens":4096,"thinkingConfig":{"includeThoughts":true,"thinkingBudget":8000}},
            "contents":[{"role":"user","parts":[{"text":"hi"}]}],
            "safetySettings":[{"category":"HARM_CATEGORY_HARASSMENT","threshold":"OFF"}]}"""
    )

    private fun context(modelId: String, level: ReasoningLevel = ReasoningLevel.HIGH, maxTokens: Int? = null, reasoning: Boolean = true) =
        AntigravityRequestContext(
            modelId = modelId,
            projectId = "proj-1",
            reasoningEnabled = reasoning,
            reasoningLevel = level,
            maxTokens = maxTokens,
            sessionSeed = "conversation-1",
            requestIdFactory = { "fixed" },
            nowMillis = { 1234L },
        )

    @Test
    fun `envelope wraps the gemini request like the native client`() {
        val body = buildAntigravityRequest(gemini, context("gemini-3-flash"))
        assertEquals("gemini-3-flash", body.str("model"))
        assertEquals("antigravity", body.str("userAgent"))
        assertEquals("agent", body.str("requestType"))
        assertEquals("proj-1", body.str("project"))
        assertEquals("agent-fixed", body.str("requestId"))
        val request = body.request()
        assertNull(request["safetySettings"])
        assertEquals("sys", request["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject.str("text"))
        // 非 Claude 删 maxOutputTokens，温度保留
        val config = request["generationConfig"]!!.jsonObject
        assertNull(config["maxOutputTokens"])
        assertEquals("0.5", config["temperature"]!!.jsonPrimitive.content)
        val sessionId = request.str("sessionId")!!
        assertTrue(sessionId.matches(Regex("-\\d+")))
        assertEquals(sessionId, buildAntigravityRequest(gemini, context("gemini-3-flash")).request().str("sessionId"))
        assertTrue(sessionId != buildAntigravityRequest(gemini, context("gemini-3-flash").copy(sessionSeed = "other")).request().str("sessionId"))
    }

    @Test
    fun `session id falls back to first user text`() {
        val a = deriveAntigravitySessionId(null, contents("""[{"role":"user","parts":[{"text":"same"}]}]"""))
        val b = deriveAntigravitySessionId(null, contents("""[{"role":"user","parts":[{"text":"same"}]},{"role":"model","parts":[{"text":"x"}]}]"""))
        assertEquals(a, b)
    }

    @Test
    fun `image models use image_gen request ids and no session`() {
        val body = buildAntigravityRequest(gemini, context("gemini-3.1-flash-image"))
        assertEquals("image_gen", body.str("requestType"))
        assertEquals("image_gen/1234/fixed/12", body.str("requestId"))
        assertNull(body.request()["sessionId"])
        assertNull(body.request()["generationConfig"]!!.jsonObject["thinkingConfig"])
    }

    @Test
    fun `gemini thinking uses levels and auto uses dynamic budget`() {
        fun thinking(level: ReasoningLevel) =
            buildAntigravityRequest(gemini, context("gemini-3.1-pro-low", level)).request()["generationConfig"]!!
                .jsonObject["thinkingConfig"]!!.jsonObject
        assertEquals("medium", thinking(ReasoningLevel.MEDIUM).str("thinkingLevel"))
        assertEquals("high", thinking(ReasoningLevel.XHIGH).str("thinkingLevel"))
        assertNull(thinking(ReasoningLevel.MEDIUM)["thinkingBudget"])
        assertEquals("-1", thinking(ReasoningLevel.AUTO).str("thinkingBudget"))
        val off = thinking(ReasoningLevel.OFF)
        assertEquals("low", off.str("thinkingLevel"))
        assertEquals("false", off.str("includeThoughts"))
    }

    @Test
    fun `claude thinking uses a budget below max output and validated tools`() {
        val body = buildAntigravityRequest(gemini, context("claude-sonnet-4-6", ReasoningLevel.HIGH))
        val config = body.request()["generationConfig"]!!.jsonObject
        assertEquals("4096", config.str("maxOutputTokens"))
        assertEquals("4095", config["thinkingConfig"]!!.jsonObject.str("thinkingBudget"))
        assertEquals(
            "VALIDATED",
            body.request()["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject.str("mode"),
        )
        assertNull(body.request()["contents"]!!.jsonArray.firstOrNull { it.jsonObject.str("role") == "user" && it.jsonObject["parts"]!!.jsonArray[0].jsonObject.str("text") == "" })
    }

    @Test
    fun `claude defaults max output and clamps low budgets`() {
        val noMax = JsonObject(gemini + ("generationConfig" to JsonObject(emptyMap())))
        val config = buildAntigravityRequest(noMax, context("claude-opus-4-6-thinking", ReasoningLevel.LOW))
            .request()["generationConfig"]!!.jsonObject
        assertEquals("64000", config.str("maxOutputTokens"))
        assertEquals("1024", config["thinkingConfig"]!!.jsonObject.str("thinkingBudget"))

        val tiny = JsonObject(gemini + ("generationConfig" to obj("""{"maxOutputTokens":1000}""")))
        val tinyConfig = buildAntigravityRequest(tiny, context("claude-opus-4-6-thinking", ReasoningLevel.HIGH))
            .request()["generationConfig"]!!.jsonObject
        assertNull(tinyConfig["thinkingConfig"])

        val off = buildAntigravityRequest(gemini, context("claude-sonnet-4-6", ReasoningLevel.OFF))
        assertNull(off.request()["generationConfig"]!!.jsonObject["thinkingConfig"])
    }

    @Test
    fun `cache warm replay keeps max output tokens of one for gemini`() {
        val replay = JsonObject(gemini + ("generationConfig" to obj("""{"maxOutputTokens":1}""")))
        val config = buildAntigravityRequest(replay, context("gemini-3-flash", maxTokens = 1))
            .request()["generationConfig"]!!.jsonObject
        assertEquals("1", config.str("maxOutputTokens"))
    }

    @Test
    fun `gpt-oss and non-reasoning models send no thinking config`() {
        assertNull(buildAntigravityRequest(gemini, context("gpt-oss-120b-medium")).request()["generationConfig"]!!.jsonObject["thinkingConfig"])
        assertNull(buildAntigravityRequest(gemini, context("gemini-3-flash", reasoning = false)).request()["generationConfig"]!!.jsonObject["thinkingConfig"])
    }

    @Test
    fun `gemini signatures - first call gets bypass, responses and user parts lose signatures`() {
        val out = sanitizeGeminiSignatures(
            contents(
                """[
                {"role":"user","parts":[{"text":"q","thoughtSignature":"u"}]},
                {"role":"model","parts":[{"text":"t","thoughtSignature":"keep"},
                  {"functionCall":{"name":"a","args":{}}},{"functionCall":{"name":"b","args":{}},"thoughtSignature":"skip_thought_signature_validator"}]},
                {"role":"user","parts":[{"functionResponse":{"name":"a","response":{}},"thoughtSignature":"x"}]},
                {"role":"model","parts":[{"functionCall":{"name":"c","args":{}},"thoughtSignature":"real"}]}
                ]"""
            )
        )
        assertNull(out[0]["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"])
        val model = out[1]["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals("keep", model[0].str("thoughtSignature"))
        assertEquals(ANTIGRAVITY_SKIP_SIGNATURE, model[1].str("thoughtSignature"))
        assertNull(model[2]["thoughtSignature"])
        assertNull(out[2]["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"])
        assertEquals("real", out[3]["parts"]!!.jsonArray[0].jsonObject.str("thoughtSignature"))
    }

    @Test
    fun `claude signatures - unsigned or empty thinking dropped, other parts unsigned`() {
        val out = sanitizeClaudeSignatures(
            contents(
                """[
                {"role":"model","parts":[{"text":"think","thought":true,"thoughtSignature":"sig"},
                  {"text":"no sig","thought":true},{"text":"  ","thought":true,"thoughtSignature":"sig2"},
                  {"text":"answer","thoughtSignature":"s"},{"text":""},
                  {"functionCall":{"name":"a","args":{}},"thoughtSignature":"skip_thought_signature_validator"}]},
                {"role":"model","parts":[{"text":"only","thought":true}]},
                {"role":"user","parts":[{"functionResponse":{"name":"a","response":{}},"thoughtSignature":"x"}]}
                ]"""
            )
        )
        assertEquals(2, out.size)
        val parts = out[0]["parts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("think", "answer", null), parts.map { it.str("text") })
        assertEquals("sig", parts[0].str("thoughtSignature"))
        assertNull(parts[1]["thoughtSignature"])
        assertNull(parts[2]["thoughtSignature"])
        assertNull(out[1]["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"])
    }

    @Test
    fun `function response turns are reordered, renamed and become model turns`() {
        val out = normalizeFunctionResponseTurns(
            contents(
                """[
                {"role":"user","parts":[{"text":"q"}]},
                {"role":"model","parts":[{"functionCall":{"id":"c1","name":"first","args":{}}},{"functionCall":{"id":"c2","name":"second","args":{}}}]},
                {"role":"user","parts":[{"functionResponse":{"id":"c2","name":"second","response":{}}},{"functionResponse":{"id":"c1","name":"unknown","response":{}}}]},
                {"role":"model","parts":[{"functionCall":{"id":"c3","name":"third","args":{}}}]},
                {"role":"user","parts":[{"functionResponse":{"id":"c3","name":"third","response":{}}},{"text":"extra"}]}
                ]"""
            )
        )
        val responses = out[2]
        assertEquals("model", responses.str("role"))
        val ids = responses["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject.str("id") }
        assertEquals(listOf("c1", "c2"), ids)
        assertEquals("first", responses["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject.str("name"))
        assertEquals("user", out[4].str("role"))
    }

    @Test
    fun `boundary turns - leading model gets empty user, trailing thought-only dropped`() {
        val out = ensureBoundaryUserContent(
            contents(
                """[
                {"role":"model","parts":[{"text":"hello"}]},
                {"role":"user","parts":[{"text":"q"}]},
                {"role":"model","parts":[{"text":"a"}]},
                {"role":"model","parts":[{"text":"thinking","thought":true}]}
                ]"""
            )
        )
        assertEquals(listOf("user", "model", "user", "model", "user"), out.map { it.str("role") })
        assertEquals("", out.first()["parts"]!!.jsonArray[0].jsonObject.str("text"))
        assertEquals("", out.last()["parts"]!!.jsonArray[0].jsonObject.str("text"))

        val pendingResponse = ensureBoundaryUserContent(
            contents("""[{"role":"user","parts":[{"text":"q"}]},{"role":"model","parts":[{"functionResponse":{"name":"a","response":{}}}]}]""")
        )
        assertEquals(2, pendingResponse.size)
    }

    @Test
    fun `history signatures are only replayed to the same model family`() {
        val signature = GoogleThoughtMetadata(thoughtSignature = "sig").toMetadata()
        val claudeMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("think", Clock.System.now(), null, signature),
                UIMessagePart.Text("answer", signature),
            ),
            modelId = Uuid.random(),
        )
        val history = listOf(UIMessage.user("q"), claudeMessage)
        val source: (UIMessage) -> String? = { if (it === claudeMessage) "claude-sonnet-4-6" else null }

        val toClaude = prepareAntigravityHistory(history, "claude-opus-4-6-thinking", source)
        assertEquals(history, toClaude)

        val toGemini = prepareAntigravityHistory(history, "gemini-3-flash", source)[1]
        assertEquals(2, toGemini.parts.size)
        assertTrue(toGemini.parts.all { it.metadata == null })

        val foreign = prepareAntigravityHistory(history, "claude-sonnet-4-6") { null }[1]
        assertEquals(listOf("answer"), foreign.parts.map { (it as UIMessagePart.Text).text })
        assertNull(foreign.parts.single().metadata)
    }

    @Test
    fun `full body from GoogleProvider cleans tool schemas and keeps the workspace wire guard`() {
        val tool = Tool(
            name = "pick",
            description = "Pick one",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("color", buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(listOf(JsonPrimitive("red"), JsonPrimitive("blue"))))
                        })
                    },
                )
            },
            execute = { emptyList() },
        )
        val params = TextGenerationParams(
            model = Model(modelId = "claude-sonnet-4-6", abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING)),
            tools = listOf(tool),
            reasoningLevel = ReasoningLevel.MEDIUM,
            sessionId = "conv",
        )
        val body = AntigravityChatApi(OkHttpClient()).buildGenerateBody(
            listOf(UIMessage.system("sys"), UIMessage.user("hi")),
            params,
            projectId = "proj",
        ) { null }
        val request = body.request()
        val declaration = request["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        val parameters = declaration["parameters"]!!.jsonObject
        assertEquals("Allowed: red, blue", parameters["properties"]!!.jsonObject["color"]!!.jsonObject.str("description"))
        // Claude VALIDATED：没有必填就补 `_`
        assertEquals(listOf("_"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("proj", body.str("project"))
        assertEquals("2000", request["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject.str("thinkingBudget"))
        assertFalse("safetySettings" in request)
    }
}
