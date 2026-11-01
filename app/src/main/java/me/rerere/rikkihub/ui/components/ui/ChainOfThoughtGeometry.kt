package me.rerere.rikkihub.ui.components.ui

internal data class ChainLineSegment(val startY: Float, val endY: Float)

/** Draw around the node instead of masking a continuous line with another translucent fill. */
internal fun chainOfThoughtHeaderSegments(
    height: Float,
    nodeHalfHeight: Float,
    isFirst: Boolean,
    isLast: Boolean,
): List<ChainLineSegment> {
    if (!height.isFinite() || !nodeHalfHeight.isFinite() || height <= 0f || nodeHalfHeight < 0f) return emptyList()
    val center = height / 2f
    return buildList {
        val upperEnd = (center - nodeHalfHeight).coerceAtLeast(0f)
        val lowerStart = (center + nodeHalfHeight).coerceAtMost(height)
        if (!isFirst && upperEnd > 0f) add(ChainLineSegment(0f, upperEnd))
        if (!isLast && lowerStart < height) add(ChainLineSegment(lowerStart, height))
    }
}
