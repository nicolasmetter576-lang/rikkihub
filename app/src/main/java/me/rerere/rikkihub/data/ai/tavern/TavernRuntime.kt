package me.rerere.rikkihub.data.ai.tavern

import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.macro.MacroEngine
import me.rerere.tavern.regex.QuickJsRegexRuntime
import me.rerere.tavern.regex.RegexEngine
import me.rerere.tavern.token.TokenCounter

/**
 * 酒馆引擎在 App 里的共享实例：宏引擎、JS 正则运行时与正则引擎。
 * 都是无请求状态的（宏环境、变量、脚本列表每次调用传入），进程内一份即可。
 */
class TavernRuntime(
    val regexFactory: JsRegexFactory = QuickJsRegexRuntime(),
    val macroEngine: MacroEngine = MacroEngine(),
) : AutoCloseable {
    val regexEngine: RegexEngine = RegexEngine(regexFactory)

    /** 分词器首次使用要加载编码表（约 1 秒）；应用启动后在后台线程调一次。 */
    fun warmUp() = TokenCounter.warmUp()

    override fun close() {
        (regexFactory as? AutoCloseable)?.close()
    }
}
