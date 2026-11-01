package me.rerere.rikkihub.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import hct.Hct

internal val AmoledBlack = Color(0xFF000000)

/** AMOLED 强劲模式是否生效（暗色 + AMOLED + 强劲三者都开）。 */
val LocalAmoledStrong = compositionLocalOf { false }

/**
 * AMOLED 强劲模式的配色：中性容器色（按钮、卡片、列表项、侧边栏、输入框、代码块、对话框……）全部改成纯黑。
 * 纯黑上的文字按 HCT 色调夹进舒适区间：正文不到纯白（对比度约 14.6～16.3:1，减轻光晕），
 * 次要文字不低于约 9:1；描边色同样夹住，好让同为纯黑的容器靠细描边分出边界。
 */
internal fun ColorScheme.toAmoledStrong(): ColorScheme {
    val text = onSurface.withToneIn(86.0, 90.0)
    return copy(
        background = AmoledBlack,
        onBackground = text,
        surface = AmoledBlack,
        onSurface = text,
        surfaceVariant = AmoledBlack,
        onSurfaceVariant = onSurfaceVariant.withToneIn(70.0, 78.0),
        surfaceDim = AmoledBlack,
        surfaceBright = AmoledBlack,
        surfaceContainerLowest = AmoledBlack,
        surfaceContainerLow = AmoledBlack,
        surfaceContainer = AmoledBlack,
        surfaceContainerHigh = AmoledBlack,
        surfaceContainerHighest = AmoledBlack,
        outline = outline.withToneIn(55.0, 62.0),
        outlineVariant = outlineVariant.withToneIn(28.0, 34.0),
    )
}

private fun Color.withToneIn(min: Double, max: Double): Color {
    val hct = Hct.fromInt(toArgb())
    val tone = hct.tone.coerceIn(min, max)
    if (tone == hct.tone) return this
    return Color(Hct.from(hct.hue, hct.chroma, tone).toInt()).copy(alpha = alpha)
}

/** 强劲模式下的细描边；其他模式为 null（不描边）。 */
@Composable
fun amoledOutlineBorder(): BorderStroke? =
    if (LocalAmoledStrong.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null

/** 强劲模式下容器底色和页面同为纯黑，加一圈细描边分出边界；其他模式原样返回。 */
@Composable
fun Modifier.amoledOutline(shape: Shape): Modifier =
    if (LocalAmoledStrong.current) border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape) else this
