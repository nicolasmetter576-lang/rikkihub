package me.rerere.rikkihub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.encodeBase64
import me.rerere.rikkihub.utils.JsonInstantPretty

// Keep the original public name and first positional argument for fork callers.
data class ImageExportOptions(
    val expandReasoning: Boolean = false,
    val includeReasoning: Boolean = true,
)

/** A view-only projection. Never edits messages, tool output, bindings or approval state. */
internal fun exportedMessageParts(parts: List<UIMessagePart>, options: ImageExportOptions): List<UIMessagePart> =
    if (options.includeReasoning) parts else parts.filterNot { it is UIMessagePart.Reasoning }

internal fun buildConversationMarkdown(
    title: String,
    exportedAt: String,
    messages: List<UIMessage>,
    options: ImageExportOptions = ImageExportOptions(),
    imageData: (UIMessagePart.Image) -> String? = { it.encodeBase64().getOrNull()?.base64 },
): String = buildString {
    append("# $title\n\n")
    append("*Exported on $exportedAt*\n\n")
    messages.forEach { message ->
        val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
        append("$role:\n\n")
        exportedMessageParts(message.parts, options).forEach { part ->
            when (part) {
                is UIMessagePart.Text -> appendLine(part.text)
                is UIMessagePart.Image -> appendLine("![Image](${imageData(part)})")
                is UIMessagePart.Reasoning -> {
                    appendMarkdownReasoning(part.reasoning)
                    appendLine()
                }
                is UIMessagePart.Tool -> {
                    appendLine("**Tool**: `${part.toolName}`")
                    if (part.toolCallId.isNotBlank()) appendLine("- Call ID: `${part.toolCallId}`")
                    appendLine("Input:")
                    appendLine("```json")
                    appendLine(JsonInstantPretty.encodeToString(part.inputAsJson()))
                    appendLine("```")
                    if (part.output.isNotEmpty()) {
                        appendLine("Output:")
                        part.output.forEach { output ->
                            when (output) {
                                is UIMessagePart.Text -> {
                                    appendLine("```text")
                                    appendLine(output.text)
                                    appendLine("```")
                                }
                                is UIMessagePart.Reasoning -> {
                                    if (options.includeReasoning) appendMarkdownReasoning(output.reasoning)
                                }
                                is UIMessagePart.Image -> appendLine("![Tool Image](${imageData(output)})")
                                is UIMessagePart.Document -> appendLine("[Document: ${output.fileName}](${output.url})")
                                is UIMessagePart.Video -> appendLine("[Video](${output.url})")
                                is UIMessagePart.Audio -> appendLine("[Audio](${output.url})")
                                else -> Unit // Preserve the existing non-rendered part types.
                            }
                        }
                    }
                    appendLine()
                }
                else -> Unit
            }
        }
        appendLine()
        appendLine("---")
    }
}

private fun StringBuilder.appendMarkdownReasoning(reasoning: String) {
    reasoning.lineSequence().filter { it.isNotBlank() }.forEach { appendLine("> $it") }
}

/** Await the real export before feedback/dismissal; cancellation isn't a failed export toast. */
internal suspend fun performConversationExport(
    export: suspend () -> Unit,
    onSuccess: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    currentCoroutineContext().ensureActive()
    val failure = try {
        export()
        currentCoroutineContext().ensureActive()
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        e
    }
    // Callback failures must not be mistaken for a second export outcome.
    if (failure == null) onSuccess() else onFailure(failure)
}
