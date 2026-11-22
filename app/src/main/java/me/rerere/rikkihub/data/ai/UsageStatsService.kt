package me.rerere.rikkihub.data.ai

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.cli.CliAgent
import me.rerere.rikkihub.data.db.dao.UsageGroupRow
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import me.rerere.rikkihub.data.db.entity.QuotaSnapshotEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.sqrt

/** 统计页的时间范围 */
@Serializable
enum class UsageRange(val days: Long?) {
    D7(7), D30(30), D90(90), ALL(null);

    companion object {
        fun parse(value: String?): UsageRange = when (value?.lowercase()) {
            "7d", "d7" -> D7
            "30d", "d30" -> D30
            "90d", "d90" -> D90
            "all" -> ALL
            else -> D30
        }
    }
}

/** 一组用量的合计；[cost] 只算填了价格的模型，[pricedTokens] 是其中有价格的 token 数 */
@Serializable
data class UsageAmount(
    val requests: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val totalTokens: Long = 0,
    val cost: Double = 0.0,
    val pricedTokens: Long = 0,
) {
    operator fun plus(o: UsageAmount) = UsageAmount(
        requests + o.requests, promptTokens + o.promptTokens, completionTokens + o.completionTokens,
        cachedTokens + o.cachedTokens, totalTokens + o.totalTokens, cost + o.cost, pricedTokens + o.pricedTokens,
    )

    /** 缓存是输入的子集。旧数据或上游缺报不满足这个条件时，不把异常值伪装成有效命中率。 */
    val cacheHitRate: Double? get() = when {
        promptTokens < 0 || cachedTokens < 0 || cachedTokens > promptTokens -> null
        promptTokens == 0L -> 0.0
        else -> cachedTokens.toDouble() / promptTokens
    }

    /** 全部 token 都有价格 */
    val fullyPriced: Boolean get() = promptTokens + completionTokens > 0 &&
        pricedTokens >= promptTokens + completionTokens
}

@Serializable
data class UsageBucketStat(val bucket: String, val amount: UsageAmount)

@Serializable
data class UsageModelStat(
    /** 这一行在流水里的分组键，删除统计时传回 */
    val key: String,
    val modelId: String?,
    val name: String,
    val providerName: String?,
    val priced: Boolean,
    val amount: UsageAmount,
)

@Serializable
data class UsageSourceStat(val source: String, val amount: UsageAmount)

/** 提供商种类：订阅类的三家有额度估算 */
@Serializable
enum class UsageProviderKind { DEVIN, ANTIGRAVITY, CODEX, CLI, OTHER, UNKNOWN, DELETED }

@Serializable
data class UsageProviderStat(
    /** 未知提供商（旧数据的模型已删除）为 null */
    val providerId: String?,
    val name: String,
    val kind: UsageProviderKind,
    val amount: UsageAmount,
    val models: List<UsageModelStat>,
    val quotas: List<QuotaEstimate> = emptyList(),
)

/** 估算的一种计量方式：满额度合多少 */
@Serializable
data class QuotaMeasure(
    /** 各账号各自的 100% 额度相加（token 数 / 花费 / 请求数），不要再乘账号数。 */
    val perFull: Double,
    /** 各段之间的离散程度（变异系数），越小越稳定；不足两段时为 null */
    val variation: Double?,
    /** 各账号独立估算后相加，不用平均额度乘账号数。过期/缺样本时为 null。 */
    val remaining: Double? = null,
)

/**
 * 一个额度窗口的反推结果。先独立估算每个账号，再合计容量与剩余量，不假设账号容量相同。
 *
 * @param window `daily` / `weekly` / `primary` / `secondary` / `model:<id>`
 * @param models Antigravity 共用这份额度的模型（其余提供商为空）
 * @param remaining 各账号当前剩余比例的平均值
 * @param observedDrop 用来估算的额度下降总量（比例，各段相加）
 * @param tokens 按 token 数（输入 + 输出，含缓存）计
 * @param cost 按填写的价格计；参与的用量全部有价格时才给
 * @param requests 按请求次数计
 * @param likelyUnit 各段之间最稳定的计量方式（`tokens` / `cost` / `requests`），段数不足时为 null
 */
