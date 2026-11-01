package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.proposal.ProposalErrorCode
import me.rerere.rikkihub.data.management.proposal.ProposalServiceError

/**
 * adapters/{subagent,skills,conversation} 共享支撑类型。
 *
 * 公共 DTO 一律复用 management 核心的冻结契约：
 * `management.common.*`（ManagementDomain/ObjectRef/VersionToken/ReadSet(ReadSetEntry)/
 * DiffLine/RetentionNote/RiskLevel）、`management.ports.*`（ManagementCommand/
 * StoreOperation/StoreGateway/ManagementReadPort/HostIdAllocator/…）、
 * `management.coordinator.ManagementDomainAdapter`/`PlanContext`/`PlannedMutation`、
 * `management.proposal.*`（ProposalServiceError 等）。**本包不重复定义公共类型。**
 */

/** 预算常量（adapter 侧下限；各域取更严格值）。 */
object AdapterBudget {
    const val DEFAULT_PAGE = 40
    const val MAX_PAGE = 100
    const val MAX_READ_CHARS = 64 * 1024
    const val MAX_FILE_CHARS = 256 * 1024
    const val MAX_PROPOSAL_CHARS = 256 * 1024
    const val MAX_STEPS = 64
    const val MAX_TARGETS = 256

    fun clampPage(limit: Int): Int = limit.coerceIn(1, MAX_PAGE)

    /** 截断正文到预算，返回 (text, omitted)。 */
    fun truncate(text: String, max: Int = MAX_READ_CHARS): Pair<String, Boolean> =
        if (text.length <= max) text to false else text.substring(0, max) to true
}

/** dry-run 校验失败的统一出口（提案层映射成结构化 errorCode）。 */
fun reject(code: ProposalErrorCode, detail: String): Nothing =
    throw ProposalServiceError.DomainError(code, detail)

fun rejectNotFound(detail: String): Nothing = reject(ProposalErrorCode.NOT_FOUND, detail)
fun rejectInvalid(detail: String): Nothing = reject(ProposalErrorCode.INVALID_VALUE, detail)
fun rejectConflict(detail: String): Nothing = reject(ProposalErrorCode.TOKEN_CONFLICT, detail)
fun rejectBusy(detail: String): Nothing = reject(ProposalErrorCode.LEASE_BUSY, detail)
fun rejectReserved(detail: String): Nothing = reject(ProposalErrorCode.RESERVED_ID, detail)
fun rejectForbidden(detail: String): Nothing = reject(ProposalErrorCode.FORBIDDEN_FIELD, detail)

object AdapterJson {
    fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 可控 JSON 字符串字面量（plan step payload 的辅助，非自由拼接任意 JSON）。 */
    fun str(value: String?): JsonPrimitive =
        if (value == null) JsonPrimitive(null as String?) else JsonPrimitive(value)

    fun objOf(vararg pairs: Pair<String, Any?>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                null -> put(k, JsonPrimitive(null as String?))
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is JsonPrimitive -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }
}
