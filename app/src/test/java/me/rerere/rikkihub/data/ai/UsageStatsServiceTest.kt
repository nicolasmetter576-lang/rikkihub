package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.db.dao.UsageGroupRow
import me.rerere.rikkihub.data.db.entity.QuotaSnapshotEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class UsageStatsServiceTest {
    private val now = Instant.parse("2026-11-03T18:00:00Z").toEpochMilli()
    private val model = Model(modelId = "mock-chat", pricing = ModelPricing(input = 2.0, output = 4.0))
    private val provider = ProviderSetting.OpenAI(models = listOf(model))
    private fun row(prompt: Long = 100, output: Long = 20, total: Long = 999, incomplete: Long = 0) =
        UsageGroupRow("2026-11-03", provider.id.toString(), model.id.toString(), model.modelId,
            "CHAT", 1, prompt, output, 40, total, incomplete)

    @Test fun `deleting a model row removes only its records, deleting a provider removes its quota readings too`() = runBlocking {
        val other = Model(modelId = "other-chat")
        val devin = ProviderSetting.Devin(models = listOf(other))
        val dao = UsageTestDAO().apply {
            rows = listOf(
                row(),
                row().copy(providerId = devin.id.toString(), modelId = other.id.toString(), modelName = other.modelId),
            )
            snapshots += QuotaSnapshotEntity(createdAt = now, accountKey = "devin:a", window = "daily", remaining = 0.5)
        }
        val service = UsageStatsService(dao, clock = { now })
        val providers = listOf(provider, devin)

        val before = service.report(UsageRange.ALL, providers)
        service.deleteModelUsage(before.models.first { it.name == "mock-chat" }.key)
        assertEquals(listOf("other-chat"), service.report(UsageRange.ALL, providers).models.map { it.name })
        assertEquals(1, dao.snapshots.size)

        service.deleteProviderUsage(providers, devin.id.toString())
        assertEquals(emptyList<UsageModelStat>(), service.report(UsageRange.ALL, providers).models)
        assertEquals(emptyList<QuotaSnapshotEntity>(), dao.snapshots)
    }

    @Test fun `provider cost counts only that provider at current prices`() = runBlocking {
        val other = ProviderSetting.OpenAI(models = listOf(model))
        val dao = UsageTestDAO().apply {
            rows = listOf(row(prompt = 1_000_000, output = 0), row().copy(providerId = other.id.toString()))
        }
        val service = UsageStatsService(dao, clock = { now })
        assertEquals(2.0, service.providerCost(listOf(provider, other), provider.id.toString(), since = 0), 1e-9)
    }

    @Test fun `inconsistent cache counts cannot produce a 106 percent hit rate or a fake 100 percent`() = runBlocking {
        val dao = UsageTestDAO().apply { rows = listOf(row().copy(cachedTokens = 106)) }
        val report = UsageStatsService(dao, clock = { now }).report(UsageRange.D7, listOf(provider))
        assertNull(report.total.cacheHitRate)
        assertNull(report.models.single().amount.cacheHitRate)
        // Preserve the upstream/legacy counters for diagnosis rather than silently rewriting history.
        assertEquals(106L, report.total.cachedTokens)
        assertEquals(100L, report.total.promptTokens)
        assertEquals(0.4, UsageAmount(promptTokens = 100, cachedTokens = 40).cacheHitRate!!, 0.0)
        assertEquals(1.0, UsageAmount(promptTokens = 100, cachedTokens = 100).cacheHitRate!!, 0.0)
        assertEquals(0.0, UsageAmount().cacheHitRate!!, 0.0)
        assertNull(UsageAmount(cachedTokens = 1).cacheHitRate)
        assertNull(UsageAmount(promptTokens = 100, cachedTokens = -1).cacheHitRate)
    }

    @Test fun `legacy model UUID loss falls back to an unambiguous model snapshot and current prices`() = runBlocking {
        val current = ProviderSetting.Devin(models = listOf(model))
        val dao = UsageTestDAO().apply {
            rows = listOf(row().copy(providerId = null, modelId = "former-model-uuid"))
        }
        val report = UsageStatsService(dao, clock = { now }).report(UsageRange.ALL, listOf(current))
        assertEquals(current.id.toString(), report.providers.single().providerId)
        assertEquals(UsageProviderKind.DEVIN, report.providers.single().kind)
        assertEquals(current.name, report.models.single().providerName)
        assertTrue(report.models.single().priced)
        assertEquals(0.00028, report.total.cost, 1e-12)
    }

    @Test fun `ambiguous legacy model names remain unknown instead of guessing a provider or price`() = runBlocking {
        val second = ProviderSetting.Devin(models = listOf(model.copy(id = kotlin.uuid.Uuid.random())))
        val dao = UsageTestDAO().apply {
            rows = listOf(row().copy(providerId = null, modelId = "former-model-uuid"))
        }
        val report = UsageStatsService(dao, clock = { now }).report(UsageRange.ALL, listOf(provider, second))
        assertEquals(UsageProviderKind.UNKNOWN, report.providers.single().kind)
        assertNull(report.models.single().providerName)
        assertEquals(0L, report.total.pricedTokens)
    }

    @Test fun `explicit provider identity scopes name fallback and deleted providers are never reassigned`() = runBlocking {
        val second = ProviderSetting.Devin(models = listOf(model.copy(id = kotlin.uuid.Uuid.random(),
            pricing = ModelPricing(input = 0.0, output = 0.0))))
        val dao = UsageTestDAO().apply {
            rows = listOf(row().copy(providerId = second.id.toString(), modelId = "former-model-uuid"))
        }
        val service = UsageStatsService(dao, clock = { now })
        val report = service.report(UsageRange.ALL, listOf(provider, second))
        assertEquals(UsageProviderKind.DEVIN, report.providers.single().kind)
        assertEquals(second.name, report.models.single().providerName)
        assertTrue(report.models.single().priced)
        assertEquals(0.0, report.total.cost, 0.0)
        dao.rows = listOf(row().copy(providerId = "deleted-provider"))
        val deleted = service.report(UsageRange.ALL, listOf(provider, second))
        assertEquals(UsageProviderKind.DELETED, deleted.providers.single().kind)
        assertNull(deleted.models.single().providerName)
        assertEquals(0L, deleted.total.pricedTokens)
    }

    @Test fun `exact model UUID wins over duplicate names while missing snapshots stay unknown`() = runBlocking {
        val second = ProviderSetting.Devin(models = listOf(model.copy(id = kotlin.uuid.Uuid.random())))
        val dao = UsageTestDAO().apply { rows = listOf(row().copy(providerId = null)) }
        val service = UsageStatsService(dao, clock = { now })
        assertEquals(provider.id.toString(),
            service.report(UsageRange.ALL, listOf(provider, second)).providers.single().providerId)
        dao.rows = listOf(row().copy(providerId = null, modelId = "former-model-uuid", modelName = null))
        assertEquals(UsageProviderKind.UNKNOWN,
            service.report(UsageRange.ALL, listOf(provider, second)).providers.single().kind)
    }

    @Test fun `copied model UUIDs cannot hide ambiguous historical ownership`() = runBlocking {
        val second = ProviderSetting.Devin(models = listOf(model))
        val dao = UsageTestDAO()
        val service = UsageStatsService(dao, clock = { now })
        for (id in listOf(model.id.toString(), "former-model-uuid")) {
            dao.rows = listOf(row().copy(providerId = null, modelId = id))
            val report = service.report(UsageRange.ALL, listOf(provider, second))
            assertEquals(UsageProviderKind.UNKNOWN, report.providers.single().kind)
            assertEquals(0L, report.total.pricedTokens)
        }
        dao.rows = listOf(row())
        val explicit = service.report(UsageRange.ALL, listOf(provider, second))
        assertEquals(provider.id.toString(), explicit.providers.single().providerId)
        assertTrue(explicit.models.single().priced)
    }

    @Test fun `current field prices distinguish missing free and cached fallback`() = runBlocking {
        val dao = UsageTestDAO().apply { rows = listOf(row()) }
        val service = UsageStatsService(dao, clock = { now }, zone = { ZoneId.of("UTC") })
        val priced = service.report(UsageRange.D7, listOf(provider))
        assertEquals(7, priced.trend.size)
        assertEquals(999L, priced.total.totalTokens)
        assertEquals(120L, priced.total.pricedTokens)
        assertEquals(0.00028, priced.total.cost, 1e-12)
        assertTrue(priced.total.fullyPriced)
        val partialProvider = provider.copy(models = listOf(model.copy(pricing = ModelPricing(cachedInput = 1.0))))
        val partial = service.report(UsageRange.D7, listOf(partialProvider))
        assertEquals(40L, partial.total.pricedTokens)
        assertFalse(partial.models.single().priced)
        val freeProvider = provider.copy(models = listOf(model.copy(pricing = ModelPricing(input = 0.0, output = 0.0))))
        val free = service.report(UsageRange.D7, listOf(freeProvider))
        assertTrue(free.models.single().priced)
        assertEquals(0.0, free.total.cost, 0.0)
        assertEquals(120L, free.total.pricedTokens)
    }

    @Test fun `day boundaries are local calendar dates including DST`() = runBlocking {
        val dao = UsageTestDAO()
        val zone = ZoneId.of("America/Chicago")
        UsageStatsService(dao, clock = { now }, zone = { zone }).report(UsageRange.D7, emptyList())
        assertEquals(Instant.parse("2026-10-28T05:00:00Z").toEpochMilli(), dao.since)
    }

    private fun snapshot(account: String, minute: Int, remaining: Double, window: String = "primary",
                         reset: Long = now + 60_000) =
        QuotaSnapshotEntity(createdAt = now - (10 - minute) * 60_000, accountKey = account,
            window = window, remaining = remaining, resetAt = reset)

    @Test fun `account capacities and remaining are estimated independently then summed`() = runBlocking {
        val dao = UsageTestDAO().apply {
            snapshots += listOf(snapshot("codex:a", 0, 1.0), snapshot("codex:a", 5, .9),
                snapshot("codex:b", 0, .7), snapshot("codex:b", 5, .6))
            accountRows = { account, _, _ -> listOf(row(prompt = if (account.endsWith("a")) 100 else 200, output = 0)) }
        }
        val p = ProviderSetting.Codex()
        val quota = UsageStatsService(dao, clock = { now }).report(UsageRange.D7, listOf(provider, p))
            .providers.first { it.kind == UsageProviderKind.CODEX }.quotas.single()
        assertTrue(quota.enoughData)
        assertEquals(3000.0, quota.tokens!!.perFull, 1e-8)
        assertEquals(2100.0, quota.tokens.remaining!!, 1e-8)
        assertTrue(quota.boundaryUncertain)
        assertNull(quota.likelyUnit)
    }

    @Test fun `incomplete intervals and tiny drops cannot produce a confident estimate`() = runBlocking {
        val dao = UsageTestDAO().apply {
            snapshots += listOf(snapshot("codex:a", 0, 1.0), snapshot("codex:a", 5, .9))
            accountRows = { _, _, _ -> listOf(row(incomplete = 1)) }
        }
        suspend fun quota() = UsageStatsService(dao, clock = { now })
            .report(UsageRange.ALL, listOf(ProviderSetting.Codex())).providers.single().quotas.single()
        assertFalse(quota().enoughData)
        assertNull(quota().tokens)
        dao.accountRows = { _, _, _ -> listOf(row()) }
        dao.snapshots[1] = snapshot("codex:a", 5, .97)
        assertFalse(quota().enoughData)
    }

    @Test fun `explicit total zero cannot make missing output price fully known`() = runBlocking {
        val dao = UsageTestDAO().apply {
            snapshots += listOf(snapshot("codex:a", 0, 1.0), snapshot("codex:a", 5, .9))
            accountRows = { _, _, _ -> listOf(row(total = 0)) }
        }
        val partial = provider.copy(models = listOf(model.copy(pricing = ModelPricing(input = 2.0))))
        val quota = UsageStatsService(dao, clock = { now })
            .report(UsageRange.ALL, listOf(partial, ProviderSetting.Codex())).providers.single().quotas.single()
        assertEquals(1200.0, quota.tokens!!.perFull, 1e-8)
        assertNull(quota.cost)
    }

    @Test fun `expired readings suppress remaining and disabled accounts disappear`() = runBlocking {
        val dao = UsageTestDAO().apply {
            snapshots += listOf(snapshot("codex:a", 0, 1.0, reset = now - 1),
                snapshot("codex:a", 5, .9, reset = now - 1))
            accountRows = { _, _, _ -> listOf(row()) }
        }
        val p = listOf(ProviderSetting.Codex())
        val stale = UsageStatsService(dao, clock = { now }).report(UsageRange.ALL, p).providers.single().quotas.single()
        assertTrue(stale.stale)
        assertNull(stale.tokens!!.remaining)
        assertTrue(UsageStatsService(dao, clock = { now }, currentSnapshots = { emptyList() })
            .report(UsageRange.ALL, p).providers.isEmpty())
    }

    @Test fun `Antigravity pool grouping requires multiple matched declines and reset times`() = runBlocking {
        val dao = UsageTestDAO().apply {
            for (window in listOf("model:a", "model:b")) {
                snapshots += listOf(snapshot("antigravity:a", 0, 1.0, window),
                    snapshot("antigravity:a", 2, .9, window))
            }
        }
        suspend fun quotas() = UsageStatsService(dao, clock = { now })
            .report(UsageRange.ALL, listOf(ProviderSetting.Antigravity())).providers.single().quotas
        assertEquals(2, quotas().size)
        for (window in listOf("model:a", "model:b")) dao.snapshots += snapshot("antigravity:a", 5, .8, window)
        assertEquals(listOf("a", "b"), quotas().single().models)
        dao.snapshots[dao.snapshots.lastIndex] = dao.snapshots.last().copy(resetAt = now + 600_000)
        assertEquals(2, quotas().size)
    }

    @Test fun `resets and rises split quota periods`() {
        val points = listOf(snapshot("codex:a", 0, 1.0), snapshot("codex:a", 1, .8),
            snapshot("codex:a", 2, .9), snapshot("codex:a", 3, .7, reset = now + 3_600_000))
        assertEquals(listOf(2, 1, 1), UsageStatsService.splitPeriods(points).map { it.size })
    }
}
