package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.claudelife.CacheKeepAliveConfig
import me.rerere.ai.claudelife.CacheWarmStatus
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [mergeCacheKeepAliveConfig] merge-patch 校验与 [CacheWarmStatus.toEvent]
 * 单调时钟→墙钟换算的纯函数测试。
 */
class CacheKeepAliveRoutesTest {

    private val existing = CacheKeepAliveConfig(
        enabled = false,
        intervalSeconds = 270,
        maxDurationMinutes = 30,
    )

    private fun assertBadRequest(body: JsonObject) {
        try {
            mergeCacheKeepAliveConfig(existing, body)
            fail("expected BadRequestException for body $body")
        } catch (expected: BadRequestException) {
            // ok
        }
    }

    // ---------------------------------------------------------------
    // merge-patch 正常合并
    // ---------------------------------------------------------------
    @Test
    fun `merge enabled true keeps other fields`() {
        val merged = mergeCacheKeepAliveConfig(
            existing,
            buildJsonObject { put("enabled", true) },
        )
        assertTrue(merged.enabled)
        assertEquals(270, merged.intervalSeconds)
        assertEquals(30, merged.maxDurationMinutes)
    }

    @Test
    fun `merge interval and max duration updates both`() {
        val merged = mergeCacheKeepAliveConfig(
            existing,
            buildJsonObject {
                put("intervalSeconds", 120)
                put("maxDurationMinutes", 10)
            },
        )
        assertFalse(merged.enabled)
        assertEquals(120, merged.intervalSeconds)
        assertEquals(10, merged.maxDurationMinutes)
    }

    @Test
    fun `merge large valid values accepted without narrow upper limit`() {
        val merged = mergeCacheKeepAliveConfig(
            existing,
            buildJsonObject {
                put("intervalSeconds", 86400)
                put("maxDurationMinutes", 100000)
            },
        )
        assertEquals(86400, merged.intervalSeconds)
        assertEquals(100000, merged.maxDurationMinutes)
    }

    // ---------------------------------------------------------------
    // 非法 body 一律 400
    // ---------------------------------------------------------------
    @Test
    fun `empty body rejected`() {
        assertBadRequest(JsonObject(emptyMap()))
    }

    @Test
    fun `unknown field rejected`() {
        assertBadRequest(buildJsonObject { put("foo", 1) })
    }

    @Test
    fun `enabled as string rejected`() {
        assertBadRequest(buildJsonObject { put("enabled", "true") })
    }

    @Test
    fun `intervalSeconds non positive rejected`() {
        assertBadRequest(buildJsonObject { put("intervalSeconds", 0) })
        assertBadRequest(buildJsonObject { put("intervalSeconds", -5) })
    }

    @Test
    fun `intervalSeconds non integer or wrong type rejected`() {
        assertBadRequest(buildJsonObject { put("intervalSeconds", 1.5) })
        assertBadRequest(buildJsonObject { put("intervalSeconds", "270") })
        assertBadRequest(buildJsonObject { put("intervalSeconds", JsonNull) })
    }

    @Test
    fun `max duration not greater than interval rejected`() {
        // 4 分钟 = 240s <= 270s 间隔
        assertBadRequest(buildJsonObject { put("maxDurationMinutes", 4) })
    }

    @Test
    fun `intervalSeconds overflow rejected`() {
        assertBadRequest(buildJsonObject { put("intervalSeconds", 99999999999L) })
    }

    // ---------------------------------------------------------------
    // toEvent：单调时钟换算墙钟
    // ---------------------------------------------------------------
    @Test
    fun `scheduled status converts monotonic next warm to wall clock`() {
        val event = CacheWarmStatus.Scheduled(nextWarmAtMs = 5_000L).toEvent(
            conversationId = "conv-1",
            monotonicNowMs = 1_000L,
            wallNowMs = 1_000_000L,
        )
        assertEquals("cache_warm", event.type)
        assertEquals("conv-1", event.conversationId)
        assertEquals("scheduled", event.state)
        // nextWarmAt = wallNow + (nextWarmAtMs - monotonicNow) = 1_000_000 + 4_000
        assertEquals(1_004_000L, event.nextWarmAt)
        assertEquals(1_000_000L, event.serverTime)
        assertNull(event.reason)
    }

    @Test
    fun `refreshing status has no next warm`() {
        val event = CacheWarmStatus.Refreshing.toEvent(
            conversationId = "conv-1",
            monotonicNowMs = 1_000L,
            wallNowMs = 1_000_000L,
        )
        assertEquals("refreshing", event.state)
        assertNull(event.nextWarmAt)
        assertNull(event.reason)
        assertEquals(1_000_000L, event.serverTime)
    }

    @Test
    fun `inactive status carries reason`() {
        val event = CacheWarmStatus.Inactive("disabled").toEvent(
            conversationId = "conv-1",
            monotonicNowMs = 1_000L,
            wallNowMs = 1_000_000L,
        )
        assertEquals("inactive", event.state)
        assertEquals("disabled", event.reason)
        assertNull(event.nextWarmAt)
        assertEquals(1_000_000L, event.serverTime)
    }
}
