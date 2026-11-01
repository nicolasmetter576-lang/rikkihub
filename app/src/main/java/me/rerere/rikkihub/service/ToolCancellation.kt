package me.rerere.rikkihub.service

import me.rerere.ai.ui.UIMessagePart
import kotlin.time.Instant

/**
 * 用户停止生成时，还没有结果的工具调用的收尾：写入「已取消」的结果。
 * 已经开始执行的（有 `startedAt`）耗时算到取消为止；没开始的不计时。
 */
internal fun UIMessagePart.Tool.cancelledByUser(now: Instant): UIMessagePart.Tool = copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
        )
    ),
    finishedAt = finishedAt ?: startedAt?.let { now },
)