@Serializable
data class QuotaEstimate(
    val window: String,
    val models: List<String> = emptyList(),
    val accounts: Int,
    val remaining: Double,
    val resetAt: Long?,
    val observedDrop: Double,
    val segments: Int,
    val enoughData: Boolean,
    val tokens: QuotaMeasure? = null,
    val cost: QuotaMeasure? = null,
    val requests: QuotaMeasure? = null,
    val likelyUnit: String? = null,
    val stale: Boolean = false,
    val boundaryUncertain: Boolean = false,
)

@Serializable
data class UsageStatsReport(
    val range: UsageRange,
    /** 趋势的分桶：选 7/30/90 天按天（`yyyy-MM-dd`），全部按月（`yyyy-MM`） */
    val bucketUnit: String,
    val total: UsageAmount,
    val trend: List<UsageBucketStat>,
    val models: List<UsageModelStat>,
    val sources: List<UsageSourceStat>,
    val providers: List<UsageProviderStat>,
)

/**
 * 统计页（App 与网页端共用）：从 usage_record 汇总用量、按当前设置里的模型价格算花费，
 * 并用 quota_snapshot 的额度变化点反推订阅额度。
 *
 * 费用按打开统计页时的价格计算，所以后补的价格对历史用量（含升级时回填的）同样生效。
 * 旧数据没有提供商，先按模型 UUID 找；UUID 失效时仅用无歧义的型号快照恢复归属。
 */
