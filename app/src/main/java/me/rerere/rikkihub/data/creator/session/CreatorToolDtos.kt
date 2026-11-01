package me.rerere.rikkihub.data.creator.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest

/**
 * `creator_*` 工具的严格输入/输出 DTO。
 *
 * - 参数先严格解码：白名单字段、字面类型、有界数值、深度限制、重复键按
 *   "保留首值+标记"处理（不静默二义）；
 * - [canonicalDigest] 对**实际执行 DTO** 做规范化签发（UTF-8、键排序、
 *   无空白差异敏感）——批准的是 exact digest，不是模型自报文本。
 */

object CreatorCanonicalizer {
    private val canonicalJson = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    /** canonical 序列化（键按字典序递归排序的 JsonObject 展开）。 */
    fun canonicalize(element: JsonElement): String =
        buildString { appendCanonical(element, this) }

    private fun appendCanonical(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonObject -> {
                out.append('{')
                element.entries.sortedBy { it.key }.forEachIndexed { index, (key, value) ->
                    if (index > 0) out.append(',')
                    out.append(kotlinx.serialization.json.JsonPrimitive(key).toString()).append(':')
                    appendCanonical(value, out)
                }
                out.append('}')
            }
            is kotlinx.serialization.json.JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    appendCanonical(item, out)
                }
                out.append(']')
            }
            else -> out.append(element.toString())
        }
    }

    /** sha256(canonical json) hex。 */
    fun canonicalDigest(element: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = canonicalize(element).toByteArray(Charsets.UTF_8)
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * 白名单对象域。读取投影与命令域一致：read 的 `contentJson` 就是 Patch 可写的字段。
 * [writable] = false 的是只读引用目录（MCP 服务器、工作区），只用来查 ID。
 * 提供商可新建、可改（Key 等秘密字段只写不读），不能删除。
 */
enum class CreatorObjectKind(val writable: Boolean = true) {
    LOREBOOK,
    QUICK_MESSAGE,
    MODE_INJECTION,
    ASSISTANT,
    ASSISTANT_TAG,
    SUBAGENT_PRESET,
    SKILL,
    MODEL_DEFINITION,
    DEFAULT_SLOT,
    PROMPT,
    CONVERSATION,
    PROVIDER,
    MCP_SERVER(writable = false),
    WORKSPACE(writable = false),
    ;

    companion object {
        /** Lenient lookup: case-insensitive, `-`/space as `_`, common plural/alias forms. */
        fun parse(raw: String): CreatorObjectKind? {
            val key = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { it.name == key } ?: ALIASES[key]
        }

        private val ALIASES = mapOf(
            "LOREBOOKS" to LOREBOOK, "WORLD_BOOK" to LOREBOOK, "WORLDBOOK" to LOREBOOK,
            "QUICK_MESSAGES" to QUICK_MESSAGE, "MODE_INJECTIONS" to MODE_INJECTION,
            "ASSISTANTS" to ASSISTANT, "TAG" to ASSISTANT_TAG, "TAGS" to ASSISTANT_TAG, "ASSISTANT_TAGS" to ASSISTANT_TAG,
            "SUBAGENT_PRESETS" to SUBAGENT_PRESET, "SUBAGENT" to SUBAGENT_PRESET, "SKILLS" to SKILL,
            "MODEL" to MODEL_DEFINITION, "MODELS" to MODEL_DEFINITION, "MODEL_DEFINITIONS" to MODEL_DEFINITION,
            "DEFAULT_SLOTS" to DEFAULT_SLOT, "DEFAULT_MODEL" to DEFAULT_SLOT, "PROMPTS" to PROMPT,
            "CONVERSATIONS" to CONVERSATION, "PROVIDERS" to PROVIDER, "MCP_SERVERS" to MCP_SERVER, "MCP" to MCP_SERVER,
            "WORKSPACES" to WORKSPACE,
        )

        val names: String get() = entries.joinToString("|") { it.name }
    }
}

/** `creator_list_objects` 输入。 */
@Serializable
data class CreatorListObjectsInput(
    val kind: String,
    val filter: String = "",
    val pageToken: String? = null,
    val pageSize: Int = 40,
)

/** `creator_list_objects` 输出：白名单摘要，不一次发出所有正文。 */
@Serializable
data class CreatorObjectSummary(
    val kind: String,
    val id: String,
    val title: String,
    val digest: String,
)

@Serializable
data class CreatorListObjectsOutput(
    val items: List<CreatorObjectSummary>,
    val nextPageToken: String? = null,
    val omittedCount: Int = 0,
    val note: String? = null,
)

/** `creator_read_object` 输入/输出。正文按授权 kind/id + 预算截断。 */
@Serializable
data class CreatorReadObjectInput(
    val kind: String,
    val id: String,
)

/**
 * `contentJson` holds exactly the writable fields (send changed ones back as Patch `fields`);
 * `readOnly` is context that cannot be written; `refs` names the ids the object references.
 */
@Serializable
data class CreatorReadObjectOutput(
    val kind: String,
    val id: String,
    val digest: String,
    val contentJson: JsonElement,
    val truncated: Boolean = false,
    val readOnly: JsonObject? = null,
    val refs: Map<String, String>? = null,
    val hint: String? = null,
)

/** `creator_describe` 输入。 */
@Serializable
data class CreatorDescribeInput(
    val kind: String,
    val command: String? = null,
)

/**
 * `creator_preview_change` 输入：typed 命令 + expected token + 目标。
 * 宿主分配新 ID；模型不提供 commit 工具，成功结果是一份提案草稿。
 */
@Serializable
data class CreatorPreviewChangeInput(
    val operations: List<CreatorOperation>,
    val readSet: List<CreatorReadSetEntry> = emptyList(),
    val expiresInMillis: Long = 24L * 60 * 60 * 1000,
)

@Serializable
data class CreatorOperation(
    val kind: String,
    val targetId: String? = null,
    /** typed 命令载荷（Create/Patch/Reorder/Bind/Unbind/Delete/Restore/…）。 */
    val command: String,
    val params: JsonObject = JsonObject(emptyMap()),
    val expectedToken: String? = null,
)

/** read-set 基线条目：object/collection/predicate 版本断言。 */
@Serializable
data class CreatorReadSetEntry(
    val kind: String,
    val id: String,
    val token: String,
)

/** `creator_preview_change` 输出：DRAFT 提案（落 Vault proposal 表）。 */
@Serializable
data class ProposalDraft(
    val proposalId: String,
    val canonicalDigest: String,
    val operationCount: Int,
    val riskSummary: String,
    val expiresAtMillis: Long,
    /** 结构化差异摘要（脱敏；原生 Diff 用同一 payload）。 */
    val diffSummaryJson: JsonElement,
)

/** `creator_get_change_status` 输入/输出：本人合法提案的脱敏状态。 */
@Serializable
data class CreatorGetChangeStatusInput(
    /** null = list every proposal of this conversation. */
    val proposalId: String? = null,
)

@Serializable
data class CreatorChangeStatusOutput(
    val proposalId: String,
    val state: String,
    val resultSummary: String? = null,
    val conflict: String? = null,
)

/** `creator_propose_undo` 输入/输出：根据审计/回收记录生成新的 inverse 提案。 */
@Serializable
data class CreatorProposeUndoInput(
    val proposalId: String,
    val auditRef: String? = null,
)
