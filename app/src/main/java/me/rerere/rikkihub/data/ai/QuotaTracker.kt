package me.rerere.rikkihub.data.ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.antigravity.AntigravityAccount
import me.rerere.rikkihub.data.antigravity.AntigravityAccountRepository
import me.rerere.rikkihub.data.codex.CodexAccount
import me.rerere.rikkihub.data.codex.CodexAccountRepository
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import me.rerere.rikkihub.data.db.entity.QuotaSnapshotEntity
import me.rerere.rikkihub.data.devin.DevinAccount
import me.rerere.rikkihub.data.devin.DevinAccountRepository
import me.rerere.rikkihub.data.devin.DevinAccountStatus
import me.rerere.rikkihub.data.antigravity.AntigravityTokenStatus
import me.rerere.rikkihub.data.codex.CodexTokenStatus

private const val TAG = "QuotaTracker"

/** 一次额度读数（还没写库） */
internal data class QuotaReading(
    val accountKey: String,
    val window: String,
    val remaining: Double,
    val resetAt: Long?,
    val observedAt: Long = 0,
)

internal fun DevinAccount.quotaReadings(): List<QuotaReading> {
    // 没刷新过的账号是默认的 100% / 重置时间 0，不是真实读数
    val key = "devin:$id"
    return buildList {
        if (dailyQuotaResetAt > 0 && quotaUpdatedAt > 0) {
            add(QuotaReading(key, "daily", dailyQuotaRemainingPercent.coerceIn(0, 100) / 100.0, dailyQuotaResetAt * 1000, quotaUpdatedAt))
        }
        if (weeklyQuotaResetAt > 0 && quotaUpdatedAt > 0) {
            add(QuotaReading(key, "weekly", weeklyQuotaRemainingPercent.coerceIn(0, 100) / 100.0, weeklyQuotaResetAt * 1000, quotaUpdatedAt))
        }
    }
}

internal fun AntigravityAccount.quotaReadings(): List<QuotaReading> =
    if (quotaUpdatedAt <= 0) {
        emptyList()
    } else {
        quotas.map { quota ->
            QuotaReading(id, "model:${quota.modelId}", quota.remainingFraction.coerceIn(0.0, 1.0), quota.resetAt.takeIf { it > 0 }, quotaUpdatedAt)
        }
    }

internal fun CodexAccount.quotaReadings(): List<QuotaReading> {
    val usage = usage ?: return emptyList()
    val key = "codex:$id"
    return listOfNotNull(
        usage.primary?.let { QuotaReading(key, "primary", (1.0 - it.usedPercent / 100.0).coerceIn(0.0, 1.0), it.resetsAt?.times(1000), usage.updatedAt) },
        usage.secondary?.let { QuotaReading(key, "secondary", (1.0 - it.usedPercent / 100.0).coerceIn(0.0, 1.0), it.resetsAt?.times(1000), usage.updatedAt) },
    )
}

/**
 * 记录订阅类提供商（Devin / Antigravity / Codex）的额度读数，并在用过之后自动刷新额度。
 *
 * - 读数来自各账号仓库的状态流：每次刷新到新的额度，只有剩余比例或重置时间变了才记一行
 *   （记下的是「变化点」，反推额度时用它们，整数百分比也不会多算或少算一格）。
 * - Devin 和 Antigravity 的额度要主动查：某账号有新用量后，离上次查询满 [refreshIntervalMs] 就在后台查一次；
 *   不满的话，到点再补查一次，让最后一段用量也有读数。Codex 每个响应头都带额度，不用另外查。
 */
