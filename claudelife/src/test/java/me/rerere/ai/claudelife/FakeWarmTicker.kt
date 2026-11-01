package me.rerere.ai.claudelife

import kotlinx.coroutines.CompletableDeferred

/**
 * 手动推进的假钟：delayMs 挂起直到 advance 越过目标时刻或调用方取消。
 * 配合 Dispatchers.Unconfined scope 时 complete() 内联恢复等待者 → 测试确定性。
 * 不以真实 sleep 作为主要虚拟时钟。
 */
class FakeWarmTicker(startMs: Long = 0L) : WarmTicker {
    private val lock = Any()
    private var now: Long = startMs
    private val waiters = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()

    override fun nowMs(): Long = synchronized(lock) { now }

    override suspend fun delayMs(ms: Long) {
        if (ms <= 0) return
        val deferred = CompletableDeferred<Unit>()
        synchronized(lock) {
            waiters.add(now + ms to deferred)
        }
        try {
            deferred.await()
        } finally {
            synchronized(lock) {
                waiters.removeAll { it.second === deferred }
            }
        }
    }

    fun advanceBy(ms: Long) {
        advanceTo(nowMs() + ms)
    }

    fun advanceTo(targetMs: Long) {
        val fire = mutableListOf<CompletableDeferred<Unit>>()
        synchronized(lock) {
            now = targetMs
            val iterator = waiters.iterator()
            while (iterator.hasNext()) {
                val waiter = iterator.next()
                if (waiter.first <= targetMs) {
                    iterator.remove()
                    fire.add(waiter.second)
                }
            }
        }
        fire.forEach { it.complete(Unit) }
    }

    fun pendingWaiterCount(): Int = synchronized(lock) { waiters.size }
}
