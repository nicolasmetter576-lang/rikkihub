package me.rerere.tavern.prompt

import me.rerere.tavern.core.PromptRole

/**
 * 作者注释（Authors Note）的最小模型：`ST:public/scripts/authors-note.js:323-380` 的 `setFloatingPrompt` 移植。
 *
 * 配置的存放（会话元数据 note_prompt/note_interval/note_depth/note_position/note_role 与
 * extension_settings.note.* 默认）由接入层负责；这里只做纯函数 [resolve]。
 *
 * ST 的语义：
 * - `interval == 1` 时每回合都插入（lastMessageNumber 强制为 1）；
 * - 没有用户消息或 interval <= 0 时禁用（NOTE 置 NONE/最大深度，即本实现返回 null）；
 * - `messagesTillInsertion = userCount >= interval ? userCount % interval : interval - userCount`，
 *   为 0 时本回合插入；
 * - 角色专属注释（chara note，[CharaNote]）仅在启用时按 replace/before/after 并入注释文本。
 */
data class AuthorsNote(
    /** 注释文本（note_prompt）。 */
    val prompt: String = "",
    /** 间隔（note_interval，单位是用户消息数）。 */
    val interval: Int = 1,
    /** 历史内深度（note_depth）。 */
    val depth: Int = 4,
    /** 落点（note_position）：extension_prompt_types，IN_PROMPT(0)=main 末尾、IN_CHAT(1)=历史深度、BEFORE_PROMPT(2)=main 开头、NONE=-1。 */
    val position: Int = ExtensionPromptType.IN_CHAT,
    /** 角色（note_role）。 */
    val role: PromptRole = PromptRole.SYSTEM,
    /** 角色专属注释（extension_settings.note.chara 条目）。 */
    val charaNote: CharaNote? = null,
) {
    data class CharaNote(
        val prompt: String,
        /** useChara=false 时等于没有。 */
        val useChara: Boolean = true,
        /** 0=replace、1=before、2=after（chara_note_position）。 */
        val position: Int = REPLACE,
    ) {
        companion object {
            const val REPLACE = 0
            const val BEFORE = 1
            const val AFTER = 2
        }
    }

    /**
     * 本回合的作者注释产物：null = 本回合不插（此时世界书的 anBefore/anAfter 也不会出现，与上游
     * `shouldWIAddPrompt` 门一致）；否则为待写入 extension_prompts['2_floating_prompt'] 的内容。
     */
    fun resolve(userMessageCount: Int): ResolvedAuthorsNote? {
        var lastMessageNumber = userMessageCount
        // interval 1 强制每回合插入（ST:authors-note.js:342）
        if (interval == 1) lastMessageNumber = 1
        if (lastMessageNumber <= 0 || interval <= 0) return null

        val messagesTillInsertion = if (lastMessageNumber >= interval)
            lastMessageNumber % interval
        else
            interval - lastMessageNumber
        val shouldAddPrompt = messagesTillInsertion == 0
        if (!shouldAddPrompt) return null

        var p = prompt
        val cn = charaNote
        if (cn != null && cn.useChara) {
            p = when (cn.position) {
                CharaNote.BEFORE -> cn.prompt + "\n" + p
                CharaNote.AFTER -> p + "\n" + cn.prompt
                else -> cn.prompt
            }
        }
        return ResolvedAuthorsNote(p, position, depth, role)
    }
}

/** [AuthorsNote.resolve] 的产物：作者注释文本 + 落点/深度/角色。 */
data class ResolvedAuthorsNote(
    val text: String,
    val position: Int,
    val depth: Int,
    val role: PromptRole,
)
