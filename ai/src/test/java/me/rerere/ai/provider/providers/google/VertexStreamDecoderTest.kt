package me.rerere.ai.provider.providers.google

import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.stream.ToolStreamProtocolException
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.IOException

@RunWith(Parameterized::class)
class VertexStreamDecoderTest(
    private val name: String,
    private val frames: List<String>,
    private val expected: String,
    private val done: Boolean,
) {
    @Test
    fun terminalEvidence() {
        val decoder = GoogleStreamDecoder("resp", "test", vertexAI = true)
        val chunks = mutableListOf<StreamChunk>()
        var failure: Exception? = null
        try {
            frames.forEach {
                val result = decoder.accept(SseEvent(data = it))
                assertFalse("$name must not complete early", result.completed)
                assertTrue(result.chunks.none { chunk ->
                    chunk is StreamChunk.ToolCallEnd || chunk is StreamChunk.Finish ||
                        chunk is StreamChunk.ToolCallDelta && chunk.inputDelta.isNotEmpty()
                })
                chunks += result.chunks
            }
            chunks += if (done) decoder.accept(SseEvent(data = "[DONE]")).chunks else decoder.onClosed()
        } catch (e: ToolStreamProtocolException) {
            failure = e
        } catch (e: IOException) {
            failure = e
        }
        if (expected.startsWith("google_") || expected == RETRY) {
            if (expected == RETRY) {
                assertTrue("$name must retry: $failure", failure is IOException)
            } else {
                assertEquals(name, expected, (failure as? ToolStreamProtocolException)?.code)
            }
            assertFalse(failure!!.message.orEmpty().contains("synthetic-secret"))
            assertTrue(chunks.none { it is StreamChunk.Finish || it is StreamChunk.ToolCallEnd })
        } else {
            assertEquals(name, null, failure)
            assertEquals(expected, chunks.filterIsInstance<StreamChunk.Finish>().single().finishReason)
            if (frames.any { it.contains("functionCall") }) {
                assertEquals(1, chunks.filterIsInstance<StreamChunk.ToolCallEnd>().size)
            }
        }
        assertTrue(decoder.onClosed().isEmpty())
        assertTrue(decoder.accept(SseEvent(data = "[DONE]")).chunks.isEmpty())
    }

    companion object {
        private const val RETRY = "retry"
        private fun candidate(fields: String) = """{"candidates":[{$fields}]}"""
        private fun reason(value: String) = candidate(""""finishReason":$value""")
        private fun tool(args: String) = candidate(
            """"content":{"parts":[{"functionCall":{"name":"save","id":"call"$args}}]}""",
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}, DONE={3}")
        fun cases(): List<Array<Any>> = buildList {
            fun case(name: String, frames: List<String>, expected: String) {
                for (done in listOf(false, true)) add(arrayOf(name, frames, expected, done))
            }
            val text = candidate(""""content":{"parts":[{"text":"hello"}]}""")
            val stop = reason(""""STOP"""")
            val unknown = reason(""""FUTURE_REASON"""")
            // Overload/quota/timeout frames retry; malformed or permanent errors fail the attempt.
            for ((error, expected) in listOf(
                "null" to "google_stream_upstream_error", "[]" to "google_stream_upstream_error",
                "\"synthetic-secret\"" to "google_stream_upstream_error", "{}" to "google_stream_upstream_error",
                """{"code":503,"message":"synthetic-secret"}""" to RETRY,
                """{"code":429}""" to RETRY, """{"code":500,"status":"INTERNAL"}""" to RETRY,
                """{"status":"UNAVAILABLE","message":"synthetic-secret"}""" to RETRY,
                """{"code":400,"status":"INVALID_ARGUMENT","message":"synthetic-secret"}""" to "google_stream_upstream_error",
                """{"code":"503"}""" to "google_stream_upstream_error",
                """{"status":"synthetic-secret UNAVAILABLE"}""" to "google_stream_upstream_error",
            )) {
                for ((prefixName, prefix) in listOf(
                    "first" to emptyList(), "text" to listOf(text),
                    "tool" to listOf(tool("")), "STOP" to listOf(stop),
                )) {
                    case("error $prefixName $error",
                        prefix + """{"error":$error,"candidates":[{"finishReason":123}]}""",
                        expected)
                }
            }
            val placeholders = listOf("{}", """{"candidates":[]}""", reason("null"),
                reason("\"\""), reason(""""  """"), reason(""""FINISH_REASON_UNSPECIFIED""""))
            case("missing", listOf(text), RETRY)
            case("only placeholders", placeholders, RETRY)
            case("unknown text", listOf(text, unknown) + placeholders, "FUTURE_REASON")
            case("unknown image", listOf(candidate(
                """"content":{"parts":[{"inlineData":{"mimeType":"image/png","data":"YQ=="}}]}""",
            ), unknown), "FUTURE_REASON")
            case("STOP text", listOf(text, stop) + placeholders, "STOP")
            for (bad in listOf("0", "true", "{}", "[]")) {
                case("invalid reason $bad", listOf(reason(bad)), "google_invalid_finish_reason")
            }
            for (bad in listOf("MALFORMED_FUNCTION_CALL", "UNEXPECTED_TOOL_CALL")) {
                case("known error $bad", listOf(reason(""""$bad"""")), "google_incomplete_tool_call")
            }
            for (fields in listOf(""""index":1""", """"index":null""", """"index":"0"""",
                """"index":true""", """"index":{}""", """"index":0.5""")) {
                case("candidate $fields", listOf(candidate(fields)), "google_invalid_candidate")
            }
            case("multiple candidates", listOf("""{"candidates":[{},{}]}"""), "google_invalid_candidate")
            case("invalid candidates", listOf("""{"candidates":{}}"""), "google_invalid_candidate")
            case("invalid candidate", listOf("""{"candidates":[null]}"""), "google_invalid_candidate")
            case("index zero", listOf(candidate(""""index":0,"finishReason":"STOP"""")), "STOP")
            for (args in listOf("", ""","args":{}""", ""","args":{"path":"test"}""")) {
                val call = tool(args)
                case("tool missing $args", listOf(call), RETRY)
                case("tool STOP $args", listOf(call, stop, stop) + placeholders, "STOP")
                case("tool unknown $args", listOf(call, unknown), "google_incomplete_tool_call")
                for (nonStop in listOf(unknown, reason(""""MAX_TOKENS""""), reason("""" STOP """"))) {
                    case("STOP then nonSTOP $args $nonStop", listOf(call, stop, nonStop), "google_incomplete_tool_call")
                    case("nonSTOP then STOP $args $nonStop", listOf(call, nonStop) + placeholders + stop, "google_incomplete_tool_call")
                    case("late tool $args $nonStop", listOf(nonStop, call, stop), "google_incomplete_tool_call")
                }
            }
        }
    }
}
