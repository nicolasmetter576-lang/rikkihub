package me.rerere.tavern.core

/**
 * JavaScript 正则的抽象。真实实现是 QuickJS（`regex/QuickJsRegexRuntime`），语义与 ST 在浏览器里一致；
 * 单测可以替换成假实现。
 */
interface JsRegexFactory {
    /** @throws JsRegexSyntaxException 模式或标志不合法（对应 JS 的 SyntaxError）。 */
    suspend fun compile(source: String, flags: String): JsRegex

    /**
     * 对应 ST 的 `regexFromString`（ST:public/scripts/utils.js:1387）：输入可以是 `/模式/标志`，也可以是不带斜杠的纯模式；
     * 标志不合法时把整串当模式。解析逻辑在 JS 引擎里执行，与上游逐字一致。非法返回 null。
     */
    suspend fun compileFromString(input: String): JsRegex?
}

interface JsRegex {
    val source: String
    val flags: String

    /** 对应 RegExp.prototype.test（不使用 lastIndex 状态，每次从头）。 */
    suspend fun test(text: String): Boolean

    /**
     * 找匹配。带 g 标志返回全部，否则最多返回第一个。
     * 对应 `String.prototype.matchAll` / `exec` 的结果；index 为 UTF-16 下标。
     */
    suspend fun matchAll(text: String): List<JsMatch>
}

data class JsMatch(
    val index: Int,
    val text: String,
    /** 捕获组 1..n，未参与匹配的组为 null。 */
    val groups: List<String?>,
    /** 命名捕获组，未参与匹配为 null。 */
    val named: Map<String, String?>,
)

class JsRegexSyntaxException(message: String) : RuntimeException(message)

/** 单次正则执行超时（防回溯爆炸）。 */
class JsRegexTimeoutException(message: String) : RuntimeException(message)
