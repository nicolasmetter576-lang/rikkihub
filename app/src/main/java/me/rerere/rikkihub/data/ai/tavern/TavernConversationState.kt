package me.rerere.rikkihub.data.ai.tavern

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkihub.data.model.AuthorsNoteDefaults
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.AuthorsNote
import me.rerere.tavern.prompt.ExtensionPromptType
import me.rerere.tavern.worldinfo.TimedEffectsState

/**
 * 会话里的酒馆状态（`conversations.tavern_metadata`）。键名照酒馆的 `chat_metadata`：
 * `variables`（聊天变量）、`timedWorldInfo`（世界书粘滞/冷却）、`note_prompt` 等作者注释字段。
 * 认不得的键原样保留，写回时不丢。
 */
data class TavernConversationState(
    val variables: JsonObject = JsonObject(emptyMap()),
    val timedEffects: TimedEffectsState = TimedEffectsState.EMPTY,
    val authorsNote: AuthorsNote? = null,
    private val rest: JsonObject = JsonObject(emptyMap()),
) {
    /** 写回会话的 JSON：只覆盖本类认识的键，其余原样带上；值为空的键不写。 */
    fun toMetadata(): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(rest)
        out.remove(VARIABLES); out.remove(TIMED)
        if (variables.isNotEmpty()) out[VARIABLES] = variables
        val timed = timedEffects.toJson()
        if (timedEffects.sticky.isNotEmpty() || timedEffects.cooldown.isNotEmpty()) out[TIMED] = timed
        return JsonObject(out)
    }

    /** 编辑作者注释时看到的值：会话里写了的用会话的，没写的字段回落到全局默认。注释文本为空也照样给出（[authorsNote] 在文本为空时是 null）。 */
    data class NoteFields(
        val prompt: String = "",
        val interval: Int = 1,
        val depth: Int = 4,
        val position: Int = ExtensionPromptType.IN_CHAT,
        val role: PromptRole = PromptRole.SYSTEM,
    )

    companion object {
        const val VARIABLES = "variables"
        const val TIMED = "timedWorldInfo"
        private const val NOTE_PROMPT = "note_prompt"
        private const val NOTE_INTERVAL = "note_interval"
        private const val NOTE_DEPTH = "note_depth"
        private const val NOTE_POSITION = "note_position"
        private const val NOTE_ROLE = "note_role"
        private val NOTE_KEYS = listOf(NOTE_PROMPT, NOTE_INTERVAL, NOTE_DEPTH, NOTE_POSITION, NOTE_ROLE)

        fun from(metadata: JsonObject, defaults: AuthorsNoteDefaults = AuthorsNoteDefaults()): TavernConversationState {
            val variables = metadata[VARIABLES] as? JsonObject ?: JsonObject(emptyMap())
            val timed = TimedEffectsState.fromJson(metadata[TIMED])
            return TavernConversationState(variables, timed, readNote(metadata, defaults), metadata)
        }

        /** 作者注释各字段的当前值（会话的，缺的回落到默认；酒馆是 `??`，空字符串不算缺）。 */
        fun readNoteFields(metadata: JsonObject, defaults: AuthorsNoteDefaults = AuthorsNoteDefaults()): NoteFields {
            fun text(key: String) = (metadata[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
            fun int(key: String) = (metadata[key] as? JsonPrimitive)?.intOrNull
            return NoteFields(
                prompt = text(NOTE_PROMPT) ?: defaults.prompt,
                interval = int(NOTE_INTERVAL) ?: defaults.interval,
                depth = int(NOTE_DEPTH) ?: defaults.depth,
                position = int(NOTE_POSITION)?.takeIf { it.isNotePosition() } ?: defaults.position.takeIf { it.isNotePosition() } ?: ExtensionPromptType.IN_CHAT,
                role = PromptRole.fromCode(int(NOTE_ROLE) ?: defaults.role),
            )
        }

        /** 把作者注释写进会话元数据（五个键一起写，同酒馆）。其余键不动。 */
        fun writeNoteFields(metadata: JsonObject, fields: NoteFields): JsonObject {
            val out = LinkedHashMap<String, JsonElement>(metadata)
            out[NOTE_PROMPT] = JsonPrimitive(fields.prompt)
            out[NOTE_INTERVAL] = JsonPrimitive(fields.interval)
            out[NOTE_DEPTH] = JsonPrimitive(fields.depth)
            out[NOTE_POSITION] = JsonPrimitive(fields.position)
            out[NOTE_ROLE] = JsonPrimitive(fields.role.code)
            return JsonObject(out)
        }

        /**
         * 把 [patch] 里的键套到 [current] 上（值为 null 的键去掉）。认识的键校验类型与范围，不认识的键原样收下，
         * 创造助手整个 `tavern_metadata` 都可读可写。
         */
        fun mergeMetadata(current: JsonObject, patch: JsonObject): JsonObject {
            fun int(k: String, v: JsonElement, range: IntRange) {
                val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                require(n != null && n in range) { "tavernMetadata.$k must be an integer $range" }
            }
            patch.forEach { (k, v) ->
                if (v is JsonNull) return@forEach
                when (k) {
                    NOTE_PROMPT -> require(v is JsonPrimitive && v.isString) { "tavernMetadata.$k must be a string" }
                    NOTE_INTERVAL, NOTE_DEPTH -> int(k, v, 0..100_000)
                    NOTE_POSITION, NOTE_ROLE -> int(k, v, 0..2)
                    VARIABLES, TIMED -> require(v is JsonObject) { "tavernMetadata.$k must be an object" }
                }
            }
            val out = LinkedHashMap<String, JsonElement>(current)
            patch.forEach { (k, v) -> if (v is JsonNull) out.remove(k) else out[k] = v }
            val merged = JsonObject(out)
            require(merged.toString().length <= 256 * 1024) { "tavernMetadata must stay under 256 KiB" }
            return merged
        }

        /** 去掉会话自己的作者注释，让它回到跟随全局默认。 */
        fun clearNoteFields(metadata: JsonObject): JsonObject = JsonObject(metadata - NOTE_KEYS.toSet())

        /** 会话里是否写过作者注释（有任何一个 `note_*` 键）。 */
        fun hasOwnNote(metadata: JsonObject): Boolean = NOTE_KEYS.any { it in metadata }

        /** 换掉聊天变量；空的就去掉这个键。其余键不动。 */
        fun writeVariables(metadata: JsonObject, variables: JsonObject): JsonObject {
            val out = LinkedHashMap<String, JsonElement>(metadata)
            if (variables.isEmpty()) out.remove(VARIABLES) else out[VARIABLES] = variables
            return JsonObject(out)
        }

        private fun Int.isNotePosition() =
            this == ExtensionPromptType.IN_PROMPT || this == ExtensionPromptType.IN_CHAT || this == ExtensionPromptType.BEFORE_PROMPT

        /** 没有作者注释文本就当没有（上游空文本也不会产出内容）。 */
        private fun readNote(metadata: JsonObject, defaults: AuthorsNoteDefaults): AuthorsNote? {
            val fields = readNoteFields(metadata, defaults)
            if (fields.prompt.isEmpty()) return null
            return AuthorsNote(
                prompt = fields.prompt,
                interval = fields.interval,
                depth = fields.depth,
                position = fields.position,
                role = fields.role,
            )
        }
    }
}
