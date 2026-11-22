package me.rerere.rikkihub.data.model

import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.worldinfo.WorldInfoEntry

/*
 * 世界书编辑用的纯函数：关键词的文字写法、触发类型、条目增删、搜索。App 和网页端共用这套规则。
 * 条目是酒馆的原格式（`WorldInfoEntry`），这里不改任何字段的含义，只提供编辑界面要用的读写方式。
 */

/**
 * 把一行关键词文字拆成关键词列表：逗号分隔，首尾空白去掉，空项丢掉。
 * 写成 `/模式/标志` 的正则关键词里可以带逗号（`/a,b/i`）：只有 `/…/标志` 后面紧跟逗号或结尾才算它结束，
 * 找不到合法结尾就按普通关键词处理。
 */
fun parseKeyList(text: String): List<String> {
    val keys = ArrayList<String>()
    var i = 0
    val n = text.length
    while (i < n) {
        while (i < n && (text[i] == ',' || text[i].isWhitespace())) i++
        if (i >= n) break
        var end = -1 // 关键词（含标志）的结束位置，不含
        if (text[i] == '/') {
            var slash = text.indexOf('/', i + 1)
            while (slash >= 0) {
                var flagsEnd = slash + 1
                while (flagsEnd < n && text[flagsEnd] in 'a'..'z') flagsEnd++
                var after = flagsEnd
                while (after < n && text[after].isWhitespace()) after++
                if (after >= n || text[after] == ',') {
                    end = flagsEnd
                    break
                }
                slash = text.indexOf('/', slash + 1)
            }
        }
        if (end < 0) {
            end = text.indexOf(',', i).let { if (it < 0) n else it }
        }
        val key = text.substring(i, end).trim()
        if (key.isNotEmpty()) keys += key
        i = end
    }
    return keys
}

/** [parseKeyList] 的逆：逗号加空格连接。 */
fun formatKeyList(keys: List<String>): String = keys.joinToString(", ")

/** 条目在列表里显示的标题：备注，没有就取第一个关键词。 */
val WorldInfoEntry.title: String get() = comment.ifBlank { key.firstOrNull().orEmpty() }

/** 搜索：备注、关键词、次要关键词、内容里任意一处含有 [query]（不分大小写）；空查询匹配全部。 */
fun WorldInfoEntry.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return comment.contains(q, ignoreCase = true) ||
        content.contains(q, ignoreCase = true) ||
        key.any { it.contains(q, ignoreCase = true) } ||
        keysecondary.any { it.contains(q, ignoreCase = true) }
}

fun WorldInfoEntry.withPrimaryKeys(text: String): WorldInfoEntry = copy(key = parseKeyList(text))

/** 次要关键词：写了就一并打开 `selective`（引擎只在 `selective` 为真时才用次要关键词）。 */
fun WorldInfoEntry.withSecondaryKeys(text: String): WorldInfoEntry {
    val keys = parseKeyList(text)
    return copy(keysecondary = keys, selective = if (keys.isNotEmpty()) true else selective)
}

/** 触发概率，夹在 0..100；设置它就意味着要按概率判断。 */
fun WorldInfoEntry.withProbability(percent: Int): WorldInfoEntry =
    copy(probability = percent.coerceIn(0, 100), useProbability = true)

/** 条目的触发类型；空集合 = 所有类型。认不得的类型名由 [withTriggerSet] 原样保留。 */
val WorldInfoEntry.triggerSet: Set<GenerationType>
    get() = triggers.mapNotNull { wire -> GenerationType.entries.firstOrNull { it.wire == wire } }.toSet()

/** 改触发类型；没有勾选或勾满都存成空列表（全部），认不得的类型名留在末尾。 */
fun WorldInfoEntry.withTriggerSet(selected: Set<GenerationType>): WorldInfoEntry {
    val known = GenerationType.entries.map { it.wire }.toSet()
    val unknown = triggers.filter { it !in known }
    val chosen = if (selected.size == GenerationType.entries.size) emptyList() else GenerationType.entries.filter { it in selected }.map { it.wire }
    return copy(triggers = chosen + unknown)
}

/** 三态字段（跟随全局 / 开 / 关）在界面上的三个取值。 */
enum class TriState { INHERIT, ON, OFF }

val Boolean?.triState: TriState get() = when (this) {
    null -> TriState.INHERIT
    true -> TriState.ON
    false -> TriState.OFF
}

fun TriState.toBoolean(): Boolean? = when (this) {
    TriState.INHERIT -> null
    TriState.ON -> true
    TriState.OFF -> false
}

/** 新增一条空条目：uid 取现有最大值加一（空书从 0 起）。返回新的书和新条目。 */
fun Lorebook.withNewEntry(): Pair<Lorebook, WorldInfoEntry> {
    val uid = (entries.maxOfOrNull { it.uid } ?: -1) + 1
    val entry = WorldInfoEntry(uid = uid)
    return copy(entries = entries + entry) to entry
}

/** 按 uid 改一条条目；找不到就不动。uid 不能改。 */
fun Lorebook.withEntryUpdated(uid: Int, transform: (WorldInfoEntry) -> WorldInfoEntry): Lorebook =
    copy(entries = entries.map { if (it.uid == uid) transform(it).copy(uid = uid) else it })

fun Lorebook.withoutEntry(uid: Int): Lorebook = copy(entries = entries.filter { it.uid != uid })

/** 开关全部条目（书内批量启停）。 */
fun Lorebook.withAllEntriesEnabled(enabled: Boolean): Lorebook = copy(entries = entries.map { it.copy(disable = !enabled) })
