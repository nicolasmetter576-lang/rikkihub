package me.rerere.rikkihub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.isEmptyUIMessage
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.cli.displayModelOrNull
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Avatar
import me.rerere.rikkihub.ui.components.ui.AutoAIIcon
import me.rerere.rikkihub.ui.components.ui.UIAvatar
import me.rerere.rikkihub.ui.pages.character.CharacterAvatar
import me.rerere.rikkihub.ui.context.LocalSettings

@Composable
fun ChatMessageUserAvatar(
    message: UIMessage,
    avatar: Avatar,
    nickname: String,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    if (message.role == MessageRole.USER && !message.parts.isEmptyUIMessage() && settings.displaySetting.showUserAvatar) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = nickname.ifEmpty { stringResource(R.string.user_default_name) },
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
            UIAvatar(
                name = nickname,
                modifier = Modifier.size(28.dp),
                value = avatar,
                loading = false,
            )
        }
    }
}

@Composable
fun ChatMessageAssistantAvatar(
    message: UIMessage,
    loading: Boolean,
    model: Model?,
    assistant: Assistant?,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val resolvedModel = model ?: message.displayModelOrNull(settings.providers)
    val showIcon = settings.displaySetting.showModelIcon
    val useAssistantAvatar = assistant?.useAssistantAvatar == true
    val character = LocalActiveCharacter.current
    if (message.role == MessageRole.ASSISTANT && character != null) {
        // 和角色聊天：头像与名字是角色的，不是模型的
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier,
        ) {
            if (showIcon) CharacterAvatar(character.id, character.name, character.record.avatarPath, size = 28.dp)
            if (settings.displaySetting.showModelName) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MessageTitleLabel(message.title, Modifier.weight(1f, fill = false))
        }
    } else if (message.role == MessageRole.ASSISTANT && (resolvedModel != null || useAssistantAvatar)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
        ) {
            if (useAssistantAvatar) {
                if (showIcon) {
                    UIAvatar(
                        name = assistant.name,
                        modifier = Modifier.size(28.dp),
                        value = assistant.avatar,
                        loading = loading,
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = assistant.name.ifEmpty { stringResource(R.string.assistant_page_default_assistant) },
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 1,
                        )
                    }
                    MessageTitleLabel(message.title, Modifier.weight(1f, fill = false))
                }
            } else if (resolvedModel != null) {
                if (showIcon) {
                    AutoAIIcon(
                        name = resolvedModel.modelId,
                        modifier = Modifier.size(28.dp),
                        loading = loading
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (settings.displaySetting.showModelName) {
                        Text(
                            text = resolvedModel.displayName,
                            style = MaterialTheme.typography.labelLargeEmphasized,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MessageTitleLabel(message.title, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/** 每轮标题：模型名右侧的浅色小字。 */
@Composable
private fun MessageTitleLabel(title: String?, modifier: Modifier = Modifier) {
    if (title.isNullOrBlank()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
