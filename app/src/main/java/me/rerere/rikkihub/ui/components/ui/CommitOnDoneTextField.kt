package me.rerere.rikkihub.ui.components.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation

/**
 * 文本字段先写本地 state，仅在失焦 / IME Done / 离开组合时才提交，
 * 避免每次按键都触发整棵设置树重组和 DataStore 写入。
 *
 * 外部 [value] 变化时（例如重置、切换对象）本地文本自动重新同步。
 *
 * [commitOnChange] 为 true 时每次输入都立即提交，用于 [onCommit] 只更新本地草稿、
 * 再由保存按钮统一落盘的场景：点击按钮不会让输入框失焦，延迟提交会丢掉最后一次输入。
 */
@Composable
fun CommitOnDoneTextField(
    value: String,
    onCommit: (String) -> Unit,
    label: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: (String) -> Boolean = { false },
    supportingText: (@Composable (String) -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    transform: (String) -> String = { it },
    commitOnChange: Boolean = false,
) {
    var text by remember(value) { mutableStateOf(value) }
    val currentValue by rememberUpdatedState(value)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentTransform by rememberUpdatedState(transform)

    fun commit() {
        val newValue = transform(text)
        if (newValue != value) {
            onCommit(newValue)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val newValue = currentTransform(text)
            if (newValue != currentValue) {
                currentOnCommit(newValue)
            }
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            if (commitOnChange) commit()
        },
        label = label,
        modifier = modifier.onFocusChanged { focusState ->
            if (!focusState.isFocused) {
                commit()
            }
        },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        textStyle = textStyle,
        placeholder = placeholder,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        isError = isError(text),
        supportingText = supportingText?.let { st -> { st(text) } },
        keyboardOptions = if (singleLine && keyboardOptions.imeAction == ImeAction.Default) {
            keyboardOptions.copy(imeAction = ImeAction.Done)
        } else {
            keyboardOptions
        },
        keyboardActions = if (singleLine && keyboardActions == KeyboardActions.Default) {
            KeyboardActions(onDone = { commit() })
        } else {
            keyboardActions
        },
    )
}
