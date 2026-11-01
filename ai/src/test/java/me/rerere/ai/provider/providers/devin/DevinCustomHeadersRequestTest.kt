package me.rerere.ai.provider.providers.devin

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.mergeCustomHeaders
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class DevinCustomHeadersRequestTest {
    private val provider = ProviderSetting.Devin(customHeaders = listOf(
        CustomHeader("X-Provider", "global"), CustomHeader("X-Override", "global"),
        CustomHeader("Authorization", "not-account"), CustomHeader("Content-Type", "wrong"),
        CustomHeader("Connect-Protocol-Version", "99"), CustomHeader("User-Agent", "wrong"),
        CustomHeader("Accept", "wrong"),
    ))
    private fun client(captured: MutableList<Request>, bytes: ByteArray) =
        OkHttpClient.Builder().addInterceptor { chain ->
            captured += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody("application/proto".toMediaType())).build()
        }.build()

    @Test fun `Devin model list receives provider headers but keeps account and Connect protocol`() {
        val captured = mutableListOf<Request>()
        val api = DevinChatApi(client(captured, byteArrayOf()), baseUrl = "https://devin.invalid")
        assertTrue(api.getCliModelConfigs("token", "seed", osName = "linux", customHeaders = provider.mergeCustomHeaders()).isEmpty())
        val request = captured.single()
        assertEquals("global", request.header("X-Provider"))
        assertEquals(listOf("Basic token-token"), request.headers.values("Authorization"))
        assertEquals(listOf("application/proto"), request.headers.values("Content-Type"))
        assertEquals(listOf("1"), request.headers.values("Connect-Protocol-Version"))
        assertEquals(listOf("connect-go/1.19.1 (go1.25.0)"), request.headers.values("User-Agent"))
    }

    @Test fun `Devin chat receives merged snapshot while final protocol headers win`() = runBlocking {
        val captured = mutableListOf<Request>()
        val eos = wrapConnectEnvelopeWithFlag(CONNECT_FLAG_END_STREAM, "{}".toByteArray())
        val api = DevinChatApi(client(captured, eos), baseUrl = "https://devin.invalid")
        val request = DevinChatRequest(
            sessionToken = "token", deviceSeed = "seed", osName = "linux", chatModelUid = "model",
            customHeaders = provider.mergeCustomHeaders(listOf(CustomHeader("x-override", "request"))),
        )
        assertEquals(1, api.streamChat(request).toList().filterIsInstance<DevinStreamEvent.Eos>().size)
        val actual = captured.single()
        assertEquals("global", actual.header("X-Provider"))
        assertEquals(listOf("request"), actual.headers.values("X-Override"))
        assertEquals(listOf("Basic token-token"), actual.headers.values("Authorization"))
        assertEquals(listOf("application/connect+proto"), actual.headers.values("Content-Type"))
        assertEquals(listOf("1"), actual.headers.values("Connect-Protocol-Version"))
        assertEquals(listOf("*/*"), actual.headers.values("Accept"))
        // Application interceptor sees the intentionally empty UA. The existing
        // network interceptor removes it on an actual transport, not exercised here.
        assertEquals("", actual.header("User-Agent"))
    }

    @Test fun `transport headers do not enter protobuf or change original request bytes`() {
        val session = "headers-${System.nanoTime()}"
        val original = DevinChatRequest(sessionToken = "token", deviceSeed = "seed", osName = "linux",
            chatModelUid = "model", sessionId = session, cascadeId = session)
        try {
            // Encoding increments the existing per-session turn counter. Freeze it
            // before each comparison so only the transport-header field differs.
            resetDevinSessionTurnIndex(session)
            val withoutHeaders = original.buildRequestBytes()
            resetDevinSessionTurnIndex(session)
            val withHeaders = original.copy(customHeaders = provider.mergeCustomHeaders()).buildRequestBytes()
            assertArrayEquals(withoutHeaders, withHeaders)
        } finally {
            resetDevinSessionTurnIndex(session)
        }
    }
}
