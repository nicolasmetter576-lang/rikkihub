package me.rerere.rikkihub.ui.pages.stats

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.QuotaEstimate
import me.rerere.rikkihub.data.ai.UsageAmount
import me.rerere.rikkihub.data.ai.UsageModelStat
import me.rerere.rikkihub.data.ai.UsageProviderKind
import me.rerere.rikkihub.data.ai.UsageProviderStat
import me.rerere.rikkihub.data.ai.UsageRange
import me.rerere.rikkihub.data.ai.UsageSource
import me.rerere.rikkihub.data.ai.UsageStatsReport
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import me.rerere.rikkihub.ui.theme.AppSpacing

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<StatsDeletion?>(null) }


    pendingDeletion?.let { deletion ->
        DeleteUsageDialog(
            deletion = deletion,
            onConfirm = {
                vm.delete(deletion)
                pendingDeletion = null
            },
            onDismiss = { pendingDeletion = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    TextButton(onClick = vm::refresh, enabled = !stats.isRefreshing) {
                        Text(stringResource(R.string.stats_refresh))
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        val report = stats.report
        if (stats.failed) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.stats_load_failed))
                TextButton(onClick = vm::refresh) { Text(stringResource(R.string.stats_refresh)) }
            }
        } else if (report == null || report.range != stats.range) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(horizontal = AppSpacing.sm, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RangeSelector(range = stats.range, onChange = vm::setRange) }
                item { SummaryCard(report = report) }
                item {
                    Text(stringResource(R.string.stats_scope_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item { TrendCard(report = report) }
                if (report.providers.isNotEmpty()) {
                    item { ProvidersCard(report = report, onDelete = { pendingDeletion = it }) }
                }
                if (report.models.isNotEmpty()) {
                    item { ModelsCard(report = report, onDelete = { pendingDeletion = it }) }
                }
                if (report.sources.isNotEmpty()) {
                    item { SourcesCard(report = report) }
                }
                item { HeatmapCard(requestsPerDay = stats.requestsPerDay) }
                item { OverviewGrid(stats = stats) }
            }
        }
    }
}

// ---- 范围与摘要 ----

