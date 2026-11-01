package me.rerere.rikkihub.claudelife

import me.rerere.ai.claudelife.SystemWarmTicker
import me.rerere.ai.claudelife.WarmTicker

/**
 * claudelife :app 侧生产时钟：:ai 的 [SystemWarmTicker]（SystemClock.elapsedRealtime +
 * kotlinx delay，单调、含深睡流逝时间）。放 :ai 使 [me.rerere.ai.claudelife.CacheWarmer]
 * 单测可注入 FakeWarmTicker。
 *
 * 后台能力边界：delay 不持唤醒锁；前台精准，退后台进程存活时大概率继续，
 * 锁屏/doze 推迟（晚于计划 15 秒即结束任务），进程被杀即停。
 */
internal fun productionWarmTicker(): WarmTicker = SystemWarmTicker()
