package me.rerere.tavern.worldinfo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tavern.core.ChatLine
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.core.TavernRandom
import me.rerere.tavern.core.WorldInfoPromptResult
import me.rerere.tavern.token.TokenCounter

/** 差分测试的夹具：把 golden 里的用例输入还原成引擎入参，并把引擎结果转成和 golden 同形状的 JSON。 */
internal object GoldenSupport {
    val json = Json { ignoreUnknownKeys = true }

    fun load(name: String): JsonObject {
        val text = GoldenSupport::class.java.getResource("/golden/worldinfo/$name.json")!!.readText()
        return json.parseToJsonElement(text).jsonObject
    }

    /** 上游 `{uid, ...newWorldInfoEntryTemplate, ...覆盖}`：模板取自本实现的规范默认值（另有测试核对它与真酒馆模板一致）。 */
    fun mergeTemplate(uid: Int, partial: JsonObject): JsonObject {
        val template = WorldInfoCodec.encodeEntry(WorldInfoEntry(uid = uid))
        val merged = LinkedHashMap<String, JsonElement>(template)
        for ((k, v) in partial) merged[k] = v
        return JsonObject(merged)
    }

    fun buildBook(def: JsonObject): WorldInfoBook {
        val entries = LinkedHashMap<String, JsonElement>()
        for ((uid, partial) in def.getValue("entries").jsonObject) {
            entries[uid] = mergeTemplate(uid.toInt(), partial.jsonObject)
        }
        return WorldInfoCodec.decodeBook(JsonObject(mapOf("entries" to JsonObject(entries))))
    }

    class Case(val input: JsonObject) {
        val name: String get() = input.getValue("name").jsonPrimitive.content
        val worlds: Map<String, WorldInfoBook> = (input["worlds"] as? JsonObject)?.mapValues { buildBook(it.value.jsonObject) } ?: emptyMap()

        private fun ref(name: String) = WorldInfoBookRef(name, worlds.getValue(name))

