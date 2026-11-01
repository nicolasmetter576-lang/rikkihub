package me.rerere.ai.provider.providers.antigravity

import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Antigravity 客户端版本（CPA `misc/antigravity_version.go`）：从 auto-updater manifest 取最新版本，
 * 6 小时缓存；取不到用 [FALLBACK]（Cloud Code 对低于 2.9.0 的客户端拒绝新模型）。
 * 请求不等它：先用缓存/兜底值，后台刷新。
 */
class AntigravityVersion(
    private val manifestUrl: String = MANIFEST_URL,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var cached: String? = null

    @Volatile
    private var expiresAt: Long = 0

    @Volatile
    private var refreshing = false

    fun current(): String = cached?.takeIf { nowMillis() < expiresAt } ?: FALLBACK

    fun isStale(): Boolean = cached == null || nowMillis() >= expiresAt

    /** 拉一次 manifest；失败保留旧值（过期后 [current] 回到兜底值）。 */
    suspend fun refresh(client: OkHttpClient) {
        if (refreshing) return
        refreshing = true
        try {
            val response = client.newCall(
                Request.Builder()
                    .url(manifestUrl)
                    .header("User-Agent", "electron-builder")
                    .header("Cache-Control", "no-cache")
                    .get()
                    .build()
            ).await()
            response.use {
                if (!it.isSuccessful) return
                val version = parseManifestVersion(it.body.string().take(4096)) ?: return
                cached = version
                expiresAt = nowMillis() + CACHE_TTL_MS
            }
        } catch (_: Exception) {
            // 网络失败不影响请求，沿用缓存或兜底版本
        } finally {
            refreshing = false
        }
    }

    companion object {
        const val FALLBACK = "2.9.1"
        const val PLATFORM = "darwin/arm64"
        const val MANIFEST_URL =
            "https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/manifest/latest-arm64-mac.yml"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

        /** manifest 是 YAML，只取顶层 `version:`，要求 x.y.z 全数字。 */
        fun parseManifestVersion(yaml: String): String? {
            val line = yaml.lineSequence().firstOrNull { it.startsWith("version:") } ?: return null
            val value = line.removePrefix("version:").trim().trim('"', '\'')
            val parts = value.split('.')
            if (parts.size != 3 || parts.any { part -> part.isEmpty() || !part.all(Char::isDigit) }) return null
            return value
        }

        fun userAgent(version: String): String = "antigravity/hub/$version $PLATFORM"
    }
}
