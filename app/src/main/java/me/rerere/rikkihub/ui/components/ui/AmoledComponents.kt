package me.rerere.rikkihub.ui.components.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import me.rerere.rikkihub.ui.theme.LocalAmoledStrong
import me.rerere.rikkihub.ui.theme.amoledOutline
import me.rerere.rikkihub.ui.theme.amoledOutlineBorder

/*
 * 与 Material3 同名同参的对话框、底部面板、下拉菜单和色调按钮，只声明本项目用到的参数。
 * AMOLED 强劲模式下：浮层的底色和页面同为纯黑、阴影在黑底上也看不见，加一圈细描边分出边界；
 * 色调按钮去掉底色（文字按钮改成描边样式）。其他模式与 Material3 完全一致。
 * 页面里改成 import 这里的版本即可，调用处不用动。
 */

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest,
    confirmButton = confirmButton,
    modifier = modifier.amoledOutline(AlertDialogDefaults.shape),
    dismissButton = dismissButton,
    icon = icon,
    title = title,
    text = text,
    properties = properties,
)

@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetGesturesEnabled: Boolean = true,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val outline = amoledOutlineBorder()
    val shape = BottomSheetDefaults.ExpandedShape
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // 面板拖动/滑入的位移在这个修饰符之后才生效，描边要按面板当前的偏移画
        modifier = if (outline == null) modifier else modifier.drawWithContent {
            drawContent()
            val top = runCatching { sheetState.requireOffset() }.getOrNull() ?: return@drawWithContent
            translate(top = top) {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    brush = outline.brush,
                    style = Stroke(outline.width.toPx()),
                )
            }
        },
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        dragHandle = dragHandle,
        content = content,
    )
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.compose.material3.DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    offset = offset,
    border = amoledOutlineBorder(),
    content = content,
)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: ButtonShapes? = null,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val resolvedColors = colors
        ?: if (LocalAmoledStrong.current) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.filledTonalButtonColors()
    val border = amoledOutlineBorder()
    // 两个重载的默认形状和内边距不同，没传的参数交给 Material3 自己的默认值
    when {
        shapes != null && contentPadding != null -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick, shapes = shapes, modifier = modifier, enabled = enabled,
            colors = resolvedColors, border = border, contentPadding = contentPadding, content = content,
        )
        shapes != null -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick, shapes = shapes, modifier = modifier, enabled = enabled,
            colors = resolvedColors, border = border, content = content,
        )
        contentPadding != null -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick, modifier = modifier, enabled = enabled,
            colors = resolvedColors, border = border, contentPadding = contentPadding, content = content,
        )
        else -> androidx.compose.material3.FilledTonalButton(
            onClick = onClick, modifier = modifier, enabled = enabled,
            colors = resolvedColors, border = border, content = content,
        )
    }
}

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shapes: IconButtonShapes? = null,
    colors: IconButtonColors? = null,
    content: @Composable () -> Unit,
) {
    val resolvedColors = colors ?: if (LocalAmoledStrong.current) {
        IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    } else {
        IconButtonDefaults.filledTonalIconButtonColors()
    }
    if (shapes != null) {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick, shapes = shapes, modifier = modifier, enabled = enabled,
            colors = resolvedColors, content = content,
        )
    } else {
        androidx.compose.material3.FilledTonalIconButton(
            onClick = onClick, modifier = modifier, enabled = enabled, colors = resolvedColors, content = content,
        )
    }
}