        val books = WorldInfoBooks(
            global = input.strings("global").map(::ref),
            character = input.strings("characterBooks").filter { it.isNotEmpty() }.map(::ref),
            chat = input["chatBook"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let(::ref),
            persona = input["personaBook"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let(::ref),
        )
        val settings: WorldInfoSettings = json.decodeFromJsonElement(
            WorldInfoSettings.serializer(),
            input["settings"] as? JsonObject ?: JsonObject(emptyMap()),
        )
        val maxContext: Int = input["maxContext"]?.jsonPrimitive?.int ?: 4096
        val trigger: GenerationType = GenerationType.fromWire(input["trigger"]?.jsonPrimitive?.contentOrNull)
        val dryRun: Boolean = input["dryRun"]?.jsonPrimitive?.boolean ?: false
        val globalScan: WorldInfoGlobalScanData = (input["globalScan"] as? JsonObject)?.let {
            WorldInfoGlobalScanData(
                personaDescription = it.str("personaDescription"),
                characterDescription = it.str("characterDescription"),
                characterPersonality = it.str("characterPersonality"),
                characterDepthPrompt = it.str("characterDepthPrompt"),
                scenario = it.str("scenario"),
                creatorNotes = it.str("creatorNotes"),
            )
        } ?: WorldInfoGlobalScanData()
        val character: WorldInfoCharacterContext = run {
            val avatar = input["avatar"]?.jsonPrimitive?.contentOrNull ?: "default_Seraphina.png"
            val fileName = avatar.substringBeforeLast('.', avatar)
            val tags = when (val t = input["tags"]) {
                null -> emptyList()
                // 上游 getTagKeyForEntity 对没有标签表项的角色会建一个空表，所以 null 与 [] 等价
                is JsonNull -> emptyList()
                else -> t.jsonArray.map { it.jsonPrimitive.content }
            }
            WorldInfoCharacterContext(fileName, tags)
        }
        val injected: List<String> = input.strings("injected")
        val regexScripts: List<JsonObject> = (input["regexScripts"] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
        val timedInit: TimedEffectsState = TimedEffectsState.fromJson(input["timedInit"])

        class Step(val chat: List<ChatLine>, val dryRun: Boolean, val random: List<Double>)

        val steps: List<Step> = run {
            val caseRandom = input.doubles("random")
            val stepDefs = (input["steps"] as? JsonArray)?.map { it.jsonObject } ?: listOf(JsonObject(mapOf("chat" to input.getValue("chat"))))
            stepDefs.map { s ->
                Step(
                    chat = s.getValue("chat").jsonArray.map {
                        val o = it.jsonObject
                        val n = o.getValue("name").jsonPrimitive.content
                        ChatLine(name = n, isUser = n == "User", isSystem = false, text = o.getValue("text").jsonPrimitive.content)
                    },
                    dryRun = (s["dryRun"] ?: input["dryRun"])?.jsonPrimitive?.boolean ?: false,
                    random = if (s.containsKey("random")) s.doubles("random") else caseRandom,
                )
            }
        }
    }

    private fun JsonObject.strings(k: String): List<String> = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    private fun JsonObject.doubles(k: String): List<Double> = (this[k] as? JsonArray)?.map { it.jsonPrimitive.content.toDouble() } ?: emptyList()
    private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull ?: ""

    /**
     * 记录调用顺序的宿主：宏只认 `{{addvar::wtrace::X}}`（真酒馆里同一个宏把 X 追加到变量 wtrace，副作用即调用顺序）、
     * `{{user}}`、`{{char}}`；提示词正则按 golden 里的脚本做字面替换并检查 min/maxDepth（与 getRegexedString 的深度过滤一致）。
     */
    class RecordingHost(
        override val regexFactory: JsRegexFactory,
        private val scripts: List<JsonObject>,
        private val user: String,
        private val char: String,
    ) : WorldInfoHost {
        val trace = StringBuilder()
        val calls = ArrayList<String>()
        var seq: List<Double> = emptyList()
        var used = 0

        override val random = TavernRandom {
            check(used < seq.size) { "random sequence exhausted" }
            seq[used++]
        }

        private val traceMacro = Regex("""\{\{addvar::wtrace::([^}]*)\}\}""")

        override fun substitute(text: String): String {
            calls += text
            return traceMacro.replace(text) { trace.append(it.groupValues[1]); "" }
                .replace("{{user}}", user)
                .replace("{{char}}", char)
        }

        override suspend fun applyWorldInfoRegex(text: String, depth: Int?): String {
            if (text.isEmpty()) return text
            var s = text
            for (script in scripts) {
                if (script["disabled"]?.jsonPrimitive?.boolean == true) continue
                if (script["promptOnly"]?.jsonPrimitive?.boolean != true) continue
                if (script["placement"]!!.jsonArray.none { it.jsonPrimitive.int == 5 }) continue
                if (depth != null) {
                    val min = script["minDepth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    val max = script["maxDepth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    if (min != null && min >= -1 && depth < min) continue
                    if (max != null && max >= 0 && depth > max) continue
                }
                // 夹具里的 findRegex 只用 `/字面量/g`
                val find = script.getValue("findRegex").jsonPrimitive.content.removePrefix("/").substringBeforeLast("/")
                s = s.replace(find, script.getValue("replaceString").jsonPrimitive.content)
            }
            return s
        }

        override fun countTokens(text: String): Int = TokenCounter.count(text)
    }

    fun promptJson(p: WorldInfoPromptResult): JsonObject = JsonObject(
        linkedMapOf(
            "worldInfoString" to JsonPrimitive(p.worldInfoString),
            "worldInfoBefore" to JsonPrimitive(p.worldInfoBefore),
            "worldInfoAfter" to JsonPrimitive(p.worldInfoAfter),
            "worldInfoExamples" to JsonArray(
                p.worldInfoExamples.map {
                    JsonObject(mapOf("position" to JsonPrimitive(if (it.before) 0 else 1), "content" to JsonPrimitive(it.content)))
                },
            ),
            "worldInfoDepth" to JsonArray(
                p.worldInfoDepth.map {
                    JsonObject(
                        mapOf(
                            "depth" to JsonPrimitive(it.depth),
                            "entries" to JsonArray(it.entries.map(::JsonPrimitive)),
                            "role" to JsonPrimitive(it.role.code),
                        ),
                    )
                },
            ),
            "anBefore" to JsonArray(p.anBefore.map(::JsonPrimitive)),
            "anAfter" to JsonArray(p.anAfter.map(::JsonPrimitive)),
            "outletEntries" to JsonObject(p.outletEntries.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }),
        ),
    )
}
