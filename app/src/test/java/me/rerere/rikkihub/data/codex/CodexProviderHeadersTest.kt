package me.rerere.rikkihub.data.codex

import android.os.Build
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.utils.JsonInstant as json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.rikkihub.AppScope
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

/** No OAuth/login/credential IO. Exercise Codex's actual private adaptation helpers. */
class CodexProviderHeadersTest {
    private val account = CodexAccount("id", name = "test", chatgptAccountId = "account", accessToken = "token",
        refreshToken = "unused", expiresAt = Long.MAX_VALUE)
    private val setting = ProviderSetting.Codex(customHeaders = listOf(
        CustomHeader("X-Provider", "provider"), CustomHeader("X-Override", "provider"),
        CustomHeader("Authorization", "wrong"), CustomHeader("ChatGPT-Account-Id", "wrong"),
        CustomHeader("originator", "wrong"), CustomHeader("OpenAI-Beta", "wrong"), CustomHeader("User-Agent", "wrong"),
    ))

    private fun <T> withProvider(block: (CodexProvider) -> T): T {
        val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
        // Android's JVM mock jar supplies null static ABI data; test-only initialization
        // lets the existing Codex UA initializer run without touching production code.
        val abisField = Build::class.java.getDeclaredField("SUPPORTED_ABIS")
        val oldAbis = abisField.get(null)
        if (oldAbis == null) unsafe.putObject(unsafe.staticFieldBase(abisField), unsafe.staticFieldOffset(abisField), arrayOf("arm64-v8a"))
        val scope = AppScope()
        try {
            val repository = unsafe.allocateInstance(CodexAccountRepository::class.java) as CodexAccountRepository
            return block(CodexProvider(OkHttpClient(), repository, json, scope))
        } finally {
            scope.cancel()
            if (oldAbis == null) unsafe.putObject(unsafe.staticFieldBase(abisField), unsafe.staticFieldOffset(abisField), null)
        }
    }

    private fun synthetic(provider: CodexProvider) = CodexProvider::class.java.getDeclaredMethod(
        "syntheticSetting", ProviderSetting.Codex::class.java, CodexAccount::class.java,
    ).apply { isAccessible = true }.invoke(provider, setting, account) as ProviderSetting.OpenAI
    private fun params(provider: CodexProvider, params: TextGenerationParams, stream: Boolean) =
        CodexProvider::class.java.getDeclaredMethod("withCodexParams", TextGenerationParams::class.java,
            CodexAccount::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(provider, params, account, stream) as TextGenerationParams

    @Test fun `synthetic OpenAI setting propagates headers while account bearer remains authoritative`() = withProvider { provider ->
        val synthetic = synthetic(provider)
        assertEquals(setting.customHeaders, synthetic.customHeaders)
        assertEquals(account.accessToken, synthetic.apiKey)
        assertNull(synthetic.mergeCustomHeaders()["Authorization"])
        assertEquals(setting.id, synthetic.id)
    }

    @Test fun `Codex model-list dedicated header builder overrides provider credentials and UA`() = withProvider { provider ->
        val builder = Request.Builder().url("https://codex.invalid/models").headers(setting.mergeCustomHeaders())
        val method = CodexProvider::class.java.getDeclaredMethod("codexHeaders", Request.Builder::class.java, CodexAccount::class.java)
            .apply { isAccessible = true }
        val request = (method.invoke(provider, builder, account) as Request.Builder).build()
        assertEquals("provider", request.header("X-Provider"))
        assertEquals(listOf("Bearer token"), request.headers.values("Authorization"))
        assertEquals(listOf("account"), request.headers.values("ChatGPT-Account-Id"))
        assertEquals(listOf("codex_cli_rs"), request.headers.values("originator"))
        assertEquals(listOf("responses=experimental"), request.headers.values("OpenAI-Beta"))
        assertTrue(request.header("User-Agent")!!.startsWith("codex_cli_rs/"))
    }

    @Test fun `actual Responses builder uses Codex merged headers for both stream modes`() = withProvider { provider ->
        runBlocking {
            for (stream in listOf(false, true)) {
                val captured = mutableListOf<Request>()
                val response = """{"id":"r","model":"m","status":"completed","output":[]}"""
                val client = OkHttpClient.Builder().addInterceptor { chain ->
                    captured += chain.request()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body((if (stream) "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":$response}\n\n" else response)
                            .toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
                }.build()
                val original = TextGenerationParams(Model(modelId = "m"), customHeaders = listOf(CustomHeader("x-override", "request")))
                val adapted = params(provider, original, stream)
                assertEquals(original.sessionId, adapted.sessionId)
                if (stream) ResponseAPI(client).streamText(synthetic(provider), listOf(UIMessage.system("hi"), UIMessage.user("hi")), adapted).toList()
                else ResponseAPI(client).generateText(synthetic(provider), listOf(UIMessage.system("hi"), UIMessage.user("hi")), adapted)
                val request = captured.single()
                assertEquals("provider", request.header("X-Provider"))
                assertEquals(listOf("request"), request.headers.values("X-Override"))
                assertEquals(listOf("Bearer token"), request.headers.values("Authorization"))
                assertEquals(listOf("account"), request.headers.values("ChatGPT-Account-Id"))
                assertEquals(listOf("codex_cli_rs"), request.headers.values("originator"))
                assertTrue(request.header("User-Agent")!!.startsWith("codex_cli_rs/"))
                assertEquals(original.sessionId, request.header("X-Session-ID"))
            }
        }
    }

    @Test fun `Codex and Devin production adapter call sites keep header propagation`() {
        fun source(path: String) = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        val codex = source("app/src/main/java/me/rerere/rikkihub/data/codex/CodexProvider.kt")
        assertTrue(codex.contains(".headers(providerSetting.mergeCustomHeaders())\n                .codexHeaders(account)"))
        assertTrue(codex.contains("providerSetting = syntheticSetting(providerSetting, account)"))
        val devin = source("app/src/main/java/me/rerere/rikkihub/data/devin/DevinProvider.kt")
        assertTrue(devin.contains("customHeaders = providerSetting.mergeCustomHeaders(),"))
        assertTrue(devin.contains("customHeaders = providerSetting.mergeCustomHeaders(params.customHeaders),"))
    }
}
