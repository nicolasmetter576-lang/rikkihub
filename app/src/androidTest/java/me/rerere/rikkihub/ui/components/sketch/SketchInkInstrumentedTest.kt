package me.rerere.rikkihub.ui.components.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Ink 笔画、文字、选择和导出要用到原生库与 Android 排版，只能在设备上跑。 */
@RunWith(AndroidJUnit4::class)
class SketchInkInstrumentedTest {
    private fun stroke(from: Offset, to: Offset, brush: SketchBrush = SketchBrush.Marker): Stroke {
        val inputs = MutableStrokeInputBatch()
        val steps = 10
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            inputs.add(
                type = InputToolType.TOUCH,
                x = from.x + (to.x - from.x) * t,
                y = from.y + (to.y - from.y) * t,
                elapsedTimeMillis = i * 16L,
            )
        }
        return Stroke(brush.create(Color.Black, 6f), inputs)
    }

    private fun paper(): SketchState = SketchState(aspectRatio = 1f).apply { fit(Size(400f, 400f)) }

    @Test
    fun erasingUndoRedoAndClearTrackBlankness() {
        val state = SketchState()
        assertTrue(state.isBlank)
        val line = state.erase(Offset.Zero, 3f)
        state.extend(line, Offset(5f, 5f))
        state.finish(line)
        assertFalse(state.isBlank)
        assertTrue(state.canUndo)

        state.clear()
        assertTrue("a cleared paper counts as blank", state.isBlank)
        state.undo()
        assertFalse(state.isBlank)
        assertTrue(state.canRedo)
        state.redo()
        assertTrue(state.isBlank)

        // 新的一步清掉重做栈
        state.undo()
        state.erase(Offset(1f, 1f), 3f)
        assertFalse(state.canRedo)
    }

    @Test
    fun paperSizeIsFixedOnceSomethingIsDrawn() {
        val state = SketchState(aspectRatio = 1f)
        state.fit(Size(400f, 800f))
        state.erase(Offset.Zero, 2f)
        // 落笔之后再变可用区域（旋转屏幕）不改画纸的尺寸，只整体缩放显示
        state.fit(Size(800f, 400f))
        assertEquals(400f, state.paperSize.width, 0.01f)
    }

    @Test
    fun everyBrushBuildsAStroke() {
        SketchBrush.entries.forEach { brush ->
            val step = SketchStep.Ink(stroke(Offset(-50f, -50f), Offset(50f, 50f), brush))
            assertTrue("${brush.name} should cover the line", step.bounds.width > 0f)
        }
    }

    @Test
    fun selectionUsesTheStrokeShapeNotItsBoundingBox() {
        val state = paper()
        // 一条斜线：右上角的空白在它的包围盒里，但没碰到笔画
        state.draw(listOf(stroke(Offset(-100f, 100f), Offset(100f, -100f))))
        state.select(Rect(-100f, -100f, -80f, -80f), topmost = false)
        assertTrue(state.selection.isEmpty())
        state.select(Rect(-10f, -10f, 10f, 10f), topmost = false)
        assertEquals(1, state.selection.size)
    }

    @Test
    fun moveIsOneUndoableStep() {
        val state = paper()
        state.draw(listOf(stroke(Offset(-20f, 0f), Offset(20f, 0f))))
        state.select(Rect(-5f, -5f, 5f, 5f), topmost = true)
        val before = state.selectionBounds!!
        state.moving = SketchPlacement.move(Offset(50f, 0f))
        state.settle()
        assertEquals(before.left + 50f, state.selectionBounds!!.left, 0.5f)
        state.undo()
        assertTrue(state.selection.isEmpty())
        val item = state.steps.filterIsInstance<SketchStep.Ink>().single()
        assertEquals(before.left, state.placements().getValue(item).apply(item.bounds).left, 0.5f)
    }

    @Test
    fun textWrapsInsideThePaper() {
        val state = paper()
        state.beginText(Offset(100f, 0f), width = 6f)
        state.write("  a fairly long sentence that cannot fit in one line  ")
        val text = state.steps.filterIsInstance<SketchStep.Text>().single()
        assertEquals("a fairly long sentence that cannot fit in one line", text.text)
        assertTrue(text.layout.lineCount > 1)
        assertTrue(text.bounds.right <= state.paperSize.width / 2 + 1f)
    }

    @Test
    fun blankPaperExportsAPngAndPhotoExportsAJpegAtImageResolution() {
        val blank = paper()
        blank.draw(listOf(stroke(Offset(-50f, 0f), Offset(50f, 0f))))
        val sketch = blank.toResult()
        assertEquals("png", sketch.extension)
        assertEquals(1536, maxOf(sketch.bitmap.width, sketch.bitmap.height))
        assertTrue(sketch.encode().isNotEmpty())

        val photo = SketchState().apply {
            fit(Size(400f, 400f))
            useBackground(ImageBitmap(800, 600))
            draw(listOf(stroke(Offset(-50f, 0f), Offset(50f, 0f))))
        }
        val result = photo.toResult()
        assertEquals("jpg", result.extension)
        assertEquals(800, result.bitmap.width)
        assertEquals(600, result.bitmap.height)
        // 画了线的地方不再是底图的颜色
        val center = result.bitmap.getPixel(400, 300)
        assertNotEquals(0, center)
    }
}
