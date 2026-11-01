package me.rerere.ai.claudelife

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * 时钟/延迟 seam：生产实现 = SystemClock.elapsedRealtime + kotlinx delay；
 * 测试注入假钟手动推进（仓库无 kotlinx-coroutines-test，不靠真实 sleep）。
 */
interface WarmTicker {
    /** 单调时钟毫秒值（生产为 elapsedRealtime）。 */
    fun nowMs(): Long

    /** 挂起延迟；取消必须可中断（CancellationException 向上传播）。 */
    suspend fun delayMs(ms: Long)
}

/** 生产实现：SystemClock.elapsedRealtime + kotlinx.coroutines.delay。 */
class SystemWarmTicker : WarmTicker {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()

    override suspend fun delayMs(ms: Long) {
        if (ms > 0) delay(ms)
    }
}
