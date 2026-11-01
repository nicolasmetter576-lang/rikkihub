package me.rerere.rikkihub.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arithmetic used by the actual draw modifier; not a GPU/Compose pixel test. */
class ChainOfThoughtGeometryTest {
    @Test fun `first visible node draws only its lower segment`() {
        assertEquals(listOf(ChainLineSegment(28f, 36f)), chainOfThoughtHeaderSegments(36f, 10f, true, false))
    }
    @Test fun `middle node leaves the full twenty pixel gap without an alpha mask`() {
        assertEquals(listOf(ChainLineSegment(0f, 8f), ChainLineSegment(28f, 36f)),
            chainOfThoughtHeaderSegments(36f, 10f, false, false))
    }
    @Test fun `last visible node draws only its upper segment`() {
        assertEquals(listOf(ChainLineSegment(0f, 8f)), chainOfThoughtHeaderSegments(36f, 10f, false, true))
    }
    @Test fun `single visible node has no spurious vertical line`() {
        assertTrue(chainOfThoughtHeaderSegments(36f, 10f, true, true).isEmpty())
    }
    @Test fun `multiline labels center the node gap on their actual row height`() {
        assertEquals(listOf(ChainLineSegment(0f, 22f), ChainLineSegment(42f, 64f)),
            chainOfThoughtHeaderSegments(64f, 10f, false, false))
    }
    @Test fun `scaling all lengths preserves segment geometry`() {
        assertEquals(listOf(ChainLineSegment(0f, 24f), ChainLineSegment(84f, 108f)),
            chainOfThoughtHeaderSegments(108f, 30f, false, false))
    }
    @Test fun `short rows do not draw reversed or zero length segments`() {
        listOf(0f, 1f, 12f, 20f).forEach {
            assertTrue(chainOfThoughtHeaderSegments(it, 10f, false, false).isEmpty())
        }
    }
    @Test fun `invalid geometry fails closed without nonfinite draw calls`() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertTrue(chainOfThoughtHeaderSegments(it, 10f, false, false).isEmpty())
            assertTrue(chainOfThoughtHeaderSegments(36f, it, false, false).isEmpty())
        }
    }
}