@Composable
private fun RangeSelector(range: UsageRange, onChange: (UsageRange) -> Unit) {
    val options = listOf(
        UsageRange.D7 to stringResource(R.string.stats_range_7d),
        UsageRange.D30 to stringResource(R.string.stats_range_30d),
        UsageRange.D90 to stringResource(R.string.stats_range_90d),
        UsageRange.ALL to stringResource(R.string.stats_range_all),
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == range,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun SummaryCard(report: UsageStatsReport) {
    val total = report.total
    StatsSection {
        Text(
            text = stringResource(R.string.stats_total_tokens),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = formatTokens(total.totalTokens), style = MaterialTheme.typography.displaySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        total.pricedTokens > 0 -> formatCost(total.cost)
                        else -> "—"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when {
                        total.totalTokens == 0L -> stringResource(R.string.stats_cost)
                        total.pricedTokens == 0L -> stringResource(R.string.stats_cost_none)
                        !total.fullyPriced -> stringResource(
                            R.string.stats_cost_partial,
                            formatPercent(total.pricedTokens.toDouble() / (total.promptTokens + total.completionTokens)),
                        )
                        else -> stringResource(R.string.stats_cost)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryCell(stringResource(R.string.stats_requests), formatCount(total.requests), Modifier.weight(1f))
            SummaryCell(stringResource(R.string.stats_page_input_tokens), formatTokens(total.promptTokens), Modifier.weight(1f))
            SummaryCell(stringResource(R.string.stats_page_output_tokens), formatTokens(total.completionTokens), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            SummaryCell(stringResource(R.string.stats_page_cached_tokens), formatTokens(total.cachedTokens), Modifier.weight(1f))
            SummaryCell(stringResource(R.string.stats_cache_hit_rate), formatPercent(total.cacheHitRate), Modifier.weight(2f))
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 趋势 ----

@Composable
private fun TrendCard(report: UsageStatsReport) {
    val buckets = report.trend
    var selected by remember(report) { mutableIntStateOf(buckets.lastIndex) }
    StatsSection(title = stringResource(R.string.stats_trend_title)) {
        val max = buckets.maxOfOrNull { it.amount.totalTokens } ?: 0L
        if (max == 0L) {
            Text(
                stringResource(R.string.stats_trend_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@StatsSection
        }
        buckets.getOrNull(selected)?.let { bucket ->
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = bucket.bucket,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = buildString {
                        append(formatTokens(bucket.amount.totalTokens))
                        if (bucket.amount.pricedTokens > 0) append(" · ").append(formatCost(bucket.amount.cost))
                        append(" · ").append(formatCount(bucket.amount.requests))
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        val barColor = MaterialTheme.colorScheme.primary
        val dimColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(buckets) {
                    detectTapGestures { offset ->
                        val slot = size.width / buckets.size.toFloat()
                        selected = (offset.x / slot).toInt().coerceIn(0, buckets.lastIndex)
                    }
                },
        ) {
            val slot = size.width / buckets.size
            val gap = (slot * 0.25f).coerceAtMost(4.dp.toPx())
            val barWidth = (slot - gap).coerceAtLeast(1f)
            buckets.forEachIndexed { index, bucket ->
                val left = index * slot + gap / 2
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                val value = bucket.amount.totalTokens
                if (value > 0) {
                    val h = (value.toFloat() / max * size.height).coerceAtLeast(2.dp.toPx())
                    drawRoundRect(
                        color = if (index == selected) barColor else dimColor,
                        topLeft = Offset(left, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        Row {
            Text(
                buckets.first().bucket,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                buckets.last().bucket,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 删除统计 ----

/** 等用户确认的删除：一行模型或一个提供商的全部用量记录 */
sealed interface StatsDeletion {
    val name: String

    data class Model(val key: String, override val name: String) : StatsDeletion
    data class Provider(val providerId: String?, override val name: String) : StatsDeletion
}

@Composable
private fun DeleteUsageButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            imageVector = HugeIcons.Delete01,
            contentDescription = stringResource(R.string.stats_delete_usage),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeleteUsageDialog(deletion: StatsDeletion, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stats_delete_usage)) },
        text = {
            Text(
                stringResource(
                    when (deletion) {
                        is StatsDeletion.Model -> R.string.stats_delete_model_confirm
                        is StatsDeletion.Provider -> R.string.stats_delete_provider_confirm
                    },
                    deletion.name,
                )
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete)) } },
    )
}

// ---- 提供商（含订阅额度） ----

@Composable
private fun ProvidersCard(report: UsageStatsReport, onDelete: (StatsDeletion) -> Unit) {
    StatsSection(title = stringResource(R.string.stats_providers_title)) {
        val max = report.providers.maxOf { it.amount.totalTokens }.coerceAtLeast(1)
        report.providers.forEachIndexed { index, provider ->
            if (index > 0) HorizontalDivider()
            val name = providerName(provider)
            ProviderRow(provider = provider, max = max, onDelete = { onDelete(StatsDeletion.Provider(provider.providerId, name)) })
        }
    }
}

@Composable
private fun ProviderRow(provider: UsageProviderStat, max: Long, onDelete: () -> Unit) {
    // 订阅类提供商默认展开，额度一眼能看到
    var expanded by rememberSaveable(provider.providerId) { mutableStateOf(provider.quotas.isNotEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = providerName(provider),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AmountText(provider.amount)
            DeleteUsageButton(onDelete)
        }
        UsageBar(provider.amount.totalTokens, max)
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                provider.quotas.forEach { quota -> QuotaBlock(quota) }
                if (provider.quotas.isNotEmpty()) {
                    Text(
                        stringResource(R.string.stats_quota_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                provider.models.forEach { model -> ModelLine(model, showProvider = false, onDelete = null) }
            }
        }
    }
}

@Composable
private fun QuotaBlock(quota: QuotaEstimate) {
    Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(quotaTitle(quota), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { quota.remaining.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = buildList {
                    add(stringResource(R.string.stats_quota_remaining, formatPercent(quota.remaining)))
                    if (quota.stale) add(stringResource(R.string.stats_quota_stale))
                    quota.resetAt?.let { add(stringResource(R.string.stats_quota_reset, formatDateTime(it))) }
                    if (quota.accounts > 1) add(stringResource(R.string.stats_quota_accounts, quota.accounts))
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (quota.boundaryUncertain) {
                Text(stringResource(R.string.stats_quota_boundary), style = MaterialTheme.typography.bodySmall)
            }
            if (!quota.enoughData) {
                Text(
                    stringResource(
                        R.string.stats_quota_insufficient,
                        formatPercent(quota.observedDrop),
                        formatPercent(me.rerere.rikkihub.data.ai.UsageStatsService.MIN_DROP_FOR_ESTIMATE),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            val parts = buildList {
                quota.tokens?.let { add(formatTokens(it.perFull.toLong()) + " Token") }
                quota.cost?.let { add(formatCost(it.perFull)) }
                quota.requests?.let { add(stringResource(R.string.stats_quota_requests, formatCount(it.perFull.toLong()))) }
            }
            Text(stringResource(R.string.stats_quota_full, parts.joinToString(" ≈ ")), style = MaterialTheme.typography.bodyMedium)
            // 剩余量按所有账号合计
            val leftParts = buildList {
                quota.tokens?.remaining?.let { add(formatTokens(it.toLong()) + " Token") }
                quota.cost?.remaining?.let { add(formatCost(it)) }
            }
            if (leftParts.isNotEmpty()) {
                Text(stringResource(R.string.stats_quota_left, leftParts.joinToString(" ≈ ")), style = MaterialTheme.typography.bodyMedium)
            }
            val likely = when (quota.likelyUnit) {
                "tokens" -> quota.tokens
                "cost" -> quota.cost
                "requests" -> quota.requests
                else -> null
            }
            val variation = likely?.variation
            if (likely != null && variation != null) {
                val unit = when (quota.likelyUnit) {
                    "tokens" -> stringResource(R.string.stats_quota_unit_tokens)
                    "cost" -> stringResource(R.string.stats_quota_unit_cost)
                    else -> stringResource(R.string.stats_quota_unit_requests)
                }
                Text(
                    stringResource(R.string.stats_quota_likely, unit, formatPercent(variation)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.stats_quota_based, quota.segments, formatPercent(quota.observedDrop)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun quotaTitle(quota: QuotaEstimate): String = when {
    quota.models.isNotEmpty() -> stringResource(R.string.stats_quota_models, quota.models.joinToString("、"))
    quota.window == "daily" -> stringResource(R.string.stats_quota_daily)
    quota.window == "weekly" || quota.window == "secondary" -> stringResource(R.string.stats_quota_weekly)
    quota.window == "primary" -> stringResource(R.string.stats_quota_primary)
    else -> quota.window
}

@Composable
private fun providerName(provider: UsageProviderStat): String = when (provider.kind) {
    UsageProviderKind.UNKNOWN -> stringResource(R.string.stats_provider_unknown)
    UsageProviderKind.DELETED -> stringResource(R.string.stats_provider_deleted)
    UsageProviderKind.CLI -> stringResource(R.string.stats_provider_cli)
    else -> provider.name
}

// ---- 模型与来源 ----

@Composable
private fun ModelsCard(report: UsageStatsReport, onDelete: (StatsDeletion) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    StatsSection(title = stringResource(R.string.stats_models_title)) {
        val models = if (showAll) report.models else report.models.take(8)
        val max = report.models.maxOf { it.amount.totalTokens }.coerceAtLeast(1)
        models.forEach { model ->
            val name = model.name.ifBlank { stringResource(R.string.stats_model_unknown) }
            ModelLine(model, showProvider = true, onDelete = { onDelete(StatsDeletion.Model(model.key, name)) })
            UsageBar(model.amount.totalTokens, max)
        }
        if (report.models.size > 8) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(stringResource(if (showAll) R.string.stats_show_less else R.string.stats_show_all, report.models.size))
            }
        }
    }
}

@Composable
private fun ModelLine(model: UsageModelStat, showProvider: Boolean, onDelete: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name.ifBlank { stringResource(R.string.stats_model_unknown) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                if (showProvider) model.providerName?.let { add(it) }
                add(stringResource(R.string.stats_requests_count, formatCount(model.amount.requests)))
                if (!model.priced) add(stringResource(R.string.stats_no_price))
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        AmountText(model.amount)
        if (onDelete != null) DeleteUsageButton(onDelete)
    }
}

@Composable
private fun SourcesCard(report: UsageStatsReport) {
    StatsSection(title = stringResource(R.string.stats_sources_title)) {
        val max = report.sources.maxOf { it.amount.totalTokens }.coerceAtLeast(1)
        report.sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(sourceLabel(source.source), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.stats_requests_count, formatCount(source.amount.requests)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(source.amount)
            }
            UsageBar(source.amount.totalTokens, max)
        }
    }
}

@Composable
private fun sourceLabel(source: String): String = when (runCatching { UsageSource.valueOf(source) }.getOrNull()) {
    UsageSource.CHAT -> stringResource(R.string.stats_source_chat)
    UsageSource.COMPRESSION -> stringResource(R.string.stats_source_compression)
    UsageSource.TITLE -> stringResource(R.string.stats_source_title)
    UsageSource.SUGGESTION -> stringResource(R.string.stats_source_suggestion)
    UsageSource.TRANSLATION -> stringResource(R.string.stats_source_translation)
    UsageSource.MEMORY -> stringResource(R.string.stats_source_memory)
    UsageSource.OCR -> stringResource(R.string.stats_source_ocr)
    UsageSource.POLISH -> stringResource(R.string.stats_source_polish)
    UsageSource.TUIQIAO -> stringResource(R.string.stats_source_tuiqiao)
    UsageSource.ZAIZAO -> stringResource(R.string.stats_source_zaizao)
    UsageSource.QUBAGU -> stringResource(R.string.stats_source_qubagu)
    UsageSource.SUBAGENT -> stringResource(R.string.stats_source_subagent)
    UsageSource.KEEPALIVE -> stringResource(R.string.stats_source_keepalive)
    UsageSource.CLI -> stringResource(R.string.stats_source_cli)
    UsageSource.BOOK -> stringResource(R.string.workspace_mode_book)
    null -> source
}

@Composable
private fun AmountText(amount: UsageAmount) {
    Column(horizontalAlignment = Alignment.End) {
        Text(formatTokens(amount.totalTokens), style = MaterialTheme.typography.bodyMedium)
        if (amount.pricedTokens > 0) {
            Text(
                formatCost(amount.cost),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun UsageBar(value: Long, max: Long) {
    LinearProgressIndicator(
        progress = { (value.toFloat() / max).coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp),
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

@Composable
private fun StatsSection(title: String? = null, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(modifier = Modifier.padding(AppSpacing.sm), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

// ---- 热力图与总览 ----

@Composable
private fun HeatmapCard(requestsPerDay: Map<LocalDate, Int>) {
    StatsSection(title = stringResource(R.string.stats_page_heatmap_title)) {
        ChatHeatmap(requestsPerDay = requestsPerDay)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_page_heatmap_less),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                HeatmapCell(alpha = alpha, sizeDp = 10)
            }
            Spacer(Modifier.width(2.dp))
            Text(
                text = stringResource(R.string.stats_page_heatmap_more),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatHeatmap(requestsPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    val activeCounts = requestsPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp

    // Day-of-week labels (only Mon/Wed/Fri to save space, Sun=0)
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        ""
    )

    // Shared scroll state so month labels + grid scroll together
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (requestsPerDay[date] ?: 0)
                            val alpha = when {
                                isFuture -> -1f
                                count == 0 -> 0f
                                count <= q1 -> 0.25f
                                count <= q2 -> 0.5f
                                count <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(alpha = alpha, sizeDp = cellSize.value.toInt())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) // future
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

@Composable
private fun OverviewGrid(stats: AppStats) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.ChartColumn,
            label = stringResource(R.string.stats_page_total_conversations),
            value = formatCount(stats.totalConversations.toLong()),
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Message01,
            label = stringResource(R.string.stats_page_total_messages),
            value = formatCount(stats.totalMessages.toLong()),
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = HugeIcons.Rocket01,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatCount(stats.launchCount.toLong()),
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(text = value, style = MaterialTheme.typography.titleLarge)
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

// ---- 格式化 ----

internal fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(java.util.Locale.US, count / 1_000.0)
    else -> count.toString()
}

internal fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(java.util.Locale.US, count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(java.util.Locale.US, count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(java.util.Locale.US, count / 1_000.0)
    else -> count.toString()
}

/** 花费一律按美元显示（模型价格以 USD/百万 token 填写）。 */
internal fun formatCost(value: Double): String = when {
    value <= 0.0 -> "$0"
    value < 0.01 -> "<$0.01"
    value < 100 -> "$%.2f".format(java.util.Locale.US, value)
    else -> "$%,.0f".format(java.util.Locale.US, value)
}

internal fun formatPercent(value: Double?): String = when {
    value == null || !value.isFinite() -> "—"
    value <= 0.0 -> "0%"
    value < 0.1 -> "%.1f%%".format(java.util.Locale.US, value * 100)
    else -> "%.0f%%".format(java.util.Locale.US, value * 100)
}

private fun formatDateTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