class UsageStatsService(
    private val dao: UsageRecordDAO,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentSnapshots: () -> List<QuotaSnapshotEntity>? = { null },
) {
    suspend fun report(
        range: UsageRange,
        providers: List<ProviderSetting>,
        cliAgents: List<CliAgent> = CliAgent.BUILT_IN,
    ): UsageStatsReport {
        val zoneId = zone()
        val today = Instant.ofEpochMilli(clock()).atZone(zoneId).toLocalDate()
        val since = range.days?.let { today.minusDays(it - 1).atStartOfDay(zoneId).toInstant().toEpochMilli() } ?: 0L
        val monthly = range == UsageRange.ALL
        val rows = dao.getGroupedUsage(if (monthly) "%Y-%m" else "%Y-%m-%d", since)
        val catalog = ProviderCatalog(providers, cliAgents)

        val total = rows.fold(UsageAmount()) { acc, row -> acc + catalog.amountOf(row) }
        val trend = buildTrend(rows, catalog, range, today, monthly)
        val models = rows.groupBy { it.modelKey() }
            .map { (_, group) -> catalog.modelStat(group) }
            .sortedByDescending { it.amount.totalTokens }
        val sources = rows.groupBy { it.source }
            .map { (source, group) -> UsageSourceStat(source, group.fold(UsageAmount()) { a, r -> a + catalog.amountOf(r) }) }
            .sortedByDescending { it.amount.totalTokens }

        val estimates = quotaEstimates(catalog)
        val providerStats = rows.groupBy { catalog.providerKey(it) }
            .map { (key, group) ->
                val info = catalog.providerInfo(key)
                UsageProviderStat(
                    providerId = info.id,
                    name = info.name,
                    kind = info.kind,
                    amount = group.fold(UsageAmount()) { a, r -> a + catalog.amountOf(r) },
                    models = group.groupBy { it.modelKey() }.map { (_, g) -> catalog.modelStat(g) }
                        .sortedByDescending { it.amount.totalTokens },
                    quotas = estimates[info.kind].orEmpty(),
                )
            }
            .toMutableList()
        // 订阅类提供商在范围内没用过也显示额度
        providers.forEach { setting ->
            val kind = kindOf(setting)
            val quotas = estimates[kind].orEmpty()
            if (quotas.isNotEmpty() && providerStats.none { it.providerId == setting.id.toString() }) {
                providerStats += UsageProviderStat(setting.id.toString(), setting.name, kind, UsageAmount(), emptyList(), quotas)
            }
        }
        providerStats.sortByDescending { it.amount.totalTokens }

        return UsageStatsReport(
            range = range,
            bucketUnit = if (monthly) "month" else "day",
            total = total,
            trend = trend,
            models = models,
            sources = sources,
            providers = providerStats,
        )
    }

    /** 删掉统计页「模型」里一行（[UsageModelStat.key]）的全部流水，不限时间范围、不分提供商。 */
    suspend fun deleteModelUsage(modelKey: String) =
        deleteRows(dao.getGroupedUsage("%Y", 0).filter { it.modelKey() == modelKey })

    /**
     * 删掉一个提供商（[UsageProviderStat.providerId]，null = 归属不明的那一组）的全部流水，不限时间范围；
     * 订阅类提供商连同额度读数一起删。
     */
    suspend fun deleteProviderUsage(providers: List<ProviderSetting>, providerId: String?) {
        val catalog = ProviderCatalog(providers)
        val key = providerId ?: ProviderCatalog.UNKNOWN
        deleteRows(dao.getGroupedUsage("%Y", 0).filter { catalog.providerKey(it) == key })
        when (catalog.providerInfo(key).kind) {
            UsageProviderKind.DEVIN -> dao.deleteSnapshots("devin:")
            UsageProviderKind.ANTIGRAVITY -> dao.deleteSnapshots("antigravity:")
            UsageProviderKind.CODEX -> dao.deleteSnapshots("codex:")
            UsageProviderKind.CLI, UsageProviderKind.OTHER, UsageProviderKind.UNKNOWN, UsageProviderKind.DELETED -> Unit
        }
    }

    private suspend fun deleteRows(rows: List<UsageGroupRow>) =
        rows.map { it.providerId to it.modelId }.distinct().forEach { (provider, model) -> dao.deleteUsage(provider, model) }

    /** 某提供商从 [since] 起按当前价格算的花费（美元）；没填价格的模型不计。 */
    suspend fun providerCost(providers: List<ProviderSetting>, providerId: String, since: Long): Double {
        val catalog = ProviderCatalog(providers)
        return dao.getGroupedUsage("%Y", since)
            .filter { catalog.providerKey(it) == providerId }
            .sumOf { catalog.amountOf(it).cost }
    }

    private fun buildTrend(
        rows: List<UsageGroupRow>,
        catalog: ProviderCatalog,
        range: UsageRange,
        today: LocalDate,
        monthly: Boolean,
    ): List<UsageBucketStat> {
        val byBucket = rows.groupBy { it.bucket.orEmpty() }
            .mapValues { (_, group) -> group.fold(UsageAmount()) { a, r -> a + catalog.amountOf(r) } }
        val labels: List<String> = if (monthly) {
            val months = byBucket.keys.mapNotNull { runCatching { YearMonth.parse(it) }.getOrNull() }
            val first = months.minOrNull() ?: YearMonth.from(today)
            generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(today) }.map { it.toString() }.toList()
        } else {
            val days = range.days ?: 30
            (days - 1 downTo 0).map { today.minusDays(it).toString() }
        }
        return labels.map { UsageBucketStat(it, byBucket[it] ?: UsageAmount()) }
    }

    // ---- 额度反推 ----

    private suspend fun quotaEstimates(catalog: ProviderCatalog): Map<UsageProviderKind, List<QuotaEstimate>> {
        val result = mutableMapOf<UsageProviderKind, List<QuotaEstimate>>()
        val current = currentSnapshots()
        for ((kind, prefix) in listOf(
            UsageProviderKind.DEVIN to "devin:",
            UsageProviderKind.ANTIGRAVITY to "antigravity:",
            UsageProviderKind.CODEX to "codex:",
        )) {
            val active = current?.filter { it.accountKey.startsWith(prefix) }
            val keys = active?.map { it.accountKey }?.toSet()
            val history = dao.getSnapshots(prefix).filter { keys == null || it.accountKey in keys }
            val known = history.map { it.accountKey to it.window }.toSet()
            val snapshots = history + active.orEmpty().filter { (it.accountKey to it.window) !in known }
            if (snapshots.isEmpty()) continue
            result[kind] = estimateKind(kind, snapshots, catalog, active)
        }
        return result
    }

    private suspend fun estimateKind(
        kind: UsageProviderKind,
        snapshots: List<QuotaSnapshotEntity>,
        catalog: ProviderCatalog,
        current: List<QuotaSnapshotEntity>?,
    ): List<QuotaEstimate> {
        val series = snapshots.groupBy { it.accountKey to it.window }
        // Antigravity 按模型给额度，共用一份额度的模型读数完全一样（同一次刷新、同样的值），合成一组
        val groups: List<Pair<String, List<Pair<String, String>>>> = if (kind == UsageProviderKind.ANTIGRAVITY) {
            series.keys.groupBy { key ->
                val points = series.getValue(key)
                // 一次相同百分比不是共池证据；至少观测到两次下降，且重置窗口也相同。
                if (points.zipWithNext().count { (a, b) -> b.remaining < a.remaining } >= 2) {
                    key.first to points.map { Triple(it.createdAt, it.remaining, it.resetAt) }
                } else key to emptyList<Triple<Long, Double, Long?>>()
            }
                .values
                .map { keys -> keys.first().second to keys }
                .groupBy { (_, keys) -> keys.map { it.second }.sorted() }
                .map { (windows, entries) -> windows.joinToString("|") to entries.flatMap { it.second } }
        } else {
            series.keys.groupBy { it.second }.map { (window, keys) -> window to keys }
        }
        return groups.map { (window, keys) ->
            val models = if (kind == UsageProviderKind.ANTIGRAVITY) {
                window.split("|").map { it.removePrefix("model:") }
            } else {
                emptyList()
            }
            estimateWindow(window.substringBefore("|"), models, keys.map { it to series.getValue(it) }, catalog,
                integerPercent = kind == UsageProviderKind.DEVIN, boundaryUncertain = kind == UsageProviderKind.CODEX,
                current = current)
        }.sortedWith(compareBy({ windowOrder(it.window) }, { it.models.firstOrNull().orEmpty() }))
    }

    private suspend fun estimateWindow(
        window: String,
        models: List<String>,
        series: List<Pair<Pair<String, String>, List<QuotaSnapshotEntity>>>,
        catalog: ProviderCatalog,
        integerPercent: Boolean,
        boundaryUncertain: Boolean,
        current: List<QuotaSnapshotEntity>?,
    ): QuotaEstimate {
        val now = clock()
        val segments = mutableListOf<QuotaSegment>()
        // Antigravity 同一个账号的一组模型只算一次（读数一样，用量按整组模型取）
        val perAccount = series.groupBy { it.first.first }.mapValues { (_, list) -> list.first().second }
        for ((account, points) in perAccount) {
            for (period in splitPeriods(points)) {
                // Devin 是整数百分比：第一个读数不是「刚变化」的时刻，从第一次变化算起才不会差一格
                val usable = if (integerPercent) period.drop(1) else period
                if (usable.size < 2) continue
                val start = usable.first()
                val end = usable.last()
                val drop = start.remaining - end.remaining
                if (drop <= 1e-9) continue
                val rows = dao.getAccountUsage(account, start.createdAt, end.createdAt, wholeRequestsOnly = !boundaryUncertain)
                    .filter { models.isEmpty() || it.modelName in models }
                if (rows.isEmpty() || rows.any { it.incompleteRequests > 0 }) continue
                val amount = rows.fold(UsageAmount()) { a, r -> a + catalog.amountOf(r) }
                segments += QuotaSegment(account, drop, amount)
            }
        }
        val latest = perAccount.map { (account, points) ->
            current?.firstOrNull { it.accountKey == account && it.window == points.last().window } ?: points.last()
        }
        val stale = latest.any { (it.resetAt != null && it.resetAt <= now) || now - it.createdAt > 30 * 60_000L }
        val remaining = latest.map { it.remaining }
        val observedDrop = segments.sumOf { it.drop }
        val enough = perAccount.keys.all { account ->
            segments.filter { it.account == account }.sumOf { it.drop } + 1e-9 >= MIN_DROP_FOR_ESTIMATE
        }
        fun aggregate(value: (UsageAmount) -> Double): QuotaMeasure? {
            val measures = perAccount.keys.map { account ->
                val measure = measure(segments.filter { it.account == account }, value) ?: return null
                account to measure
            }
            return QuotaMeasure(
                perFull = measures.sumOf { it.second.perFull },
                variation = measures.mapNotNull { it.second.variation }.takeIf { it.size == measures.size }?.maxOrNull(),
                remaining = if (stale) null else measures.sumOf { (account, m) ->
                    m.perFull * latest.first { it.accountKey == account }.remaining
                },
            )
        }
        val tokens = aggregate { (it.promptTokens + it.completionTokens).toDouble() }
        val cost = if (segments.isNotEmpty() && segments.all {
                it.amount.fullyPriced || it.amount.promptTokens + it.amount.completionTokens == 0L
            }) {
            aggregate { it.cost }
        } else {
            null
        }
        val requests = aggregate { it.requests.toDouble() }
        val likely = listOfNotNull(
            tokens?.variation?.let { "tokens" to it },
            cost?.variation?.let { "cost" to it },
            requests?.variation?.let { "requests" to it },
        ).minByOrNull { it.second }?.first
        return QuotaEstimate(
            window = window,
            models = models,
            accounts = perAccount.size,
            remaining = if (remaining.isEmpty()) 0.0 else remaining.average(),
            resetAt = latest.mapNotNull { it.resetAt }.filter { it > now }.minOrNull(),
            observedDrop = observedDrop,
            segments = segments.size,
            enoughData = enough,
            tokens = tokens.takeIf { enough },
            cost = cost.takeIf { enough },
            requests = requests.takeIf { enough },
            likelyUnit = likely.takeIf { enough && !boundaryUncertain },
            stale = stale,
            boundaryUncertain = boundaryUncertain,
        )
    }

    private fun measure(segments: List<QuotaSegment>, value: (UsageAmount) -> Double): QuotaMeasure? {
        val drop = segments.sumOf { it.drop }
        if (drop <= 0) return null
        val total = segments.sumOf { value(it.amount) }
        if (total <= 0) return null
        val ratios = segments.filter { it.drop >= MIN_DROP_FOR_VARIATION }.map { value(it.amount) / it.drop }
        val variation = if (ratios.size >= 2) {
            val mean = ratios.average()
            if (mean > 0) sqrt(ratios.sumOf { (it - mean) * (it - mean) } / ratios.size) / mean else null
        } else {
            null
        }
        return QuotaMeasure(perFull = total / drop, variation = variation)
    }

    private class QuotaSegment(val account: String, val drop: Double, val amount: UsageAmount)

    companion object {
        /** 累计下降不到 5% 时不给估算 */
        const val MIN_DROP_FOR_ESTIMATE = 0.05
        /** 参与稳定性比较的一段至少下降 2% */
        const val MIN_DROP_FOR_VARIATION = 0.02

        fun kindOf(setting: ProviderSetting): UsageProviderKind = when (setting) {
            is ProviderSetting.Devin -> UsageProviderKind.DEVIN
            is ProviderSetting.Antigravity -> UsageProviderKind.ANTIGRAVITY
            is ProviderSetting.Codex -> UsageProviderKind.CODEX
            else -> UsageProviderKind.OTHER
        }

        private fun windowOrder(window: String) = when (window) {
            "primary", "daily" -> 0
            "secondary", "weekly" -> 1
            else -> 2
        }

        /**
         * 把一个账号某窗口的读数按重置周期切段：重置时间变了（超出容差）或剩余比例回升，就是新的一段。
         */
        internal fun splitPeriods(points: List<QuotaSnapshotEntity>): List<List<QuotaSnapshotEntity>> {
            val periods = mutableListOf<MutableList<QuotaSnapshotEntity>>()
            for (point in points.sortedBy { it.createdAt }) {
                val current = periods.lastOrNull()
                val prev = current?.lastOrNull()
                val newPeriod = prev == null ||
                    point.remaining > prev.remaining + 1e-9 ||
                    (prev.resetAt != null && point.resetAt != null &&
                        kotlin.math.abs(prev.resetAt - point.resetAt) >= QuotaTracker.RESET_TOLERANCE_MS) ||
                    (prev.resetAt != null && point.createdAt >= prev.resetAt)
                if (newPeriod) periods += mutableListOf(point) else current!!.add(point)
            }
            return periods
        }
    }
}

