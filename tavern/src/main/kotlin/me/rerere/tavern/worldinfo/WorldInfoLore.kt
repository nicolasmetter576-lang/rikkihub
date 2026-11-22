package me.rerere.tavern.worldinfo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 一本命名的世界书（名字就是条目的 `world`，也是计时效果键的前缀）。 */
data class WorldInfoBookRef(val name: String, val book: WorldInfoBook)

/**
 * 本回合参与扫描的世界书，按来源分组，对应 `getSortedEntries` 里的 globalLore / characterLore / chatLore / personaLore。
 * 同名的书只会按最高优先的来源出现一次（见 [resolveEntries]）。
 */
data class WorldInfoBooks(
    /** 全局选中的书（`selected_world_info`），保持选择顺序。 */
    val global: List<WorldInfoBookRef> = emptyList(),
    /** 角色书：主世界书在前，附加书在后（`getCharacterLore` 的 worldsToSearch 顺序）。 */
    val character: List<WorldInfoBookRef> = emptyList(),
    /** 聊天书（`chat_metadata.world_info`）。 */
    val chat: WorldInfoBookRef? = null,
    /** 用户设定绑定的书（`power_user.persona_description_lorebook`）。 */
    val persona: WorldInfoBookRef? = null,
)

/** 扫描时的条目工作副本：原条目 + 来源书名 + 解析出的装饰器 + 求值后的内容 + 哈希。按引用区分，不要用 equals。 */
internal class ScanEntry(
    val entry: WorldInfoEntry,
    val world: String,
    val decorators: List<String>,
    var content: String,
    val hash: Long,
)

private val KNOWN_DECORATORS = listOf("@@activate", "@@dont_activate")

/**
 * ST:public/scripts/world-info.js:4652 parseDecorators
 * 内容以 `@@` 开头时，逐行取出已知装饰器，返回（装饰器列表，去掉装饰器后的内容）。
 * 注意上游的怪行为：整段内容都是装饰器行时，内容保持原样不被清空。
 */
internal fun parseDecorators(content: String): Pair<List<String>, String> {
    if (!content.startsWith("@@")) return emptyList<String>() to content

    fun isKnown(line: String): Boolean {
        val data = if (line.startsWith("@@@")) line.substring(1) else line
        return KNOWN_DECORATORS.any { data.startsWith(it) }
    }

    var newContent = content
    val lines = content.split("\n")
    val decorators = ArrayList<String>()
    var fallbacked = false
    for (i in lines.indices) {
        val line = lines[i]
        if (line.startsWith("@@")) {
            if (line.startsWith("@@@") && !fallbacked) continue
            if (isKnown(line)) {
                decorators += if (line.startsWith("@@@")) line.substring(1) else line
                fallbacked = false
            } else {
                fallbacked = true
            }
        } else {
            newContent = lines.subList(i, lines.size).joinToString("\n")
            break
        }
    }
    return decorators to newContent
}

/**
 * 条目哈希：`getStringHash(JSON.stringify({uid, world, ...rest, decorators}))`，
 * 其中 content 是去掉装饰器后的内容。ST:public/scripts/world-info.js:4631-4640
 */
internal fun entryHash(entry: WorldInfoEntry, world: String, decorators: List<String>, content: String): Long {
    val encoded = WorldInfoCodec.encodeEntry(entry)
    val m = LinkedHashMap<String, JsonElement>()
    m["uid"] = encoded["uid"] ?: JsonPrimitive(entry.uid)
    m["world"] = JsonPrimitive(world)
    for ((k, v) in encoded) if (k != "uid") m[k] = v
    m["decorators"] = JsonArray(decorators.map { JsonPrimitive(it) })
    m["content"] = JsonPrimitive(content)
    return stringHash(jsStringify(JsonObject(m)))
}

/**
 * ST:public/scripts/world-info.js:4590 getSortedEntries
 * 按来源去重（全局 > 聊天 > 用户设定 > 角色），再按插入策略排序：聊天书、用户设定书总在最前，
 * 然后是角色书/全局书（顺序由 [strategy] 决定），每组内按 order 降序（稳定）。
 */
fun WorldInfoBooks.resolveEntries(strategy: Int): List<ResolvedWorldInfoEntry> =
    resolveScanEntries(strategy).map { ResolvedWorldInfoEntry(it.world, it.entry) }

/** 参与扫描的条目及其来源书。 */
data class ResolvedWorldInfoEntry(val world: String, val entry: WorldInfoEntry)

internal fun WorldInfoBooks.resolveScanEntries(strategy: Int): List<ScanEntry> {
    val globalNames = global.map { it.name }.toSet()
    val chatName = chat?.name?.takeIf { it.isNotEmpty() }
    val personaName = persona?.name?.takeIf { it.isNotEmpty() }

    fun entriesOf(ref: WorldInfoBookRef): List<Pair<String, WorldInfoEntry>> = ref.book.entries.map { ref.name to it }

    // getGlobalLore
    val globalLore = global.flatMap { entriesOf(it) }

    // getCharacterLore：主书 + 附加书（Set 语义去重），已经在全局/聊天/用户设定书里的跳过
    val characterLore = character.distinctBy { it.name }
        .filter { it.name !in globalNames && it.name != chatName && it.name != personaName }
        .flatMap { entriesOf(it) }

    // getChatLore
    val chatLore = if (chat != null && chatName != null && chatName !in globalNames) entriesOf(chat) else emptyList()

    // getPersonaLore
    val personaLore =
        if (persona != null && personaName != null && personaName != chatName && personaName !in globalNames) entriesOf(persona) else emptyList()

    fun sorted(list: List<Pair<String, WorldInfoEntry>>) = list.sortedWith(compareByDescending { it.second.order })

    val rest = when (strategy) {
        WorldInfoInsertionStrategy.EVENLY -> sorted(globalLore + characterLore)
        WorldInfoInsertionStrategy.CHARACTER_FIRST -> sorted(characterLore) + sorted(globalLore)
        WorldInfoInsertionStrategy.GLOBAL_FIRST -> sorted(globalLore) + sorted(characterLore)
        else -> sorted(globalLore + characterLore) // 未知策略：上游报错并按 evenly
    }
    val all = sorted(chatLore) + sorted(personaLore) + rest

    return all.map { (world, entry) ->
        val (decorators, content) = parseDecorators(entry.content)
        ScanEntry(entry, world, decorators, content, entryHash(entry, world, decorators, content))
    }
}
