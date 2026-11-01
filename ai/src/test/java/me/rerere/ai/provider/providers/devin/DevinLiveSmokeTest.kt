package me.rerere.ai.provider.providers.devin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.util.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 真实上游冒烟测试. 默认跳过, 需要:
 *   DEVIN_SMOKE=1 ./gradlew :ai:testDebugUnitTest --tests "me.rerere.ai.provider.providers.devin.DevinLiveSmokeTest"
 * 并在 ~/.cli-proxy-api/ 下有 devin-*.json (平铺 JSON, 含 session_token 字段).
 * 绝不把 token 写进任何文件或日志.
 */
class DevinLiveSmokeTest {

    private var sessionToken: String? = null

    @Before
    fun loadCredentials() {
        Assume.assumeTrue(
            "DEVIN_SMOKE not set, skipping live smoke test",
            System.getenv("DEVIN_SMOKE") == "1",
        )
        val dir = File(System.getProperty("user.home"), ".cli-proxy-api")
        val file = dir.listFiles { f ->
            f.name.startsWith("devin-") && f.extension == "json"
        }?.firstOrNull()
        Assume.assumeNotNull("no devin-*.json under ${dir.absolutePath}", file)
        val token = runCatching {
            json.parseToJsonElement(file!!.readText())
                .jsonObject["session_token"]?.jsonPrimitive?.content
        }.getOrNull()
        Assume.assumeNotNull("session_token missing in ${file!!.name}", token)
        sessionToken = token
    }

    private val api by lazy {
        DevinChatApi(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
        )
    }

    @Test
    fun `getUserStatus returns account info`() {
        val status = api.getUserStatus(sessionToken!!, deviceSeed = "")
        assertTrue(
            "expected non-empty account payload",
            status.email.isNotEmpty() || status.userId.isNotEmpty() || status.plan.isNotEmpty(),
        )
    }

    @Test
    fun `streamChat emits text and eos`() = runBlocking {
        val events = api.streamChat(
            DevinChatRequest(
                sessionToken = sessionToken!!,
                chatModelUid = "swe-2-high",
                prompts = listOf(
                    DevinPrompt(
                        messageId = "smoke-${System.nanoTime()}",
                        source = 1,
                        content = "Reply with exactly: ok",
                    )
                ),
                sessionId = "smoke-${System.nanoTime()}",
            )
        ).toList()

        assertTrue("expected at least one frame event", events.any { it is DevinStreamEvent.Frame })
        val text = events.filterIsInstance<DevinStreamEvent.Frame>()
            .joinToString("") { it.result.contentText }
        assertTrue("expected text delta, got events=$events", text.isNotEmpty())
        assertTrue("expected EOS event", events.any { it is DevinStreamEvent.Eos })
        assertNotNull(events)
    }
}
