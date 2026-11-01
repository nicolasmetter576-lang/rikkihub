package me.rerere.rikkihub.claudelife

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import me.rerere.ai.claudelife.CacheWarmStatus
import me.rerere.ai.claudelife.formatCacheWarmCountdown
import me.rerere.rikkihub.R

/**
 * 输入框 placeholder 的缓存保活状态文本。
 *
 * - [CacheWarmStatus.Scheduled] → `🔥 m:ss` 倒计时，每 250ms 用 [nowMs]（与
 *   `nextWarmAtMs` 同一单调时钟）重算真实剩余时间，绝不写入输入框文本。
 * - [CacheWarmStatus.Refreshing] → `chat_input_cache_warm_refreshing`。
 * - [CacheWarmStatus.Inactive] → `chat_input_cache_warm_inactive`（不回退到默认 placeholder）。
 */
@Composable
fun rememberCacheWarmPlaceholder(status: CacheWarmStatus, nowMs: () -> Long): String {
    val refreshingText = stringResource(R.string.chat_input_cache_warm_refreshing)
    val inactiveText = stringResource(R.string.chat_input_cache_warm_inactive)

    var remaining by remember(status) {
        mutableLongStateOf(
            if (status is CacheWarmStatus.Scheduled) status.nextWarmAtMs - nowMs() else 0L
        )
    }
    LaunchedEffect(status) {
        if (status is CacheWarmStatus.Scheduled) {
            while (true) {
                remaining = status.nextWarmAtMs - nowMs()
                delay(250)
            }
        }
    }

    return when (status) {
        is CacheWarmStatus.Scheduled -> formatCacheWarmCountdown(remaining)
        CacheWarmStatus.Refreshing -> refreshingText
        is CacheWarmStatus.Inactive -> inactiveText
    }
}
