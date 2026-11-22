package me.rerere.tavern.regex

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tavern.core.JsMatch
import me.rerere.tavern.core.JsRegex
import me.rerere.tavern.core.JsRegexFactory
import me.rerere.tavern.core.JsRegexSyntaxException
import me.rerere.tavern.core.JsRegexTimeoutException

/**
 * 用 QuickJS 执行 JavaScript 正则，语义与 ST 在浏览器里一致，不做任何语法翻译。
 *
 * 为什么不转成 java.util.regex：Android 上的正则由 ICU 实现，与 OpenJDK 在 `\s \w \d`、大小写、
 * 后行断言等处行为不同，且都不接受 `[^]`、`{{user}}` 这类孤立花括号；用真正的 JS 引擎可以一次解决。
 * 依据：55 条常见写法的对照用例（见 QuickJsRegexRuntimeTest）与 V8 一致。
 *
 * 一个实例持有一个 QuickJS 运行时，调用串行；编译结果在 JS 侧按 (标志, 源) 做 LRU 缓存
 * （对应 ST:public/scripts/extensions/regex/engine.js:39-70 的编译缓存）。用完请 [close]。
 */
class QuickJsRegexRuntime(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cacheSize: Int = 256,
    private val evalTimeoutMillis: Long = 3_000,
    private val memoryLimitBytes: Long = 96L * 1024 * 1024,
    private val maxStackBytes: Long = 2L * 1024 * 1024,
) : JsRegexFactory, AutoCloseable {
    private val mutex = Mutex()
    private var js: QuickJs? = null
    private var nextId = 1
    private val cache = object : LinkedHashMap<String, Int>(64, 0.75f, true) {}

    override suspend fun compile(source: String, flags: String): JsRegex {
        mutex.withLock { idFor(source, flags) } // 立刻校验语法
        return Handle(source, flags)
    }

    override suspend fun compileFromString(input: String): JsRegex? {
        val ok = mutex.withLock { idForString(input) } != null
        return if (ok) StringHandle(input) else null
    }

    override fun close() {
        js?.close()
        js = null
        cache.clear()
    }

    private suspend fun runtime(): QuickJs {
        js?.let { return it }
        val created = QuickJs.create(dispatcher)
        created.memoryLimit = memoryLimitBytes
        created.maxStackSize = maxStackBytes
        created.evaluationTimeoutMillis = evalTimeoutMillis
        created.evaluate<Unit>(BOOTSTRAP)
        js = created
        return created
    }

    /** 调用方必须持有 [mutex]。 */
    private suspend fun idFor(source: String, flags: String): Int {
        val key = flags + "/" + source
        cache[key]?.let { return it }
        val engine = runtime()
        val id = nextId++
        val err = engine.evalGuarded<String>("__rxCompile($id, ${lit(source)}, ${lit(flags)})")
        if (err.isNotEmpty()) throw JsRegexSyntaxException(err)
        cache[key] = id
        evict(engine)
        return id
    }

    /** 调用方必须持有 [mutex]。非法返回 null。 */
    private suspend fun idForString(input: String): Int? {
        val key = "str/$input"
        cache[key]?.let { return it }
        val engine = runtime()
        val id = nextId++
        val err = engine.evalGuarded<String>("__rxCompileFromString($id, ${lit(input)})")
        if (err.isNotEmpty()) return null
        cache[key] = id
        evict(engine)
        return id
    }

    private suspend fun evict(engine: QuickJs) {
        while (cache.size > cacheSize) {
            val eldest = cache.entries.first()
            cache.remove(eldest.key)
            engine.evalGuarded<Unit>("__rxFree(${eldest.value})")
        }
    }

    private inner class StringHandle(private val input: String) : JsRegex {
        // 源与标志在编译后才知道；这里只给诊断用
        override val source: String get() = input
        override val flags: String get() = ""

        override suspend fun test(text: String): Boolean = mutex.withLock {
            val id = idForString(input) ?: return@withLock false
            runtime().evalGuarded<Boolean>("__rxTest($id, ${lit(text)})")
        }

        override suspend fun matchAll(text: String): List<JsMatch> = mutex.withLock {
            val id = idForString(input) ?: return@withLock emptyList()
            parse(runtime().evalGuarded<String>("__rxMatchAll($id, ${lit(text)})"))
        }
    }

    private inner class Handle(override val source: String, override val flags: String) : JsRegex {
        override suspend fun test(text: String): Boolean = mutex.withLock {
            val id = idFor(source, flags)
            runtime().evalGuarded<Boolean>("__rxTest($id, ${lit(text)})")
        }

        override suspend fun matchAll(text: String): List<JsMatch> = mutex.withLock {
            val id = idFor(source, flags)
            val raw = runtime().evalGuarded<String>("__rxMatchAll($id, ${lit(text)})")
            parse(raw)
        }
    }

    private suspend inline fun <reified T> QuickJs.evalGuarded(code: String): T = try {
        evaluate<T>(code)
    } catch (e: QuickJsInterruptedException) {
        throw JsRegexTimeoutException("regex evaluation exceeded ${evalTimeoutMillis}ms")
    } catch (e: QuickJsException) {
        throw JsRegexSyntaxException(e.message ?: "QuickJS error")
    }

    private fun lit(s: String): String = JsonPrimitive(s).toString()

    private fun parse(raw: String): List<JsMatch> {
        val arr = Json.parseToJsonElement(raw).jsonArray
        return arr.map { e ->
            val m = e.jsonArray
            JsMatch(
                index = m[0].jsonPrimitive.int,
                text = m[1].jsonPrimitive.content,
                groups = m[2].jsonArray.map { g -> if (g is JsonNull) null else g.jsonPrimitive.contentOrNull },
                named = (m[3] as JsonArray).associate { p ->
                    val pair = p.jsonArray
                    pair[0].jsonPrimitive.content to (if (pair[1] is JsonNull) null else pair[1].jsonPrimitive.contentOrNull)
                },
            )
        }
    }

    private companion object {
        val BOOTSTRAP = """
            var __rx = new Map();
            function __rxCompile(id, src, flags) {
              try { __rx.set(id, new RegExp(src, flags)); return ''; }
              catch (e) { return String(e && e.message || e); }
            }
            // ST:public/scripts/utils.js:1387 regexFromString，逐字保留
            function __rxFromString(input) {
              try {
                var m = input.match(/(\/?)(.+)\1([a-z]*)/i);
                if (m[3] && !/^(?!.*?(.).*?\1)[gmixXsuUAJ]+$/.test(m[3])) { return RegExp(input); }
                return new RegExp(m[2], m[3]);
              } catch (e) { return undefined; }
            }
            function __rxCompileFromString(id, input) {
              var r = __rxFromString(input);
              if (!r) return 'invalid';
              __rx.set(id, r); return '';
            }
            function __rxFree(id) { __rx.delete(id); }
            function __rxTest(id, s) { var r = __rx.get(id); r.lastIndex = 0; return r.test(s); }
            function __rxRec(m) {
              var named = [];
              if (m.groups) { for (var k of Object.keys(m.groups)) named.push([k, m.groups[k] === undefined ? null : m.groups[k]]); }
              return [m.index, m[0], Array.prototype.slice.call(m, 1), named];
            }
            function __rxMatchAll(id, s) {
              var r = __rx.get(id), out = [];
              r.lastIndex = 0;
              if (r.global) { for (var m of s.matchAll(r)) out.push(__rxRec(m)); }
              else { var m1 = r.exec(s); if (m1) out.push(__rxRec(m1)); }
              return JSON.stringify(out);
            }
            void 0;
        """.trimIndent()
    }
}
