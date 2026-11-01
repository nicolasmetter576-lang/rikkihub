package me.rerere.rikkihub.data.ai.transformers

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.utils.toLocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.time.toJavaInstant

/**
 * 时间提醒注入转换器
 *
 * 在时间间隔较大的消息之前自动注入 <time_reminder>，帮助 AI 了解对话的时间间隔
 */
object TimeReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (!ctx.assistant.enableTimeReminder) return messages
        return applyTimeReminder(messages, ctx.assistant.timeReminderIntervalMinutes)
    }
}

internal fun applyTimeReminder(
    messages: List<UIMessage>,
    intervalMinutes: Int = 60,
): List<UIMessage> {
    val thresholdSeconds = intervalMinutes.coerceAtLeast(1).toLong() * 60
    val result = mutableListOf<UIMessage>()
    val tz = TimeZone.currentSystemDefault()

    // gap 按「上一条用户消息」计算，不受中间的助手回复或合成消息干扰
    var lastUserInstant: Instant? = null
    for (current in messages) {
        if (current.role == MessageRole.USER && !current.isSynthetic) {
            val currInstant = current.createdAt.toInstant(tz)
            val prevInstant = lastUserInstant
            if (prevInstant == null) {
                result.add(buildTimeReminderMessage(null, currInstant))
            } else {
                val gapSeconds = (currInstant - prevInstant).inWholeSeconds
                if (gapSeconds > thresholdSeconds) {
                    result.add(buildTimeReminderMessage(gapSeconds, currInstant))
                }
            }
            lastUserInstant = currInstant
        }
        result.add(current)
    }

    return result
}

private fun buildTimeReminderMessage(gapSeconds: Long?, instant: Instant): UIMessage {
    val javaInstant = instant.toJavaInstant()
    val dayOfWeek = javaInstant.atZone(ZoneId.systemDefault()).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
    val timeStr = javaInstant.toLocalDateTime()
    val content = if (gapSeconds != null) {
        val gapText = formatGap(gapSeconds)
        "<time_reminder>Current time: $dayOfWeek, $timeStr ($gapText since last message)</time_reminder>"
    } else {
        "<time_reminder>Current time: $dayOfWeek, $timeStr</time_reminder>"
    }
    return UIMessage.user(content).copy(isSynthetic = true)
}

private fun formatGap(seconds: Long): String {
    return when {
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h"
        else -> "${seconds / 86400} d"
    }
}
