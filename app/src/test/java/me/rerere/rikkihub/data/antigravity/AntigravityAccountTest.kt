package me.rerere.rikkihub.data.antigravity

import me.rerere.ai.provider.providers.antigravity.AntigravityModelCatalog
import me.rerere.ai.provider.providers.antigravity.AntigravityRemoteModel
import me.rerere.ai.util.HttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityAccountTest {
    private val now = 1_000_000L

    private fun account(
        id: String,
        enabled: Boolean = true,
        status: AntigravityTokenStatus = AntigravityTokenStatus.AVAILABLE,
        cooldownUntil: Long = 0,
        quotas: List<AntigravityModelQuota> = emptyList(),
    ) = AntigravityAccount(
        id = id,
        email = "$id@example.com",
        projectId = "p",
        accessToken = "a",
        refreshToken = "r",
        expiresAt = now + 3_600_000,
        enabled = enabled,
        status = status,
        cooldownUntil = cooldownUntil,
        quotas = quotas,
    )

    @Test
    fun `availability honours enable, invalid, cooldown and per-model quota`() {
        assertTrue(account("a").isAvailable(nowMillis = now))
        assertFalse(account("a", enabled = false).isAvailable(nowMillis = now))
        assertFalse(account("a", status = AntigravityTokenStatus.INVALID).isAvailable(nowMillis = now))
        // 过期的 token 会在取号时刷新，不算不可用
        assertTrue(account("a", status = AntigravityTokenStatus.EXPIRED).isAvailable(nowMillis = now))
        assertFalse(account("a", cooldownUntil = now + 1).isAvailable(nowMillis = now))
        assertTrue(account("a", cooldownUntil = now - 1).isAvailable(nowMillis = now))

        val exhausted = account("a", quotas = listOf(AntigravityModelQuota("m", remainingFraction = 0.0, resetAt = now + 10)))
        assertFalse(exhausted.isAvailable("m", now))
        assertTrue(exhausted.isAvailable("other", now))
        assertTrue(exhausted.isAvailable(null, now))
        val reset = account("a", quotas = listOf(AntigravityModelQuota("m", remainingFraction = 0.0, resetAt = now - 10)))
        assertTrue(reset.isAvailable("m", now))
        val unknownReset = account("a", quotas = listOf(AntigravityModelQuota("m", remainingFraction = 0.0)))
        assertFalse(unknownReset.isAvailable("m", now))
    }

    @Test
    fun `selection rotates from the start index and skips unavailable accounts`() {
        val accounts = listOf(account("a"), account("b", cooldownUntil = now + 5), account("c"))
        assertEquals(0, selectAntigravityAccountIndex(accounts, 0, nowMillis = now))
        assertEquals(2, selectAntigravityAccountIndex(accounts, 1, nowMillis = now))
        assertEquals(0, selectAntigravityAccountIndex(accounts, 3, nowMillis = now))
        assertNull(selectAntigravityAccountIndex(listOf(account("x", enabled = false)), 0, nowMillis = now))
        assertNull(selectAntigravityAccountIndex(emptyList(), 0, nowMillis = now))
    }

    @Test
    fun `refresh rejection marks the account invalid only for auth failures`() {
        assertTrue(isAntigravityRefreshRejected(HttpException("""failed: 400 {"error":"invalid_grant"}""", 400)))
        assertTrue(isAntigravityRefreshRejected(HttpException("unauthorized", 401)))
        assertFalse(isAntigravityRefreshRejected(HttpException("""failed: 400 {"error":"invalid_request"}""", 400)))
        assertFalse(isAntigravityRefreshRejected(HttpException("server", 503)))
        assertFalse(isAntigravityRefreshRejected(HttpException("network")))
    }

    @Test
    fun `quotas come from the model catalog when the server reports them`() {
        val updated = account("a").withQuotas(
            AntigravityModelCatalog(
                listOf(
                    AntigravityRemoteModel("m1", "Model 1", remainingFraction = 0.4, resetTime = "2026-10-14T00:00:00Z"),
                    AntigravityRemoteModel("m2", "Model 2"),
                    AntigravityRemoteModel("m3", "Model 3", remainingFraction = 1.7, resetTime = "garbage"),
                )
            ),
            nowMillis = now,
        )
        assertEquals(listOf("m1", "m3"), updated.quotas.map { it.modelId })
        assertEquals(1_791_936_000_000L, updated.quotas[0].resetAt)
        assertEquals(1.0, updated.quotas[1].remainingFraction, 0.0)
        assertEquals(0L, updated.quotas[1].resetAt)
        assertEquals(now, updated.quotaUpdatedAt)
    }

    @Test
    fun `manual callback input accepts urls, query strings and bare codes`() {
        val url = parseAntigravityCallbackInput("http://localhost:51121/oauth-callback?state=s1&code=4%2F0Ab&scope=x")!!
        assertEquals("4/0Ab", url.code)
        assertEquals("s1", url.state)
        assertNull(url.error)

        val query = parseAntigravityCallbackInput("code=abc&state=s2")!!
        assertEquals("abc", query.code)
        assertEquals("s2", query.state)

        val bare = parseAntigravityCallbackInput("  4/0AbCode  ")!!
        assertEquals("4/0AbCode", bare.code)
        assertNull(bare.state)

        val denied = parseAntigravityCallbackInput("http://localhost:51121/oauth-callback?error=access_denied&state=s")!!
        assertEquals("access_denied", denied.error)

        assertNull(parseAntigravityCallbackInput(""))
        assertNull(parseAntigravityCallbackInput("http://localhost:51121/oauth-callback?state=s"))
    }
}
