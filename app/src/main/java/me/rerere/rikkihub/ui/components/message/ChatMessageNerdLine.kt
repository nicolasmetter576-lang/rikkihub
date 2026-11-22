package me.rerere.rikkihub.ui.components.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Layers01
import me.rerere.hugeicons.stroke.Repeat
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.utils.formatNumber
import me.rerere.rikkihub.utils.toFixed
import java.text.NumberFormat

/**
 * 显示消息的技术统计信息（token 用量、速度），点击查看详情
 *
 * usage 是各逻辑步骤已报告值的累计；上下文只是最近步骤的用量参考，不代表下一请求容量。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    onOpenContextBreakdown: (() -> Unit)? = null,
) {
    val settings = LocalSettings.current
    val usage = message.usage
    if (!settings.displaySetting.showTokenUsage || usage == null) return

    val stats = remember(message) { message.nerdStats(usage) }
    // 模型快照不带上下文窗口，按 id 从设置里找，找不到或无 modelId 时回退到消息自带的 contextLength
    val contextLength = remember(settings.providers, message.modelId, message.contextLength) {
        (message.modelId?.let { settings.findModelById(it) }?.contextLength?.takeIf { it > 0 }
            ?: message.contextLength?.takeIf { it > 0 })
    }
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = { Text(stringResource(R.string.chat_message_usage_title)) },
            ) {
                UsageDetails(
                    stats = stats,
                    contextLength = contextLength,
                    onOpenContextBreakdown = onOpenContextBreakdown?.let { callback ->
                        {
                            tooltipState.dismiss()
                            callback()
                        }
                    },
                )
            }
        },
        state = tooltipState,
        enableUserInput = false,
        modifier = modifier,
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
            CompositionLocalProvider(LocalContentColor provides color) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { scope.launch { tooltipState.show() } }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    if (stats.steps > 1) {
                        StatsItem(
                            icon = { StatsIcon(HugeIcons.Repeat, stringResource(R.string.chat_message_usage_step_count)) },
                            content = { Text(text = stringResource(R.string.chat_message_usage_steps, stats.steps)) }
                        )
                    }
                    StatsItem(
                        icon = { StatsIcon(HugeIcons.Layers01, stringResource(R.string.chat_message_usage_context)) },
                        content = {
                            Text(
                                text = stringResource(
                                    R.string.chat_message_usage_context_short,
                                    stats.contextTokens?.formatNumber() ?: stringResource(R.string.chat_message_usage_unknown)
                                )
                            )
                            val cached = stats.lastUsage?.cachedTokens
                            if (cached != null && cached > 0) {
                                Text(text = stringResource(R.string.chat_message_usage_cached_short, cached.formatNumber()))
                            }
                        }
                    )
                    StatsItem(
                        icon = { StatsIcon(HugeIcons.Download04, stringResource(R.string.chat_message_usage_output)) },
                        content = {
                            Text(
                                text = stringResource(
                                    R.string.chat_message_usage_output_short,
                                    stats.outputTokens?.formatNumber() ?: stringResource(R.string.chat_message_usage_unknown)
                                )
                            )
                        }
                    )
                    if (stats.tps != null) {
                        StatsItem(
                            icon = { StatsIcon(HugeIcons.Zap, stringResource(R.string.chat_message_usage_speed)) },
                            content = {
                                Text(text = if (stats.estimated) {
                                    stringResource(R.string.chat_message_usage_speed_estimated, stats.tps.toFixed(1))
                                } else "${stats.tps.toFixed(1)} tok/s")
                            }
                        )
                    }
                    UsageStatus(stats)

                }
            }
        }
    }
}

@Composable
private fun UsageDetails(
    stats: NerdStats,
    contextLength: Int?,
    onOpenContextBreakdown: (() -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        val total = stats.total
        val unknown = stringResource(R.string.chat_message_usage_unknown)
        UsageStatus(stats)
        if (stats.lastUsage != null) {
            ContextUsageBar(
                lastUsage = stats.lastUsage,
                contextLength = contextLength,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            Text(stringResource(R.string.chat_message_usage_current_unknown))
        }
        if (stats.steps > 1) {
            UsageDetailRow(stringResource(R.string.chat_message_usage_step_count), stats.steps.toString())
        }
        UsageDetailRow(stringResource(R.string.chat_message_usage_total_input), stats.reportedTokens(total.promptTokens)?.exact() ?: unknown)
        if (total.cachedTokens > 0) {
            UsageDetailRow(stringResource(R.string.chat_message_usage_total_cached), total.cachedTokens.exact())
        }
        UsageDetailRow(stringResource(R.string.chat_message_usage_total_output), stats.outputTokens?.exact() ?: unknown)
        stats.reportedTokens(total.totalTokens)?.let {
            UsageDetailRow(stringResource(R.string.chat_message_usage_reported_total), it.exact())
        }
        if (stats.tps != null) {
            UsageDetailRow(stringResource(R.string.chat_message_usage_speed), if (stats.estimated) {
                stringResource(R.string.chat_message_usage_speed_estimated, stats.tps.toFixed(1))
            } else "${stats.tps.toFixed(1)} tok/s")
        }
        if (stats.durationMillis != null) {
            UsageDetailRow(
                stringResource(if (stats.estimated) R.string.chat_message_usage_duration_estimated else R.string.chat_message_usage_duration),
                "${(stats.durationMillis / 1000.0).toFixed(1)}s"
            )
        }
        Text(stringResource(R.string.chat_message_usage_context_note), style = MaterialTheme.typography.labelSmall)
        Text(stringResource(R.string.chat_message_usage_timing_note), style = MaterialTheme.typography.labelSmall)
        if (onOpenContextBreakdown != null) {
            TextButton(
                onClick = onOpenContextBreakdown,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.testTag("nerd_line_open_context_breakdown"),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_context_usage_breakdown),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun UsageStatus(stats: NerdStats) {
    when (stats.usageComplete) {
        false -> Text(stringResource(R.string.chat_message_usage_incomplete))
        null -> Text(stringResource(R.string.chat_message_usage_legacy))
        true -> Unit
    }
}

private data class UsageSegment(val label: String, val tokens: Int, val color: Color)

/**
 * 最近逻辑模型步骤的参考用量对照模型窗口；空白不代表下一次请求的精确剩余容量
 */
