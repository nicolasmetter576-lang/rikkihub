package me.rerere.rikkihub.data.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AcpPartsOutputTest {
    private val t0 = Instant.fromEpochSeconds(1_700_000_000L)

    private fun contentText(vararg texts: String): JsonArray = buildJsonArray {
        texts.forEach { text ->
            add(
                buildJsonObject {
                    put("type", "content")
                    putJsonObject("content") {
                        put("type", "text")
                        put("text", text)
                    }
                }
            )
        }
    }

    private fun update(
        sessionUpdate: String,
        toolCallId: String = "c1",
        status: String? = null,
        title: String? = null,
        kind: String? = null,
        content: JsonArray? = null,
        rawOutput: kotlinx.serialization.json.JsonElement? = null,
    ): JsonObject = buildJsonObject {
        put("sessionUpdate", sessionUpdate)
        put("toolCallId", toolCallId)
        status?.let { put("status", it) }
        title?.let { put("title", it) }
        kind?.let { put("kind", it) }
        content?.let { put("content", it) }
        rawOutput?.let { put("rawOutput", it) }
    }

    private fun emptyReply() = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())

    private fun UIMessage.onlyTool(): UIMessagePart.Tool = parts.single() as UIMessagePart.Tool

    private fun UIMessagePart.Tool.acpMeta(): JsonObject = metadata!![AcpParts.META_KEY]!!.jsonObject

    @Test
    fun `in_progress output is staged without prematurely marking executed and committed on empty completed frame`() {
        var msg = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", title = "run", kind = "execute"), t0)
        msg = AcpParts.fold(msg, update("tool_call_update", status = "in_progress", content = contentText("hello from progress")), t0 + 1.seconds)

        val midTool = msg.onlyTool()
        assertTrue("output must stay empty while in_progress", midTool.output.isEmpty())
        assertFalse("isExecuted must stay false while in_progress", midTool.isExecuted)
        // agy can transition in_progress -> pending (permission card); isPending must still hold
        val pendingTool = midTool.copy(approvalState = ToolApprovalState.Pending)
        assertTrue("isPending must still hold before terminal frame", pendingTool.isPending)

        msg = AcpParts.fold(msg, update("tool_call_update", status = "completed"), t0 + 2.seconds)
        val done = msg.onlyTool()
        assertTrue(done.isExecuted)
        assertEquals(listOf(UIMessagePart.Text("hello from progress")), done.output)
        assertNull("transient pending output key must be stripped on completion", done.acpMeta()["_pendingOutput"])
        assertNull("placeholder marker must not be present on real output", done.acpMeta()["_placeholder"])
    }

    @Test
    fun `multiple in_progress snapshots replace without duplicating and ignore blank or structured-only terminal frames`() {
        var msg = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        msg = AcpParts.fold(msg, update("tool_call_update", status = "in_progress", content = contentText("step 1")), t0 + 1.seconds)
        msg = AcpParts.fold(msg, update("tool_call_update", status = "in_progress", content = contentText("step 1\nstep 2")), t0 + 2.seconds)
        // Empty content update must keep prior staged snapshot
        msg = AcpParts.fold(msg, update("tool_call_update", status = "in_progress", content = contentText("")), t0 + 3.seconds)
        // Completed with non-text rawOutput must NOT overwrite staged output with raw JSON
        msg = AcpParts.fold(
            msg,
            update("tool_call_update", status = "completed", rawOutput = buildJsonObject { put("exit_code", 0) }),
            t0 + 4.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("step 1\nstep 2")), msg.onlyTool().output)

        // Terminal frame with newer real text replaces earlier progress text cleanly
        var msg2 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        msg2 = AcpParts.fold(msg2, update("tool_call_update", status = "in_progress", content = contentText("Running...")), t0 + 1.seconds)
        msg2 = AcpParts.fold(msg2, update("tool_call_update", status = "completed", content = contentText("3 passed")), t0 + 2.seconds)
        assertEquals(listOf(UIMessagePart.Text("3 passed")), msg2.onlyTool().output)
    }

    @Test
    fun `late output replaces synthetic placeholder but never overwrites literal completed output or Denied output`() {
        // 1. Completed with no output -> placeholder -> late update with real output replaces placeholder
        var msg = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        msg = AcpParts.fold(msg, update("tool_call_update", status = "completed"), t0 + 1.seconds)
        assertEquals(listOf(UIMessagePart.Text("[completed]")), msg.onlyTool().output)

        msg = AcpParts.fold(msg, update("tool_call_update", content = contentText("late real output")), t0 + 2.seconds)
        assertEquals(listOf(UIMessagePart.Text("late real output")), msg.onlyTool().output)
        assertNull(msg.onlyTool().acpMeta()["_placeholder"])

        // 2. Real output that literally equals "[completed]" must NOT be overwritten by a subsequent frame
        var literal = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        literal = AcpParts.fold(literal, update("tool_call_update", status = "completed", content = contentText("[completed]")), t0 + 1.seconds)
        literal = AcpParts.fold(literal, update("tool_call_update", status = "completed", content = contentText("should not overwrite")), t0 + 2.seconds)
        assertEquals(listOf(UIMessagePart.Text("[completed]")), literal.onlyTool().output)

        // 3. Denied card output must NOT be overwritten by a subsequent failed stderr frame
        var denied = AcpParts.fold(emptyReply(), update("tool_call", status = "pending", kind = "execute"), t0)
        denied = denied.copy(
            parts = listOf(
                denied.onlyTool().copy(
                    approvalState = ToolApprovalState.Denied("user declined"),
                    output = listOf(UIMessagePart.Text("user declined")),
                )
            )
        )
        denied = AcpParts.fold(
            denied,
            update("tool_call_update", status = "failed", rawOutput = buildJsonObject { put("stderr", "agent aborted") }),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("user declined")), denied.onlyTool().output)
        assertNull(denied.onlyTool().acpMeta()["_pendingOutput"])
    }

    @Test
    fun `rawOutput extracts stderr merges stdout+stderr falls back past blank content and avoids dumping raw JSON`() {
        // 1. stderr only
        var m1 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m1 = AcpParts.fold(
            m1,
            update("tool_call_update", status = "failed", rawOutput = buildJsonObject { put("stderr", "permission denied") }),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("permission denied")), m1.onlyTool().output)

        // 2. empty stdout does NOT short-circuit stderr; blank content does NOT short-circuit rawOutput
        var m2 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m2 = AcpParts.fold(
            m2,
            update(
                "tool_call_update",
                status = "failed",
                content = contentText(""),
                rawOutput = buildJsonObject {
                    put("stdout", "")
                    put("stderr", "command not found")
                },
            ),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("command not found")), m2.onlyTool().output)

        // 3. stdout + stderr merged without duplicating when output already includes stderr
        var m3 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m3 = AcpParts.fold(
            m3,
            update(
                "tool_call_update",
                status = "failed",
                rawOutput = buildJsonObject {
                    put("stdout", "line1")
                    put("stderr", "err1")
                },
            ),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("line1\nerr1")), m3.onlyTool().output)

        var m3Dedup = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m3Dedup = AcpParts.fold(
            m3Dedup,
            update(
                "tool_call_update",
                status = "failed",
                rawOutput = buildJsonObject {
                    put("output", "line1\nerr1\n")
                    put("stderr", "err1")
                },
            ),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("line1\nerr1\n")), m3Dedup.onlyTool().output)

        // 4. error.message and MCP rawOutput.content blocks
        var m4 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m4 = AcpParts.fold(
            m4,
            update(
                "tool_call_update",
                status = "failed",
                rawOutput = buildJsonObject { putJsonObject("error") { put("message", "boom") } },
            ),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("boom")), m4.onlyTool().output)

        var m5 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m5 = AcpParts.fold(
            m5,
            update(
                "tool_call_update",
                status = "completed",
                rawOutput = buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", "mcp text") })
                        },
                    )
                },
            ),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("mcp text")), m5.onlyTool().output)

        // 5. Empty output / structured-only rawOutput still falls back to [failed] (matches agy-turns.jsonl)
        var m6 = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute"), t0)
        m6 = AcpParts.fold(
            m6,
            update("tool_call_update", status = "failed", content = contentText(""), rawOutput = buildJsonObject { put("output", "") }),
            t0 + 1.seconds,
        )
        assertEquals(listOf(UIMessagePart.Text("[failed]")), m6.onlyTool().output)
    }

    @Test
    fun `tool_call direct terminal and finishTools or finishInterrupted preserve staged output and strip transient keys`() {
        // Direct completed tool_call with stderr
        val direct = AcpParts.fold(
            emptyReply(),
            update("tool_call", status = "failed", kind = "execute", rawOutput = buildJsonObject { put("stderr", "bad arg") }),
            t0,
        )
        assertEquals(listOf(UIMessagePart.Text("bad arg")), direct.onlyTool().output)
        assertNull(direct.onlyTool().acpMeta()["_pendingOutput"])

        // finishTools preserves staged in_progress output alongside cancellation reason and strips transient keys
        var running = AcpParts.fold(emptyReply(), update("tool_call", status = "in_progress", kind = "execute", content = contentText("partial log")), t0)
        val cancelled = AcpParts.finishTools(running, "cancelled", t0 + 2.seconds)
        assertEquals(listOf(UIMessagePart.Text("partial log\n[cancelled]")), cancelled.onlyTool().output)
        assertNull(cancelled.onlyTool().acpMeta()["_pendingOutput"])

        // finishInterrupted preserves staged output, strips transient keys, and stays idempotent
        val interrupted = AcpParts.finishInterrupted(running, "interrupted", t0 + 5.seconds)
        assertEquals(listOf(UIMessagePart.Text("partial log\n[interrupted]")), interrupted.onlyTool().output)
        assertEquals("interrupted", AcpParts.status(interrupted.onlyTool()))
        assertNull(interrupted.onlyTool().acpMeta()["_pendingOutput"])
        assertSame("second pass must be reference-identical", interrupted, AcpParts.finishInterrupted(interrupted, "interrupted", t0 + 5.seconds))
    }
}
