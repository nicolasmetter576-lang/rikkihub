package me.rerere.rikkihub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 输入框润色按钮的状态。 */
sealed interface PromptPolishState {
    data object Idle : PromptPolishState

    /** 正在润色：结果流式写进输入框，输入框只读。 */
    data class Running(val original: String) : PromptPolishState

    /** 已润色：输入框仍是 [polished] 时可以撤销回 [original]。 */
    data class Done(val original: String, val polished: String) : PromptPolishState
}

/**
 * 一按即润色：读输入框文字 → 流式覆盖输入框 → 可撤销。取消、失败（含写了一半）都恢复原文。
 * 输入框文字一旦不再是润色结果（用户改了、发送后清空），撤销失效、回到 [PromptPolishState.Idle]。
 */
class ChatInputPolisher(
    private val scope: CoroutineScope,
    private val polish: (String) -> Flow<String>,
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val mutableState = MutableStateFlow<PromptPolishState>(PromptPolishState.Idle)
    val state: StateFlow<PromptPolishState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (mutableState.value is PromptPolishState.Running) return
        val original = read()
        if (original.isBlank()) return
        mutableState.value = PromptPolishState.Running(original)
        job = scope.launch {
            var written: String? = null
            try {
                polish(original).collect { text ->
                    written = text
                    write(text)
                }
                val result = written
                if (result == null) {
                    write(original)
                    mutableState.value = PromptPolishState.Idle
                } else {
                    mutableState.value = PromptPolishState.Done(original, result)
                }
            } catch (e: CancellationException) {
                if (written != null) write(original)
                mutableState.value = PromptPolishState.Idle
                throw e
            } catch (e: Exception) {
                if (written != null) write(original)
                mutableState.value = PromptPolishState.Idle
                onError(e)
            }
        }
    }

    /** 润色中：取消并恢复原文。 */
    fun cancel() {
        job?.cancel()
    }

    /** 已润色：恢复原文。 */
    fun undo() {
        val done = mutableState.value as? PromptPolishState.Done ?: return
        mutableState.value = PromptPolishState.Idle
        write(done.original)
    }

    /** 输入框文字变化时调用；不再是润色结果就放弃撤销。 */
    fun onTextChanged(text: String) {
        val done = mutableState.value as? PromptPolishState.Done ?: return
        if (text != done.polished) mutableState.value = PromptPolishState.Idle
    }
}
