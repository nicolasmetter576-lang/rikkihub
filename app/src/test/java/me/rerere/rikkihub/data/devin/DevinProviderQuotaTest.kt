package me.rerere.rikkihub.data.devin

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.devin.DEVIN_CHAT_PATH
import me.rerere.ai.provider.providers.devin.DEVIN_GET_CLI_MODEL_CONFIGS_PATH
import me.rerere.ai.provider.providers.devin.DevinApiException
import me.rerere.ai.provider.providers.devin.DevinChatApi
import me.rerere.ai.provider.providers.devin.ProtoWire
import me.rerere.ai.provider.providers.devin.ProtoWire.appendBytes
import me.rerere.ai.provider.providers.devin.ProtoWire.appendString
import me.rerere.ai.provider.providers.devin.ProtoWire.appendTag
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 配额用尽时 Devin 账户仍能拉模型目录、用免费模型; 付费模型由上游报配额耗尽后
 * 只对该账户 + 模型记冷却并换号. 用本机假 Devin 服务器走完整 provider 链路.
 */
class DevinProviderQuotaTest {
    private lateinit var server: HttpServer

    /** (token, 请求体按 latin-1 解码) */
    private val chatCalls = CopyOnWriteArrayList<Pair<String, String>>()
    private val modelConfigCalls = AtomicInteger()

    /** token → 对请求体含这些子串的模型报日配额耗尽 */
    private val exhaustedModels = ConcurrentHashMap<String, Set<String>>()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(DEVIN_GET_CLI_MODEL_CONFIGS_PATH) { exchange ->
            modelConfigCalls.incrementAndGet()
            exchange.requestBody.readBytes()
            val model = ByteArrayOutputStream().apply {
                appendTag(1, ProtoWire.BYTES)
                appendString("Remote Only")
                appendTag(22, ProtoWire.BYTES)
                appendString("remote-only-model")
            }.toByteArray()
            val body = ByteArrayOutputStream().apply {
                appendTag(1, ProtoWire.BYTES)
                appendBytes(model)
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/proto")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext(DEVIN_CHAT_PATH) { exchange ->
            val token = exchange.requestHeaders.getFirst("Authorization")
                .removePrefix("Basic ")
                .substringBefore('-')
            val body = exchange.requestBody.readBytes().toString(Charsets.ISO_8859_1)
            chatCalls += token to body
            val out = ByteArrayOutputStream()
            if (exhaustedModels[token].orEmpty().any { it in body }) {
                out.connectFrame(
                    flag = 0x02,
                    payload = """{"error":{"code":"failed_precondition","message":"Your daily usage quota has been exhausted."}}"""
                        .toByteArray(),
                )
            } else {
                val text = ByteArrayOutputStream().apply {
                    appendTag(3, ProtoWire.BYTES)
                    appendString("ok:$token")
                }.toByteArray()
                out.connectFrame(flag = 0x00, payload = text)
                out.connectFrame(flag = 0x02, payload = "{}".toByteArray())
            }
            val bytes = out.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/connect+proto")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `exhausted account still fetches the remote model list`() = runBlocking {
        val provider = provider(account("a", daily = 0, dailyResetAt = FUTURE))

        val ids = provider.listModels(ProviderSetting.Devin()).map { it.modelId }

        assertEquals(1, modelConfigCalls.get())
        assertTrue("remote-only-model" in ids)
        assertTrue("claude-opus-5-5" in ids)
    }

    @Test
    fun `free model still works on an exhausted account`() = runBlocking {
        exhaustedModels["a"] = setOf("claude-opus-5-5")
        val provider = provider(account("a", daily = 0, weekly = 0, dailyResetAt = FUTURE))

        assertEquals("ok:a", provider.chat("swe-1-6"))
        assertEquals(listOf("a"), chatCalls.map { it.first })
    }

    @Test
    fun `quota error on a single account surfaces the upstream message and blocks nothing else`() =
        runBlocking {
            exhaustedModels["a"] = setOf("claude-opus-5-5")
            val provider = provider(account("a"))

            val error = provider.chatError("claude-opus-5-5")
            assertTrue(error is DevinApiException && error.statusCode == 429)
            assertTrue(error.message.orEmpty().contains("daily usage quota has been exhausted"))

            // 其他模型照常; 同一模型再发也照样交给上游, 不在本地拦
            assertEquals("ok:a", provider.chat("swe-1-6"))
            assertTrue(provider.chatError("claude-opus-5-5") is DevinApiException)
            assertEquals(listOf("a", "a", "a"), chatCalls.map { it.first })
        }

    @Test
    fun `quota error switches account and the cooldown is per model`() = runBlocking {
        exhaustedModels["a"] = setOf("claude-opus-5-5")
        val provider = provider(account("a"), account("b"))

        // a 报耗尽 → 同一请求换 b 重发
        assertEquals("ok:b", provider.chat("claude-opus-5-5"))
        assertEquals(listOf("a", "b"), chatCalls.map { it.first })

        // 轮到 a 但 a 的这个模型在冷却 → 直接用 b
        chatCalls.clear()
        assertEquals("ok:b", provider.chat("claude-opus-5-5"))
        assertEquals(listOf("b"), chatCalls.map { it.first })

        // 别的模型不受冷却影响, 照常轮到 a
        chatCalls.clear()
        assertEquals("ok:a", provider.chat("swe-1-6"))
        assertEquals(listOf("a"), chatCalls.map { it.first })
    }

    @Test
    fun `quota error with every account exhausted surfaces the upstream error`() = runBlocking {
        exhaustedModels["a"] = setOf("claude-opus-5-5")
        exhaustedModels["b"] = setOf("claude-opus-5-5")
        val provider = provider(account("a"), account("b"))

        assertTrue(provider.chatError("claude-opus-5-5") is DevinApiException)
        assertEquals(listOf("a", "b"), chatCalls.map { it.first })
    }

    @Test
    fun `only disabled or signed out accounts gives a clear error`() = runBlocking {
        val provider = provider(
            account("off", enabled = false),
            account("bad", status = DevinAccountStatus.INVALID),
        )
        val error = provider.chatError("swe-1-6")
        assertTrue(error is IllegalStateException)
        assertTrue(error.message.orEmpty().contains("disabled"))
        assertTrue(chatCalls.isEmpty())
    }

    @Test
    fun `model list labels duplicate display names and keeps enum uids upper case`() = runBlocking {
        val provider = provider()
        val models = provider.listModels(ProviderSetting.Devin())

        assertEquals(0, modelConfigCalls.get())
        val names = models.map { it.displayName }
        assertTrue("GPT-5.1 (MODEL_PRIVATE_12)" in names)
        assertTrue("Claude Opus 5.5" in names)
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)

        assertEquals("MODEL_PRIVATE_11", provider.resolveChatModelUid("MODEL_PRIVATE_11", ReasoningLevel.AUTO))
        assertEquals("MODEL_GPT_5_2_HIGH", provider.resolveChatModelUid("MODEL_GPT_5_2", ReasoningLevel.HIGH))
        assertEquals("MODEL_GPT_5_2_LOW", provider.resolveChatModelUid("MODEL_GPT_5_2_LOW", ReasoningLevel.HIGH))
    }

