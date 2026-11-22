package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.rikkihub.data.ai.CompressionTemplates
import me.rerere.rikkihub.data.ai.ConversationCompressionOverride
import me.rerere.rikkihub.data.ai.prompts.builtInCompressStructure
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.service.CarriedOriginalEdit
import me.rerere.rikkihub.service.CarriedOriginalItem
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.service.CompressionEstimate
import me.rerere.rikkihub.service.compressionRange
import me.rerere.rikkihub.service.estimateCompression
import me.rerere.rikkihub.service.hasContextCheckpoint
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import me.rerere.rikkihub.ui.components.ui.RabbitLoadingIndicator
import me.rerere.rikkihub.utils.formatNumber
import java.util.Locale
import kotlin.uuid.Uuid

/**
 * 手动压缩对话框：模板、这次要重点留下的内容、保留最近几条、从原文重压、原话带上（可单条改/移除）、
 * 本地预估与预览、先看再采用，以及本会话的自动压缩档位和默认模板。压缩在后台进行，进行中只提供取消。
 *
 * [config] 是全局设置（未叠加会话覆盖）；[budget] 是会话对话模型的输入预算，[compressionBudget] 是实际
 * 用来压缩的模型的输入预算，未知时为 null。
 */
@Composable
fun CompressContextDialog(
    compressState: CompressState?,
    conversation: Conversation,
    config: CompressionConfig,
    budget: Int?,
    compressionBudget: Int? = budget,
    separateModel: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (CompressRequest) -> Unit,
    onOverrideChange: (ConversationCompressionOverride?) -> Unit,
    onCancelCompression: () -> Unit,
) {
    val resolved = config.resolvedFor(conversation.compression)
    val hasCheckpoint = remember(conversation.messageNodes) { hasContextCheckpoint(conversation.messageNodes) }
    var additionalPrompt by remember { mutableStateOf("") }
    // 默认全部压缩，请求前缀与对话一致，缓存命中最好
    var keepRecent by remember { mutableIntStateOf(0) }
    var templateId by remember { mutableStateOf(resolved.templateId) }
    var review by remember { mutableStateOf(config.reviewBeforeApply) }
    var createNewConversation by remember { mutableStateOf(false) }
    var fromOriginal by remember { mutableStateOf(false) }
    var carryMode by remember { mutableStateOf(resolved.carryOriginalsMode) }
    var carryCount by remember { mutableIntStateOf(resolved.carryOriginalsCount) }
    var carryIncludeReplies by remember { mutableStateOf(resolved.carryIncludeReplies) }
    var carryExcludedNodeIds by remember { mutableStateOf(emptySet<Uuid>()) }
    var carryEdits by remember { mutableStateOf(emptyMap<Uuid, CarriedOriginalEdit>()) }
    var editingCarried by remember { mutableStateOf<CarriedOriginalItem?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    val isRunning = compressState?.status == CompressStatus.RUNNING

    // 和服务端的拒绝同一判断：没有新消息，或保留最近条数把可压范围（或切点）全吞掉了
    val nothingToCompress = remember(conversation.messageNodes, keepRecent, fromOriginal) {
        when {
            compressionRange(conversation.messageNodes, keepRecent, fromOriginal) != null -> null
            keepRecent > 0 && compressionRange(conversation.messageNodes, fromOriginal = fromOriginal) != null ->
                R.string.chat_page_compress_not_enough_messages
            else -> R.string.chat_page_compress_nothing_to_compress
        }
    }

    val structure = remember(config, templateId) {
        config.customTemplates.firstOrNull { it.id == templateId }?.prompt ?: builtInCompressStructure(templateId)
    }
    val locale = remember { Locale.getDefault().displayName }
    val estimate by produceState<CompressionEstimate?>(
        null,
        keepRecent,
        fromOriginal,
        carryMode,
        carryCount,
        carryIncludeReplies,
        carryExcludedNodeIds,
        carryEdits,
        structure,
        additionalPrompt,
        conversation.messageNodes,
        budget,
        compressionBudget,
        separateModel,
    ) {
        value = withContext(Dispatchers.Default) {
            conversation.estimateCompression(
                keepRecent = keepRecent,
                summaryTokens = resolved.summaryTokens,
                budget = budget,
                fromOriginal = fromOriginal,
                carryMode = carryMode,
                carryCount = carryCount,
                carryIncludeReplies = carryIncludeReplies,
                carryExcludedNodeIds = carryExcludedNodeIds,
                carryEdits = carryEdits,
                targetPercent = resolved.targetPercent,
                compressionBudget = compressionBudget,
                maxChunkRequests = resolved.maxChunkRequests,
                separateModel = separateModel,
                structure = structure,
                focus = additionalPrompt,
                locale = locale,
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_page_compress_context_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RabbitLoadingIndicator(modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (compressState.chunked && compressState.totalChunks > 0) {
                                stringResource(
                                    R.string.chat_page_compress_running_chunked,
                                    (compressState.completedChunks + 1).coerceAtMost(compressState.totalChunks),
                                    compressState.totalChunks,
                                )
                            } else {
                                stringResource(R.string.chat_page_compressing)
                            }
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_page_compress_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }
                Text(stringResource(R.string.chat_page_compress_context_desc))

                LabeledDropdown(
                    label = stringResource(R.string.chat_page_compress_template),
                    value = compressTemplateName(config, templateId),
                    options = templateIds(config).map { it to compressTemplateName(config, it) },
                    onSelect = { templateId = it },
                    testTag = "compress_template",
                )

                OutlinedTextField(
                    value = additionalPrompt,
                    onValueChange = { additionalPrompt = it.take(CompressRequest.MAX_ADDITIONAL_PROMPT) },
                    label = { Text(stringResource(R.string.chat_page_compress_additional_prompt)) },
                    placeholder = { Text(stringResource(R.string.chat_page_compress_additional_prompt_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 4,
                )

                KeepRecentStepper(keepRecent = keepRecent, onChange = { keepRecent = it })

                if (hasCheckpoint) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.chat_page_compress_from_original),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = fromOriginal,
                                onCheckedChange = { fromOriginal = it },
                                modifier = Modifier.testTag("compress_from_original_toggle"),
                            )
                        }
                        Text(
                            text = stringResource(R.string.chat_page_compress_from_original_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                CarryOriginalsSection(
                    carryMode = carryMode,
                    onModeChange = { carryMode = it },
                    carryCount = carryCount,
                    onCountChange = { carryCount = it },
                    includeReplies = carryIncludeReplies,
                    onIncludeRepliesChange = { carryIncludeReplies = it },
                    estimate = estimate.takeIf { nothingToCompress == null },
                    onEditItem = { editingCarried = it },
                    onExcludeItem = { id -> carryExcludedNodeIds = carryExcludedNodeIds + id },
                    onRestoreExcluded = { carryExcludedNodeIds = emptySet() },
                )

                if (nothingToCompress != null) {
                    Text(
                        text = stringResource(nothingToCompress),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("compress_nothing"),
                    )
                } else {
                    EstimatePanel(estimate)

                    TextButton(
                        onClick = { showPreview = !showPreview },
                        modifier = Modifier.testTag("compress_preview_toggle"),
                    ) { Text(stringResource(R.string.chat_page_compress_preview)) }
                    if (showPreview) CompressionPreview(conversation, keepRecent, fromOriginal)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.chat_page_compress_review_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = review,
                        onCheckedChange = { review = it },
                        modifier = Modifier.testTag("compress_review_toggle"),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            stringResource(R.string.chat_page_compress_new_conversation_toggle),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.chat_page_compress_new_conversation_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = createNewConversation,
                        onCheckedChange = { createNewConversation = it },
                        modifier = Modifier.testTag("compress_new_conversation_toggle"),
                    )
                }

                HorizontalDivider()
                ConversationCompressionSettings(config, conversation.compression, onOverrideChange)

                Text(
                    text = stringResource(R.string.chat_page_compress_checkpoint_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (isRunning) {
                TextButton(onClick = onCancelCompression) {
                    Text(stringResource(R.string.chat_page_compress_cancel))
                }
            } else {
                TextButton(
                    onClick = {
                        onConfirm(
                            CompressRequest(
                                additionalPrompt = additionalPrompt,
                                keepRecent = keepRecent,
                                templateId = templateId,
                                review = review,
                                fromOriginal = fromOriginal,
                                carryMode = carryMode,
                                carryCount = carryCount,
                                carryIncludeReplies = carryIncludeReplies,
                                carryExcludedNodeIds = carryExcludedNodeIds,
                                carryEdits = carryEdits,
                                createNewConversation = createNewConversation,
                            )
                        )
                        onDismiss()
                    },
                    enabled = nothingToCompress == null,
                    modifier = Modifier.testTag("compress_confirm"),
                ) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (isRunning) R.string.chat_page_compress_hide else R.string.cancel))
            }
        },
    )

    editingCarried?.let { item ->
        EditCarriedOriginalDialog(
            item = item,
            onSave = { userText, replyText ->
                carryEdits = carryEdits + (item.nodeId to CarriedOriginalEdit(userText = userText, replyText = replyText))
                editingCarried = null
            },
            onDismiss = { editingCarried = null },
        )
    }
}

/** 「先看再采用」：摘要（含附带原话）可改，采用后才插入检查点；丢弃则历史不变。 */
@Composable
fun CompressReviewDialog(
    summary: String,
    compressState: CompressState? = null,
    createNewConversation: Boolean = false,
    onApply: (String) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(summary) { mutableStateOf(summary) }
    val meta = compressResultMetaLine(compressState)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_page_compress_review_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.chat_page_compress_review_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meta != null) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("compress_review_meta"),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp)
                        .testTag("compress_review_text"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("compress_review_apply"),
            ) {
                Text(
                    stringResource(
                        if (createNewConversation) R.string.chat_page_compress_review_apply_new
                        else R.string.chat_page_compress_review_apply
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.testTag("compress_review_discard")) {
                Text(stringResource(R.string.chat_page_compress_review_discard))
            }
        },
    )
}

@Composable
internal fun compressResultMetaLine(state: CompressState?): String? {
    if (state == null || state.requestCount <= 0) return null
    val parts = buildList {
        add(stringResource(R.string.chat_page_compress_result_summary, state.requestCount))
        if (state.chunked) add(stringResource(R.string.chat_page_compress_result_chunked))
        if (state.cacheBypassed) add(stringResource(R.string.chat_page_compress_result_no_cache))
        if (state.carriedCount > 0) add(stringResource(R.string.chat_page_compress_result_carried, state.carriedCount))
        state.totalUsage?.totalTokens?.takeIf { it > 0 }?.let { total ->
            add(stringResource(R.string.chat_page_compress_result_usage, total.formatNumber()))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
fun compressModeName(mode: AutoCompressMode): String = stringResource(
    when (mode) {
        AutoCompressMode.OFF -> R.string.compress_mode_off
        AutoCompressMode.NOTIFY -> R.string.compress_mode_notify
        AutoCompressMode.AUTO -> R.string.compress_mode_auto
    }
)

@Composable
fun carryOriginalsModeName(mode: CarryOriginalsMode): String = stringResource(
    when (mode) {
        CarryOriginalsMode.OFF -> R.string.setting_compression_carry_off
        CarryOriginalsMode.RECENT -> R.string.setting_compression_carry_recent
        CarryOriginalsMode.AUTO -> R.string.setting_compression_carry_auto
    }
)

/** 内置模板按语言显示名称，自定义模板用它自己的名字；已删除的自定义模板按通用。 */
@Composable
fun compressTemplateName(config: CompressionConfig, id: String): String {
    config.customTemplates.firstOrNull { it.id == id }?.let { return it.name }
    return stringResource(
        when (id) {
            CompressionTemplates.CODE -> R.string.compress_template_code
            CompressionTemplates.WRITING -> R.string.compress_template_writing
            CompressionTemplates.RESEARCH -> R.string.compress_template_research
            CompressionTemplates.ROLEPLAY -> R.string.compress_template_roleplay
            else -> R.string.compress_template_general
        }
    )
}

private fun templateIds(config: CompressionConfig) = CompressionTemplates.BUILT_IN + config.customTemplates.map { it.id }

@Composable
private fun CarryOriginalsSection(
    carryMode: CarryOriginalsMode,
    onModeChange: (CarryOriginalsMode) -> Unit,
    carryCount: Int,
    onCountChange: (Int) -> Unit,
    includeReplies: Boolean,
    onIncludeRepliesChange: (Boolean) -> Unit,
    estimate: CompressionEstimate?,
    onEditItem: (CarriedOriginalItem) -> Unit,
    onExcludeItem: (Uuid) -> Unit,
    onRestoreExcluded: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledDropdown(
            label = stringResource(R.string.chat_page_compress_carry_title),
            value = carryOriginalsModeName(carryMode),
            options = CarryOriginalsMode.entries.map { it to carryOriginalsModeName(it) },
            onSelect = onModeChange,
            testTag = "compress_carry_mode",
        )
        if (carryMode == CarryOriginalsMode.RECENT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.setting_compression_carry_count),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                FilledTonalIconButton(
                    onClick = { onCountChange((carryCount - 1).coerceAtLeast(CompressionConfig.MIN_CARRY_COUNT)) },
                    enabled = carryCount > CompressionConfig.MIN_CARRY_COUNT,
                ) {
                    Icon(HugeIcons.Remove01, contentDescription = null)
                }
                Text(
                    text = carryCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 36.dp),
                )
                FilledTonalIconButton(
                    onClick = { onCountChange((carryCount + 1).coerceAtMost(CompressionConfig.MAX_CARRY_COUNT)) },
                    enabled = carryCount < CompressionConfig.MAX_CARRY_COUNT,
                ) {
                    Icon(HugeIcons.Add01, contentDescription = null)
                }
            }
        }
        if (carryMode != CarryOriginalsMode.OFF) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setting_compression_carry_replies),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = includeReplies,
                    onCheckedChange = onIncludeRepliesChange,
                    modifier = Modifier.testTag("compress_carry_replies"),
                )
            }
            if (estimate != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.chat_page_compress_carry_list_title,
                            estimate.carriedCount,
                            estimate.carriedTokens.formatNumber(),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (estimate.carriedExcludedCount > 0) {
                        TextButton(onClick = onRestoreExcluded, modifier = Modifier.testTag("compress_carry_restore")) {
                            Text(stringResource(R.string.chat_page_compress_carry_restore, estimate.carriedExcludedCount))
                        }
                    }
                }
                if (estimate.carriedOverBudget) {
                    Text(
                        text = stringResource(R.string.chat_page_compress_carry_over_budget),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (estimate.carriedSkippedForBudget > 0) {
                    Text(
                        text = stringResource(R.string.chat_page_compress_carry_skipped_budget, estimate.carriedSkippedForBudget),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (estimate.carriedItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_page_compress_carry_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.testTag("compress_carry_list"),
                    ) {
                        estimate.carriedItems.forEachIndexed { idx, item ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "${idx + 1}. ${item.userText.replace('\n', ' ')}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val tags = buildList {
                                            if (item.replyText != null) add(stringResource(R.string.chat_page_compress_carry_with_reply))
                                            if (item.edited) add(stringResource(R.string.chat_page_compress_carry_edited))
                                            add("~${item.tokens} tok")
                                        }.joinToString(" · ")
                                        Text(
                                            text = tags,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = { onEditItem(item) }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            HugeIcons.Edit01,
                                            contentDescription = stringResource(R.string.edit),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    IconButton(onClick = { onExcludeItem(item.nodeId) }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            HugeIcons.Cancel01,
                                            contentDescription = stringResource(R.string.chat_page_compress_carry_remove),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditCarriedOriginalDialog(
    item: CarriedOriginalItem,
    onSave: (userText: String, replyText: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var userText by remember(item) { mutableStateOf(item.userText) }
    var replyText by remember(item) { mutableStateOf(item.replyText.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_page_compress_carry_edit_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.chat_page_compress_carry_edit_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = userText,
                    onValueChange = { userText = it.take(CarriedOriginalEdit.MAX_EDIT_LENGTH) },
                    label = { Text(stringResource(R.string.chat_page_compress_carry_user_label)) },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().testTag("compress_carry_edit_user"),
                )
                if (item.replyText != null) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it.take(CarriedOriginalEdit.MAX_EDIT_LENGTH) },
                        label = { Text(stringResource(R.string.chat_page_compress_carry_reply_label)) },
                        minLines = 2,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("compress_carry_edit_reply"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(userText, if (item.replyText != null) replyText else null) },
                enabled = userText.isNotBlank(),
                modifier = Modifier.testTag("compress_carry_edit_save"),
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ConversationCompressionSettings(
    config: CompressionConfig,
    override: ConversationCompressionOverride?,
    onOverrideChange: (ConversationCompressionOverride?) -> Unit,
) {
    fun update(transform: (ConversationCompressionOverride) -> ConversationCompressionOverride) {
        onOverrideChange(transform(override ?: ConversationCompressionOverride()).takeUnless { it.isEmpty })
    }
    Text(stringResource(R.string.chat_page_compress_conversation_settings), style = MaterialTheme.typography.titleSmall)
    val follow = stringResource(R.string.compress_follow_global, compressModeName(config.mode))
    LabeledDropdown(
        label = stringResource(R.string.compress_mode_label),
        value = override?.mode?.let { compressModeName(it) } ?: follow,
        options = listOf<Pair<AutoCompressMode?, String>>(null to follow) +
            AutoCompressMode.entries.map { it to compressModeName(it) },
        onSelect = { mode -> update { it.copy(mode = mode) } },
        testTag = "compress_conversation_mode",
    )
    val followTemplate = stringResource(R.string.compress_follow_global, compressTemplateName(config, config.templateId))
    val currentTemplate = override?.templateId?.takeIf { config.templateExists(it) }
    LabeledDropdown(
        label = stringResource(R.string.chat_page_compress_default_template),
        value = currentTemplate?.let { compressTemplateName(config, it) } ?: followTemplate,
        options = listOf<Pair<String?, String>>(null to followTemplate) +
            templateIds(config).map { it to compressTemplateName(config, it) },
        onSelect = { id -> update { it.copy(templateId = id) } },
        testTag = "compress_conversation_template",
    )
}

@Composable
private fun <T> LabeledDropdown(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    testTag: String,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }, modifier = Modifier.testTag(testTag)) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                Icon(HugeIcons.ArrowDown01, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (option, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeepRecentStepper(keepRecent: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_page_compress_keep_recent),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            FilledTonalIconButton(
                onClick = { onChange((keepRecent - 1).coerceAtLeast(0)) },
                enabled = keepRecent > 0,
            ) {
                Icon(HugeIcons.Remove01, contentDescription = stringResource(R.string.chat_page_compress_keep_recent_decrease))
            }
            Text(
                text = keepRecent.toString(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 40.dp),
            )
            FilledTonalIconButton(
                onClick = { onChange((keepRecent + 1).coerceAtMost(CompressRequest.MAX_KEEP_RECENT)) },
                enabled = keepRecent < CompressRequest.MAX_KEEP_RECENT,
            ) {
                Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.chat_page_compress_keep_recent_increase))
            }
        }
        Text(
            text = stringResource(R.string.chat_page_compress_keep_recent_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EstimatePanel(estimate: CompressionEstimate?) {
    if (estimate == null) return
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("compress_estimate"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.chat_page_compress_estimate_title), style = MaterialTheme.typography.titleSmall)
            val body = MaterialTheme.typography.bodySmall
            Text(
                stringResource(
                    R.string.chat_page_compress_estimate_summarized,
                    estimate.summarizedMessages,
                    estimate.summarizedChars.formatNumber(),
                ),
                style = body,
            )
            Text(stringResource(R.string.chat_page_compress_estimate_kept, estimate.keptMessages), style = body)
            if (estimate.carriedCount > 0) {
                Text(
                    stringResource(
                        R.string.chat_page_compress_estimate_carried,
                        estimate.carriedCount,
                        estimate.carriedTokens.formatNumber(),
                    ),
                    style = body,
                )
            }
            Text(
                stringResource(
                    R.string.chat_page_compress_estimate_tokens,
                    estimate.beforeTokens.formatNumber(),
                    estimate.afterMinTokens.formatNumber(),
                    estimate.afterMaxTokens.formatNumber(),
                ),
                style = body,
            )
            estimate.budgetTokens?.let { budget ->
                Text(
                    stringResource(
                        R.string.chat_page_compress_estimate_percent,
                        (estimate.beforeTokens * 100L / budget).toInt(),
                        (estimate.afterMaxTokens * 100L / budget).toInt(),
                    ),
                    style = body,
                )
            }
            if (estimate.willChunk) {
                val exceeds = estimate.estimatedChunks > estimate.maxChunkRequests
                Text(
                    text = if (exceeds) {
                        stringResource(
                            R.string.chat_page_compress_estimate_chunk_limit_warn,
                            estimate.estimatedChunks,
                            estimate.maxChunkRequests,
                        )
                    } else {
                        stringResource(
                            R.string.chat_page_compress_estimate_chunked,
                            estimate.estimatedChunks,
                            estimate.maxChunkRequests,
                        )
                    },
                    style = body,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("compress_estimate_chunk_warn"),
                )
            } else if (estimate.willBreakCache) {
                Text(
                    text = stringResource(R.string.chat_page_compress_estimate_cache_bypassed),
                    style = body,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("compress_estimate_cache_warn"),
                )
            }
            Text(
                stringResource(
                    if (estimate.beforeExact) R.string.chat_page_compress_estimate_exact
                    else R.string.chat_page_compress_estimate_local
                ),
                style = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 将被总结的范围：首末各两条的开头，中间折成条数；以及保留的条数（预估里已有）。 */
@Composable
private fun CompressionPreview(conversation: Conversation, keepRecent: Int, fromOriginal: Boolean) {
    val range = remember(conversation.messageNodes, keepRecent, fromOriginal) {
        compressionRange(conversation.messageNodes, keepRecent, fromOriginal)?.map { it.currentMessage }.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("compress_preview")) {
        val shown = if (range.size <= 4) range else range.take(2) + range.takeLast(2)
        shown.forEachIndexed { index, message ->
            if (range.size > 4 && index == 2) {
                Text(
                    stringResource(R.string.chat_page_compress_preview_more, range.size - 4),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val who = if (message.role == MessageRole.USER) "👤" else "🤖"
            Text(
                "$who ${message.toText().replace('\n', ' ').take(80)}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
