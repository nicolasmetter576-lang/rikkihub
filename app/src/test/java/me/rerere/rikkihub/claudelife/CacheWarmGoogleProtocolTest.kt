package me.rerere.rikkihub.claudelife

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import me.rerere.ai.claudelife.CacheKeepAliveConfig
import me.rerere.ai.claudelife.CacheWarmer
import me.rerere.ai.claudelife.CacheWarmRequest
import me.rerere.ai.claudelife.WarmTicker
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.*
import org.junit.Test

class CacheWarmGoogleProtocolTest {
    private val model = Model(modelId = "gemini")
    private fun check(captured: ProviderSetting, latest: ProviderSetting): String? =
        cacheWarmGoogleProtocolChange(captured, model.id, Settings(providers = listOf(latest.copyProvider(models = listOf(model)))))

    @Test fun `effective Interactions switch in either direction invalidates baseline`() {
        val off = ProviderSetting.Google()
        val on = off.copy(useInteractionsApi = true)
        assertNotNull(check(off, on))
        assertNotNull(check(on, off))
        assertNull(check(off, off))
        assertNull(check(on, on))
    }

    @Test fun `Vertex masks flag without silently resetting it`() {
        val on = ProviderSetting.Google(useInteractionsApi = true)
        val vertex = on.copy(vertexAI = true)
        assertNotNull(check(on, vertex))
        assertNotNull(check(vertex, on))
        assertNull(check(vertex, vertex.copy(useInteractionsApi = false)))
        assertNull(check(vertex.copy(useInteractionsApi = false), vertex))
        assertNull(check(ProviderSetting.Google(), ProviderSetting.Google(vertexAI = true)))
        assertTrue(vertex.useInteractionsApi)
    }

    @Test fun `credentials headers and baseURL remain mutable for latest replay`() {
        val captured = ProviderSetting.Google(useInteractionsApi = true, apiKey = "old", baseUrl = "http://old")
        val latest = captured.copy(apiKey = "new", baseUrl = "http://new", customHeaders = listOf(CustomHeader("X-New", "v")), models = listOf(model))
        val settings = Settings(providers = listOf(latest))
        assertNull(cacheWarmGoogleProtocolChange(captured, model.id, settings))
        assertSame(latest, resolveReplayProvider(model, captured, settings))
    }

    @Test fun `latest selected model overwrite not stale captured overwrite determines protocol`() {
        val off = ProviderSetting.Google()
        val on = off.copy(useInteractionsApi = true)
        val stale = model.copy(providerOverwrite = off)
        val latest = model.copy(providerOverwrite = on)
        val parent = off.copy(models = listOf(latest))
        val settings = Settings(providers = listOf(parent))
        assertNotNull(cacheWarmGoogleProtocolChange(off, stale.id, settings))
        assertTrue((resolveReplayProvider(stale, off, settings) as ProviderSetting.Google).useInteractionsApi)
        assertNull(cacheWarmGoogleProtocolChange(on, stale.id, settings))
        val cleared = settings.copy(providers = listOf(parent.copy(models = listOf(model))))
        assertNotNull(cacheWarmGoogleProtocolChange(on, stale.id, cleared))
    }

    @Test fun `Google overwrite on a nonGoogle parent and provider type transitions are checked`() {
        val on = ProviderSetting.Google(useInteractionsApi = true)
        val parent = ProviderSetting.OpenAI(models = listOf(model.copy(providerOverwrite = on)))
        val settings = Settings(providers = listOf(parent))
        assertNull(cacheWarmGoogleProtocolChange(on, model.id, settings))
        assertNotNull(cacheWarmGoogleProtocolChange(ProviderSetting.Google(), model.id, settings))
        assertNotNull(check(on, ProviderSetting.OpenAI()))
        assertNotNull(check(ProviderSetting.OpenAI(), on))
        assertNull(check(ProviderSetting.OpenAI(), ProviderSetting.Claude()))
    }

    @Test fun `actual warmer keeps credential refresh but stops and seals round on effective protocol change`() {
        val captured = ProviderSetting.Google(useInteractionsApi = true, models = listOf(model))
        val messages = listOf(UIMessage.user("baseline"))
        val request = CacheWarmRequest(captured, messages, TextGenerationParams(model, maxTokens = 256), messages)
        var settings = Settings(providers = listOf(captured))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val ticker = object : WarmTicker {
            override fun nowMs() = 0L
            override suspend fun delayMs(ms: Long) { awaitCancellation() }
        }
        val warmer = CacheWarmer(ticker, scope, config = { CacheKeepAliveConfig(enabled = true) },
            replay = { error("No clock advance means no replay") })
        val current = { cacheWarmGoogleProtocolChange(captured, model.id, settings) }
        try {
            val generation = warmer.newGeneration()
            warmer.start(request, generation, current)
            assertTrue(warmer.isActive)
            val refreshed = captured.copy(apiKey = "new", baseUrl = "https://new.invalid/v1beta",
                customHeaders = listOf(CustomHeader("X-New", "latest")))
            settings = settings.copy(providers = listOf(refreshed))
            warmer.revalidate()
            assertTrue(warmer.isActive)
            settings = settings.copy(providers = listOf(refreshed.copy(useInteractionsApi = false)))
            warmer.revalidate()
            assertFalse(warmer.isActive)
            settings = settings.copy(providers = listOf(refreshed))
            warmer.start(request, generation, current)
            assertFalse("A protocol stop seals the current generation", warmer.isActive)
            warmer.start(request, warmer.newGeneration(), current)
            assertTrue(warmer.isActive)
        } finally {
            warmer.close()
            scope.cancel()
        }
    }

    @Test fun `context wiring captures actual request provider and resolves latest selected model`() {
        val path = "app/src/main/java/me/rerere/rikkihub/service/ChatService.kt"
        val source = sequenceOf(File(path), File("../$path")).first { it.isFile }.readText()
        assertTrue(source.contains("requestProvider = providerSetting"))
        assertTrue(source.contains("cacheWarmGoogleProtocolChange(requestProvider, currentModelId, settings)"))
        assertFalse(source.contains("params.model.findProvider"))
    }
}
