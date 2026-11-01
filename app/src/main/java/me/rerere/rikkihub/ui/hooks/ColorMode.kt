package me.rerere.rikkihub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import me.rerere.rikkihub.ui.theme.ColorMode

private const val COLOR_MODE_KEY = "colorMode"

@Composable
fun rememberColorMode(): MutableState<ColorMode> {
    val colorModeState = rememberSharedPreferenceString(COLOR_MODE_KEY, ColorMode.SYSTEM.name)
    return remember(colorModeState) {
        object : MutableState<ColorMode> {
            override var value: ColorMode
                get() = colorModeState.value.toColorMode()
                set(value) {
                    colorModeState.value = value.name
                }

            override fun component1(): ColorMode = value

            override fun component2(): (ColorMode) -> Unit = { value = it }
        }
    }
}

@Composable
fun rememberCurrentColorMode(): ColorMode {
    val colorModeValue by rememberSharedPreferenceString(COLOR_MODE_KEY, ColorMode.SYSTEM.name)
    return colorModeValue.toColorMode()
}

@Composable
fun rememberAmoledDarkMode(): MutableState<Boolean> {
    return rememberSharedPreferenceBoolean("amoledDark", false)
}

/** AMOLED 强劲模式：按钮、卡片、侧边栏等容器底色也改成纯黑，只在 AMOLED 暗色模式开着时生效。 */
@Composable
fun rememberAmoledStrongMode(): MutableState<Boolean> {
    return rememberSharedPreferenceBoolean("amoledDarkStrong", false)
}

private fun String?.toColorMode(): ColorMode {
    return ColorMode.entries.firstOrNull { it.name == this } ?: ColorMode.SYSTEM
}
