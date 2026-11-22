package me.rerere.tavern.worldinfo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 一条计时效果，对应 `chat_metadata.timedWorldInfo.<sticky|cooldown>[键]`。
 * ST:public/scripts/world-info.js:144 WITimedEffect
 */
@Serializable
data class TimedEffect(
    /** 触发该效果时条目的哈希（见 [stringHash]）。 */
    val hash: Long,
    /** 效果开始时的聊天长度。 */
    val start: Int,
    /** 效果结束时的聊天长度（含）。 */
    val end: Int,
    /** 受保护的效果在聊天没有前进时也不会被清掉（粘滞结束后自动挂上的冷却）。 */
    @SerialName("protected") val isProtected: Boolean = false,
)

/**
 * 粘滞/冷却的持久状态，键是 `"<书名>.<uid>"`，结构和失效规则照上游 `chat_metadata.timedWorldInfo`。
 * 延迟（delay）不存状态：它只由聊天长度决定。
 */
@Serializable
data class TimedEffectsState(
    val sticky: Map<String, TimedEffect> = emptyMap(),
    val cooldown: Map<String, TimedEffect> = emptyMap(),
) {
    fun toJson(): JsonObject = codec.encodeToJsonElement(serializer(), this) as JsonObject

    companion object {
        val EMPTY = TimedEffectsState()
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * 从 `timedWorldInfo` 元数据读取；缺失或格式不对的条目丢弃，
         * 对应 ST:public/scripts/world-info.js:546 ensureChatMetadata 的清理。
         */
        fun fromJson(element: JsonElement?): TimedEffectsState {
            val obj = element as? JsonObject ?: return EMPTY
            fun read(name: String): Map<String, TimedEffect> {
                val m = obj[name] as? JsonObject ?: return emptyMap()
                val out = LinkedHashMap<String, TimedEffect>()
                for ((k, v) in m) {
                    val effect = runCatching { codec.decodeFromJsonElement(TimedEffect.serializer(), v) }.getOrNull()
                    if (effect != null) out[k] = effect
                }
                return out
            }
            return TimedEffectsState(read("sticky"), read("cooldown"))
        }
    }
}

/** 条目的计时键：`ST:public/scripts/world-info.js:575` #getEntryKey。 */
internal fun timedKey(world: String, uid: Int): String = "$world.$uid"

/**
 * 一次扫描内的计时效果管理，对应 `ST:public/scripts/world-info.js:479-790` WorldInfoTimedEffects。
 * 状态通过 [initial] 传入，用 [state] 取回；不碰任何全局变量。
 */
internal class WorldInfoTimedEffects(
    private val chatLength: Int,
    private val entries: List<ScanEntry>,
    private val isDryRun: Boolean,
    initial: TimedEffectsState,
) {
    private val sticky = LinkedHashMap(initial.sticky)
    private val cooldown = LinkedHashMap(initial.cooldown)

    private val bufferSticky = ArrayList<ScanEntry>()
    private val bufferCooldown = ArrayList<ScanEntry>()
    private val bufferDelay = ArrayList<ScanEntry>()

    fun state(): TimedEffectsState = TimedEffectsState(LinkedHashMap(sticky), LinkedHashMap(cooldown))

    private fun effectFor(entry: ScanEntry, length: Int, isProtected: Boolean) = TimedEffect(
        hash = entry.hash,
        start = chatLength,
        end = chatLength + length,
        isProtected = isProtected,
    )

    /**
     * ST:public/scripts/world-info.js:589 #checkTimedEffectOfType
     * @param onEnded 效果自然结束时的回调（仅粘滞用：立刻挂上受保护的冷却）
     */
    private fun checkTimedEffectOfType(
        map: MutableMap<String, TimedEffect>,
        buffer: MutableList<ScanEntry>,
        select: (ScanEntry) -> Int?,
        onEnded: ((ScanEntry) -> Unit)?,
    ) {
        // Object.entries(...) 是快照：回调里新加的冷却也在冷却那一轮的快照里（因为冷却的快照在粘滞处理之后才取）。
        for ((key, value) in map.entries.toList()) {
            val entry = entries.firstOrNull { it.hash == value.hash }

            if (chatLength <= value.start && !value.isProtected) {
                map.remove(key)
                continue
            }

            // 找不到条目（可能属于别的角色的世界书）
            if (entry == null) {
                if (chatLength >= value.end) map.remove(key)
                continue
            }

            // 条目已不再配置该效果
            val configured = select(entry)
            if (configured == null || configured == 0) {
                map.remove(key)
                continue
            }

            if (chatLength >= value.end) {
                map.remove(key)
                onEnded?.invoke(entry)
                continue
            }

            buffer += entry
        }
    }

    /** ST:public/scripts/world-info.js:645 #checkDelayEffect */
    private fun checkDelayEffect() {
        for (entry in entries) {
            val delay = entry.entry.delay
            if (delay == null || delay == 0) continue
            if (chatLength < delay) bufferDelay += entry
        }
    }

    /** ST:public/scripts/world-info.js:664 checkTimedEffects */
    fun checkTimedEffects() {
        if (!isDryRun) {
            checkTimedEffectOfType(
                sticky, bufferSticky, { it.entry.sticky },
                onEnded = { entry ->
                    // 粘滞结束：如果条目有冷却，立刻挂上受保护的冷却，并在本次评估里生效。
                    val cd = entry.entry.cooldown
                    if (cd != null && cd != 0) {
                        cooldown[timedKey(entry.world, entry.entry.uid)] = effectFor(entry, cd, true)
                        bufferCooldown += entry
                    }
                },
            )
            checkTimedEffectOfType(cooldown, bufferCooldown, { it.entry.cooldown }, onEnded = null)
        }
        checkDelayEffect()
    }

    /** ST:public/scripts/world-info.js:735 #setTimedEffectOfType */
    private fun setTimedEffectOfType(map: MutableMap<String, TimedEffect>, entry: ScanEntry, length: Int?) {
        if (length == null || length == 0) return
        val key = timedKey(entry.world, entry.entry.uid)
        if (!map.containsKey(key)) map[key] = effectFor(entry, length, false)
    }

    /** ST:public/scripts/world-info.js:753 setTimedEffects */
    fun setTimedEffects(activated: Collection<ScanEntry>) {
        if (isDryRun) return
        for (entry in activated) {
            setTimedEffectOfType(sticky, entry, entry.entry.sticky)
            setTimedEffectOfType(cooldown, entry, entry.entry.cooldown)
        }
    }

    /** ST:public/scripts/world-info.js:785 isEffectActive */
    fun isStickyActive(entry: ScanEntry): Boolean = bufferSticky.any { it.hash == entry.hash }
    fun isCooldownActive(entry: ScanEntry): Boolean = bufferCooldown.any { it.hash == entry.hash }
    fun isDelayActive(entry: ScanEntry): Boolean = bufferDelay.any { it.hash == entry.hash }
}
