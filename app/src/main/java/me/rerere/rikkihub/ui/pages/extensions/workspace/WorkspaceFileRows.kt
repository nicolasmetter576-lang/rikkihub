package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * File rows follow the settings page groups (CardGroup): one-line ListItem height (56dp),
 * 2dp between rows, 20dp outer / 4dp inner corners. Other blocks keep the 12dp list spacing.
 */
internal val WorkspaceFileRowMinHeight = 56.dp
internal val WorkspaceFileRowSpacing = 2.dp
private val WorkspaceListBlockSpacing = 12.dp
private val WorkspaceFileRowOuterCorner = 20.dp
private val WorkspaceFileRowInnerCorner = 4.dp

/** Shape of the row at [index] of [count] rows, rounded like one settings card group. */
internal fun workspaceFileRowShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) WorkspaceFileRowOuterCorner else WorkspaceFileRowInnerCorner
    val bottom = if (index == count - 1) WorkspaceFileRowOuterCorner else WorkspaceFileRowInnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A non-row block in a list spaced by [WorkspaceFileRowSpacing]: keeps the usual 12dp gap after it. */
internal fun LazyListScope.workspaceBlock(content: @Composable LazyItemScope.() -> Unit) = item {
    Column {
        content()
        Spacer(Modifier.height(WorkspaceListBlockSpacing - WorkspaceFileRowSpacing))
    }
}
