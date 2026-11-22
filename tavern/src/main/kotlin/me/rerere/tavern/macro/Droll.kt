package me.rerere.tavern.macro

import me.rerere.tavern.core.TavernRandom

/**
 * `droll` 骰子公式的移植：`ST:node_modules/droll/droll.js:48-103`。
 * 语法 `^([1-9]\d*)?d([1-9]\d*)([+-]\d+)?$`；每个骰子 `1 + floor(random()*sides)`。
 */
internal object Droll {
    private val FORMULA = Regex("^([1-9]\\d*)?d([1-9]\\d*)([+-]\\d+)?$", RegexOption.IGNORE_CASE)

    fun validate(formula: String): Boolean = FORMULA.containsMatchIn(formula)

    /** 返回 [numDice, numSides, modifier]；无效返回 null。 */
    private fun parse(formula: String): Triple<Int, Int, Int>? {
        val m = FORMULA.matchEntire(formula) ?: return null
        val numDice = m.groupValues[1].toIntOrNull() ?: 1
        val numSides = m.groupValues[2].toInt()
        val modifier = m.groupValues[3].toIntOrNull() ?: 0
        return Triple(numDice, numSides, modifier)
    }

    /** 掷骰并返回 total；无效公式返回 null（上游 droll.roll 返回 false → 宏输出 ''）。 */
    fun roll(formula: String, random: TavernRandom): Int? {
        val (numDice, numSides, modifier) = parse(formula) ?: return null
        var total = modifier
        repeat(numDice) {
            total += 1 + kotlin.math.floor(random.nextDouble() * numSides).toInt()
        }
        return total
    }
}
