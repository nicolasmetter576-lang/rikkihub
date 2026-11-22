package me.rerere.tavern.macro.chevrotain

/**
 * chevrotain 11.2.0 的最小移植：只含 SillyTavern 宏语法用到的部分（词法的 token 类型、GAST 语法树、
 * LL(k) 前瞻、错误恢复、CST）。
 *
 * 为什么要移植而不是手写递归下降：上游宏解析器开了 `recoveryEnabled`，遇到不完整的 `{{...`
 * 时的行为（哪些宏仍被求值、哪些原样输出）完全由 chevrotain 的恢复算法决定，
 * 手写解析器很难在所有畸形输入上与它一致。这里按 chevrotain 源码逐函数照搬，并用随机畸形输入
 * 对真 chevrotain 做差分验证（见 tavern/oracle/macro/）。
 *
 * 对应 chevrotain 源码：node_modules/chevrotain/lib/src/parse/{grammar,parser/traits}、
 * node_modules/@chevrotain/gast。
 */

/** token 类型，按引用比较（没有 token 分类）。 */
class TokenType(val name: String) {
    val id: Int = nextId.getAndIncrement()
    override fun toString() = name

    private companion object {
        val nextId = java.util.concurrent.atomic.AtomicInteger()
    }
}

/** 对应 chevrotain 的 EOF 类型。 */
val EOF_TYPE = TokenType("EOF")

/**
 * 偏移量用 Double 保存：恢复时插入的 token 偏移量是 NaN，上游 MacroCstWalker 对 NaN 的比较行为（全为 false）
 * 会影响输出，必须原样保留。
 */
class Token(
    val type: TokenType,
    val image: String,
    val startOffset: Double,
    val endOffset: Double,
    val isInsertedInRecovery: Boolean = false,
) {
    override fun toString() = "$type($image)"
}

val END_OF_FILE = Token(EOF_TYPE, "", Double.NaN, Double.NaN)

// ---------------------------------------------------------------------------------------------
// GAST（@chevrotain/gast model.js）。idx 与上游录制出的出现序号一致：无后缀的 DSL 方法为 0。
// ---------------------------------------------------------------------------------------------

sealed class Prod {
    abstract val definition: List<Prod>
}

class Terminal(val type: TokenType, val idx: Int = 0, val label: String? = null) : Prod() {
    override val definition: List<Prod> get() = emptyList()
}

class NonTerminal(val ruleName: String, val idx: Int = 0, val label: String? = null) : Prod() {
    lateinit var referencedRule: Rule
    override val definition: List<Prod> get() = referencedRule.definition
}

class Alternative(override val definition: List<Prod>) : Prod()
class Option(val idx: Int, override val definition: List<Prod>) : Prod()
class Repetition(val idx: Int, override val definition: List<Prod>) : Prod()
class RepetitionMandatory(val idx: Int, override val definition: List<Prod>) : Prod()
class RepetitionMandatoryWithSeparator(val idx: Int, val separator: TokenType, override val definition: List<Prod>) : Prod()
class Alternation(val idx: Int, override val definition: List<Alternative>) : Prod()
class Rule(val name: String, override val definition: List<Prod>) : Prod()

/** 语法构建 DSL：与 chevrotain 的 `$.RULE(...)` 内的写法一一对应。 */
class SeqBuilder {
    internal val items = mutableListOf<Prod>()

    fun consume(type: TokenType, idx: Int = 0, label: String? = null) {
        items += Terminal(type, idx, label)
    }

    fun subrule(rule: String, idx: Int = 0, label: String? = null) {
        items += NonTerminal(rule, idx, label)
    }

    fun option(idx: Int = 0, body: SeqBuilder.() -> Unit) {
        items += Option(idx, SeqBuilder().apply(body).items)
    }

    fun many(idx: Int = 0, body: SeqBuilder.() -> Unit) {
        items += Repetition(idx, SeqBuilder().apply(body).items)
    }

    fun atLeastOne(idx: Int = 0, body: SeqBuilder.() -> Unit) {
        items += RepetitionMandatory(idx, SeqBuilder().apply(body).items)
    }

    fun atLeastOneSep(sep: TokenType, idx: Int = 0, body: SeqBuilder.() -> Unit) {
        items += RepetitionMandatoryWithSeparator(idx, sep, SeqBuilder().apply(body).items)
    }

    fun or(idx: Int = 0, vararg alts: SeqBuilder.() -> Unit) {
        items += Alternation(idx, alts.map { Alternative(SeqBuilder().apply(it).items) })
    }
}

class GrammarBuilder {
    internal val rules = LinkedHashMap<String, Rule>()

    fun rule(name: String, body: SeqBuilder.() -> Unit) {
        rules[name] = Rule(name, SeqBuilder().apply(body).items)
    }

    fun build(): Map<String, Rule> {
        // 解析规则引用（chevrotain 的 resolveGrammar）
        fun resolve(defs: List<Prod>) {
            for (p in defs) {
                if (p is NonTerminal) p.referencedRule = rules[p.ruleName] ?: error("rule ${p.ruleName} not defined")
                else if (p !is Terminal) resolve(p.definition)
            }
        }
        rules.values.forEach { resolve(it.definition) }
        return rules
    }
}
