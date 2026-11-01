package me.rerere.rikkihub.data.ai.tuiqiao

import kotlinx.serialization.Serializable
import me.rerere.rikkihub.data.workspace.WORKSPACE_TEXT_EXTENSIONS
import me.rerere.rikkihub.data.workspace.workspaceFileExtension
import kotlin.uuid.Uuid

/** 推敲：候选改写的档位。由选区自动判定（[Tuiqiao.classify]），面板里可手动切换。 */
@Serializable
enum class TuiqiaoMode { WORD, SENTENCE, PARAGRAPH }

/** 解释长度；写进提示词，关闭时模型不写 `why`。 */
@Serializable
enum class TuiqiaoExplanation { OFF, ONE_LINE, DETAILED }

/**
 * 文件的格式档。MARKDOWN：段落按 Markdown 语义（相邻正文行算一段），候选要保留行内标记；
 * PLAIN：一行就是一段，不校验标记。
 */
enum class TuiqiaoFormat {
    MARKDOWN, PLAIN;

    companion object {
        fun of(fileName: String): TuiqiaoFormat =
            if (workspaceFileExtension(fileName) in MARKDOWN_EXTENSIONS) MARKDOWN else PLAIN

        private val MARKDOWN_EXTENSIONS = setOf("md", "markdown")
    }
}

/**
 * 推敲角色：用户自定义的改写要求，占一个候选位（只出一条）。[modelId] 为 null = 当前助手的模型；
 * 不带助手提示词和任何工具。[modes] 是应用位置（词/句/段），列表顺序即占位优先级。
 */
@Serializable
data class TuiqiaoRole(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val prompt: String = "",
    val modes: Set<TuiqiaoMode> = setOf(TuiqiaoMode.SENTENCE, TuiqiaoMode.PARAGRAPH),
    val modelId: Uuid? = null,
    val enabled: Boolean = true,
) {
    companion object {
        const val MAX_NAME_CHARS = 16
        const val MAX_PROMPT_CHARS = 2000
    }
}

/**
 * 推敲设置（`Settings.tuiqiao`，存一个 JSON 偏好键）。三档提示词、[extensions] 为 null = 内置默认
 * （[TuiqiaoPrompts]、[DEFAULT_EXTENSIONS]），以后改默认值时没改过的用户自动跟上。
 */
@Serializable
data class TuiqiaoConfig(
    val candidateCount: Int = DEFAULT_CANDIDATES,
    val explanation: TuiqiaoExplanation = TuiqiaoExplanation.ONE_LINE,
    val wordPrompt: String? = null,
    val sentencePrompt: String? = null,
    val paragraphPrompt: String? = null,
    val roles: List<TuiqiaoRole> = emptyList(),
    val extensions: List<String>? = null,
) {
    val effectiveCandidateCount: Int get() = candidateCount.coerceIn(MIN_CANDIDATES, MAX_CANDIDATES)

    val effectiveExtensions: List<String> get() = extensions ?: DEFAULT_EXTENSIONS

    /** 该文件能否推敲（扩展名在清单里）。 */
    fun enabledFor(fileName: String): Boolean = workspaceFileExtension(fileName) in effectiveExtensions

    fun promptFor(mode: TuiqiaoMode): String = when (mode) {
        TuiqiaoMode.WORD -> wordPrompt
        TuiqiaoMode.SENTENCE -> sentencePrompt
        TuiqiaoMode.PARAGRAPH -> paragraphPrompt
    }?.takeIf { it.isNotBlank() } ?: TuiqiaoPrompts.defaultFor(mode)

    fun withPrompt(mode: TuiqiaoMode, prompt: String?): TuiqiaoConfig = when (mode) {
        TuiqiaoMode.WORD -> copy(wordPrompt = prompt)
        TuiqiaoMode.SENTENCE -> copy(sentencePrompt = prompt)
        TuiqiaoMode.PARAGRAPH -> copy(paragraphPrompt = prompt)
    }

    /** 当前档位生效的角色：启用、应用位置包含 [mode]，按列表顺序最多占满全部候选位。 */
    fun rolesFor(mode: TuiqiaoMode): List<TuiqiaoRole> =
        roles.filter { it.enabled && mode in it.modes }.take(effectiveCandidateCount)

    companion object {
        const val MIN_CANDIDATES = 3
        const val MAX_CANDIDATES = 5
        const val DEFAULT_CANDIDATES = 3
        const val MAX_ROLES = 20

        val DEFAULT_EXTENSIONS = listOf(
            "txt", "text", "md", "markdown", "rst", "org", "adoc", "asciidoc", "tex", "fountain", "srt", "vtt",
        )

        /** 可以加进清单的扩展名：编辑器能当文本打开的。 */
        val AVAILABLE_EXTENSIONS: Set<String> get() = WORKSPACE_TEXT_EXTENSIONS

        /** 规整用户输入的扩展名：去点、去空白、小写；不可用返回 null。 */
        fun normalizeExtension(raw: String): String? =
            raw.trim().trimStart('.').lowercase().takeIf { it in AVAILABLE_EXTENSIONS }
    }
}
