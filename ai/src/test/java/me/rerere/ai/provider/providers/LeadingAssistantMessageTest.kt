package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/** 聊天以助手消息开头（角色的开场白）时，Claude 与 Gemini 的请求前面补一句用户消息；以用户消息开头时一个字不变。 */
class LeadingAssistantMessageTest {
    private val claude = ClaudeProvider(OkHttpClient())
    private val google = GoogleProvider(OkHttpClient())

    private fun claudeMessages(messages: List<UIMessage>): JsonArray {
        val m = ClaudeProvider::class.java.getDeclaredMethod("buildMessages", List::class.java, Boolean::class.javaPrimitiveType, ClaudePromptCacheTtl::class.java)
        m.isAccessible = true
        return m.invoke(claude, messages, false, ClaudePromptCacheTtl.FIVE_MINUTES) as JsonArray
    }

    private fun googleContents(messages: List<UIMessage>): JsonArray {
        val m = GoogleProvider::class.java.getDeclaredMethod("buildContents", List::class.java)
        m.isAccessible = true
        return m.invoke(google, messages) as JsonArray
    }

    private fun roles(a: JsonArray) = a.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    @Test
    fun claudeGetsAUserMessageBeforeALeadingAssistantMessage() {
        val chat = listOf(UIMessage.assistant("Welcome, traveller."), UIMessage.user("Hi"))
        val out = claudeMessages(chat)
        assertEquals(listOf("user", "assistant", "user"), roles(out))
        assertEquals(LEADING_USER_PLACEHOLDER, out[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun claudeIgnoresASystemMessageWhenLookingForTheFirstTurn() {
        val out = claudeMessages(listOf(UIMessage.system("rules"), UIMessage.assistant("Welcome.")))
        assertEquals(listOf("user", "assistant"), roles(out))
    }

    @Test
    fun claudeLeavesAUserFirstChatAlone() {
        assertEquals(listOf("user", "assistant", "user"), roles(claudeMessages(listOf(UIMessage.user("a"), UIMessage.assistant("b"), UIMessage.user("c")))))
    }

    @Test
    fun googleGetsAUserTurnBeforeALeadingModelTurn() {
        val out = googleContents(listOf(UIMessage.assistant("Welcome."), UIMessage.user("Hi")))
        assertEquals(listOf("user", "model", "user"), roles(out))
        assertEquals(LEADING_USER_PLACEHOLDER, out[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("user", "model"), roles(googleContents(listOf(UIMessage.user("a"), UIMessage.assistant("b")))))
    }
}
