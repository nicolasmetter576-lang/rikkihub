package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.claudelife.CacheKeepAliveConfig
import me.rerere.ai.claudelife.CacheKeepAliveConfigError
import me.rerere.ai.claudelife.CacheWarmStatus
import me.rerere.ai.claudelife.validate
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.web.BadRequestException

/**
 * 全局缓存保活配置（claudelife）：App 与 Web 操作 `Settings.cacheKeepAliveConfig`
 * 同一份。`PUT` 为 merge-patch：只改出现的键（enabled / intervalSeconds /
 * maxDurationMinutes），缺键保留；校验与 App 一致（正整数、上限 > 间隔、无窄上限）。
 */
fun Route.cacheKeepAliveRoutes(settingsStore: SettingsStore) {
    route("/cache-keep-alive") {
        get {
            call.respond(HttpStatusCode.OK, settingsStore.settingsFlow.value.cacheKeepAliveConfig.toDto())
        }

        put {
            val body = call.receive<JsonObject>()
            var result: CacheKeepAliveConfig? = null
            settingsStore.update { settings ->
                val merged = mergeCacheKeepAliveConfig(settings.cacheKeepAliveConfig, body)
                result = merged
                settings.copy(cacheKeepAliveConfig = merged)
            }
            call.respond(HttpStatusCode.OK, (result ?: settingsStore.settingsFlow.value.cacheKeepAliveConfig).toDto())
        }
    }
}

@Serializable
data class CacheKeepAliveConfigDto(
    val enabled: Boolean,
    val intervalSeconds: Int,
    val maxDurationMinutes: Int,
)

fun CacheKeepAliveConfig.toDto() = CacheKeepAliveConfigDto(
    enabled = enabled,
    intervalSeconds = intervalSeconds,
    maxDurationMinutes = maxDurationMinutes,
)

private val CACHE_KEEP_ALIVE_KEYS = setOf("enabled", "intervalSeconds", "maxDurationMinutes")

/**
 * merge-patch 解析（纯函数，单测直接断言）。
 * - 未知键 / 空对象 → 400；
 * - `enabled` 必须是布尔；`intervalSeconds`/`maxDurationMinutes` 必须是正整数（JSON 整数，
 *   不接受小数/字符串/null）；
 * - 合并后按 [validate] 校验（上限换算后必须大于间隔）。
 */
fun mergeCacheKeepAliveConfig(existing: CacheKeepAliveConfig, body: JsonObject): CacheKeepAliveConfig {
    val unknown = body.keys - CACHE_KEEP_ALIVE_KEYS
    if (unknown.isNotEmpty()) throw BadRequestException("unknown field: ${unknown.sorted().joinToString()}")
    if (body.isEmpty()) throw BadRequestException("no field to update")
    var merged = existing
    body["enabled"]?.let { value ->
        val enabled = (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            ?: throw BadRequestException("enabled must be a boolean")
        merged = merged.copy(enabled = enabled)
    }
    body["intervalSeconds"]?.let { value ->
        merged = merged.copy(intervalSeconds = value.positiveIntOrThrow("intervalSeconds"))
    }
    body["maxDurationMinutes"]?.let { value ->
        merged = merged.copy(maxDurationMinutes = value.positiveIntOrThrow("maxDurationMinutes"))
    }
    when (merged.validate()) {
        null -> Unit
        CacheKeepAliveConfigError.INTERVAL_NOT_POSITIVE ->
            throw BadRequestException("intervalSeconds must be a positive integer")
        CacheKeepAliveConfigError.MAX_DURATION_NOT_POSITIVE ->
            throw BadRequestException("maxDurationMinutes must be a positive integer")
        CacheKeepAliveConfigError.MAX_DURATION_NOT_GREATER_THAN_INTERVAL ->
            throw BadRequestException("maxDurationMinutes must be longer than intervalSeconds")
    }
    return merged
}

private fun JsonElement.positiveIntOrThrow(field: String): Int {
    val primitive = this as? JsonPrimitive
    if (primitive == null || primitive is JsonNull || primitive.isString) {
        throw BadRequestException("$field must be a positive integer")
    }
    val content = primitive.content
    if (!content.all(Char::isDigit)) throw BadRequestException("$field must be a positive integer")
    val value = primitive.longOrNull ?: throw BadRequestException("$field must be a positive integer")
    if (value <= 0 || value > Int.MAX_VALUE) throw BadRequestException("$field must be a positive integer")
    return value.toInt()
}

/**
 * 会话 SSE `cache_warm` 事件载荷（Web 输入框）。`nextWarmAt` 为服务端墙钟毫秒
 * （客户端按 `serverTime` 校正时钟偏差后倒计时）。
 */
@Serializable
data class CacheWarmStatusEvent(
    val type: String = "cache_warm",
    val conversationId: String,
    /** `inactive` / `scheduled` / `refreshing` */
    val state: String,
    val nextWarmAt: Long? = null,
    val reason: String? = null,
    val serverTime: Long = System.currentTimeMillis(),
)

/**
 * 单调时钟状态 → 墙钟事件（纯函数）。[monotonicNowMs] 与 [wallNowMs] 为同一时刻的两种时钟读数。
 */
fun CacheWarmStatus.toEvent(
    conversationId: String,
    monotonicNowMs: Long,
    wallNowMs: Long = System.currentTimeMillis(),
): CacheWarmStatusEvent = when (this) {
    is CacheWarmStatus.Scheduled -> CacheWarmStatusEvent(
        conversationId = conversationId,
        state = "scheduled",
        nextWarmAt = wallNowMs + (nextWarmAtMs - monotonicNowMs),
        serverTime = wallNowMs,
    )

    CacheWarmStatus.Refreshing -> CacheWarmStatusEvent(
        conversationId = conversationId,
        state = "refreshing",
        serverTime = wallNowMs,
    )

    is CacheWarmStatus.Inactive -> CacheWarmStatusEvent(
        conversationId = conversationId,
        state = "inactive",
        reason = reason,
        serverTime = wallNowMs,
    )
}
