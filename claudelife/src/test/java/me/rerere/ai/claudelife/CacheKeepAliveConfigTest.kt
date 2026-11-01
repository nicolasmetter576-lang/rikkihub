package me.rerere.ai.claudelife

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class CacheKeepAliveConfigTest {

    // ---------- validate() ----------

    @Test
    fun `default config is valid`() {
        assertNull(CacheKeepAliveConfig(enabled = true).validate())
        assertNull(CacheKeepAliveConfig().validate())
    }

    @Test
    fun `non positive interval is invalid`() {
        assertEquals(
            CacheKeepAliveConfigError.INTERVAL_NOT_POSITIVE,
            CacheKeepAliveConfig(intervalSeconds = 0).validate()
        )
        assertEquals(
            CacheKeepAliveConfigError.INTERVAL_NOT_POSITIVE,
            CacheKeepAliveConfig(intervalSeconds = -5).validate()
        )
    }

    @Test
    fun `non positive max duration is invalid`() {
        assertEquals(
            CacheKeepAliveConfigError.MAX_DURATION_NOT_POSITIVE,
            CacheKeepAliveConfig(maxDurationMinutes = 0).validate()
        )
    }

    @Test
    fun `max duration not greater than interval is invalid`() {
        // 30 min = 1800 s, interval 1800 s → 上限不大于间隔
        assertEquals(
            CacheKeepAliveConfigError.MAX_DURATION_NOT_GREATER_THAN_INTERVAL,
            CacheKeepAliveConfig(intervalSeconds = 1800, maxDurationMinutes = 30).validate()
        )
        assertEquals(
            CacheKeepAliveConfigError.MAX_DURATION_NOT_GREATER_THAN_INTERVAL,
            CacheKeepAliveConfig(intervalSeconds = 270, maxDurationMinutes = 4).validate()
        )
    }

    @Test
    fun `boundary and large values are valid`() {
        // 1799s < 1800s → 合法
        assertNull(CacheKeepAliveConfig(intervalSeconds = 1799, maxDurationMinutes = 30).validate())
        // 无窄上限
        assertNull(CacheKeepAliveConfig(intervalSeconds = 86_400, maxDurationMinutes = 100_000).validate())
    }

    // ---------- parseCacheKeepAlivePositiveInt ----------

    @Test
    fun `parse positive int accepts plain and padded digits`() {
        assertEquals(270, parseCacheKeepAlivePositiveInt("270"))
        assertEquals(30, parseCacheKeepAlivePositiveInt(" 30 "))
        assertEquals(1, parseCacheKeepAlivePositiveInt("1"))
    }

    @Test
    fun `parse positive int rejects zero negatives decimals text empty and overflow`() {
        assertNull(parseCacheKeepAlivePositiveInt("0"))
        assertNull(parseCacheKeepAlivePositiveInt("-1"))
        assertNull(parseCacheKeepAlivePositiveInt("1.5"))
        assertNull(parseCacheKeepAlivePositiveInt("abc"))
        assertNull(parseCacheKeepAlivePositiveInt(""))
        assertNull(parseCacheKeepAlivePositiveInt("   "))
        assertNull(parseCacheKeepAlivePositiveInt("99999999999"))
    }

    // ---------- formatCacheWarmCountdown ----------

    @Test
    fun `countdown formats with ceiling seconds and uncapped minutes`() {
        assertEquals("🔥 4:30", formatCacheWarmCountdown(270_000L))
        assertEquals("🔥 4:30", formatCacheWarmCountdown(269_001L))
        assertEquals("🔥 4:29", formatCacheWarmCountdown(269_000L))
        assertEquals("🔥 0:00", formatCacheWarmCountdown(0L))
        assertEquals("🔥 0:00", formatCacheWarmCountdown(-500L))
        assertEquals("🔥 60:00", formatCacheWarmCountdown(3_600_000L))
    }

    // ---------- forCacheWarmReplay ----------

    @Test
    fun `forCacheWarmReplay sets maxTokens to 1 and keeps all other fields`() {
        val original = TextGenerationParams(
            model = Model(modelId = "claude-test", displayName = "Claude Test"),
            temperature = 0.7f,
            topP = 0.9f,
            maxTokens = 4096,
            sessionId = "session-1",
        )
        val replay = original.forCacheWarmReplay()
        assertEquals(CACHE_WARM_REPLAY_MAX_TOKENS, replay.maxTokens)
        assertEquals(1, replay.maxTokens)
        // 除 maxTokens 外其余字段与原始参数一致
        assertEquals(original, replay.copy(maxTokens = original.maxTokens))
    }

    @Test
    fun `forCacheWarmReplay overwrites a null maxTokens`() {
        val original = TextGenerationParams(model = Model(modelId = "m"), maxTokens = null)
        assertEquals(1, original.forCacheWarmReplay().maxTokens)
    }

    // ---------- cacheWarmPrefixIntact ----------

    @Test
    fun `prefix intact when current equals baseline`() {
        val baseline = listOf(UIMessage.user("a"), UIMessage.assistant("b"), UIMessage.user("c"))
        assertTrue(cacheWarmPrefixIntact(baseline, baseline))
    }

    @Test
    fun `prefix intact when current appends messages after baseline`() {
        val baseline = listOf(UIMessage.user("a"), UIMessage.assistant("b"))
        val current = baseline + listOf(UIMessage.user("c"), UIMessage.assistant("d"))
        assertTrue(cacheWarmPrefixIntact(baseline, current))
    }

    @Test
    fun `prefix broken when middle message text changed`() {
        val m1 = UIMessage.user("a")
        val m2 = UIMessage.assistant("b")
        val m3 = UIMessage.user("c")
        val baseline = listOf(m1, m2, m3)
        // 中间消息换 parts（id 相同）→ 修改前文
        val current = listOf(m1, m2.copy(parts = listOf(UIMessagePart.Text("B"))), m3)
        assertFalse(cacheWarmPrefixIntact(baseline, current))
    }

    @Test
    fun `prefix intact when last baseline message gains extra parts`() {
        val m1 = UIMessage.user("a")
        val m2 = UIMessage.assistant("b")
        val baseline = listOf(m1, m2)
        // 最后一条只核对 id/role：工具循环向同一 assistant 消息追加 part 属于追加
        val current = listOf(m1, m2.copy(parts = m2.parts + UIMessagePart.Text("extra")))
        assertTrue(cacheWarmPrefixIntact(baseline, current))
    }

    @Test
    fun `prefix broken when a message was removed`() {
        val baseline = listOf(UIMessage.user("a"), UIMessage.assistant("b"), UIMessage.user("c"))
        val current = baseline.dropLast(1)
        assertFalse(cacheWarmPrefixIntact(baseline, current))
    }

    @Test
    fun `prefix broken when message id differs at same index`() {
        val baseline = listOf(UIMessage.user("a"), UIMessage.assistant("b"))
        // 同文本不同 id → 不是同一条消息
        val current = listOf(UIMessage.user("a"), baseline[1])
        assertFalse(cacheWarmPrefixIntact(baseline, current))
    }

    @Test
    fun `prefix intact when reasoning part differs only in timestamps`() {
        val m1 = UIMessage.user("a")
        val reasoningBase = UIMessagePart.Reasoning(
            reasoning = "think",
            createdAt = Instant.fromEpochMilliseconds(1_000L),
            finishedAt = Instant.fromEpochMilliseconds(2_000L),
        )
        val m2 = UIMessage.assistant("b").copy(parts = listOf(reasoningBase))
        val m3 = UIMessage.user("c")
        val baseline = listOf(m1, m2, m3)

        val reasoningNow = reasoningBase.copy(
            createdAt = Instant.fromEpochMilliseconds(9_000L),
            finishedAt = null,
        )
        val current = listOf(m1, m2.copy(parts = listOf(reasoningNow)), m3)
        assertTrue(cacheWarmPrefixIntact(baseline, current))
    }

    @Test
    fun `prefix broken when reasoning part text changed`() {
        val m1 = UIMessage.user("a")
        val reasoningBase = UIMessagePart.Reasoning(
            reasoning = "think",
            createdAt = Instant.fromEpochMilliseconds(1_000L),
        )
        val m2 = UIMessage.assistant("b").copy(parts = listOf(reasoningBase))
        val m3 = UIMessage.user("c")
        val baseline = listOf(m1, m2, m3)

        val current = listOf(m1, m2.copy(parts = listOf(reasoningBase.copy(reasoning = "different"))), m3)
        assertFalse(cacheWarmPrefixIntact(baseline, current))
    }
}