@Composable
private fun ContextUsageBar(
    lastUsage: TokenUsage,
    contextLength: Int?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val segments = listOf(
        UsageSegment(stringResource(R.string.chat_message_usage_cached), lastUsage.cachedTokens, colorScheme.primary),
        UsageSegment(
            stringResource(R.string.chat_message_usage_input),
            (lastUsage.promptTokens - lastUsage.cachedTokens).coerceAtLeast(0),
            colorScheme.primary.copy(alpha = 0.45f),
        ),
        UsageSegment(stringResource(R.string.chat_message_usage_output), lastUsage.completionTokens, colorScheme.tertiary),
    ).filter { it.tokens > 0 }
    val contextTokens = lastUsage.promptTokens + lastUsage.completionTokens
    // 窗口未知或已经超出时，占用本身铺满整条
    val capacity = maxOf(contextLength ?: 0, contextTokens, 1)
    val hasFreeSpace = capacity > contextTokens
    val trackColor = colorScheme.surfaceContainerHighest

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.chat_message_usage_context),
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            if (contextLength != null) {
                val fraction = contextTokens.toFloat() / contextLength
                Text(
                    text = "${contextTokens.formatNumber()} / ${contextLength.formatNumber()} · ${(fraction * 100).toFixed(0)}%",
                    color = if (fraction >= 0.9f) colorScheme.error else Color.Unspecified,
                )
            } else {
                Text(text = contextTokens.exact())
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val gap = 2.dp.toPx()
            val radius = CornerRadius(size.height / 2)
            // 每段之间留出间隔；还有空余时最后一段和空余之间也要留
            val gaps = if (hasFreeSpace) segments.size else (segments.size - 1).coerceAtLeast(0)
            val available = size.width - gap * gaps
            var x = 0f
            segments.forEach { segment ->
                if (x >= size.width) return@forEach
                // 太小的段至少画成一个圆点，不然看不出来
                val width = (segment.tokens.toFloat() / capacity * available).coerceAtLeast(size.height)
                drawRoundRect(
                    color = segment.color,
                    topLeft = Offset(x, 0f),
                    size = Size(width.coerceAtMost(size.width - x), size.height),
                    cornerRadius = radius,
                )
                x += width + gap
            }
            if (hasFreeSpace && x < size.width) {
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(x, 0f),
                    size = Size(size.width - x, size.height),
                    cornerRadius = radius,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            segments.forEach { segment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(segment.color, CircleShape)
                    )
                    Text(
                        text = "${segment.label} ${segment.tokens.exact()}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageDetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = LocalContentColor.current.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Text(text = value)
    }
}

@Composable
private fun StatsIcon(imageVector: ImageVector, contentDescription: String) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = Modifier.size(12.dp)
    )
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}

private fun Int.exact(): String = NumberFormat.getIntegerInstance().format(this)
