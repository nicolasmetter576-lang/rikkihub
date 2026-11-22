package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlin.uuid.Uuid

/** AUTO：生成结束时直接改好；REVIEW：只记下建议，由用户在改动清单里逐条应用。 */
@Serializable
enum class QubaguMode { AUTO, REVIEW }

/**
 * 一条规则。[refinePrompt] 是这条规则命中时拼进 AI 精修提示词的改写要求，留空不拼。
 * 规则本身不带内容默认值：App 不内置任何规则，全部由用户导入或新建（见 [QubaguRulesJson]）。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface QubaguRule {
    val id: String
    val name: String
    val enabled: Boolean
    val refinePrompt: String

    fun withEnabled(enabled: Boolean): QubaguRule = when (this) {
        is Replace -> copy(enabled = enabled)
        is Quota -> copy(enabled = enabled)
    }

    /**
     * 查找替换。[regex] 为 false 时 [find] 按字面查找；为 true 时是 JS 正则，可写成 `/模式/标志`。
     * [replacements] 是候选替换词，每次命中挑一个（可用 `$1`～`$9`）；空表 = 删掉。
     * [keepFirst]：一段正文里第一次出现的不动，从第二次起才替换。
     * [atEnd]：只认在正文末尾结束的匹配；末尾跟着的台词、受保护块和空白不算正文。
     */
    @Serializable
    @SerialName("replace")
    data class Replace(
        override val id: String = Uuid.random().toString(),
        override val name: String = "",
        val sample: String = "",
        val find: String = "",
        val regex: Boolean = false,
        val replacements: List<String> = emptyList(),
        val dialogue: Boolean = false,
        val keepFirst: Boolean = false,
        val atEnd: Boolean = false,
        override val enabled: Boolean = true,
        override val refinePrompt: String = "",
    ) : QubaguRule

    /** 限量：[words] 里每个词在一段旁白里留前 [max] 次，之后的删掉（连同后面的「地」）。 */
    @Serializable
    @SerialName("quota")
    data class Quota(
        override val id: String = Uuid.random().toString(),
        override val name: String = "",
        val words: List<String> = emptyList(),
        val max: Int = DEFAULT_MAX,
        override val enabled: Boolean = true,
        override val refinePrompt: String = "",
    ) : QubaguRule {
        companion object {
            const val DEFAULT_MAX = 2
            val MAX_RANGE = 0..10
        }
    }

    companion object {
        const val MAX_NAME_CHARS = 100
        const val MAX_SAMPLE_CHARS = 300
        const val MAX_FIND_CHARS = 1500
        const val MAX_REPLACEMENTS = 24
        const val MAX_REPLACEMENT_CHARS = 200
        const val MAX_WORDS = 60
        const val MAX_REFINE_PROMPT_CHARS = 1000
    }
}

/** 一组规则，整组可开关、导入导出。规则按包的顺序、包内的顺序执行，重叠时先到先得。 */
@Serializable
data class QubaguRulePack(
    val id: String = Uuid.random().toString(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val rules: List<QubaguRule> = emptyList(),
) {
    companion object {
        const val MAX_DESCRIPTION_CHARS = 500
    }
}

/**
 * 去八股设置（`Settings.qubagu`，存一个 JSON 偏好键）。[enabled] 是聊天回复的总开关（所有助手、所有会话）；
 * 工作区文件的八股标注不看它，只用这里的规则与保护项。[refinePrompt] 为 null = 内置的精修要求。
 */
@Serializable
data class QubaguConfig(
    val enabled: Boolean = false,
    val mode: QubaguMode = QubaguMode.AUTO,
    val protectDialogue: Boolean = true,
    val protectCornerQuotes: Boolean = true,
    val protectDetails: Boolean = true,
    val packs: List<QubaguRulePack> = emptyList(),
    val merge: Boolean = false,
    val mergeMaxChars: Int = DEFAULT_MERGE_MAX_CHARS,
    val refine: Boolean = false,
    val refineModelId: Uuid? = null,
    val refinePrompt: String? = null,
) {
    val effectiveMergeMaxChars: Int get() = mergeMaxChars.coerceIn(MERGE_MAX_CHARS_RANGE)
    val effectiveRefinePrompt: String get() = refinePrompt?.takeIf { it.isNotBlank() } ?: QubaguRefine.DEFAULT_PROMPT

    /** 要跑的规则，按执行顺序，连同它在改动记录里的 id（见 [QubaguRules.key]）。 */
    val activeRules: List<Pair<String, QubaguRule>>
        get() = packs.filter { it.enabled }.flatMap { pack ->
            pack.rules.filter { it.enabled }.map { QubaguRules.key(pack.id, it.id) to it }
        }

    /** 有没有东西可跑：打开的规则或碎句合并。 */
    val hasWork: Boolean get() = merge || packs.any { pack -> pack.enabled && pack.rules.any { it.enabled } }

    /** 改动记录里的规则 id 对应的规则（规则后来被删了就是 null）。 */
    fun rule(key: String): QubaguRule? {
        val (packId, ruleId) = key.split('/', limit = 2).takeIf { it.size == 2 } ?: return null
        return packs.firstOrNull { it.id == packId }?.rules?.firstOrNull { it.id == ruleId }
    }

    /** 这条改动是不是「只认结尾」的规则产生的：碎句合并不跨过它，AI 精修不重写它。 */
    fun endsText(key: String): Boolean = (rule(key) as? QubaguRule.Replace)?.atEnd == true

    companion object {
        const val DEFAULT_MERGE_MAX_CHARS = 8
        val MERGE_MAX_CHARS_RANGE = 2..20
        const val MAX_PACKS = 50
        const val MAX_RULES = 500
        const val MAX_REFINE_PROMPT_CHARS = 4000
    }
}
