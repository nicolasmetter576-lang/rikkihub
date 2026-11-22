package me.rerere.tavern.worldinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 世界书文件（`/api/worldinfo/get` 返回的 JSON，即 `ST:src/endpoints/worldinfo.js` 读写的格式）的解码与编码。
 *
 * 往返无损：未知键进 `extra`；键顺序、`delayUntilRecursion: false` 这类非规范写法记在 [EntryLayout] 里，
 * 只要对应字段没被改动就原样写回。
 */
object WorldInfoCodec {
    private val json = Json { prettyPrint = false }
    private val KNOWN: Set<String> = WORLD_INFO_ENTRY_KEYS.toSet()

    fun decodeBook(text: String): WorldInfoBook = decodeBook(json.parseToJsonElement(text))

    fun decodeBook(element: JsonElement): WorldInfoBook {
        val obj = element as? JsonObject ?: return WorldInfoBook()
        val entriesEl = obj["entries"]
        val entries = ArrayList<WorldInfoEntry>()
        when (entriesEl) {
            // Object.keys 的顺序：数组下标形式的键升序在前。
            is JsonObject -> jsKeyOrder(entriesEl.keys).forEachIndexed { index, k ->
                val v = entriesEl.getValue(k) as? JsonObject ?: return@forEachIndexed
                entries += decodeEntry(v, k.toIntOrNull() ?: index)
            }

            is JsonArray -> entriesEl.forEachIndexed { index, v ->
                (v as? JsonObject)?.let { entries += decodeEntry(it, index) }
            }

            else -> Unit
        }
        val extra = LinkedHashMap<String, JsonElement>()
        var name: String? = null
        var description: String? = null
        for ((k, v) in obj) {
            when {
                k == "entries" -> Unit
                k == "name" && v is JsonPrimitive && v.isString -> name = v.content
                k == "description" && v is JsonPrimitive && v.isString -> description = v.content
                else -> extra[k] = v
            }
        }
        return WorldInfoBook(entries, name, description, JsonObject(extra), obj.keys.toList())
    }

    fun encodeBook(book: WorldInfoBook): JsonObject {
        val entriesObj = JsonObject(LinkedHashMap<String, JsonElement>().also { m ->
            for (e in book.entries) m[e.uid.toString()] = encodeEntry(e)
        })
        val out = LinkedHashMap<String, JsonElement>()
        val order = book.keyOrder
        if (order == null) {
            out["entries"] = entriesObj
            book.name?.let { out["name"] = JsonPrimitive(it) }
            book.description?.let { out["description"] = JsonPrimitive(it) }
            for ((k, v) in book.extra) out.putIfAbsent(k, v)
            return JsonObject(out)
        }
        for (k in order) {
            when {
                k == "entries" -> out[k] = entriesObj
                k == "name" && book.name != null -> out[k] = JsonPrimitive(book.name)
                k == "description" && book.description != null -> out[k] = JsonPrimitive(book.description)
                else -> book.extra[k]?.let { out[k] = it }
            }
        }
        if ("entries" !in out) out["entries"] = entriesObj
        book.name?.let { out.putIfAbsent("name", JsonPrimitive(it)) }
        book.description?.let { out.putIfAbsent("description", JsonPrimitive(it)) }
        for ((k, v) in book.extra) out.putIfAbsent(k, v)
        return JsonObject(out)
    }

    fun encodeBookToString(book: WorldInfoBook): String = encodeBook(book).toString()

    // ---------------------------------------------------------------- 条目

    /**
     * ST:public/scripts/world-info.js:2104 addMissingWorldInfoFields 的宽松版：缺失字段取模板默认值，
     * 其余按 JS 的真值/ToNumber 规则读取（上游 JSON 里 `role: null`、`delayUntilRecursion: false` 都是常见写法）。
     */
    fun decodeEntry(obj: JsonObject, fallbackUid: Int = 0): WorldInfoEntry {
        fun bool(k: String, d: Boolean): Boolean = obj[k]?.isJsTruthy() ?: d
        fun boolOrNull(k: String): Boolean? = obj[k]?.let { if (it is JsonNull) null else it.isJsTruthy() }
        fun int(k: String, d: Int, nullValue: Int = 0): Int =
            obj[k]?.let { if (it is JsonNull) nullValue else numberToInt(it, d) } ?: d
        fun intOrNull(k: String): Int? = obj[k]?.let { if (it is JsonNull) null else toIntLenient(it, null) }
        fun str(k: String, d: String = ""): String = obj[k]?.let { scalarToString(it) } ?: d
        fun strings(k: String): List<String> = (obj[k] as? JsonArray)?.mapNotNull { scalarToString(it) } ?: emptyList()

        // `entry.disable == true`：宽松相等，true / 1 / "1" 为真。
        val disable = obj["disable"]?.let { el ->
            el is JsonPrimitive && el !is JsonNull &&
                if (el.isString) el.content == "1" else el.content == "true" || el.content.toDoubleOrNull() == 1.0
        } ?: false

        // delayUntilRecursion：true → 1，false → 0，数字照原值。
        val delayUntilRecursion = obj["delayUntilRecursion"]?.let { numberToInt(it, 0) } ?: 0

        val characterFilter = (obj["characterFilter"] as? JsonObject)?.let { f ->
            WorldInfoCharacterFilter(
                isExclude = f["isExclude"].isJsTruthy(),
                names = (f["names"] as? JsonArray)?.mapNotNull { scalarToString(it) } ?: emptyList(),
                tags = (f["tags"] as? JsonArray)?.mapNotNull { scalarToString(it) } ?: emptyList(),
            )
        }

        val extra = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) if (k !in KNOWN) extra[k] = v

