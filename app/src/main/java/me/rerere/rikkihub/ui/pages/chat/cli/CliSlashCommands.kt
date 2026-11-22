package me.rerere.rikkihub.ui.pages.chat.cli

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.rikkihub.data.cli.AcpCommand
import me.rerere.rikkihub.ui.components.ai.completion.ChatCompletionContext
import me.rerere.rikkihub.ui.components.ai.completion.ChatCompletionItem
import me.rerere.rikkihub.ui.components.ai.completion.ChatCompletionList
import me.rerere.rikkihub.ui.components.ai.completion.ChatCompletionProvider

/**
 * What a message typed in a CLI chat means before it is sent. [Local] commands are handled by
 * rikkihub and never reach the agent; `!command` runs once in the workspace; anything else
 * (including the agent's own `/commands`) is sent to the agent as text.
 */
sealed interface CliInput {
    enum class Local(val command: String) { MODEL("/model"), MODE("/mode"), EFFORT("/effort"), STATUS("/status"), RESTART("/restart"), LOGIN("/login") }

    data class LocalCommand(val local: Local) : CliInput
    data class Shell(val command: String) : CliInput
    data object Prompt : CliInput

    companion object {
        fun parse(text: String): CliInput {
            val trimmed = text.trim()
            if (trimmed.startsWith("!") && trimmed.length > 1) return Shell(trimmed.substring(1).trim())
            val local = Local.entries.firstOrNull { it.command == trimmed || (it == Local.EFFORT && trimmed == "/thinking") }
            return local?.let(::LocalCommand) ?: Prompt
        }
    }
}

/** `/` at the start of the input: rikkihub's own commands, then the agent's (from `available_commands_update`). */
class CliSlashCompletionProvider(
    private val agentCommands: () -> List<AcpCommand>,
    private val localLabels: Map<CliInput.Local, String>,
) : ChatCompletionProvider {
    override val id: String = "cli_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection || !context.text.startsWith("/")) return null
        val token = context.text.substring(0, context.cursor)
        if (token.any { it.isWhitespace() }) return null
        val query = token.removePrefix("/").lowercase()
        val local = CliInput.Local.entries.filter { it.command.removePrefix("/").startsWith(query) }.map { local ->
            ChatCompletionItem(
                label = local.command,
                insertText = local.command,
                detail = localLabels[local],
                icon = HugeIcons.Settings01,
                sortScore = 100,
            )
        }
        val agent = agentCommands().filter { it.name.lowercase().contains(query) }.map { command ->
            ChatCompletionItem(
                label = "/${command.name}",
                insertText = "/${command.name} ",
                detail = command.description.lineSequence().firstOrNull()?.take(80),
                icon = HugeIcons.CommandLine,
                sortScore = if (command.name.lowercase().startsWith(query)) 50 else 10,
            )
        }
        val items = (local + agent).sortedByDescending { it.sortScore }
        if (items.isEmpty()) return null
        return ChatCompletionList(providerId = id, replacementRange = TextRange(0, context.cursor), items = items)
    }
}
