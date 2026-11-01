package me.rerere.rikkihub.data.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class OAuthCallbackDecisionTest {
    private val now = 1_000_000_000L
    private fun sessions(vararg states: String, createdAt: Long = now) =
        ConcurrentHashMap<String, OAuthPkceSession>().apply {
            states.forEach { put(it, OAuthPkceSession(verifier = "v-$it", redirectUri = "r", createdAt = createdAt)) }
        }

    @Test
    fun `pending session with code is exchanged once`() {
        val map = sessions("good")
        val decision = map.claimCallback("good", "code-1", null, now)
        assertEquals(OAuthCallbackDecision.Exchange("code-1", OAuthPkceSession("v-good", "r", now)), decision)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `forged state is unknown and keeps the real session pending`() {
        val map = sessions("good")
        assertEquals(OAuthCallbackDecision.Unknown, map.claimCallback("forged", "x", null, now))
        assertEquals(OAuthCallbackDecision.Unknown, map.claimCallback(null, "x", null, now))
        assertEquals(setOf("good"), map.keys)
    }

    @Test
    fun `reload after success is unknown, not a failure`() {
        val map = sessions("good")
        map.claimCallback("good", "code-1", null, now)
        assertEquals(OAuthCallbackDecision.Unknown, map.claimCallback("good", "code-1", null, now))
    }

    @Test
    fun `own expired session still reports a failure`() {
        val map = sessions("old", createdAt = now - OAUTH_SESSION_TTL_MS - 1)
        val decision = map.claimCallback("old", "code", null, now)
        assertTrue(decision is OAuthCallbackDecision.Failed)
        assertTrue((decision as OAuthCallbackDecision.Failed).message.contains("expired"))
    }

    @Test
    fun `provider error and missing code fail the pending session`() {
        assertEquals(
            OAuthCallbackDecision.Failed("access_denied"),
            sessions("a").claimCallback("a", null, "access_denied", now),
        )
        assertEquals(
            OAuthCallbackDecision.Failed("Missing authorization code"),
            sessions("a").claimCallback("a", "", null, now),
        )
    }

    @Test
    fun `claiming also drops other expired sessions`() {
        val map = sessions("fresh")
        map["stale"] = OAuthPkceSession("v", "r", createdAt = now - OAUTH_SESSION_TTL_MS - 1)
        map.claimCallback("forged", null, null, now)
        assertEquals(setOf("fresh"), map.keys)
    }

    @Test
    fun `unknown page has no deep link back into the app`() {
        val page = oauthUnknownCallbackPage("T")
        assertTrue("rikkihub://" !in page)
    }
}
