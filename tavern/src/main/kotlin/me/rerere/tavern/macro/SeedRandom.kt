package me.rerere.tavern.macro

/**
 * `seedrandom`（David Bau, ARC4）的确定性路径移植：`ST:node_modules/seedrandom/seedrandom.js:44-147`。
 * `{{pick}}` 用 `seedrandom(String(finalSeed))` 无熵调用，这里只需要 mixkey + ARC4 + prng()。
 */
internal class SeedRandom(seed: String) {
    private val s = IntArray(WIDTH)
    private var i = 0
    private var j = 0

    init {
        val key = IntArray(WIDTH)
        var smear = 0
        // ST:seedrandom.js:179 mixkey
        for (c in seed.indices) {
            val idx = MASK and c
            smear = smear xor (key[idx] * 19)
            key[idx] = MASK and (smear + seed[c].code)
        }
        // ST:seedrandom.js:116 ARC4 构造：keylen = key.length = min(seed.length, 256)（稀疏数组长度），
        // 短种子不会用到未写的 0 槽位；空 key 按上游的 [0] 防一手
        var keylen = minOf(seed.length, WIDTH)
        val keyArr = if (keylen == 0) intArrayOf(0).also { keylen = 1 } else key
        var t: Int
        var jj = 0
        for (k in 0 until WIDTH) s[k] = k
        // KSA 的 i/j 是局部变量；g() 用的 me.i/me.j 保持 0（上游 me.i = me.j = 0）
        for (k in 0 until WIDTH) {
            t = s[k]
            jj = MASK and (jj + keyArr[k % keylen] + t)
            s[k] = s[jj]
            s[jj] = t
        }
        // RC4-drop[256]
        g(WIDTH)
    }

    /** ST:seedrandom.js:133 ARC4.g(count)：count 个字节拼成一个数，r = r*256 + s。 */
    private fun g(count: Int): Double {
        var n = count
        var r = 0.0
        var t: Int
        while (n-- > 0) {
            i = MASK and (i + 1)
            t = s[i]
            j = MASK and (j + t)
            val ti = s[j]
            s[i] = ti
            s[j] = t
            r = r * WIDTH + s[MASK and (ti + t)]
        }
        return r
    }

    /** ST:seedrandom.js:58 prng()：[0,1) 均匀 double，52 位有效位。 */
    fun nextDouble(): Double {
        var n = g(CHUNKS)
        var d = STARTDENOM
        var x = 0.0
        while (n < SIGNIFICANCE) {
            n = (n + x) * WIDTH
            d *= WIDTH
            x = g(1)
        }
        while (n >= OVERFLOW) {
            n /= 2
            d /= 2
            x = floor2(x)
        }
        return (n + x) / d
    }

    /** `x >>>= 1`：x 只有 0..255，无符号右移一位。 */
    private fun floor2(x: Double): Double {
        val v = x.toLong() and 0xFF
        return (v ushr 1).toDouble()
    }

    companion object {
        private const val WIDTH = 256
        private const val CHUNKS = 6
        private val STARTDENOM = Math.pow(WIDTH.toDouble(), CHUNKS.toDouble())
        private val SIGNIFICANCE = Math.pow(2.0, 52.0)
        private const val OVERFLOW = 2 * 4.503599627370496E15 // significance * 2 = 2^53
        private const val MASK = WIDTH - 1
    }
}