private fun UsageGroupRow.modelKey(): String = modelId ?: "name:${modelName.orEmpty()}"

/** 当前设置里的提供商与模型，用来给流水找归属和价格 */
private class ProviderCatalog(
    providers: List<ProviderSetting>,
    cliAgents: List<CliAgent> = CliAgent.BUILT_IN,
) {
    private val providersById = providers.associateBy { it.id.toString() }
    private val cliAgentNames = cliAgents.associate { it.id to it.name }
    private val models = providers.flatMap { provider -> provider.models.map { provider to it } }
    private val modelsById = models.groupBy { it.second.id.toString() }
    private val modelsByName = models.groupBy { it.second.modelId }

    private fun isCliRow(row: UsageGroupRow): Boolean =
        row.source == UsageSource.CLI.name || row.providerId?.startsWith(CLI_PREFIX) == true

    private fun ownersOf(row: UsageGroupRow): List<Pair<ProviderSetting, Model>> {
        if (isCliRow(row)) return emptyList()
        val owners = row.modelId?.let { modelsById[it] }.orEmpty()
            .filter { row.providerId == null || row.providerId == it.first.id.toString() }
        if (owners.isNotEmpty()) return owners
        // OAuth 重新拉目录或删除后重新添加模型会产生新 UUID。型号只能作保守回退，
        // 不能把明确属于另一个（或已删除）提供商的流水挪过来。
        return row.modelName?.takeIf { it.isNotBlank() }?.let { modelsByName[it] }.orEmpty()
            .filter { row.providerId == null || row.providerId == it.first.id.toString() }
    }

    fun pricingOf(row: UsageGroupRow): ModelPricing? =
        ownersOf(row).singleOrNull()?.second?.pricing?.takeUnless { it.isEmpty }

    fun amountOf(row: UsageGroupRow): UsageAmount {
        val pricing = pricingOf(row)
        return UsageAmount(
            requests = row.requests,
            promptTokens = row.promptTokens,
            completionTokens = row.completionTokens,
            cachedTokens = row.cachedTokens,
            totalTokens = row.totalTokens,
            cost = pricing?.cost(row.promptTokens, row.completionTokens, row.cachedTokens) ?: 0.0,
            pricedTokens = pricing?.pricedTokens(row.promptTokens, row.completionTokens, row.cachedTokens) ?: 0,
        )
    }

    fun providerKey(row: UsageGroupRow): String =
        row.providerId ?: ownersOf(row).map { it.first.id.toString() }.distinct().singleOrNull() ?: UNKNOWN

    data class ProviderInfo(val id: String?, val name: String, val kind: UsageProviderKind)

    fun providerInfo(key: String): ProviderInfo {
        if (key == UNKNOWN) return ProviderInfo(null, "", UsageProviderKind.UNKNOWN)
        if (key.startsWith(CLI_PREFIX)) {
            val agentId = key.removePrefix(CLI_PREFIX)
            val name = cliAgentNames[agentId]?.takeIf { it.isNotBlank() } ?: agentId
            return ProviderInfo(key, name, UsageProviderKind.CLI)
        }
        val provider = providersById[key] ?: return ProviderInfo(key, "", UsageProviderKind.DELETED)
        return ProviderInfo(key, provider.name, UsageStatsService.kindOf(provider))
    }

    fun modelStat(group: List<UsageGroupRow>): UsageModelStat {
        val first = group.first()
        val owner = ownersOf(first).singleOrNull()
        val model = owner?.second
        val name = model?.displayName?.ifBlank { null } ?: model?.modelId
            ?: group.firstNotNullOfOrNull { it.modelName } ?: ""
        val providerName = providerInfo(providerKey(first)).name.ifBlank { null }
        return UsageModelStat(
            key = first.modelKey(),
            modelId = first.modelId,
            name = name,
            providerName = providerName,
            priced = group.all { amountOf(it).fullyPriced },
            amount = group.fold(UsageAmount()) { a, r -> a + amountOf(r) },
        )
    }

    companion object {
        const val UNKNOWN = "__unknown__"
        const val CLI_PREFIX = "cli:"
    }
}
