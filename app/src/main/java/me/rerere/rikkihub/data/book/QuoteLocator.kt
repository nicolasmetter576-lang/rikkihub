package me.rerere.rikkihub.data.book

/** 在正文里找模型给的引文：逐字找，找不到再忽略空白、引号和强调符号的差别。返回正文里的原文区间。 */
internal object QuoteLocator {
    private const val MIN_LOOSE_CHARS = 6
    private val QUOTES = "“”„‟\"「」『』‘’'＂＇".toSet()
    private val IGNORED = "*_~`#>\\".toSet()

    fun locate(text: String, quote: String): IntRange? {
        val wanted = quote.trim()
        if (wanted.isEmpty()) return null
        val exact = text.indexOf(wanted)
        if (exact >= 0) return exact until exact + wanted.length
        val (loose, positions) = simplify(text)
        val (looseQuote, _) = simplify(wanted)
        // 太短的忽略差别之后容易撞上别处
        if (looseQuote.length < MIN_LOOSE_CHARS) return null
        val at = loose.indexOf(looseQuote)
        if (at < 0) return null
        return positions[at]..positions[at + looseQuote.length - 1]
    }

    private fun simplify(text: String): Pair<String, IntArray> {
        val out = StringBuilder()
        val positions = IntArray(text.length)
        text.forEachIndexed { index, c ->
            if (c.isWhitespace() || c == '　' || c in QUOTES || c in IGNORED) return@forEachIndexed
            positions[out.length] = index
            out.append(c)
        }
        return out.toString() to positions
    }
}
