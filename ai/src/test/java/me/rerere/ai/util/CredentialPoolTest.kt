package me.rerere.ai.util

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ApiKeyEntry
import me.rerere.ai.provider.CredentialRotationStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VertexServiceAccount
import me.rerere.ai.provider.effectiveApiKeyEntries
import me.rerere.ai.provider.effectiveServiceAccounts
import me.rerere.ai.provider.parseVertexServiceAccountJsons
import me.rerere.ai.provider.resolvedLocation
import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CredentialPoolTest {
    private fun candidates(vararg ids: String): List<PooledCredential<String>> =
        ids.map { id -> PooledCredential(id = id, displayLabel = "label-$id", value = "secret-$id") }

    @Test
    fun `round robin balances healthy credentials while failover sticks to first healthy`() = runBlocking {
        val pool = CredentialPool { 1_000L }
        val creds = candidates("k1", "k2", "k3")

        val rrOrder = (1..6).map {
            pool.executeWithFailover("p1", "m1", creds, CredentialRotationStrategy.ROUND_ROBIN) { it.id }
        }
        assertEquals(listOf("k1", "k2", "k3", "k1", "k2", "k3"), rrOrder)

        val foOrder = (1..4).map {
            pool.executeWithFailover("p2", "m1", creds, CredentialRotationStrategy.FAILOVER) { it.id }
        }
        assertEquals(listOf("k1", "k1", "k1", "k1"), foOrder)
    }

    @Test
    fun `rate limit cooldown is isolated per model and steps 60s 120s 300s`() = runBlocking {
        var now = 10_000L
        val pool = CredentialPool { now }
        val creds = candidates("k1", "k2")

        // k1 hits 429 on model-A -> switches seamlessly to k2
        val seenA = mutableListOf<String>()
        val resultA = pool.executeWithFailover("p1", "model-A", creds, CredentialRotationStrategy.FAILOVER) { cred ->
            seenA += cred.id
            if (cred.id == "k1") throw HttpException("429", statusCode = 429)
            "ok-A"
        }
        assertEquals("ok-A", resultA)
        assertEquals(listOf("k1", "k2"), seenA)

        // k1 on model-B must still be healthy and selected first under FAILOVER
        val selectedB = pool.executeWithFailover("p1", "model-B", creds, CredentialRotationStrategy.FAILOVER) { it.id }
        assertEquals("k1", selectedB)

        val snap1 = pool.snapshotsFor("p1", listOf("k1"))["k1"]!!
        assertEquals(CredentialHealthStatus.COOLING_DOWN, snap1.status)
        assertEquals(60_000L, snap1.remainingMillis)

        // Second 429 after expiry bumps step to 120s, third bumps to 300s, success resets
        now += 61_000L
        pool.executeWithFailover("p1", "model-A", creds, CredentialRotationStrategy.FAILOVER) { cred ->
            if (cred.id == "k1") throw HttpException("429", statusCode = 429)
            "ok"
        }
        assertEquals(120_000L, pool.snapshotsFor("p1", listOf("k1"))["k1"]!!.remainingMillis)

        now += 121_000L
        pool.executeWithFailover("p1", "model-A", creds, CredentialRotationStrategy.FAILOVER) { cred ->
            if (cred.id == "k1") throw HttpException("429", statusCode = 429)
            "ok"
        }
        assertEquals(300_000L, pool.snapshotsFor("p1", listOf("k1"))["k1"]!!.remainingMillis)

        // Retry-After is clamped to [1s, 600s]
        now += 301_000L
        pool.executeWithFailover("p1", "model-A", creds, CredentialRotationStrategy.FAILOVER) { cred ->
            if (cred.id == "k1") throw HttpException("429", statusCode = 429, retryAfterMillis = 120L)
            "ok"
        }
        assertEquals(1_000L, pool.snapshotsFor("p1", listOf("k1"))["k1"]!!.remainingMillis)

        // Explicit success resets consecutive count
        now += 2_000L
        assertEquals("k1", pool.executeWithFailover("p1", "model-A", creds, CredentialRotationStrategy.FAILOVER) { it.id })
        assertEquals(CredentialHealthStatus.HEALTHY, pool.snapshotsFor("p1", listOf("k1"))["k1"]!!.status)
    }

    @Test
    fun `auth errors isolate credential across all models`() = runBlocking {
        val now = 50_000L
        val pool = CredentialPool { now }
        val creds = candidates("k1", "k2")

        val res = pool.executeWithFailover("p1", "model-A", creds, CredentialRotationStrategy.FAILOVER) { cred ->
            if (cred.id == "k1") throw HttpException("401", statusCode = 401)
            "ok-$cred"
        }
        assertTrue(res.contains("k2"))

        // Even on model-B, k1 is skipped because AUTH_ERROR is credential-wide
        val chosenB = pool.executeWithFailover("p1", "model-B", creds, CredentialRotationStrategy.FAILOVER) { it.id }
        assertEquals("k2", chosenB)
        assertEquals(CredentialHealthStatus.AUTH_ERROR, pool.snapshotsFor("p1", listOf("k1"))["k1"]!!.status)
    }

    @Test
    fun `when all candidates cool down a single call probes only earliest expiring one once`() = runBlocking {
        var now = 100_000L
        val pool = CredentialPool { now }
        val creds = candidates("k1", "k2", "k3")

        // Cool k1 (10s), k2 (2s -> earliest), k3 (30s)
        pool.executeWithFailover("p1", "m", creds, CredentialRotationStrategy.ROUND_ROBIN) { cred ->
            when (cred.id) {
                "k1" -> throw HttpException("429", 429, 10_000L)
                "k2" -> throw HttpException("429", 429, 2_000L)
                else -> "ok-first"
            }
        }
        now += 1_000L
        try {
            pool.executeWithFailover("p1", "m", listOf(creds[2]), CredentialRotationStrategy.ROUND_ROBIN) {
                throw HttpException("429", 429, 30_000L)
            }
            fail("expected 429")
        } catch (_: HttpException) {}

        // Now all 3 are cooling down; k2 has earliest cooldownUntil (102_000).
        // New request should try k2 ONCE and immediately throw without touching k1 or k3.
        val tried = mutableListOf<String>()
        try {
            pool.executeWithFailover("p1", "m", creds, CredentialRotationStrategy.ROUND_ROBIN) { cred ->
                tried += cred.id
                throw HttpException("429", 429, 5_000L)
            }
            fail("expected 429")
        } catch (expected: HttpException) {
            assertEquals(429, expected.statusCode)
        }
        assertEquals(listOf("k2"), tried)
    }

    @Test
    fun `streamWithFailover switches before first chunk and never switches after emitting chunks`() = runBlocking {
        val pool = CredentialPool { 1_000L }
        val creds = candidates("k1", "k2")
        val events = mutableListOf<CredentialSwitchEvent>()

        // Switches before first chunk on UpstreamStreamRateLimitException
        val chunks = withContext(CredentialSwitchObserver { events += it }) {
            pool.streamWithFailover("p1", "m1", creds, CredentialRotationStrategy.FAILOVER) { cred ->
                flow {
                    if (cred.id == "k1") throw UpstreamStreamRateLimitException("429", 429)
                    emit(StreamChunk.TextDelta(id = "t1", text = "hello "))
                    emit(StreamChunk.TextDelta(id = "t2", text = "world"))
                }
            }.toList()
        }
        assertEquals(2, chunks.size)
        assertEquals(1, events.size)
        assertEquals("label-k1", events.single().fromLabel)
        assertEquals("label-k2", events.single().toLabel)
        assertEquals(429, events.single().statusCode)

        // Reset and fail AFTER emitting a chunk on k1 -> must NOT switch to k2 (would corrupt partial stream)
        pool.resetProvider("p1")
        val attempts = AtomicInteger(0)
        val emittedThenFailed = mutableListOf<StreamChunk>()
        try {
            pool.streamWithFailover("p1", "m1", creds, CredentialRotationStrategy.FAILOVER) { cred ->
                attempts.incrementAndGet()
                flow {
                    emit(StreamChunk.TextDelta(id = "t1", text = "partial"))
                    throw HttpException("mid-stream 429", statusCode = 429)
                }
            }.toList(emittedThenFailed)
            fail("expected mid-stream exception")
        } catch (expected: HttpException) {
            assertEquals(429, expected.statusCode)
        }
        assertEquals(1, attempts.get())
        assertEquals(1, emittedThenFailed.size)
    }

    @Test
    fun `service account multi json parser and legacy helpers handle single array concatenated and blank location`() {
        val obj1 = """{"project_id":"p1","client_email":"sa1@example.invalid","private_key":"k1"}"""
        val obj2 = """{"projectId":"p2","serviceAccountEmail":"sa2@example.invalid","privateKey":"k2","location":"global","label":"Edge"}"""
        val invalid = """{"project_id":"missing-key"}"""

        val single = parseVertexServiceAccountJsons(obj1)
        assertEquals(1, single.accounts.size)
        assertEquals(0, single.skippedCount)

        val arr = parseVertexServiceAccountJsons("[$obj1,$invalid,$obj2]")
        assertEquals(2, arr.accounts.size)
        assertEquals(1, arr.skippedCount)
        assertEquals("global", arr.accounts[1].resolvedLocation("us-central1"))
        assertEquals("us-central1", arr.accounts[0].resolvedLocation("us-central1"))

        val concat = parseVertexServiceAccountJsons("$obj1\n$obj2")
        assertEquals(2, concat.accounts.size)
        assertEquals("p1", concat.accounts[0].projectId)
        assertEquals("Edge", concat.accounts[1].label)

        // Legacy fallback conversions
        val legacyGoogle = ProviderSetting.Google(
            apiKey = "k-1, k-2\nk-1",
            vertexAI = true,
            useServiceAccount = true,
            projectId = "leg-p",
            serviceAccountEmail = "leg@e",
            privateKey = "leg-pk",
            location = "europe-west1",
        )
        assertEquals(listOf("k-1", "k-2"), legacyGoogle.effectiveApiKeyEntries().map { it.key })
        val saList = legacyGoogle.effectiveServiceAccounts()
        assertEquals(1, saList.size)
        assertEquals("europe-west1", saList.single().resolvedLocation(legacyGoogle.location))

        // ResetChanged resets only changed/removed credentials
        val pool = CredentialPool { 1_000L }
        val prev = ProviderSetting.OpenAI(
            apiKeys = listOf(
                ApiKeyEntry(id = "a", key = "k-a"),
                ApiKeyEntry(id = "b", key = "k-b"),
            )
        )
        runBlocking {
            pool.executeWithFailover(prev.id.toString(), "m", prev.apiKeyCandidates(), CredentialRotationStrategy.FAILOVER) {
                if (it.id == "a") throw HttpException("429", 429)
                "ok"
            }
        }
        assertNotNull(pool.snapshotsFor(prev.id.toString(), listOf("a"))["a"])
        assertEquals(
            CredentialHealthStatus.COOLING_DOWN,
            pool.snapshotsFor(prev.id.toString(), listOf("a"))["a"]!!.status,
        )
        val unchangedNext = prev.copy()
        pool.resetChanged(prev.id.toString(), prev, unchangedNext)
        assertEquals(
            CredentialHealthStatus.COOLING_DOWN,
            pool.snapshotsFor(prev.id.toString(), listOf("a"))["a"]!!.status,
        )
        val modifiedNext = prev.copy(apiKeys = listOf(ApiKeyEntry(id = "a", key = "k-a-new"), ApiKeyEntry(id = "b", key = "k-b")))
        pool.resetChanged(prev.id.toString(), prev, modifiedNext)
        assertEquals(
            CredentialHealthStatus.HEALTHY,
            pool.snapshotsFor(prev.id.toString(), listOf("a"))["a"]!!.status,
        )
        assertFalse(prev.id.toString().isEmpty())
    }
}