        val base = WorldInfoEntry(
            uid = obj["uid"]?.let { numberToInt(it, fallbackUid) } ?: fallbackUid,
            key = strings("key"),
            keysecondary = strings("keysecondary"),
            comment = str("comment"),
            content = str("content"),
            constant = bool("constant", false),
            vectorized = bool("vectorized", false),
            selective = bool("selective", true),
            selectiveLogic = int("selectiveLogic", WorldInfoLogic.AND_ANY),
            addMemo = bool("addMemo", false),
            order = int("order", 100),
            position = int("position", WorldInfoPosition.BEFORE),
            disable = disable,
            ignoreBudget = bool("ignoreBudget", false),
            excludeRecursion = bool("excludeRecursion", false),
            preventRecursion = bool("preventRecursion", false),
            matchPersonaDescription = bool("matchPersonaDescription", false),
            matchCharacterDescription = bool("matchCharacterDescription", false),
            matchCharacterPersonality = bool("matchCharacterPersonality", false),
            matchCharacterDepthPrompt = bool("matchCharacterDepthPrompt", false),
            matchScenario = bool("matchScenario", false),
            matchCreatorNotes = bool("matchCreatorNotes", false),
            delayUntilRecursion = delayUntilRecursion,
            probability = int("probability", 100),
            useProbability = bool("useProbability", true),
            // `entry.depth ?? DEFAULT_DEPTH`、`entry.groupWeight ?? DEFAULT_WEIGHT`：null 取默认值。
            depth = int("depth", WI_DEFAULT_DEPTH, nullValue = WI_DEFAULT_DEPTH),
            outletName = str("outletName"),
            group = str("group"),
            groupOverride = bool("groupOverride", false),
            groupWeight = int("groupWeight", WI_DEFAULT_WEIGHT, nullValue = WI_DEFAULT_WEIGHT),
            scanDepth = intOrNull("scanDepth"),
            caseSensitive = boolOrNull("caseSensitive"),
            matchWholeWords = boolOrNull("matchWholeWords"),
            useGroupScoring = boolOrNull("useGroupScoring"),
            automationId = str("automationId"),
            role = if (obj.containsKey("role")) intOrNull("role") else 0,
            sticky = intOrNull("sticky"),
            cooldown = intOrNull("cooldown"),
            delay = intOrNull("delay"),
            triggers = strings("triggers"),
            characterFilter = characterFilter,
            displayIndex = intOrNull("displayIndex"),
            extra = JsonObject(extra),
        )

