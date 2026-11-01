package me.rerere.rikkihub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Remove01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressState
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.ui.components.ui.RabbitLoadingIndicator

@Composable
fun CompressContextDialog(
    compressState: CompressState?,
    onDismiss: () -> Unit,
    onConfirm: (additionalPrompt: String, keepRecent: Int) -> Unit,
    onCancelCompression: () -> Unit,
) {
    var additionalPrompt by remember { mutableStateOf("") }
    // 默认全部压缩，请求前缀与对话一致，缓存命中最好
    var keepRecent by remember { mutableIntStateOf(0) }
    // Runs in the background; this dialog only starts it or offers to cancel it.
    val isRunning = compressState?.status == CompressStatus.RUNNING

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_page_compress_context_title))
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (isRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RabbitLoadingIndicator(
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.chat_page_compressing))
                    }
                    Text(
                        text = stringResource(R.string.chat_page_compress_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(stringResource(R.string.chat_page_compress_context_desc))

                    OutlinedTextField(
                        value = additionalPrompt,
                        onValueChange = { additionalPrompt = it.take(CompressRequest.MAX_ADDITIONAL_PROMPT) },
                        label = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt))
                        },
                        placeholder = {
                            Text(stringResource(R.string.chat_page_compress_additional_prompt_hint))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )

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
                                onClick = { keepRecent = (keepRecent - 1).coerceAtLeast(0) },
                                enabled = keepRecent > 0,
                            ) {
                                Icon(
                                    HugeIcons.Remove01,
                                    contentDescription = stringResource(R.string.chat_page_compress_keep_recent_decrease),
                                )
                            }
                            Text(
                                text = keepRecent.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(min = 40.dp),
                            )
                            FilledTonalIconButton(
                                onClick = { keepRecent = (keepRecent + 1).coerceAtMost(CompressRequest.MAX_KEEP_RECENT) },
                                enabled = keepRecent < CompressRequest.MAX_KEEP_RECENT,
                            ) {
                                Icon(
                                    HugeIcons.Add01,
                                    contentDescription = stringResource(R.string.chat_page_compress_keep_recent_increase),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.chat_page_compress_keep_recent_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(
                        text = stringResource(R.string.chat_page_compress_checkpoint_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (isRunning) {
                TextButton(onClick = onCancelCompression) {
                    Text(stringResource(R.string.chat_page_compress_cancel))
                }
            } else {
                TextButton(onClick = {
                    onConfirm(additionalPrompt, keepRecent)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(if (isRunning) R.string.chat_page_compress_hide else R.string.cancel))
            }
        }
    )
}
