package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.cli.CliAgent
import me.rerere.rikkihub.data.db.dao.UsageGroupRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class UsageStatsServiceCliTest {
    private val now = Instant.parse("2026-11-03T18:00:00Z").toEpochMilli()

    @Test
    fun `cli usage record is classified as CLI and never matches API provider pricing or ownership`() = runBlocking {
        val apiModel = Model(
            modelId = "claude-sonnet-4-5",
            pricing = ModelPricing(input = 3.0, output = 15.0, cachedInput = 0.3),
        )
        val apiProvider = ProviderSetting.OpenAI(
            name = "Anthropic Proxy",
            models = listOf(apiModel),
        )
        val dao = UsageTestDAO().apply {
            rows = listOf(
                UsageGroupRow(
                    bucket = "2026-11-03",
                    providerId = "cli:claude",
                    modelId = null,
                    modelName = "claude-sonnet-4-5",
                    source = UsageSource.CLI.name,
                    requests = 2,
                    promptTokens = 1_000_000,
                    completionTokens = 100_000,
                    cachedTokens = 500_000,
                    totalTokens = 1_100_000,
                )
            )
        }

        val report = UsageStatsService(dao, clock = { now }).report(UsageRange.ALL, listOf(apiProvider))

        val providerStat = report.providers.single()
        assertEquals(UsageProviderKind.CLI, providerStat.kind)
        assertEquals("cli:claude", providerStat.providerId)
        assertEquals("claude", providerStat.name)
        assertEquals(0.0, providerStat.amount.cost, 0.0)
        assertEquals(0L, providerStat.amount.pricedTokens)

        assertEquals(0.0, report.total.cost, 0.0)
        assertEquals(0L, report.total.pricedTokens)

        val modelStat = report.models.single()
        assertEquals("claude-sonnet-4-5", modelStat.name)
        assertEquals("claude", modelStat.providerName)
        assertFalse(modelStat.priced)
        assertEquals(0.0, modelStat.amount.cost, 0.0)
    }

    @Test
    fun `built-in cli agent resolves display name and CLI provider kind`() = runBlocking {
        val dao = UsageTestDAO().apply {
            rows = listOf(
                UsageGroupRow(
                    bucket = "2026-11-03",
                    providerId = "cli:${CliAgent.AGY}",
                    modelId = null,
                    modelName = "Antigravity",
                    source = UsageSource.CLI.name,
                    requests = 1,
                    promptTokens = 100,
                    completionTokens = 50,
                    cachedTokens = 0,
                    totalTokens = 150,
                )
            )
        }

        val report = UsageStatsService(dao, clock = { now }).report(UsageRange.ALL, emptyList())

        val providerStat = report.providers.single()
        assertEquals(UsageProviderKind.CLI, providerStat.kind)
        assertEquals("cli:${CliAgent.AGY}", providerStat.providerId)
        assertEquals("Antigravity", providerStat.name)
    }

    @Test
    fun `recordCliUsage writes CLI entity directly without account or quota callbacks`() = runBlocking {
        val dao = UsageTestDAO()
        var accountCallbackCalled = false
        var quotaCallbackCalled = false
        val recorder = UsageRecorder(dao, CoroutineScope(SupervisorJob()), clock = { now }).apply {
            onAccountUsage = { accountCallbackCalled = true }
            onQuotaReadings = { quotaCallbackCalled = true }
        }
        val conversationId = Uuid.random()

        recorder.recordCliUsage(
            agentId = "claude",
            agentName = "Claude Code",
            modelName = "  ",
            conversationId = conversationId,
            usage = TokenUsage(promptTokens = 120, completionTokens = 30, cachedTokens = 20, totalTokens = 150),
        )

        val entity = dao.records.single()
        assertEquals(UsageSource.CLI.name, entity.source)
        assertEquals("cli:claude", entity.providerId)
        assertNull(entity.modelId)
        assertEquals("Claude Code", entity.modelName)
        assertEquals(conversationId.toString(), entity.conversationId)
        assertEquals(120L, entity.promptTokens)
        assertEquals(30L, entity.completionTokens)
        assertEquals(20L, entity.cachedTokens)
        assertEquals(150L, entity.totalTokens)
        assertFalse(accountCallbackCalled)
        assertFalse(quotaCallbackCalled)
    }
}
