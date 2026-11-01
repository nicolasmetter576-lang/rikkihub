package me.rerere.rikkihub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolCatalog
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.ToolOverride
import me.rerere.rikkihub.data.ai.tools.applyBuiltinOverride
import me.rerere.rikkihub.data.ai.tools.resolveBuiltinToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline native regression for the dokar3 QuickJS runtime behind the JavaScript
 * local tool. These tests run the real engine (JVM substitution of
 * io.github.dokar3:quickjs-kt-jvm), never a fake implementation.
 */
class JavascriptToolTest {
    private suspend fun evaluate(code: String, timeoutMillis: Long = 2_000) =
        Json.parseToJsonElement(evaluateJavascript(code, timeoutMillis)).jsonObject

    @Test(timeout = 30_000)
    fun `preserves completion values`() = runBlocking {
        assertEquals("3", evaluate("1 + 2")["result"]?.jsonPrimitive?.content)
        assertEquals("10", evaluate("const x = 5; x * 2")["result"]?.jsonPrimitive?.content)
        assertEquals("true", evaluate("2 > 1")["result"]?.jsonPrimitive?.content)
        assertEquals("text", evaluate("'te' + 'xt'")["result"]?.jsonPrimitive?.content)
        assertEquals("😀\u0001end", evaluate("'😀\\u0001end'")["result"]?.jsonPrimitive?.content)
        assertEquals("{\"value\":42}", evaluate("({value: 42})")["result"]?.jsonPrimitive?.content)
        assertEquals("[1,2]", evaluate("[1, 2]")["result"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, evaluate("undefined")["result"])
        assertEquals(JsonNull, evaluate("null")["result"])
        assertNull(evaluate("1")["logs"])
    }

    @Test(timeout = 30_000)
    fun `captures console output`() = runBlocking {
        val output = evaluate(
            "console.log('你好', 42); console.warn('注意'); console.error({a: 1}); console.debug('dbg'); '😀'"
        )
        assertEquals(
            "[LOG] 你好 42\n[WARN] 注意\n[ERROR] {\"a\":1}\n[LOG] dbg",
            output["logs"]?.jsonPrimitive?.content,
        )
        assertEquals("😀", output["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 30_000)
    fun `reports syntax errors`() = runBlocking {
        val broken = evaluate("const = ;")
        assertTrue(broken.containsKey("error"))
        assertTrue(broken["error"]!!.jsonPrimitive.content.isNotBlank())
        // A syntax error must not poison subsequent evaluations.
        assertEquals("3", evaluate("1 + 2")["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 30_000)
    fun `reports thrown exceptions`() = runBlocking {
        val error = evaluate("throw new Error('failure')")
        assertTrue(error["error"]!!.jsonPrimitive.content.contains("failure"))
        assertTrue(evaluate("(function() { throw {code: 7}; })()").containsKey("error"))
        assertEquals("4", evaluate("2 + 2")["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 30_000)
    fun `interrupts infinite loops including during conversion`() = runBlocking {
        for (code in listOf(
            "while (true) {}",
            "while (true) { console.log('spam'); }",
            "({toJSON() { while (true) {} }})",
            "({get value() { while (true) {} }})",
        )) {
            val payload = evaluate(code, 200)
            assertTrue(
                "expected a timeout error for `$code`, got $payload",
                payload["error"]!!.jsonPrimitive.content.contains("timed out"),
            )
            // Each timed out runtime must leave the next evaluation fully functional.
            assertEquals("2", evaluate("1 + 1")["result"]?.jsonPrimitive?.content)
        }
    }

    @Test(timeout = 30_000)
    fun `bounds logs and result output`() = runBlocking {
        val logs = evaluate("for (let i = 0; i < 10000; i++) console.log('1234567890'); 1")
        val text = logs["logs"]!!.jsonPrimitive.content
        assertTrue("logs must be bounded, got ${text.length} chars", text.length < 70_000)
        assertTrue(text.endsWith("[Logs truncated]"))
        assertTrue(evaluate("'x'.repeat(2 * 1024 * 1024)").containsKey("error"))
    }

    @Test(timeout = 30_000)
    fun `memory and stack exhaustion return errors`() = runBlocking {
        assertTrue(evaluate("new ArrayBuffer(128 * 1024 * 1024)").containsKey("error"))
        assertTrue(evaluate("function recurse() { return recurse(); } recurse();").containsKey("error"))
        assertEquals("3", evaluate("1 + 2")["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 30_000)
    fun `external cancellation interrupts native evaluation`() = runBlocking {
        val execution = async(Dispatchers.Default) {
            evaluateJavascript("while (true) {}", timeoutMillis = 60_000)
        }
        // Give the evaluation a moment to reach native code; the evidence is the
        // propagated exception type, not this delay.
        delay(200)
        execution.cancel()
        val failure = withTimeout(10_000) {
            runCatching { execution.await() }.exceptionOrNull()
        }
        assertTrue(
            "external cancellation must propagate as CancellationException, got $failure",
            failure is CancellationException,
        )
        // Cancelling a native evaluation must not leave the environment wedged.
        assertEquals("4", evaluate("2 + 2")["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 30_000)
    fun `concurrent evaluations run in isolated runtimes`() = runBlocking {
        val jobs = (1..4).map { n ->
            async(Dispatchers.Default) {
                evaluateJavascript(
                    """
                    globalThis.marker = $n;
                    var acc = 0;
                    for (var i = 0; i < 100000; i++) acc += i;
                    acc + ':' + marker
                    """.trimIndent(),
                    timeoutMillis = 5_000,
                )
            }
        }
        val payloads = jobs.awaitAll().map { Json.parseToJsonElement(it).jsonObject }
        payloads.forEachIndexed { index, payload ->
            // A shared runtime would let other jobs overwrite globalThis.marker.
            assertEquals("4999950000:${index + 1}", payload["result"]?.jsonPrimitive?.content)
        }
    }

    @Test(timeout = 30_000)
    fun `sequential evaluations do not share globals`() = runBlocking {
        evaluate("globalThis.leaked = 123; 1")
        assertEquals("undefined", evaluate("typeof leaked")["result"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 10_000)
    fun `tool keeps builtin id description schema and override semantics`() {
        val tool = buildJavascriptTool()
        val spec = BuiltinToolCatalog.require(BuiltinToolIds.EVAL_JAVASCRIPT)
        assertEquals(BuiltinToolIds.EVAL_JAVASCRIPT, tool.name)
        assertEquals(spec.defaultDescription, tool.description)
        val schema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("code"), schema.required)
        assertTrue(schema.properties.containsKey("code"))

        val overrides = mapOf(
            BuiltinToolIds.EVAL_JAVASCRIPT to ToolOverride(
                name = "js_run",
                description = "Custom runner",
                systemPrompt = "Be safe",
            )
        )
        val renamed = tool.applyBuiltinOverride(overrides)
        assertEquals("js_run", renamed.name)
        assertEquals("Custom runner", renamed.description)
        assertEquals("Be safe", renamed.systemPrompt(Model(modelId = "m", displayName = "m"), emptyList()))
        assertEquals(
            BuiltinToolIds.EVAL_JAVASCRIPT,
            resolveBuiltinToolId("js_run", overrides),
        )
    }

    @Test(timeout = 30_000)
    fun `tool execute evaluates code and returns the json payload`() = runBlocking {
        val tool = buildJavascriptTool()
        val parts = tool.execute(buildJsonObject { put("code", "console.log('hi'); 6 * 7") })
        val text = parts.single() as UIMessagePart.Text
        val payload = Json.parseToJsonElement(text.text).jsonObject
        assertEquals("42", payload["result"]?.jsonPrimitive?.content)
        assertEquals("[LOG] hi", payload["logs"]?.jsonPrimitive?.content)
    }

    @Test(timeout = 10_000)
    fun `tool execute requires code`() {
        val tool = buildJavascriptTool()
        assertThrows(Exception::class.java) {
            runBlocking { tool.execute(buildJsonObject { }) }
        }
    }
}
