package me.rerere.rikkihub.claudelife

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.ai.claudelife.CacheKeepAliveConfig
import me.rerere.ai.provider.Model
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Settings.cacheKeepAliveConfig`（claudelife）持久化兼容测试：
 * 旧备份（无该字段 / 模型上的历史 `cacheKeepAlive` 字段）必须能解码，
 * 且不得被误迁移成全局开启。
 */
class CacheKeepAliveSettingsCompatTest {

    // ---------------------------------------------------------------
    // 旧备份没有 cacheKeepAliveConfig → 解码为默认值（关闭/270s/30min）
    // ---------------------------------------------------------------
    @Test
    fun `empty settings json decodes to default keep alive config`() {
        val settings = JsonInstant.decodeFromString<Settings>("{}")
        assertEquals(CacheKeepAliveConfig(), settings.cacheKeepAliveConfig)
        assertFalse(settings.cacheKeepAliveConfig.enabled)
        assertEquals(270, settings.cacheKeepAliveConfig.intervalSeconds)
        assertEquals(30, settings.cacheKeepAliveConfig.maxDurationMinutes)
    }

    // ---------------------------------------------------------------
    // 模型上的历史 cacheKeepAlive 字段：可解码（ignoreUnknownKeys），不迁移
    // ---------------------------------------------------------------
    @Test
    fun `legacy model cacheKeepAlive field still decodes`() {
        val model = JsonInstant.decodeFromString<Model>(
            """{"modelId":"x","cacheKeepAlive":true}"""
        )
        assertEquals("x", model.modelId)
    }

    @Test
    fun `settings json without keep alive config stays disabled`() {
        val settings = JsonInstant.decodeFromString<Settings>(
            """{"webServerEnabled":true}"""
        )
        // 旧备份没有全局保活字段 → 保持默认关闭（历史模型 flag 不迁移成全局开启）
        assertEquals(CacheKeepAliveConfig(), settings.cacheKeepAliveConfig)
        assertFalse(settings.cacheKeepAliveConfig.enabled)
    }

    // ---------------------------------------------------------------
    // 编码→解码 round-trip 保真
    // ---------------------------------------------------------------
    @Test
    fun `settings keep alive config round trips`() {
        val original = Settings(
            cacheKeepAliveConfig = CacheKeepAliveConfig(
                enabled = true,
                intervalSeconds = 100,
                maxDurationMinutes = 20,
            )
        )
        val decoded = JsonInstant.decodeFromString<Settings>(
            JsonInstant.encodeToString(original)
        )
        assertEquals(original.cacheKeepAliveConfig, decoded.cacheKeepAliveConfig)
        assertTrue(decoded.cacheKeepAliveConfig.enabled)
        assertEquals(100, decoded.cacheKeepAliveConfig.intervalSeconds)
        assertEquals(20, decoded.cacheKeepAliveConfig.maxDurationMinutes)
    }
}