    private fun provider(vararg accounts: DevinAccount): DevinProvider {
        val client = OkHttpClient()
        val api = DevinChatApi(client, "http://127.0.0.1:${server.address.port}")
        val repository = DevinAccountRepository(
            store = InMemoryStore(DevinAccountState(accounts = accounts.toList())),
            client = client,
            chatApi = api,
            json = Json,
        )
        return DevinProvider(api, repository)
    }

    private suspend fun DevinProvider.chat(modelId: String): String {
        val chunks = streamText(
            ProviderSetting.Devin(),
            listOf(UIMessage.user("hi")),
            TextGenerationParams(model = Model(modelId = modelId)),
        ).toList()
        assertFalse(chunks.none { it is StreamChunk.Finish })
        return chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
    }

    private suspend fun DevinProvider.chatError(modelId: String): Throwable {
        try {
            chat(modelId)
        } catch (error: Throwable) {
            return error
        }
        fail("expected $modelId to fail")
        error("unreachable")
    }

    private fun ByteArrayOutputStream.connectFrame(flag: Int, payload: ByteArray) {
        write(flag)
        write(ByteBuffer.allocate(4).putInt(payload.size).array())
        write(payload)
    }

    private class InMemoryStore(var state: DevinAccountState) : DevinAccountStore {
        override fun read(): DevinAccountState = state
        override fun write(state: DevinAccountState) {
            this.state = state
        }
    }

    private fun account(
        id: String,
        enabled: Boolean = true,
        status: DevinAccountStatus = DevinAccountStatus.ACTIVE,
        daily: Long = 100,
        weekly: Long = 100,
        dailyResetAt: Long = 0,
    ) = DevinAccount(
        id = id,
        sessionToken = id,
        enabled = enabled,
        status = status,
        dailyQuotaRemainingPercent = daily,
        weeklyQuotaRemainingPercent = weekly,
        dailyQuotaResetAt = dailyResetAt,
    )

    private companion object {
        const val FUTURE = 2_000_000_000L
    }
}
