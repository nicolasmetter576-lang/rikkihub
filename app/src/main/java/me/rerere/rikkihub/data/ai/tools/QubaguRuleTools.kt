package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.qubagu.Qubagu
import me.rerere.rikkihub.data.ai.qubagu.QubaguConfig
import me.rerere.rikkihub.data.ai.qubagu.QubaguMode
import me.rerere.rikkihub.data.ai.qubagu.QubaguRule
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulePack
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesException
import me.rerere.rikkihub.data.ai.qubagu.QubaguRulesJson

/** 内置技能 `qubagu-rules`：助手启用它时追加 [createQubaguRuleTools] 的三个工具。 */
const val QUBAGU_RULES_SKILL = "qubagu-rules"

/** 一段测试样本最多多少字。 */
private const val MAX_TEST_CHARS = 20_000

private fun description(id: String) = BuiltinToolCatalog.require(id).defaultDescription

private val packsSchema = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject { put("type", "object") })
    put("description", "Whole rule packs in the rules file v1 format (each with id, name, description, enabled, rules)")
}

/**
 * 去八股规则的读、测、写。只碰规则包；模式、保护项、精修这些本机设置不在这里改。
 * 写入每次都要用户批准；校验和规则文件导入同一套，但正则写错时整次拒绝（导入是关掉那条），好让模型改了再写。
 */
fun createQubaguRuleTools(
    engine: Qubagu,
    config: () -> QubaguConfig,
    savePacks: suspend (List<QubaguRulePack>) -> Unit,
): List<Tool> = listOf(
    Tool(
        name = BuiltinToolIds.QUBAGU_RULES_READ,
        description = description(BuiltinToolIds.QUBAGU_RULES_READ),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("pack", buildJsonObject {
                        put("type", "string")
                        put("description", "Id of one pack to read in full. Omit for the overview of all packs")
                    })
                },
            )
        },
        execute = { args ->
            val current = config()
            val id = args.string("pack")
            if (id == null) {
                output { overview(current) }
            } else {
                val pack = current.packs.firstOrNull { it.id == id }
                if (pack == null) rejected("No pack '$id'. Packs: ${current.packs.joinToString { it.id }.ifEmpty { "(none)" }}")
                else listOf(UIMessagePart.Text(QubaguRulesJson.encode(listOf(pack))))
            }
        },
    ),
    Tool(
        name = BuiltinToolIds.QUBAGU_RULES_TEST,
        description = description(BuiltinToolIds.QUBAGU_RULES_TEST),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("description", "Sample text to clean, at most $MAX_TEST_CHARS characters")
                    })
                    put("packs", packsSchema)
                    put("only", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true: run only the given packs instead of merging them into the current rules")
                    })
                },
                required = listOf("text"),
            )
        },
        execute = execute@{ args ->
            val text = args.string("text").orEmpty()
            if (text.isBlank()) return@execute rejected("text is required")
            if (text.length > MAX_TEST_CHARS) return@execute rejected("text is longer than $MAX_TEST_CHARS characters")
            val current = config()
            val candidates = when (val parsed = parsePacks(args, engine)) {
                is Parsed.Failed -> return@execute rejected(parsed.message)
                is Parsed.Packs -> parsed.packs.map { it.copy(enabled = true) }
            }
            val packs = if (args.boolean("only")) candidates else mergePacks(current.packs, candidates)
            val tested = current.copy(packs = packs, mode = QubaguMode.AUTO, merge = current.merge && !args.boolean("only"))
            val record = engine.clean(text, tested)
            output {
                put("result", record.render(text))
                putJsonArray("edits") {
                    for (edit in record.edits) add(buildJsonObject {
                        put("rule", edit.rule)
                        tested.rule(edit.rule)?.let { put("ruleName", it.name) }
                        put("before", edit.before)
                        put("after", edit.after)
                    })
                }
            }
        },
    ),
    Tool(
        name = BuiltinToolIds.QUBAGU_RULES_WRITE,
        description = description(BuiltinToolIds.QUBAGU_RULES_WRITE),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("packs", packsSchema)
                    put("delete", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Ids of packs to delete")
                    })
                    put("order", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Every pack id after this change, in the new order. Omit to keep the order (new packs go last)")
                    })
                },
            )
        },
        needsApproval = { true },
        execute = execute@{ args ->
            val incoming = when (val parsed = parsePacks(args, engine)) {
                is Parsed.Failed -> return@execute rejected(parsed.message)
                is Parsed.Packs -> parsed.packs
            }
            val delete = args.strings("delete")
            val order = args.strings("order")
            if (incoming.isEmpty() && delete.isEmpty() && order.isEmpty()) return@execute rejected("Nothing to change: give packs, delete or order")
            val existing = config().packs
            val existingIds = existing.map { it.id }.toSet()
            delete.filter { it !in existingIds }.takeIf { it.isNotEmpty() }?.let { return@execute rejected("No pack to delete: ${it.joinToString()}") }
            incoming.filter { it.id in delete }.takeIf { it.isNotEmpty() }?.let {
                return@execute rejected("A pack cannot be written and deleted at once: ${it.joinToString { pack -> pack.id }}")
            }
            var packs = mergePacks(existing.filter { it.id !in delete }, incoming)
            if (order.isNotEmpty()) {
                if (order.size != packs.size || order.toSet() != packs.map { it.id }.toSet()) {
                    return@execute rejected("order must list every pack id exactly once: ${packs.joinToString { it.id }}")
                }
                val byId = packs.associateBy { it.id }
                packs = order.map { byId.getValue(it) }
            }
            QubaguRulesJson.validate(packs)?.let { return@execute rejected(it) }
            savePacks(packs)
            output {
                putJsonArray("created") { incoming.filter { it.id !in existingIds }.forEach { add(JsonPrimitive(it.id)) } }
                putJsonArray("replaced") { incoming.filter { it.id in existingIds }.forEach { add(JsonPrimitive(it.id)) } }
                putJsonArray("deleted") { delete.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("order") { packs.forEach { add(JsonPrimitive(it.id)) } }
                put("chatCleanupOn", config().enabled)
            }
        },
    ),
)