        val canon = canonicalFields(base)
        val raw = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) if (k in KNOWN && canon[k] != v) raw[k] = v
        return base.copy(layout = EntryLayout(obj.keys.toList(), raw))
    }

    fun encodeEntry(e: WorldInfoEntry): JsonObject {
        val canon = canonicalFields(e)
        val out = LinkedHashMap<String, JsonElement>()
        val layout = e.layout
        if (layout == null) {
            for (k in WORLD_INFO_ENTRY_KEYS) canon[k]?.let { out[k] = it }
            for ((k, v) in e.extra) out.putIfAbsent(k, v)
            return JsonObject(out)
        }
        for (k in layout.keyOrder) {
            if (k in KNOWN) {
                val raw = layout.raw[k]
                if (raw != null && rawStillValid(k, raw, e, canon)) out[k] = raw
                else canon[k]?.let { out[k] = it }
            } else {
                e.extra[k]?.let { out[k] = it }
            }
        }
        // 解码后才改动（或新增）的字段：与默认值不同才写出，保持“原本没有的键不凭空出现”。
        val defaults = canonicalFields(WorldInfoEntry(uid = e.uid))
        for (k in WORLD_INFO_ENTRY_KEYS) {
            if (k in out) continue
            val c = canon[k] ?: continue
            if (k == "uid" || c != defaults[k]) out[k] = c
        }
        for ((k, v) in e.extra) out.putIfAbsent(k, v)
        return JsonObject(out)
    }

    /** 原始值解码后仍等于当前类型化值，才可以原样写回。 */
    private fun rawStillValid(k: String, raw: JsonElement, e: WorldInfoEntry, canon: Map<String, JsonElement?>): Boolean {
        val probe = decodeEntry(JsonObject(mapOf("uid" to JsonPrimitive(e.uid), k to raw)), e.uid)
        return canonicalFields(probe)[k] == canon[k]
    }

    /** 规范写法下每个已知键的值；null 表示该键不写出（`displayIndex`、`characterFilter` 为空时）。 */
    private fun canonicalFields(e: WorldInfoEntry): Map<String, JsonElement?> {
        fun s(v: String): JsonElement = JsonPrimitive(v)
        fun b(v: Boolean): JsonElement = JsonPrimitive(v)
        fun n(v: Int): JsonElement = JsonPrimitive(v)
        fun nOrNull(v: Int?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
        fun bOrNull(v: Boolean?): JsonElement = if (v == null) JsonNull else JsonPrimitive(v)
        fun list(v: List<String>): JsonElement = JsonArray(v.map { JsonPrimitive(it) })
        val m = LinkedHashMap<String, JsonElement?>()
        m["uid"] = n(e.uid)
        m["key"] = list(e.key)
        m["keysecondary"] = list(e.keysecondary)
        m["comment"] = s(e.comment)
        m["content"] = s(e.content)
        m["constant"] = b(e.constant)
        m["vectorized"] = b(e.vectorized)
        m["selective"] = b(e.selective)
        m["selectiveLogic"] = n(e.selectiveLogic)
        m["addMemo"] = b(e.addMemo)
        m["order"] = n(e.order)
        m["position"] = n(e.position)
        m["disable"] = b(e.disable)
        m["ignoreBudget"] = b(e.ignoreBudget)
        m["excludeRecursion"] = b(e.excludeRecursion)
        m["preventRecursion"] = b(e.preventRecursion)
        m["matchPersonaDescription"] = b(e.matchPersonaDescription)
        m["matchCharacterDescription"] = b(e.matchCharacterDescription)
        m["matchCharacterPersonality"] = b(e.matchCharacterPersonality)
        m["matchCharacterDepthPrompt"] = b(e.matchCharacterDepthPrompt)
        m["matchScenario"] = b(e.matchScenario)
        m["matchCreatorNotes"] = b(e.matchCreatorNotes)
        m["delayUntilRecursion"] = n(e.delayUntilRecursion)
        m["probability"] = n(e.probability)
        m["useProbability"] = b(e.useProbability)
        m["depth"] = n(e.depth)
        m["outletName"] = s(e.outletName)
        m["group"] = s(e.group)
        m["groupOverride"] = b(e.groupOverride)
        m["groupWeight"] = n(e.groupWeight)
        m["scanDepth"] = nOrNull(e.scanDepth)
        m["caseSensitive"] = bOrNull(e.caseSensitive)
        m["matchWholeWords"] = bOrNull(e.matchWholeWords)
        m["useGroupScoring"] = bOrNull(e.useGroupScoring)
        m["automationId"] = s(e.automationId)
        m["role"] = nOrNull(e.role)
        m["sticky"] = nOrNull(e.sticky)
        m["cooldown"] = nOrNull(e.cooldown)
        m["delay"] = nOrNull(e.delay)
        m["triggers"] = list(e.triggers)
        m["displayIndex"] = e.displayIndex?.let { n(it) }
        m["characterFilter"] = e.characterFilter?.let {
            JsonObject(
                linkedMapOf(
                    "isExclude" to b(it.isExclude),
                    "names" to list(it.names),
                    "tags" to list(it.tags),
                ),
            )
        }
        return m
    }

    private fun scalarToString(el: JsonElement): String? = when {
        el is JsonPrimitive && el.isString -> el.content
        el is JsonPrimitive && el !is JsonNull -> when (el.content) {
            "true", "false" -> el.content
            else -> jsNumberToString(el.content.toDoubleOrNull() ?: return null)
        }

        else -> null
    }

    /** 数字字段：JS 的 ToNumber 后向零取整；NaN 取 [default]。 */
    private fun numberToInt(el: JsonElement, default: Int): Int = toIntLenient(el, default) ?: default

    private fun toIntLenient(el: JsonElement, default: Int?): Int? {
        val n = el.toJsNumber()
        if (n.isNaN()) return default
        return when {
            n >= Int.MAX_VALUE -> Int.MAX_VALUE
            n <= Int.MIN_VALUE -> Int.MIN_VALUE
            else -> n.toInt()
        }
    }
}
