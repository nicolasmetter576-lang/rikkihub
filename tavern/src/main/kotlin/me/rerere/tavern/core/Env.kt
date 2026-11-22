package me.rerere.tavern.core

import java.time.ZoneId

/** 时钟由调用方注入：差分测试固定它，宏 `{{time}}` 等才可重复。 */
interface TavernClock {
    fun nowMillis(): Long
    val zone: ZoneId
}

object SystemTavernClock : TavernClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override val zone: ZoneId get() = ZoneId.systemDefault()
}

class FixedTavernClock(private val millis: Long, override val zone: ZoneId = ZoneId.of("UTC")) : TavernClock {
    override fun nowMillis(): Long = millis
}

/** 随机数由调用方注入（对应 JS 的 Math.random），测试里固定。 */
fun interface TavernRandom {
    /** 返回 [0, 1) */
    fun nextDouble(): Double
}

object SystemTavernRandom : TavernRandom {
    override fun nextDouble(): Double = Math.random()
}