class QuotaTracker(
    private val dao: UsageRecordDAO,
    private val scope: CoroutineScope,
    private val devin: DevinAccountRepository,
    private val antigravity: AntigravityAccountRepository,
    private val codex: CodexAccountRepository,
    private val refreshIntervalMs: Long = 3 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val writeLock = Mutex()
    private val refreshGate = QuotaRefreshGate(refreshIntervalMs, clock)
    private val pendingRefresh = mutableMapOf<String, Job>()
    private val refreshLock = Any()
    private val usageVersions = mutableMapOf<String, Long>()

    /** 当前有效账号的真实读数；不把凭据文件里的旧读数伪装成刚刚采样。 */
    fun currentSnapshots(): List<QuotaSnapshotEntity> = buildList {
        addAll(devin.accounts.value.filter { it.enabled && it.status != DevinAccountStatus.INVALID }.flatMap { it.quotaReadings() })
        addAll(antigravity.accounts.value.filter { it.enabled && it.status != AntigravityTokenStatus.INVALID }.flatMap { it.quotaReadings() })
        addAll(codex.accounts.value.filter { it.enabled && it.tokenStatus != CodexTokenStatus.INVALID }.flatMap { it.quotaReadings() })
    }.map { QuotaSnapshotEntity(createdAt = it.observedAt, accountKey = it.accountKey,
        window = it.window, remaining = it.remaining, resetAt = it.resetAt) }

    internal fun onRequestQuota(readings: List<QuotaReading>) {
        scope.launch(Dispatchers.IO) {
            runCatching { record(readings) }.onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "Failed to record request quota", it)
            }
        }
    }

    fun requestRefresh() {
        scope.launch(Dispatchers.IO) { refreshAllIfStale() }
    }

    fun start() {
        observe(devin.accounts) { it.flatMap(DevinAccount::quotaReadings) }
        observe(antigravity.accounts) { it.flatMap(AntigravityAccount::quotaReadings) }
        // Codex 的响应头按请求捕获，等该请求的用量写入后再记录，避免 StateFlow 合并并发读数。
    }

    private fun <T> observe(flow: StateFlow<List<T>>, readings: (List<T>) -> List<QuotaReading>) {
        scope.launch(Dispatchers.IO) {
            flow.collect { accounts ->
                runCatching { record(readings(accounts)) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "Failed to record quota", it)
                    }
            }
        }
    }

    internal suspend fun record(readings: List<QuotaReading>) = writeLock.withLock {
        // 同一次刷新的读数用同一个时间，Antigravity 共用额度的模型据此能认出是一组
        readings.forEach { reading ->
            if (!reading.remaining.isFinite() || reading.observedAt <= 0) return@forEach
            val latest = dao.getLatestSnapshot(reading.accountKey, reading.window)
            if (latest != null && reading.observedAt < latest.createdAt) return@forEach
            if (latest != null && sameReading(latest, reading)) return@forEach
            dao.insertSnapshot(
                QuotaSnapshotEntity(
                    createdAt = reading.observedAt,
                    accountKey = reading.accountKey,
                    window = reading.window,
                    remaining = reading.remaining,
                    resetAt = reading.resetAt,
                )
            )
        }
    }

    private fun sameReading(latest: QuotaSnapshotEntity, reading: QuotaReading): Boolean {
        if (kotlin.math.abs(latest.remaining - reading.remaining) > 1e-9) return false
        // Codex 的重置时间由「剩余秒数 + 当前时间」算出，每次差几秒；差 10 分钟以内算同一个
        val a = latest.resetAt
        val b = reading.resetAt
        return if (a == null || b == null) a == b else kotlin.math.abs(a - b) < RESET_TOLERANCE_MS
    }

    /** 记了一条带账号的用量后调用 */
    fun onAccountUsage(accountKey: String) {
        if (!accountKey.startsWith("devin:") && !accountKey.startsWith("antigravity:")) return
        synchronized(refreshLock) {
            usageVersions[accountKey] = (usageVersions[accountKey] ?: 0) + 1
            if (pendingRefresh[accountKey]?.isActive == true) return
            // 至少等几秒：额度在服务端结算需要一点时间
            pendingRefresh[accountKey] = scope.launch(Dispatchers.IO) {
                try {
                    while (isRefreshable(accountKey)) {
                        val version = synchronized(refreshLock) { usageVersions[accountKey] }
                        val last = refreshGate.lastAt(accountKey)
                        delay((last + refreshIntervalMs - clock()).coerceAtLeast(SETTLE_DELAY_MS))
                        if (!refreshNow(accountKey)) continue
                        val finished = synchronized(refreshLock) {
                            if (usageVersions[accountKey] != version) false else {
                                // Remove while holding the same lock as onAccountUsage, not after Job completion.
                                pendingRefresh.remove(accountKey)
                                true
                            }
                        }
                        if (finished) break
                    }
                } finally {
                    val job = currentCoroutineContext().job
                    synchronized(refreshLock) {
                        if (pendingRefresh[accountKey] == job) pendingRefresh.remove(accountKey)
                    }
                }
            }
        }
    }

    /** 统计页打开时调用：各账号离上次查询满间隔的就查一次 */
    suspend fun refreshAllIfStale() {
        val keys = devin.accounts.value.filter { it.enabled && it.status != DevinAccountStatus.INVALID }.map { "devin:${it.id}" } +
            antigravity.accounts.value.filter { it.enabled && it.status != AntigravityTokenStatus.INVALID }.map { it.id }
        keys.forEach { key ->
            val last = refreshGate.lastAt(key)
            if (clock() - last >= refreshIntervalMs) refreshNow(key)
        }
    }

    private suspend fun refreshNow(accountKey: String): Boolean {
        if (!isRefreshable(accountKey)) return false
        val sampledAt = currentSnapshots().filter { it.accountKey == accountKey }.maxOfOrNull { it.createdAt } ?: 0L
        if (!refreshGate.tryAcquire(accountKey, sampledAt)) return false
        runCatching {
            when {
                accountKey.startsWith("devin:") -> devin.refreshAccount(accountKey.removePrefix("devin:"))
                accountKey.startsWith("antigravity:") -> antigravity.refreshQuotas(accountKey)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Quota refresh failed", it)
        }
        return true
    }

    private fun isRefreshable(accountKey: String): Boolean = when {
        accountKey.startsWith("devin:") -> devin.accounts.value.any {
            "devin:${it.id}" == accountKey && it.enabled && it.status != DevinAccountStatus.INVALID
        }
        accountKey.startsWith("antigravity:") -> antigravity.accounts.value.any {
            it.id == accountKey && it.enabled && it.status != AntigravityTokenStatus.INVALID
        }
        else -> false
    }

    companion object {
        internal const val RESET_TOLERANCE_MS = 10 * 60_000L
        private const val SETTLE_DELAY_MS = 5_000L
    }
}

/** One account cannot issue two refreshes inside the interval, even when the first fails. */
internal class QuotaRefreshGate(private val intervalMs: Long, private val clock: () -> Long) {
    private val attempts = mutableMapOf<String, Long>()
    @Synchronized fun lastAt(account: String): Long = attempts[account] ?: 0L

    @Synchronized fun tryAcquire(account: String, sampledAt: Long): Boolean {
        val last = maxOf(attempts[account] ?: 0L, sampledAt)
        val now = clock()
        if (now - last < intervalMs) {
            attempts[account] = last
            return false
        }
        attempts[account] = now
        return true
    }
}