/** 同 id 的包原位替换，新包追加在后面。 */
private fun mergePacks(existing: List<QubaguRulePack>, incoming: List<QubaguRulePack>): List<QubaguRulePack> {
    val byId = incoming.associateBy { it.id }
    return existing.map { byId[it.id] ?: it } + incoming.filter { pack -> existing.none { it.id == pack.id } }
}

private sealed interface Parsed {
    data class Packs(val packs: List<QubaguRulePack>) : Parsed
    data class Failed(val message: String) : Parsed
}

/** 参数里的 `packs` 按规则文件 v1 解析、校验，再查正则；任何一处不合格都整次失败。 */
private suspend fun parsePacks(args: JsonElement, engine: Qubagu): Parsed {
    val raw = args.jsonObject["packs"] ?: return Parsed.Packs(emptyList())
    if (raw !is JsonArray) return Parsed.Failed("packs must be an array of pack objects")
    val file = buildJsonObject {
        put("type", QubaguRulesJson.TYPE)
        put("version", QubaguRulesJson.VERSION)
        put("packs", raw)
    }
    val packs = try {
        QubaguRulesJson.decode(file.toString())
    } catch (e: QubaguRulesException) {
        return Parsed.Failed("Invalid packs: ${e.detail.ifEmpty { e.reason.name }}")
    }
    val regexErrors = packs.flatMap { pack ->
        pack.rules.mapNotNull { rule -> engine.check(rule)?.let { "Rule '${rule.id}' in pack '${pack.id}': $it" } }
    }
    return if (regexErrors.isEmpty()) Parsed.Packs(packs) else Parsed.Failed("Invalid regex: ${regexErrors.joinToString("; ")}")
}

private fun JsonObjectBuilder.overview(config: QubaguConfig) {
    put("chatCleanupOn", config.enabled)
    put("mode", config.mode.name)
    put("protectDialogue", config.protectDialogue)
    put("protectCornerQuotes", config.protectCornerQuotes)
    put("protectDetails", config.protectDetails)
    put("mergeChoppyLines", config.merge)
    putJsonArray("packs") {
        for (pack in config.packs) add(buildJsonObject {
            put("id", pack.id)
            put("name", pack.name)
            put("description", pack.description)
            put("enabled", pack.enabled)
            putJsonArray("rules") {
                for (rule in pack.rules) add(buildJsonObject {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("kind", if (rule is QubaguRule.Quota) "quota" else "replace")
                    put("enabled", rule.enabled)
                })
            }
        })
    }
    putJsonObject("limits") {
        put("packs", QubaguConfig.MAX_PACKS)
        put("rules", QubaguConfig.MAX_RULES)
    }
}

private fun JsonElement.string(key: String): String? = (jsonObject[key] as? JsonPrimitive)?.contentOrNull

private fun JsonElement.boolean(key: String): Boolean = (jsonObject[key] as? JsonPrimitive)?.booleanOrNull == true

private fun JsonElement.strings(key: String): List<String> =
    (jsonObject[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

private fun output(builder: JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject(builder).toString()))

private fun rejected(message: String): List<UIMessagePart> = output { put("error", message) }
