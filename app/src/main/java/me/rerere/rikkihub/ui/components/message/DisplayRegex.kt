package me.rerere.rikkihub.ui.components.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.ai.core.MessageRole
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.ai.tavern.TavernRegexes
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import org.koin.compose.koinInject

/**
 * 当前渲染的消息离聊天末尾几条（最新一条是 0）。聊天列表在每条消息外面提供；没有提供（分享、预览……）就不按深度过滤。
 * 「仅显示」脚本的深度范围按它判断。
 */
val LocalMessageDepthFromEnd = compositionLocalOf<Int?> { null }

/** 当前会话绑定的角色（及其生效的角色书、卡内正则是否放行）；聊天页提供，没有角色或不在聊天页是 null。 */
val LocalActiveCharacter = compositionLocalOf<ActiveCharacter?> { null }

/**
 * 渲染用的文本：跑「仅显示」（markdownOnly）正则，不改存储。没有这类脚本时原样返回，不起协程。
 * 脚本是 JS 正则（QuickJS），所以结果异步给出：先显示原文，算完替换成处理后的文本。
 */
@Composable
fun rememberRegexedText(
    text: String,
    role: MessageRole,
    settings: Settings,
    assistant: Assistant?,
    isReasoning: Boolean = false,
    depthFromEnd: Int? = LocalMessageDepthFromEnd.current,
    character: ActiveCharacter? = LocalActiveCharacter.current,
): String {
    val runtime = koinInject<TavernRuntime>()
    val regexes = remember(runtime) { TavernRegexes(runtime) }
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(text, role, settings.tavern.regexScripts, assistant?.promptPreset, isReasoning, depthFromEnd, character?.regexScripts, character?.name) {
        shown = if (regexes.hasDisplayScripts(settings, assistant, character)) {
            regexes.display(text, role, depth = depthFromEnd, settings = settings, assistant = assistant, isReasoning = isReasoning, character = character)
        } else {
            text
        }
    }
    // 文本变了而异步结果还没出来的那一帧，没有脚本就直接用新文本，避免多显示一帧旧内容
    return if (shown == text || regexes.hasDisplayScripts(settings, assistant, character)) shown else text
}
