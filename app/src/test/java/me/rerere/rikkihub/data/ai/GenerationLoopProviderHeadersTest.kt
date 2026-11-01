package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class GenerationLoopProviderHeadersTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `standalone run keeps generated provenance through every tool step`() = runBlocking {
        val impl = ScriptedProvider(ArrayDeque(listOf(toolStepScript("c1", "echo_tool"), textStepScript("done"))))
        val model = testChatModel()
        val settings = testSettings(model)
        val provider = (settings.providers.single() as ProviderSetting.OpenAI).copy(
            customHeaders = listOf(CustomHeader("X-Session-ID", "provider-manual")),
        )
        testGenerationLoop(impl, FakeContext(folder.newFolder())).generateText(
            settings = settings.copy(providers = listOf(provider)), model = model, assistant = testAssistant(),
            messages = listOf(UIMessage.user("hi")), tools = listOf(echoTool()),
        ).toList()
        assertEquals(2, impl.streamParams.size)
        assertEquals(1, impl.streamParams.map { it.sessionId }.toSet().size)
        impl.streamParams.forEach { assertNull(it.resolvedSessionId(provider)) }
    }

    @Test fun `conversation run still wins over provider session through every tool step`() = runBlocking {
        val impl = ScriptedProvider(ArrayDeque(listOf(toolStepScript("c1", "echo_tool"), textStepScript("done"))))
        val model = testChatModel()
        val settings = testSettings(model)
        val provider = (settings.providers.single() as ProviderSetting.OpenAI).copy(
            customHeaders = listOf(CustomHeader("X-Session-ID", "provider-manual")),
        )
        val id = Uuid.random()
        testGenerationLoop(impl, FakeContext(folder.newFolder())).generateText(
            settings = settings.copy(providers = listOf(provider)), model = model, assistant = testAssistant(),
            messages = listOf(UIMessage.user("hi")), tools = listOf(echoTool()), conversationId = id,
        ).toList()
        assertEquals(2, impl.streamParams.size)
        impl.streamParams.forEach { assertEquals(id.toString(), it.resolvedSessionId(provider)) }
    }
}
