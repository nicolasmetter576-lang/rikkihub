package me.rerere.rikkihub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmoledColorsTest {
    private fun contrastOnBlack(color: Color) = (color.luminance() + 0.05f) / 0.05f

    private fun ColorScheme.neutralSurfaces() = listOf(
        background, surface, surfaceVariant, surfaceDim, surfaceBright,
        surfaceContainerLowest, surfaceContainerLow, surfaceContainer,
        surfaceContainerHigh, surfaceContainerHighest,
    )

    private val darkSchemes = PresetThemes.map { it.id to it.standardDark } +
        ("custom" to CustomTheme(primaryColorArgb = 0xFF00897B).generateColorScheme(dark = true)) +
        ("m3-default" to darkColorScheme())

    @Test
    fun everyNeutralSurfaceTurnsPureBlack() {
        darkSchemes.forEach { (id, scheme) ->
            scheme.toAmoledStrong().neutralSurfaces().forEach { color ->
                assertEquals(id, Color.Black, color)
            }
        }
    }

    @Test
    fun textStaysComfortableOnBlack() {
        darkSchemes.forEach { (id, scheme) ->
            val strong = scheme.toAmoledStrong()
            val body = contrastOnBlack(strong.onSurface)
            assertTrue("$id onSurface $body", body in 14f..16.6f)
            assertEquals(id, strong.onSurface, strong.onBackground)
            val secondary = contrastOnBlack(strong.onSurfaceVariant)
            assertTrue("$id onSurfaceVariant $secondary", secondary in 9f..12.2f)
            val hairline = contrastOnBlack(strong.outlineVariant)
            assertTrue("$id outlineVariant $hairline", hairline in 1.9f..2.8f)
        }
    }

    @Test
    fun pureWhiteTextIsToneddown() {
        val strong = darkColorScheme(onSurface = Color.White, onSurfaceVariant = Color(0xFF555555)).toAmoledStrong()
        assertTrue(contrastOnBlack(strong.onSurface) < 17f)
        assertTrue(contrastOnBlack(strong.onSurfaceVariant) >= 9f)
    }

    @Test
    fun accentRolesAreKept() {
        darkSchemes.forEach { (id, scheme) ->
            val strong = scheme.toAmoledStrong()
            assertEquals(id, scheme.primary, strong.primary)
            assertEquals(id, scheme.primaryContainer, strong.primaryContainer)
            assertEquals(id, scheme.secondaryContainer, strong.secondaryContainer)
            assertEquals(id, scheme.surfaceTint, strong.surfaceTint)
            assertEquals(id, scheme.error, strong.error)
        }
    }
}
