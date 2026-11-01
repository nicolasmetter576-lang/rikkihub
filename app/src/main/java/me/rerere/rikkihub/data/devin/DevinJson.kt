package me.rerere.rikkihub.data.devin

import java.net.URLDecoder

/** Devin session token 的线上前缀 (devin-cli / CPA 约定) */
const val DEVIN_SESSION_TOKEN_PREFIX = "devin-session-token$"

/**
 * 规范化 session token:
 * - 已带 `devin-session-token$` 前缀 → 原样返回
 * - `eyJ` 开头的 JWT → 补前缀
 * - 其余原样返回 (opaque token)
 */
fun formatDevinSessionToken(raw: String): String {
    val token = raw.trim()
    if (token.startsWith(DEVIN_SESSION_TOKEN_PREFIX)) return token
    if (token.startsWith("eyJ")) return DEVIN_SESSION_TOKEN_PREFIX + token
    return token
}

/** [parseDevinManualInput] 的解析结果 */
internal sealed interface DevinManualInput {
    /** `devin-session-token$…` 或 `eyJ…` JWT */
    data class SessionToken(val token: String) : DevinManualInput

    /** 含 code+state 的回调 URL */
    data class CallbackUrl(val code: String, val state: String) : DevinManualInput

    /** 其余非空串按裸授权码处理 */
    data class BareCode(val code: String) : DevinManualInput
}

internal fun parseDevinManualInput(input: String): DevinManualInput? {
    val value = input.trim()
    if (value.isEmpty()) return null
    if (value.startsWith(DEVIN_SESSION_TOKEN_PREFIX) || value.startsWith("eyJ")) {
        return DevinManualInput.SessionToken(value)
    }
    val code = extractDevinQueryParam(value, "code")
    val state = extractDevinQueryParam(value, "state")
    if (!code.isNullOrBlank() && !state.isNullOrBlank()) {
        return DevinManualInput.CallbackUrl(code = code, state = state)
    }
    return DevinManualInput.BareCode(value)
}

/** 从 URL query / fragment 中提取并解码一个参数; 缺失返回 null */
internal fun extractDevinQueryParam(input: String, name: String): String? {
    val match = Regex("[?#&]" + Regex.escape(name) + "=([^&#]*)").find(input) ?: return null
    return runCatching { URLDecoder.decode(match.groupValues[1], "UTF-8") }
        .getOrElse { match.groupValues[1] }
        .takeIf { it.isNotBlank() }
}
