package me.rerere.rikkihub.web.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.ai.ProviderSpendLimit
import me.rerere.rikkihub.data.ai.QuotaTracker
import me.rerere.rikkihub.data.ai.UsageRange
import me.rerere.rikkihub.data.ai.UsageRecorder
import me.rerere.rikkihub.data.ai.UsageSource
import me.rerere.rikkihub.data.ai.UsageStatsReport
import me.rerere.rikkihub.data.ai.UsageStatsService
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.ConversationDAO
import me.rerere.rikkihub.data.db.dao.MessageNodeDAO
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 网页端统计页与网页端发起的润色 / 推敲 / 再造的用量记录要用到的依赖 */
class WebUsageStats(
    val recorder: UsageRecorder,
    val service: UsageStatsService,
    val quotaTracker: QuotaTracker,
    val usageDao: UsageRecordDAO,
    val conversationDao: ConversationDAO,
    val messageNodeDao: MessageNodeDAO,
)

@Serializable
data class WebStatsOverview(
    val conversations: Int,
    val messages: Int,
    val launchCount: Int,
    /** 每天（本地日期 yyyy-MM-dd）的对话请求数，热力图用，最近 53 周 */
    val requestsPerDay: Map<String, Int>,
)

@Serializable
data class WebStatsResponse(
    val report: UsageStatsReport,
    val overview: WebStatsOverview,
)

@Serializable
data class WebProviderLimitResponse(
    /** 没有设置上限时为 null */
    val limit: ProviderSpendLimit? = null,
    /** 从 [ProviderSpendLimit.since] 起已花的美元（按模型当前价格估算） */
    val spent: Double = 0.0,
)

@Serializable
data class WebProviderLimitRequest(
    /** 新的上限（美元）；与 [lift] 二选一 */
    val limit: Double? = null,
    /** true：解除已触发的限制并从现在重新累计 */
    val lift: Boolean = false,
)

private const val MAX_LIMIT_USD = 1_000_000.0

/**
 * 统计页 API（与 App 统计页同一份数据）：
 *   GET /api/stats?range=7d|30d|90d|all  → [WebStatsResponse]
 *   DELETE /api/stats/models/{key}      删掉一行模型的全部用量记录（key 是报告里的 `models[].key`）
 *   DELETE /api/stats/providers/{id}    删掉一个提供商的全部用量记录（id 为 `unknown` = 归属不明的一组）
 *   GET|PUT|DELETE /api/stats/provider-limits/{providerId}  提供商消费上限（PUT `{limit}` 设置、`{lift:true}` 解除限制）
 * 花费一律是美元。
 * 打开统计页时顺带刷新订阅类提供商的额度（离上次查询不满 3 分钟的账号跳过）。
 */
fun Route.statsRoutes(settingsStore: SettingsStore, stats: WebUsageStats) {
    route("/stats") {
        get {
            val range = UsageRange.parse(call.request.queryParameters["range"])
            if (call.request.queryParameters["refreshQuota"] != "false") {
                stats.quotaTracker.requestRefresh()
            }
            val settings = settingsStore.settingsFlow.value
            val report = stats.service.report(range, settings.providers)
            call.respond(WebStatsResponse(report = report, overview = overview(settingsStore, stats)))
        }
        route("/provider-limits/{id}") {
            get { call.respond(providerLimit(settingsStore, stats, call.parameters["id"].toUuid("providerId"))) }
            put {
                val id = call.parameters["id"].toUuid("providerId")
                val request = call.receive<WebProviderLimitRequest>()
                val newLimit = request.limit
                if (!request.lift && (newLimit == null || !newLimit.isFinite() || newLimit <= 0 || newLimit > MAX_LIMIT_USD)) {
                    throw BadRequestException("limit must be between 0 and $MAX_LIMIT_USD")
                }
                settingsStore.update { settings ->
                    if (settings.providers.none { it.id == id }) throw NotFoundException("Provider not found")
                    val existing = settings.providerSpendLimits[id]
                    val next = if (request.lift) {
                        existing?.copy(blocked = false, since = System.currentTimeMillis())
                            ?: throw BadRequestException("No limit is set for this provider")
                    } else {
                        ProviderSpendLimit(limit = newLimit!!, since = existing?.since ?: System.currentTimeMillis())
                    }
                    settings.copy(providerSpendLimits = settings.providerSpendLimits + (id to next))
                }
                call.respond(providerLimit(settingsStore, stats, id))
            }
            delete {
                val id = call.parameters["id"].toUuid("providerId")
                settingsStore.update { it.copy(providerSpendLimits = it.providerSpendLimits - id) }
                call.respond(providerLimit(settingsStore, stats, id))
            }
        }
        delete("/models/{key}") {
            stats.service.deleteModelUsage(call.parameters["key"].orEmpty())
            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }
        delete("/providers/{id}") {
            val id = call.parameters["id"].orEmpty().takeUnless { it == "unknown" }
            stats.service.deleteProviderUsage(settingsStore.settingsFlow.value.providers, id)
            call.respond(io.ktor.http.HttpStatusCode.NoContent)
        }
    }
}

private suspend fun providerLimit(settingsStore: SettingsStore, stats: WebUsageStats, id: kotlin.uuid.Uuid): WebProviderLimitResponse {
    val settings = settingsStore.settingsFlow.value
    val limit = settings.providerSpendLimits[id]
        ?: return WebProviderLimitResponse()
    return WebProviderLimitResponse(limit, stats.service.providerCost(settings.providers, id.toString(), limit.since))
}

private suspend fun overview(settingsStore: SettingsStore, stats: WebUsageStats): WebStatsOverview {
    val start = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(52)
    val since = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return WebStatsOverview(
        conversations = stats.conversationDao.countAll(),
        messages = stats.messageNodeDao.countAll(),
        launchCount = settingsStore.launchCountFlow.first(),
        requestsPerDay = stats.usageDao.getRequestCountPerDay(UsageSource.CHAT.name, since).associate { it.day to it.count },
    )
}
