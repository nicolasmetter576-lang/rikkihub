package me.rerere.asr

import kotlin.math.log10
import kotlin.math.sqrt

private const val MAX_AMPLITUDES = 32

/**
 * 直接按字节解析 PCM16LE 采样 (buffer[i] | buffer[i+1]<<8) 计算 RMS 振幅,
 * 避免 ByteBuffer/ShortBuffer 的包装开销。返回 0f~1f 的分贝归一化值。
 */
fun calculateRmsAmplitude(buffer: ByteArray, readBytes: Int): Float {
    val count = readBytes / 2
    if (count == 0) return 0f
    var sum = 0.0
    var i = 0
    while (i + 1 < readBytes) {
        val sample = (buffer[i].toInt() and 0xFF or (buffer[i + 1].toInt() shl 8)).toShort().toDouble()
        sum += sample * sample
        i += 2
    }
    val rms = sqrt(sum / count)
    val linear = (rms / Short.MAX_VALUE).toFloat()
    if (linear < 1e-6f) return 0f
    val db = 20f * log10(linear)
    // -60dB ~ 0dB -> 0f ~ 1f
    return ((db + 60f) / 60f).coerceIn(0f, 1f)
}

/**
 * 追加一个振幅采样 (copy-on-write)。返回同一实例表示值未变化,
 * 让 StateFlow 的 equals 去重直接跳过这次发射, 降低更新频率。
 */
fun ArrayDeque<Float>.appendAmplitude(amplitude: Float): ArrayDeque<Float> {
    if (lastOrNull() == amplitude) return this
    val next = ArrayDeque(this)
    if (next.size >= MAX_AMPLITUDES) next.removeFirst()
    next.addLast(amplitude)
    return next
}
