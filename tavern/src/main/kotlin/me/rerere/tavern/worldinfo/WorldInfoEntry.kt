package me.rerere.tavern.worldinfo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `ST:public/scripts/world-info.js:855` world_info_position。 */
object WorldInfoPosition {
    const val BEFORE = 0
    const val AFTER = 1
    const val AN_TOP = 2
    const val AN_BOTTOM = 3
    const val AT_DEPTH = 4
    const val EM_TOP = 5
    const val EM_BOTTOM = 6
    const val OUTLET = 7
}

/** `ST:public/scripts/world-info.js:33` world_info_logic。 */
object WorldInfoLogic {
    const val AND_ANY = 0
    const val NOT_ALL = 1
    const val NOT_ANY = 2
    const val AND_ALL = 3
}

/** `ST:public/scripts/world-info.js:27` world_info_insertion_strategy。 */
object WorldInfoInsertionStrategy {
    const val EVENLY = 0
    const val CHARACTER_FIRST = 1
    const val GLOBAL_FIRST = 2
}

/** `ST:public/scripts/world-info.js:96-98`。 */
const val WI_DEFAULT_DEPTH = 4
const val WI_DEFAULT_WEIGHT = 100
const val WI_MAX_SCAN_DEPTH = 1000

/** 条目上的角色过滤（`characterFilter`），`ST:public/scripts/world-info.js:2125`、`:3136`。 */
data class WorldInfoCharacterFilter(
    val isExclude: Boolean = false,
    /** 角色卡文件名（不含扩展名）。 */
    val names: List<String> = emptyList(),
    /** 标签 id。 */
    val tags: List<String> = emptyList(),
)

/**
 * 世界书条目，字段名和默认值照 `ST:public/scripts/world-info.js:4082` newWorldInfoEntryDefinition。
 *
 * 上游那张表里有 42 个键，其中 `characterFilterNames/Tags/Exclude` 三个只是编辑器的虚拟字段
 * （`excludeFromTemplate`），文件里实际落盘的是一个 `characterFilter` 对象，所以这里是
 * 39 个模板字段 + `uid` + `displayIndex` + `characterFilter`，其余未知键放进 [extra]。
 *
 * 数值字段统一用 Int；允许 null 的字段（`scanDepth`、`caseSensitive` ... `role`）用可空类型。
 * [layout] 记录解码时的键顺序和“值与规范写法不同”的原始 JSON（如 `delayUntilRecursion: false`、`role: null`），
 * 编码时只要字段的类型化值没变就原样写回，保证往返无损。
 */
data class WorldInfoEntry(
    val uid: Int = 0,
    val key: List<String> = emptyList(),
    val keysecondary: List<String> = emptyList(),
    val comment: String = "",
    val content: String = "",
    val constant: Boolean = false,
    val vectorized: Boolean = false,
    val selective: Boolean = true,
    val selectiveLogic: Int = WorldInfoLogic.AND_ANY,
    val addMemo: Boolean = false,
    val order: Int = 100,
    val position: Int = WorldInfoPosition.BEFORE,
    val disable: Boolean = false,
    val ignoreBudget: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val matchPersonaDescription: Boolean = false,
    val matchCharacterDescription: Boolean = false,
    val matchCharacterPersonality: Boolean = false,
    val matchCharacterDepthPrompt: Boolean = false,
    val matchScenario: Boolean = false,
    val matchCreatorNotes: Boolean = false,
    /** 0 = 不延迟；数字 n = 延迟到第 n 级递归；上游旧数据里的 true/false 解码成 1/0。 */
    val delayUntilRecursion: Int = 0,
    val probability: Int = 100,
    val useProbability: Boolean = true,
    val depth: Int = WI_DEFAULT_DEPTH,
    val outletName: String = "",
    val group: String = "",
    val groupOverride: Boolean = false,
    val groupWeight: Int = WI_DEFAULT_WEIGHT,
    val scanDepth: Int? = null,
    val caseSensitive: Boolean? = null,
    val matchWholeWords: Boolean? = null,
    val useGroupScoring: Boolean? = null,
    val automationId: String = "",
    /** `extension_prompt_roles`：0 系统 / 1 用户 / 2 助手；null 按系统处理。 */
    val role: Int? = 0,
    val sticky: Int? = null,
    val cooldown: Int? = null,
    val delay: Int? = null,
    val triggers: List<String> = emptyList(),
    val characterFilter: WorldInfoCharacterFilter? = null,
    val displayIndex: Int? = null,
    /** 未知键（如 convertCharacterBook 带进来的 `extensions`），原样保留。 */
    val extra: JsonObject = JsonObject(emptyMap()),
    /** 解码时的键顺序与非规范写法，见类注释。新建的条目为 null。 */
    val layout: EntryLayout? = null,
)

/** 条目 JSON 的原貌：键顺序，以及与规范写法不同的原始值。 */
data class EntryLayout(
    val keyOrder: List<String>,
    val raw: Map<String, JsonElement>,
)

/** 所有已知键，顺序即上游模板顺序（`uid` 在最前）。 */
internal val WORLD_INFO_ENTRY_KEYS: List<String> = listOf(
    "uid", "key", "keysecondary", "comment", "content", "constant", "vectorized", "selective",
    "selectiveLogic", "addMemo", "order", "position", "disable", "ignoreBudget", "excludeRecursion",
    "preventRecursion", "matchPersonaDescription", "matchCharacterDescription",
    "matchCharacterPersonality", "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes",
    "delayUntilRecursion", "probability", "useProbability", "depth", "outletName", "group",
    "groupOverride", "groupWeight", "scanDepth", "caseSensitive", "matchWholeWords", "useGroupScoring",
    "automationId", "role", "sticky", "cooldown", "delay", "triggers", "displayIndex", "characterFilter",
)
