package me.rerere.rikkihub.ui.components.message.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.SubagentMetadata
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.ui.components.richtext.DiffAddedColor
import me.rerere.rikkihub.ui.components.richtext.DiffRemovedColor
import me.rerere.rikkihub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkihub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkihub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.JsonInstantPretty

/** 详情页打开期间, 运行中日志的轮询间隔 */
private const val SUBAGENT_LOG_POLL_MS = 500L

/** 标题里未知统计的占位符 */
private const val SUBAGENT_UNKNOWN_MARK = "—"

/**
 * 工作区子代理: 标题为「任务名 · 模型id · 步数 · x s · xx k tok」实时摘要,
 * 点击展开的 BottomSheet 展示任务提示词与按时间序的推理/工具执行过程 (从运行日志读取)。
 */
object SubagentToolUI : ToolUIRenderer {
    override val toolName: String = BuiltinToolIds.SUBAGENT

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val metadata = rememberSubagentMetadata(context)
        val running = subagentIsRunning(
            loading = context.loading,
            isExecuted = context.tool.isExecuted,
            metadata = metadata,
        )
        val stats = subagentStatSegments(metadata, running)
        val taskName = subagentTaskName(
            metadata = metadata,
            description = context.arguments.getStringContent("description"),
            prompt = context.arguments.getStringContent("prompt"),
        )
        return subagentHeaderLine(
            taskName = taskName,
            modelId = metadata?.modelId,
            stepsLabel = stats.steps,
            elapsedLabel = stats.elapsed,
            tokensLabel = stats.tokens,
            fallback = stringResource(R.string.tool_ui_subagent_default),
        )
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.tool.isExecuted || context.arguments.getStringContent("prompt") != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val metadata = rememberSubagentMetadata(context)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            WorkspaceSubagentPresetRunLabel(metadata)
            metadata?.status?.let { rawStatus ->
                val status = if (rawStatus == "running" && !context.loading) "interrupted" else rawStatus
                Text(
                    text = stringResource(R.string.tool_ui_subagent_status, subagentStatusLabel(status)),
                    style = MaterialTheme.typography.labelSmall,
                    color = subagentStatusColor(status),
                )
            }
            val summary = metadata?.summary ?: context.arguments.getStringContent("prompt").orEmpty()
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (metadata?.truncated == true) {
                Text(
                    text = stringResource(R.string.tool_ui_subagent_truncated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        SubagentRunPreview(context)
    }
}

/** Show the saved role snapshot separately from the task title; never look up the current preset. */
@Composable
private fun WorkspaceSubagentPresetRunLabel(metadata: SubagentMetadata?) {
    subagentPresetIdentity(metadata)?.let { identity ->
        Text(
            text = stringResource(R.string.workspace_subagent_preset_log_identity, identity),
            style = MaterialTheme.typography.labelSmall,
        )
    }
    metadata?.presetRevision?.takeIf { it > 0 }?.let { revision ->
        Text(
            text = stringResource(R.string.workspace_subagent_preset_log_revision, revision),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 合并三处来源的 SubagentMetadata (tool part 实时 metadata > 输出 part metadata > 旧输出 JSON) */
@Composable
private fun rememberSubagentMetadata(context: ToolUIContext): SubagentMetadata? {
    val contentObject = context.content?.jsonObjectOrNull
    return remember(context.tool, contentObject) {
        subagentMetadata(context.tool, contentObject)
    }
}

private data class SubagentStatSegments(
    val steps: String?,
    val elapsed: String?,
    val tokens: String?,
)

/** 本地化统计段: 步数 / 耗时 / token; 未知显示占位符, 耗时空段省略 */
@Composable
private fun subagentStatSegments(metadata: SubagentMetadata?, running: Boolean): SubagentStatSegments {
    val elapsedSeconds = rememberSubagentElapsedSeconds(metadata, running)
    return SubagentStatSegments(
        steps = metadata?.stepCount?.let { stringResource(R.string.tool_ui_subagent_steps, it) }
            ?: stringResource(R.string.tool_ui_subagent_steps_unknown),
        elapsed = elapsedSeconds?.let { stringResource(R.string.tool_ui_subagent_elapsed, it) },
        tokens = stringResource(
            R.string.tool_ui_subagent_tokens,
            subagentKiloTokens(metadata?.totalTokens) ?: SUBAGENT_UNKNOWN_MARK,
        ),
    )
}

/**
 * 运行中每秒滴答计时; 非运行态冻结在终态耗时 (或 startedAt/completedAt 之差)。
 * loading=false 时 status=running 的残留 metadata 不会驱动计时。
 */
@Composable
private fun rememberSubagentElapsedSeconds(metadata: SubagentMetadata?, running: Boolean): Long? {
    val seconds by produceState(
        initialValue = subagentElapsedSeconds(metadata, isRunning = false, nowMs = 0L),
        metadata,
        running,
    ) {
        value = subagentElapsedSeconds(metadata, running, System.currentTimeMillis())
        while (running && isActive) {
            delay(1_000)
            value = subagentElapsedSeconds(metadata, isRunning = true, nowMs = System.currentTimeMillis())
        }
    }
    return seconds
}

/** 原始 status 字符串到本地化标签 */
@Composable
private fun subagentStatusLabel(status: String): String = stringResource(
    when (status) {
        "running" -> R.string.tool_ui_subagent_state_running
        "ok" -> R.string.tool_ui_subagent_state_ok
        "step_limit" -> R.string.tool_ui_subagent_state_step_limit
        "error" -> R.string.tool_ui_subagent_state_error
        "cancelled" -> R.string.tool_ui_subagent_state_cancelled
        "interrupted" -> R.string.tool_ui_subagent_state_interrupted
        "model_unavailable" -> R.string.tool_ui_subagent_state_model_unavailable
        "prompt_too_long" -> R.string.tool_ui_subagent_state_prompt_too_long
        "context_too_long" -> R.string.tool_ui_subagent_state_context_too_long
        else -> R.string.tool_ui_subagent_state_unknown
    }
)

@Composable
private fun subagentStatusColor(status: String): Color = when (status) {
    "running" -> MaterialTheme.colorScheme.primary
    "ok" -> DiffAddedColor
    "step_limit", "prompt_too_long", "context_too_long" -> MaterialTheme.colorScheme.tertiary
    "cancelled", "interrupted" -> MaterialTheme.colorScheme.onSurfaceVariant
    "error", "model_unavailable" -> DiffRemovedColor
    else -> LocalContentColor.current
}

/** BottomSheet 详情: 头部统计 + 报告/提示词 + 时间线 (日志文件驱动) */
@Composable
private fun SubagentRunPreview(context: ToolUIContext) {
    val appContext = LocalContext.current
    val metadata = rememberSubagentMetadata(context)
    val running = subagentIsRunning(context.loading, context.tool.isExecuted, metadata)
    val taskName = subagentTaskName(
        metadata = metadata,
        description = context.arguments.getStringContent("description"),
        prompt = context.arguments.getStringContent("prompt"),
        maxChars = Int.MAX_VALUE,
    )
    val prompt = context.arguments.getStringContent("prompt")
    val extraContext = context.arguments.getStringContent("context")

    // 仅在详情打开且仍在运行时轮询日志; 终态只读一次, 避免闪烁地保留已有内容
    var logState by remember(metadata?.logPath) {
        mutableStateOf<SubagentLogState>(SubagentLogState.Loading)
    }
    LaunchedEffect(running, metadata?.logPath) {
        while (true) {
            val next = withContext(Dispatchers.IO) {
                readSubagentLogState(appContext.filesDir, metadata?.logPath)
            }
            // 轮询中途文件短暂缺失/解析失败时保留上一次成功内容, 不闪空
            if (next is SubagentLogState.Parsed || logState !is SubagentLogState.Parsed) {
                logState = next
            }
            if (!running) break
            delay(SUBAGENT_LOG_POLL_MS)
        }
    }

    val log = (logState as? SubagentLogState.Parsed)?.log
    val timeline = remember(log) { log?.let(::subagentTimelineItems).orEmpty() }
    val rawStatus = log?.status ?: metadata?.status
    val status = if (rawStatus == "running" && !running) "interrupted" else rawStatus
    val detailMetadata = if (log == null) metadata else (metadata ?: SubagentMetadata()).copy(
        status = status,
        modelId = log.modelId ?: metadata?.modelId,
        stepCount = log.stepCount ?: metadata?.stepCount,
        elapsedMs = log.elapsedMs ?: metadata?.elapsedMs,
        totalTokens = log.totalTokens ?: metadata?.totalTokens,
        startedAtEpochMs = log.startedAtEpochMs ?: metadata?.startedAtEpochMs,
        completedAtEpochMs = log.completedAtEpochMs ?: metadata?.completedAtEpochMs,
        presetId = log.presetId ?: metadata?.presetId,
        presetKey = log.presetKey ?: metadata?.presetKey,
        presetName = log.presetName ?: metadata?.presetName,
        presetRevision = log.presetRevision ?: metadata?.presetRevision,
    )
    val stats = subagentStatSegments(detailMetadata, running && (status == null || status == "running"))

    LazyColumn(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 头部: 完整任务名 + 状态 + 统计
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = taskName ?: stringResource(R.string.tool_ui_subagent_default),
                    style = MaterialTheme.typography.titleMedium,
                )
                WorkspaceSubagentPresetRunLabel(detailMetadata)
                status?.let {
                    Text(
                        text = stringResource(R.string.tool_ui_subagent_status, subagentStatusLabel(it)),
                        style = MaterialTheme.typography.labelMedium,
                        color = subagentStatusColor(it),
                    )
                }
                if (running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                val statLine = listOf(
                    detailMetadata?.modelId?.takeIf { it.isNotBlank() } ?: SUBAGENT_UNKNOWN_MARK,
                    stats.steps ?: SUBAGENT_UNKNOWN_MARK,
                    stats.elapsed ?: "— s",
                    stats.tokens ?: "— k tok",
                ).joinToString(" · ")
                if (statLine.isNotBlank()) {
                    Text(
                        text = statLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val error = log?.error ?: metadata?.error
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // 报告 (终态 summary)
        val summary = log?.summary?.takeIf { it.isNotBlank() } ?: metadata?.summary
        if (!summary.isNullOrBlank()) {
            item(key = "summary") {
                SubagentCollapsibleSection(
                    title = stringResource(R.string.tool_ui_subagent_summary),
                    initiallyExpanded = true,
                ) {
                    SubagentReadableText(summary, markdown = true)
                }
            }
        }

        // 任务提示词
        if (!prompt.isNullOrBlank()) {
            item(key = "prompt") {
                SubagentCollapsibleSection(
                    title = stringResource(R.string.tool_ui_subagent_task_prompt),
                    initiallyExpanded = false,
                ) {
                    SubagentReadableText(prompt)
                }
            }
        }

        // 附加上下文 (父代理透传的 context 参数)
        if (!extraContext.isNullOrBlank()) {
            item(key = "context") {
                SubagentCollapsibleSection(
                    title = stringResource(R.string.tool_ui_subagent_context_label),
                    initiallyExpanded = false,
                ) {
                    SubagentReadableText(extraContext)
                }
            }
        }

        // 系统提示词 (worker preamble), 默认折叠避免长文淹没工作记录
        val preamble = log?.preamble?.takeIf { it.isNotBlank() }
        if (preamble != null) {
            item(key = "preamble") {
                SubagentCollapsibleSection(
                    title = stringResource(R.string.tool_ui_subagent_preamble),
                    initiallyExpanded = false,
                ) {
                    SubagentReadableText(preamble)
                }
            }
        }

        // 工作记录
        item(key = "timeline_label") {
            Text(
                text = stringResource(R.string.tool_ui_subagent_log),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        when (val state = logState) {
            is SubagentLogState.Parsed -> {
                if (timeline.isEmpty()) {
                    item(key = "timeline_empty") {
                        SubagentInfoText(stringResource(
                        if (running) R.string.tool_ui_subagent_state_running else R.string.tool_ui_subagent_log_empty
                    ))
                    }
                } else {
                    items(
                        count = timeline.size,
                        key = { index -> "timeline_$index" },
                    ) { index ->
                        SubagentTimelineRow(item = timeline[index])
                    }
                }
            }

            SubagentLogState.Loading -> item(key = "timeline_loading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SubagentInfoText(stringResource(R.string.tool_ui_subagent_log_loading))
                }
            }

            SubagentLogState.Missing -> item(key = "timeline_missing") {
                SubagentInfoText(stringResource(
                    if (running) R.string.tool_ui_subagent_log_loading else R.string.tool_ui_subagent_log_missing
                ))
            }

            SubagentLogState.Invalid -> item(key = "timeline_invalid") {
                SubagentInfoText(stringResource(R.string.tool_ui_subagent_log_invalid))
            }

            SubagentLogState.Unavailable -> item(key = "timeline_unavailable") {
                SubagentInfoText(stringResource(
                    if (running) R.string.tool_ui_subagent_state_running else R.string.tool_ui_subagent_no_log
                ))
            }
        }

        // 工具与编辑文件统计 (metadata 兜底, 日志优先)
        val toolsUsed = (log?.toolsUsed ?: metadata?.toolsUsed).orEmpty()
        if (toolsUsed.isNotEmpty()) {
            item(key = "tools_used") {
                Text(
                    text = stringResource(R.string.tool_ui_subagent_tools) + ": " + toolsUsed.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        val editedPaths = (log?.editedPaths ?: metadata?.editedPaths).orEmpty()
        if (editedPaths.isNotEmpty()) {
            item(key = "edited_paths") {
                Text(
                    text = stringResource(R.string.tool_ui_subagent_paths) + ": " + editedPaths.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SubagentInfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 可折叠小节: 标题行点击展开/收起, 右侧箭头指示 */
@Composable
private fun SubagentCollapsibleSection(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            content()
        }
    }
}

/** 单条时间线内容 */
@Composable
private fun SubagentTimelineRow(item: SubagentTimelineItem) {
    when (item) {
        is SubagentTimelineItem.AssistantText -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    SubagentReadableText(item.text, markdown = true)
                }
            }
        }

        is SubagentTimelineItem.ReasoningBlock -> {
            SubagentCollapsibleRow(
                icon = HugeIcons.Idea01,
                label = stringResource(R.string.tool_ui_subagent_reasoning),
            ) {
                SubagentReadableText(item.text, markdown = true)
            }
        }

        is SubagentTimelineItem.ToolCall -> {
            SubagentCollapsibleRow(
                icon = HugeIcons.Tools,
                label = item.name,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.input?.takeIf { it.isNotBlank() }?.let { input ->
                        Text(
                            text = stringResource(R.string.chat_message_tool_call_label, item.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HighlightCodeBlock(
                            code = prettySubagentToolText(input),
                            language = "json",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item.output?.takeIf { it.isNotBlank() }?.let { output ->
                        Text(
                            text = stringResource(R.string.chat_message_tool_call_result),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HighlightCodeBlock(
                            code = prettySubagentToolText(output),
                            language = "json",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        is SubagentTimelineItem.NoteBlock -> {
            val label = when (item.kind) {
                SubagentNoteKind.SYSTEM -> stringResource(R.string.tool_ui_subagent_note_system)
                SubagentNoteKind.TOOL_RESULT -> stringResource(R.string.tool_ui_subagent_note_tool_result)
                SubagentNoteKind.OTHER -> stringResource(R.string.tool_ui_subagent_note_other)
            }
            SubagentCollapsibleRow(icon = null, label = label) {
                SubagentReadableText(item.text)
            }
        }

        is SubagentTimelineItem.Media -> {
            ZoomableAsyncImage(
                model = item.url,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp),
            )
        }
    }
}

/** 时间线内默认折叠的一行 (reasoning / 工具调用 / 旁注) */
@Composable
private fun SubagentCollapsibleRow(
    icon: ImageVector?,
    label: String,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowDown01 else HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 22.dp, top = 4.dp)) {
                content()
            }
        }
    }
}

/** 默认限制长文本的首屏体积，但始终允许展开全文，不丢失工作内容。 */
@Composable
private fun SubagentReadableText(text: String, markdown: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val displayed = if (expanded) text else truncateSubagentText(text)
    Column {
        SelectionContainer {
            if (markdown) {
                MarkdownBlock(
                    content = displayed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(text = displayed, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (text.length > SUBAGENT_TEXT_DISPLAY_LIMIT) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.code_block_collapse else R.string.code_block_expand))
            }
        }
    }
}

/** 工具输入/输出展示前的美化: JSON 对象/数组 pretty print, 其他原样 */
private fun prettySubagentToolText(text: String): String {
    val element = runCatching { JsonInstant.parseToJsonElement(text) }.getOrNull()
        ?: return text
    if (element is JsonPrimitive) return text
    return runCatching { JsonInstantPretty.encodeToString(element) }.getOrDefault(text)
}
