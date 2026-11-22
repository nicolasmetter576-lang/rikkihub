package me.rerere.tavern.core

/** 酒馆 `extension_prompt_roles`：ST:public/script.js（SYSTEM=0, USER=1, ASSISTANT=2）。 */
enum class PromptRole(val code: Int, val wire: String) {
    SYSTEM(0, "system"),
    USER(1, "user"),
    ASSISTANT(2, "assistant");

    companion object {
        fun fromCode(code: Int?): PromptRole = entries.firstOrNull { it.code == code } ?: SYSTEM
        fun fromWire(wire: String?): PromptRole = entries.firstOrNull { it.wire == wire } ?: SYSTEM
    }
}

/** 生成类型（ST 的 `type` 参数）。世界书触发器、提示词条目的 injection_trigger 都按它过滤。 */
enum class GenerationType(val wire: String) {
    NORMAL("normal"),
    CONTINUE("continue"),
    IMPERSONATE("impersonate"),
    SWIPE("swipe"),
    REGENERATE("regenerate"),
    QUIET("quiet");

    companion object {
        fun fromWire(wire: String?): GenerationType = entries.firstOrNull { it.wire == wire } ?: NORMAL
    }
}

/**
 * 酒馆消息的最小视图（宏、世界书扫描、正则深度用）。
 * 下标 0 是最早的一条；"最新在前"的排列由使用方自己 reversed()。
 */
data class ChatLine(
    val name: String,
    val isUser: Boolean,
    val isSystem: Boolean,
    val text: String,
    val swipeId: Int = 0,
    val swipeCount: Int = 1,
    /** 发送时间（毫秒时间戳）。宏 `{{idleDuration}}` 用；上游 message.send_date。 */
    val sendDateMillis: Long? = null,
)
