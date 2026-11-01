package me.rerere.rikkihub.ui.components.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchStateTest {
    @Test
    fun `placement composes scale then offset and inverts`() {
        val move = SketchPlacement.move(Offset(10f, 0f))
        val zoom = SketchPlacement.scale(2f, around = Offset(5f, 5f))
        val both = move.then(zoom)
        val point = Offset(1f, 1f)
        assertEquals(zoom.apply(move.apply(point)), both.apply(point))

        val rect = Rect(0f, 0f, 4f, 4f)
        val placed = both.apply(rect)
        val back = both.invert(placed)
        assertEquals(rect.left, back.left, 1e-4f)
        assertEquals(rect.bottom, back.bottom, 1e-4f)
        // 以不动点缩放，不动点本身不动
        assertEquals(Offset(5f, 5f), zoom.apply(Offset(5f, 5f)))
    }

    @Test
    fun `paper fills the available area until something is drawn`() {
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(400f, 800f))
        assertEquals(Size(400f, 400f), state.paperSize)

        state.useAspectRatio(16f / 9f)
        assertEquals(400f, state.paperSize.width, 0.01f)
        assertEquals(225f, state.paperSize.height, 0.01f)

        // 还没落笔时画纸跟着可用区域走
        state.fit(Size(800f, 400f))
        assertEquals(400f, state.paperSize.height, 0.01f)
        assertEquals(400f * 16f / 9f, state.paperSize.width, 0.01f)
    }

    @Test
    fun `choosing a preset colour leaves the eraser`() {
        val state = SketchState()
        state.useEraser(true)
        assertTrue(state.erasing)
        state.usePreset(SketchDefaults.BrushColors[2])
        assertFalse(state.erasing)
        assertEquals(SketchTool.Brush, state.tool)
        assertEquals(SketchDefaults.BrushColors[2], state.color)
    }

    @Test
    fun `fit helper keeps the aspect ratio inside the box`() {
        assertEquals(Size(100f, 50f), Size(100f, 100f).fit(2f))
        assertEquals(Size(50f, 100f), Size(100f, 100f).fit(0.5f))
    }
}
