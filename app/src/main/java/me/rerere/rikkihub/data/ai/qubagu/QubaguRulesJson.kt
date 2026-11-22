package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.utils.JsonInstantPretty
import kotlin.uuid.Uuid

class QubaguRulesException(val reason: Reason, val detail: String = "") : RuntimeException("$reason $detail".trim()) {
    enum class Reason { NOT_JSON, WRONG_TYPE, NEWER_VERSION, INVALID }
}

/** 导入时 id 和已有规则包相同的包怎么办。 */
enum class QubaguImportChoice { OVERWRITE, COPY, SKIP }

/** 导入结果：[invalid] 是正则写错、已被关掉的规则（「包名 › 规则名」）。 */
data class QubaguImport(val packs: List<QubaguRulePack>, val invalid: List<String>)

/**
 * 去八股规则文件（JSON，格式 v1）：`{"type": "rikkihub.qubagu.rules", "version": 1, "packs": [...]}`。
 * 只含规则包；模式、保护项、精修模型和精修要求是本机设置，不进文件。App 与网页端共用这一份解析与校验。
 */
object QubaguRulesJson {
    const val TYPE = "rikkihub.qubagu.rules"
    const val VERSION = 1

    @Serializable
    private data class RulesFile(val type: String, val version: Int, val packs: List<QubaguRulePack>)

    fun encode(packs: List<QubaguRulePack>): String = JsonInstantPretty.encodeToString(RulesFile(TYPE, VERSION, packs))

    /** 解析并校验规则文件；不合格抛 [QubaguRulesException]。 */
    fun decode(text: String): List<QubaguRulePack> {
        val element = runCatching { JsonInstant.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw QubaguRulesException(QubaguRulesException.Reason.NOT_JSON)
        if ((element["type"] as? JsonPrimitive)?.contentOrNull != TYPE) throw QubaguRulesException(QubaguRulesException.Reason.WRONG_TYPE)
        val version = (element["version"] as? JsonPrimitive)?.intOrNull ?: throw QubaguRulesException(QubaguRulesException.Reason.WRONG_TYPE)
        if (version > VERSION) throw QubaguRulesException(QubaguRulesException.Reason.NEWER_VERSION, version.toString())
        val packs = try {
            JsonInstant.decodeFromJsonElement<RulesFile>(element).packs
        } catch (e: SerializationException) {
            throw QubaguRulesException(QubaguRulesException.Reason.INVALID, e.message.orEmpty().lineSequence().first())
        } catch (e: IllegalArgumentException) {
            throw QubaguRulesException(QubaguRulesException.Reason.INVALID, e.message.orEmpty().lineSequence().first())
        }
        validate(packs)?.let { throw QubaguRulesException(QubaguRulesException.Reason.INVALID, it) }
        return packs
    }

    /** 规则包哪里不合格（英文短句，接口报错和导入提示共用）；合格返回 null。正则语法另由 [checkRegex] 查。 */
    fun validate(packs: List<QubaguRulePack>): String? {
        if (packs.size > QubaguConfig.MAX_PACKS) return "At most ${QubaguConfig.MAX_PACKS} packs"
        if (packs.sumOf { it.rules.size } > QubaguConfig.MAX_RULES) return "At most ${QubaguConfig.MAX_RULES} rules"
        if (packs.any { it.id.isBlank() } || packs.map { it.id }.toSet().size != packs.size) return "Pack ids must be unique and not blank"
        for (pack in packs) {
            val where = "pack '${pack.id}'"
            if (pack.name.length > QubaguRule.MAX_NAME_CHARS) return "Name of $where is too long"
            if (pack.description.length > QubaguRulePack.MAX_DESCRIPTION_CHARS) return "Description of $where is too long"
            if (pack.rules.any { it.id.isBlank() } || pack.rules.map { it.id }.toSet().size != pack.rules.size) {
                return "Rule ids in $where must be unique and not blank"
            }
            for (rule in pack.rules) validate(rule)?.let { return "Rule '${rule.id}' in $where: $it" }
        }
        return null
    }

    private fun validate(rule: QubaguRule): String? {
        if (rule.name.length > QubaguRule.MAX_NAME_CHARS) return "name is too long"
        if (rule.refinePrompt.length > QubaguRule.MAX_REFINE_PROMPT_CHARS) return "refinePrompt is too long"
        return when (rule) {
            is QubaguRule.Replace -> when {
                rule.find.isEmpty() || rule.find.length > QubaguRule.MAX_FIND_CHARS -> "find must be 1..${QubaguRule.MAX_FIND_CHARS} characters"
                rule.sample.length > QubaguRule.MAX_SAMPLE_CHARS -> "sample is too long"
                rule.replacements.size > QubaguRule.MAX_REPLACEMENTS -> "at most ${QubaguRule.MAX_REPLACEMENTS} replacements"
                rule.replacements.any { it.length > QubaguRule.MAX_REPLACEMENT_CHARS } -> "a replacement is too long"
                else -> null
            }
            is QubaguRule.Quota -> when {
                rule.words.size > QubaguRule.MAX_WORDS -> "at most ${QubaguRule.MAX_WORDS} words"
                rule.words.any { it.length > QubaguRule.MAX_REPLACEMENT_CHARS } -> "a word is too long"
                rule.max !in QubaguRule.Quota.MAX_RANGE -> "max is out of range"
                else -> null
            }
        }
    }

    /** 正则写错的规则关掉（其余原样），连同它们的名字返回。 */
    suspend fun checkRegex(packs: List<QubaguRulePack>, engine: Qubagu): QubaguImport {
        val invalid = mutableListOf<String>()
        val checked = packs.map { pack ->
            pack.copy(rules = pack.rules.map { rule ->
                if (engine.check(rule) == null) rule
                else rule.withEnabled(false).also { invalid += "${pack.name.ifBlank { pack.id }} › ${rule.name.ifBlank { rule.id }}" }
            })
        }
        return QubaguImport(checked, invalid)
    }

    /**
     * 把导入的包并进 [existing]：id 没冲突的追加在后面；冲突的由 [choose] 决定——覆盖（留在原位置）、
     * 另存副本（追加，换新 id，名字加「(2)」）或跳过。
     */
    fun merge(
        existing: List<QubaguRulePack>,
        imported: List<QubaguRulePack>,
        choose: (QubaguRulePack) -> QubaguImportChoice,
    ): List<QubaguRulePack> {
        val result = existing.toMutableList()
        for (pack in imported) {
            val index = result.indexOfFirst { it.id == pack.id }
            if (index < 0) {
                result += pack
                continue
            }
            when (choose(pack)) {
                QubaguImportChoice.OVERWRITE -> result[index] = pack
                QubaguImportChoice.COPY -> result += pack.copy(id = Uuid.random().toString(), name = "${pack.name} (2)")
                QubaguImportChoice.SKIP -> Unit
            }
        }
        return result
    }
}
