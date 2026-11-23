package me.rerere.rikkihub.data.book

/** 章号的读写：阿拉伯数字（含全角）与中文数字。 */
internal object ChapterNumbers {
    private const val DIGITS = "零一二三四五六七八九"
    private val UNITS = mapOf('十' to 10, '百' to 100, '千' to 1000)

    private fun digit(c: Char): Int? = when (c) {
        '〇', '○', '◯' -> 0
        '两' -> 2
        else -> DIGITS.indexOf(c).takeIf { it >= 0 }
    }

    fun parse(text: String): Int? {
        val normalized = text.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("")
        if (normalized.isEmpty()) return null
        if (normalized.all { it in '0'..'9' }) return normalized.toIntOrNull()
        val chinese = normalized.replace("廿", "二十").replace("卅", "三十")
        if (chinese.any { digit(it) == null && it !in UNITS && it != '万' }) return null
        if (chinese.none { it in UNITS || it == '万' }) {
            return chinese.map { digit(it)!! }.joinToString("").toIntOrNull()
        }
        var total = 0
        var section = 0
        var current = 0
        for (c in chinese) {
            when {
                c == '万' -> {
                    total += (section + current) * 10_000
                    section = 0
                    current = 0
                }
                c in UNITS -> {
                    section += (if (current == 0) 1 else current) * UNITS.getValue(c)
                    current = 0
                }
                else -> current = digit(c)!!
            }
        }
        return total + section + current
    }

    fun toChinese(value: Int): String {
        require(value in 1..99_999) { "Out of range: $value" }
        if (value >= 10_000) {
            val rest = value % 10_000
            return toChinese(value / 10_000) + "万" + when {
                rest == 0 -> ""
                rest < 1000 -> "零" + toChinese(rest)
                else -> toChinese(rest)
            }
        }
        val out = StringBuilder()
        var zero = false
        var rest = value
        for ((unit, base) in listOf("千" to 1000, "百" to 100, "十" to 10, "" to 1)) {
            val d = rest / base
            rest %= base
            if (d == 0) {
                if (out.isNotEmpty() && rest > 0) zero = true
                continue
            }
            if (zero) out.append('零')
            zero = false
            out.append(DIGITS[d]).append(unit)
        }
        return if (value in 10..19) out.toString().removePrefix("一") else out.toString()
    }

    private const val NUMBER = "[0-9０-９零〇○◯一二三四五六七八九十百千万两廿卅]+"
    private val LEADING = Regex("^(\\d+)([\\s._\\-—、　]*)")
    private val ORDINAL = Regex("第(\\s*)($NUMBER)(\\s*[章节回折幕话])")
    private val ENGLISH = Regex("((?:chapter|ch)[\\s._-]*)(\\d+)", RegexOption.IGNORE_CASE)

    /** 照 [fileName] 的写法给出下一章的文件名（不含章名）；看不出章号时返回 null。 */
    fun nextFileName(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        val stem = fileName.removeSuffix(extension)
        val leading = LEADING.find(stem)
        val body = if (leading != null) stem.substring(leading.range.last + 1) else stem
        val prefix = leading?.let {
            val digits = it.groupValues[1]
            ((digits.toLongOrNull() ?: return null) + 1).toString().padStart(digits.length, '0') + it.groupValues[2]
        }.orEmpty()
        val ordinal = ORDINAL.find(body)
        val english = ENGLISH.find(body)
        val next = when {
            ordinal != null -> {
                val (gap, number, unit) = ordinal.destructured
                val value = (parse(number) ?: return null) + 1
                body.substring(0, ordinal.range.first) + "第" + gap + like(number, value) + unit
            }
            english != null -> body.substring(0, english.range.first) + english.groupValues[1] + like(english.groupValues[2], english.groupValues[2].toInt() + 1)
            leading != null -> ""
            else -> return null
        }
        return (prefix + next).trimEnd(' ', '.', '_', '-', '—', '、', '　') + extension
    }

    private fun like(sample: String, value: Int): String = when {
        sample.all { it in '0'..'9' } -> value.toString().padStart(sample.length, '0')
        sample.all { it in '０'..'９' } -> value.toString().map { '０' + (it - '0') }.joinToString("")
        else -> toChinese(value)
    }
}
