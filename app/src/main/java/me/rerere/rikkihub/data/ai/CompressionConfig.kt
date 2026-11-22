package me.rerere.rikkihub.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** 自动压缩档位：关 / 用量快到上限时只提醒 / 自动压缩后再发送。 */
@Serializable
enum class AutoCompressMode {
    @SerialName("off")
    OFF,

    @SerialName("notify")
    NOTIFY,

    @SerialName("auto")
    AUTO,
}

/** 原话带上档位：关 / 带最近几条 / 按「压到」目标余量自动尽量多带。 */
@Serializable
enum class CarryOriginalsMode {
    @SerialName("off")
    OFF,

    @SerialName("recent")
    RECENT,

    @SerialName("auto")
    AUTO,
}

/**
 * 上下文压缩的全局设置，`Settings.compression`。会话可以用 [ConversationCompressionOverride] 单独改档位和模板。
 *
 * 比例都相对「输入预算」（上下文窗口减去输出预留和 5% 余量，见 `ConversationCompression.kt` 的 `compressionInputBudget`）。
 */
@Serializable
data class CompressionConfig(
    val mode: AutoCompressMode = AutoCompressMode.NOTIFY,
    /** 用量达到输入预算的这个百分比时提醒或自动压缩 */
    val triggerPercent: Int = DEFAULT_TRIGGER_PERCENT,
    /** 自动压缩后估算用量要低于这个百分比，放不下时少保留几条 */
    val targetPercent: Int = DEFAULT_TARGET_PERCENT,
    /** 自动压缩时不进摘要的最近消息数 */
    val keepRecent: Int = DEFAULT_KEEP_RECENT,
    val templateId: String = CompressionTemplates.GENERAL,
    /** 摘要长度上限（token），写进指令，也是预估里摘要的体积 */
    val summaryTokens: Int = DEFAULT_SUMMARY_TOKENS,
    /** 摘要用的模型；null = 跟随会话的对话模型（同一套请求前缀，能命中缓存） */
    val modelId: Uuid? = null,
    val customTemplates: List<CompressionTemplate> = emptyList(),
    /** 手动压缩时先看摘要再决定是否采用（压缩对话框里的开关，记住上次的选择） */
    val reviewBeforeApply: Boolean = false,
    /** 附带用户原话写入检查点正文的模式 */
    val carryOriginalsMode: CarryOriginalsMode = CarryOriginalsMode.OFF,
    /** [CarryOriginalsMode.RECENT] 时带上的最近用户消息条数 */
    val carryOriginalsCount: Int = DEFAULT_CARRY_COUNT,
    /** 附带用户原话时是否一并附上当时助手的可见回复（不含思考与工具调用） */
    val carryIncludeReplies: Boolean = false,
    /** 单次压缩（含分段与超限缩容重试）最多发起的模型请求次数 */
    val maxChunkRequests: Int = DEFAULT_MAX_CHUNK_REQUESTS,
    /** 分段单次压缩请求超时（秒）；null = 跟随全局网络设置 */
    val chunkTimeoutSeconds: Int? = null,
) {
    /** 读设置时收进合法范围：手改的设置文件或旧版本的值不会让比例倒挂。 */
    fun normalized(): CompressionConfig {
        val trigger = triggerPercent.coerceIn(MIN_TRIGGER_PERCENT, MAX_TRIGGER_PERCENT)
        return copy(
            triggerPercent = trigger,
            targetPercent = targetPercent.coerceIn(MIN_TARGET_PERCENT, trigger - 5),
            keepRecent = keepRecent.coerceIn(0, MAX_KEEP_RECENT),
            summaryTokens = summaryTokens.coerceIn(MIN_SUMMARY_TOKENS, MAX_SUMMARY_TOKENS),
            carryOriginalsCount = carryOriginalsCount.coerceIn(MIN_CARRY_COUNT, MAX_CARRY_COUNT),
            maxChunkRequests = maxChunkRequests.coerceIn(MIN_MAX_CHUNK_REQUESTS, MAX_MAX_CHUNK_REQUESTS),
            chunkTimeoutSeconds = chunkTimeoutSeconds?.coerceIn(MIN_CHUNK_TIMEOUT_SECONDS, MAX_CHUNK_TIMEOUT_SECONDS),
        )
    }

    /** 会话覆盖之后实际生效的设置。 */
    fun resolvedFor(override: ConversationCompressionOverride?): CompressionConfig = normalized().copy(
        mode = override?.mode ?: mode,
        templateId = override?.templateId?.takeIf { templateExists(it) } ?: templateId,
    )

    fun templateExists(id: String): Boolean =
        id in CompressionTemplates.BUILT_IN || customTemplates.any { it.id == id }

    companion object {
        const val DEFAULT_TRIGGER_PERCENT = 80
        const val DEFAULT_TARGET_PERCENT = 60
        const val DEFAULT_KEEP_RECENT = 6
        const val DEFAULT_SUMMARY_TOKENS = 4096
        const val MIN_TRIGGER_PERCENT = 30
        const val MAX_TRIGGER_PERCENT = 95
        const val MIN_TARGET_PERCENT = 10
        const val MAX_KEEP_RECENT = 100
        const val MIN_SUMMARY_TOKENS = 256
        const val MAX_SUMMARY_TOKENS = 32_768
        const val MAX_CUSTOM_TEMPLATES = 50
        const val MAX_TEMPLATE_NAME = 40
        const val MAX_TEMPLATE_PROMPT = 8_000
        const val DEFAULT_CARRY_COUNT = 3
        const val MIN_CARRY_COUNT = 1
        const val MAX_CARRY_COUNT = 50
        const val DEFAULT_MAX_CHUNK_REQUESTS = 8
        const val MIN_MAX_CHUNK_REQUESTS = 2
        const val MAX_MAX_CHUNK_REQUESTS = 32
        const val MIN_CHUNK_TIMEOUT_SECONDS = 30
        const val MAX_CHUNK_TIMEOUT_SECONDS = 600
    }
}

/** 自定义摘要模板：[prompt] 替换内置模板的段落结构，通用要求（不调工具、语言、长度、重点）照常追加。 */
@Serializable
data class CompressionTemplate(
    val id: String,
    val name: String,
    val prompt: String,
)

/** 会话自己的压缩设置；字段为 null 表示跟随全局。存在会话表的 `compression` 列（JSON，空串 = 全部跟随）。 */
@Serializable
data class ConversationCompressionOverride(
    val mode: AutoCompressMode? = null,
    val templateId: String? = null,
) {
    val isEmpty: Boolean get() = mode == null && templateId == null
}

object CompressionTemplates {
    const val GENERAL = "general"
    const val CODE = "code"
    const val WRITING = "writing"
    const val RESEARCH = "research"
    const val ROLEPLAY = "roleplay"

    val BUILT_IN = listOf(GENERAL, CODE, WRITING, RESEARCH, ROLEPLAY)
}
