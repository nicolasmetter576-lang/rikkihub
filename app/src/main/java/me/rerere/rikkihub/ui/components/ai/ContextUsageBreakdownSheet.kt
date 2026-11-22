package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.rikkihub.R
import me.rerere.rikkihub.service.ContextUsageBreakdown
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import me.rerere.rikkihub.utils.formatNumber

private data class BreakdownSegment(
    val color: Color,
    val label: String,
    val tokens: Int,
)

@Composable
fun ContextUsageBreakdownSheet(
    breakdown: ContextUsageBreakdown?,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onOpenCompress: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.ChartColumn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_page_context_breakdown_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            when {
                isLoading && breakdown == null -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error != null && breakdown == null -> {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                breakdown != null -> {
                    BreakdownContent(
                        breakdown = breakdown,
                        onOpenCompress = onOpenCompress,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakdownContent(
    breakdown: ContextUsageBreakdown,
    onOpenCompress: () -> Unit,
) {
    val badgeText = when {
        breakdown.totalExact -> stringResource(R.string.chat_page_context_breakdown_exact_badge)
        breakdown.reportedContextTokens != null && breakdown.draftTokens > 0 -> stringResource(
            R.string.chat_page_context_breakdown_exact_plus_draft,
            breakdown.reportedContextTokens.formatNumber(),
            breakdown.draftTokens.formatNumber(),
        )
        else -> stringResource(R.string.chat_page_context_breakdown_estimated_badge)
    }

    val segments = listOf(
        BreakdownSegment(
            color = MaterialTheme.colorScheme.primary,
            label = stringResource(R.string.chat_page_context_breakdown_system),
            tokens = breakdown.systemPromptTokens,
        ),
        BreakdownSegment(
            color = MaterialTheme.colorScheme.secondary,
            label = stringResource(R.string.chat_page_context_breakdown_memory, breakdown.memoryFileCount),
            tokens = breakdown.memoryTokens,
        ),
        BreakdownSegment(
            color = MaterialTheme.colorScheme.tertiary,
            label = stringResource(R.string.chat_page_context_breakdown_lorebook, breakdown.activeLorebookCount),
            tokens = breakdown.lorebookAndTavernTokens,
        ),
        BreakdownSegment(
            color = Color(0xFF00897B),
            label = stringResource(R.string.chat_page_context_breakdown_tools_local, breakdown.toolsLocalCount),
            tokens = breakdown.toolsLocalTokens,
        ),
        BreakdownSegment(
            color = Color(0xFF0288D1),
            label = stringResource(R.string.chat_page_context_breakdown_tools_mcp, breakdown.toolsMcpCount),
            tokens = breakdown.toolsMcpTokens,
        ),
        BreakdownSegment(
            color = Color(0xFF7B1FA2),
            label = stringResource(R.string.chat_page_context_breakdown_tools_skills, breakdown.toolsSkillsCount),
            tokens = breakdown.toolsSkillsTokens,
        ),
        BreakdownSegment(
            color = Color(0xFFEF6C00),
            label = stringResource(R.string.chat_page_context_breakdown_tools_workspace, breakdown.toolsWorkspaceCount),
            tokens = breakdown.toolsWorkspaceTokens,
        ),
        BreakdownSegment(
            color = Color(0xFF5C6BC0),
            label = stringResource(R.string.chat_page_context_breakdown_checkpoint),
            tokens = breakdown.historyCheckpointTokens,
        ),
        BreakdownSegment(
            color = Color(0xFF2E7D32),
            label = stringResource(R.string.chat_page_context_breakdown_history, breakdown.historyMessageCount),
            tokens = breakdown.historyMessagesTokens,
        ),
        BreakdownSegment(
            color = Color(0xFFD81B60),
            label = stringResource(R.string.chat_page_context_breakdown_attachments, breakdown.attachmentCount),
            tokens = breakdown.attachmentsTokens,
        ),
        BreakdownSegment(
            color = Color(0xFFF9A825),
            label = stringResource(R.string.chat_page_context_breakdown_draft),
            tokens = breakdown.draftTokens,
        ),
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("context_breakdown_summary_card"),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = buildString {
                        append(breakdown.totalTokens.formatNumber())
                        breakdown.contextLength?.takeIf { it > 0 }?.let { limit ->
                            append(" / ")
                            append(limit.formatNumber())
                        }
                        append(" tokens")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (breakdown.totalExact) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (breakdown.totalExact) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            val window = breakdown.contextLength?.takeIf { it > 0 }
            if (window != null) {
                val ratio = (breakdown.totalTokens.toFloat() / window).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }

            val positiveSegments = segments.filter { it.tokens > 0 }
            if (positiveSegments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                ) {
                    positiveSegments.forEach { segment ->
                        Box(
                            modifier = Modifier
                                .weight(segment.tokens.toFloat())
                                .fillMaxHeight()
                                .background(segment.color),
                        )
                    }
                }
            }

            Text(
                text = stringResource(
                    R.string.chat_page_context_breakdown_note,
                    breakdown.estimatedTotalTokens.formatNumber(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val totalEst = breakdown.estimatedTotalTokens.coerceAtLeast(1)
        segments.forEachIndexed { index, segment ->
            val hideZeroOptional = index >= segments.size - 1 && segment.tokens == 0
            if (hideZeroOptional) return@forEachIndexed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(segment.color),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = segment.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                val pct = (segment.tokens * 100f / totalEst)
                Text(
                    text = if (segment.tokens > 0) {
                        "${segment.tokens.formatNumber()} (${"%.1f".format(pct)}%)"
                    } else {
                        "0"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (segment.tokens > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (index == 2 || index == 6) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }

    FilledTonalButton(
        onClick = onOpenCompress,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("context_breakdown_open_compress"),
    ) {
        Text(stringResource(R.string.chat_page_context_breakdown_open_compress))
    }
}
