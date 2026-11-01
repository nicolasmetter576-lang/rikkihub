package me.rerere.rikkihub.data.ai

import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.toHeaders
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

/** Capture params at the actual production provider call, not at a consumer observer. */
class GenerationLoopSessionIdentityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `known conversation identity survives all tool steps`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(
            toolStepScript("call-1", "echo_tool"), textStepScript("done"),
        )))
        val model = testChatModel()
        val id = Uuid.random()
        testGenerationLoop(provider, FakeContext(folder.newFolder())).generateText(
            settings = testSettings(model), model = model, assistant = testAssistant(),
            messages = listOf(UIMessage.user("hi")), tools = listOf(echoTool()), conversationId = id,
        ).toList()
        assertEquals(listOf(id.toString(), id.toString()), provider.streamParams.map { it.sessionId })
    }

    @Test(timeout = 15_000) fun `standalone identity stays stable across failed retry and next tool step`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(
            ScriptedAttempt(emptyList(), IOException("connection reset")),
            toolStepScript("call-1", "echo_tool"), textStepScript("done"),
        )))
        val model = testChatModel()
        val settings = testSettings(model)
        val chunks = testGenerationLoop(provider, FakeContext(folder.newFolder())).generateText(
            settings = settings.copy(networkSetting = settings.networkSetting.copy(enableAutoRetry = true)),
            model = model, assistant = testAssistant(), messages = listOf(UIMessage.user("hi")),
            tools = listOf(echoTool()), conversationId = null,
        ).toList()
        val ids = provider.streamParams.map { it.sessionId }
        assertEquals(3, ids.size)
        assertEquals(1, ids.toSet().size)
        assertEquals(ids.first(), Uuid.parse(ids.first()!!).toString())
        assertEquals(2, chunks.filterIsInstance<GenerationChunk.StepFinished>().size)
    }

    @Test fun `nonstreaming standalone identity stays stable across tool steps`() = runBlocking {
        val model = testChatModel()
        val provider = ScriptedProvider(ArrayDeque(), ArrayDeque(listOf(
            TextGenerationResult("r1", model.modelId, UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Tool("call-1", "echo_tool", "{}")),
            ), finishReason = "tool_calls"),
            TextGenerationResult("r2", model.modelId, UIMessage(
                role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("done")),
            )),
        )))
        testGenerationLoop(provider, FakeContext(folder.newFolder())).generateText(
            settings = testSettings(model), model = model, assistant = testAssistant(streamOutput = false),
            messages = listOf(UIMessage.user("hi")), tools = listOf(echoTool()), conversationId = null,
        ).toList()
        assertEquals(2, provider.generateParams.size)
        assertEquals(1, provider.generateParams.map { it.sessionId }.toSet().size)
        assertTrue(provider.generateParams.first().sessionId != null)
    }

    @Test fun `each collection of a standalone cold flow owns its own identity`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(textStepScript("a"), textStepScript("b"))))
        val model = testChatModel()
        val flow = testGenerationLoop(provider, FakeContext(folder.newFolder())).generateText(
            settings = testSettings(model), model = model, assistant = testAssistant(),
            messages = listOf(UIMessage.user("hi")), tools = emptyList(),
        )
        flow.toList()
        flow.toList()
        assertNotEquals(provider.streamParams[0].sessionId, provider.streamParams[1].sessionId)
    }

    @Test fun `standalone manual headers remain unchanged through the production loop`() = runBlocking {
        val provider = ScriptedProvider(ArrayDeque(listOf(textStepScript("done"))))
        val model = testChatModel().copy(customHeaders = listOf(
            CustomHeader("X-Session-ID", "manual"), CustomHeader("x-opencode-session", "other-manual"),
        ))
        testGenerationLoop(provider, FakeContext(folder.newFolder())).generateText(
            settings = testSettings(model), model = model, assistant = testAssistant(),
            messages = listOf(UIMessage.user("hi")), tools = emptyList(),
        ).toList()
        val params = provider.streamParams.single()
        assertNull(params.sessionId)
        val request = Request.Builder().url("https://opencode.ai/v1/chat")
            .headers(params.customHeaders.toHeaders())
            .configureSessionHeaders("https://opencode.ai/v1/chat", params.sessionId).build()
        assertEquals("manual", request.header("X-Session-ID"))
        assertEquals("other-manual", request.header("x-opencode-session"))
    }
}
