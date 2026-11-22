package me.rerere.rikkihub.data.cli

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.QUBAGU_RULES_SKILL
import me.rerere.rikkihub.data.ai.tools.formatEffectiveSkillsXml
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.files.SkillMetadata
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.promptPreviewText
import me.rerere.rikkihub.data.workspace.deviceDirectoryPromptLines
import me.rerere.rikkihub.data.workspace.resolveCliGuestPath
import me.rerere.rikkihub.data.workspace.rootfsEnvironmentPromptLines
import me.rerere.tavern.macro.LegacyPlaceholderMigration
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.workspace.RootfsEnvironment
import java.security.MessageDigest

/** What a CLI session gets from rikkihub: the assistant's prompt text and, when needed, the earlier chat. */
object CliPrompts {
    const val HISTORY_SEED_CHARS = 24_000
    private const val TOOL_TITLE_CHARS = 200
    private const val UPLOAD_READ_ONLY_LINE =
        "- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: " +
            "read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. " +
            "If you need to change an uploaded file, copy it into `/workspace` first and edit the copy."
    private const val WORKSPACE_AGENTS_HINT =
        "- Workspace conventions and agent instructions may be defined in `/root/.agents/AGENTS.md`; " +
            "check it if present before making assumptions about project conventions."

    /**
     * The assistant prompt as plain text, without side effects: the conversation's own system prompt
     * when the assistant allows one, else the enabled preset text entries, merged with the selected
     * relative prompt library entries by anchor and descending order, plus optional CLI environment,
     * mount, and skill guidance. Only static identity macros (`{{user}}`, `<USER>`, `{{char}}`,
     * `<BOT>`, `{{nickname}}`) are substituted; dynamic macros, lorebooks and regexes are not applied.
     */
    fun systemPrompt(
        assistant: Assistant,
        conversation: Conversation,
        settings: Settings,
        exposedTargets: Set<String> = emptySet(),
        filesScope: String? = null,
        rootfsEnvironment: RootfsEnvironment? = null,
        userName: String? = null,
        allSkills: List<SkillMetadata> = emptyList(),
    ): String {
        val basePrompt = conversation.customSystemPrompt
            ?.takeIf { assistant.allowConversationSystemPrompt && it.isNotBlank() }
            ?.let { LegacyPlaceholderMigration.migrate(it.trim()) }
            ?: assistant.promptPreviewText()
        val ids = (if (assistant.allowConversationPromptInjection) conversation.modeInjectionIds else assistant.libraryEntryIds)
            .mapTo(HashSet()) { it.toString() }
        val relativeLibrary = settings.promptLibrary
            .filter {
                it.libraryEnabled &&
                    it.identifier in ids &&
                    it.injectionPosition != InjectionPosition.ABSOLUTE &&
                    !it.content.isNullOrBlank()
            }
            .sortedByDescending { it.injectionOrder ?: 0 }

        fun anchored(anchor: String): List<String> = relativeLibrary
            .filter { (it.libraryAnchor ?: LibraryEntryMeta.AFTER_SYSTEM) == anchor }
            .mapNotNull { it.content }

        val envSection = buildList {
            rootfsEnvironmentPromptLines(rootfsEnvironment).trimEnd().takeIf { it.isNotBlank() }?.let(::add)
            if (filesScope == null) {
                deviceDirectoryPromptLines(exposedTargets).trimEnd().takeIf { it.isNotBlank() }?.let(::add)
                if ("/upload" in exposedTargets) add(UPLOAD_READ_ONLY_LINE)
                if (exposedTargets.isNotEmpty() || rootfsEnvironment != null) add(WORKSPACE_AGENTS_HINT)
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }

        val skillsSection = if (
            filesScope == null &&
            "/skills" in exposedTargets &&
            assistant.enabledSkills.isNotEmpty()
        ) {
            val available = allSkills
                .sortedBy { it.isBuiltin }
                .distinctBy { it.name }
                .filter { it.name in assistant.enabledSkills && it.name != QUBAGU_RULES_SKILL }
            if (available.isNotEmpty()) {
                buildString {
                    appendLine("**Skills**")
                    appendLine(
                        "You have access to the following skills. Read a skill's `SKILL.md` with your file-reading tool when the user's request matches, and follow its instructions."
                    )
                    appendLine("<available_skills>")
                    appendLine(formatEffectiveSkillsXml(available, exposedTargets).trimEnd())
                    append("</available_skills>")
                }
            } else {
                null
            }
        } else {
            null
        }

        val assembled = (anchored(LibraryEntryMeta.BEFORE_SYSTEM) +
            listOfNotNull(basePrompt.takeIf { it.isNotBlank() }) +
            anchored(LibraryEntryMeta.AFTER_SYSTEM) +
            anchored(LibraryEntryMeta.BEFORE_HISTORY) +
            listOfNotNull(envSection, skillsSection))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        return applyStaticMacros(assembled, userName = userName, charName = assistant.name)
    }

    private fun applyStaticMacros(text: String, userName: String?, charName: String): String {
        var result = text
        val resolvedUser = userName?.takeIf { it.isNotBlank() }
        if (resolvedUser != null) {
            result = result
                .replace("{{user}}", resolvedUser)
                .replace("<USER>", resolvedUser)
                .replace("{{nickname}}", resolvedUser)
        }
        val resolvedChar = charName.takeIf { it.isNotBlank() }
        if (resolvedChar != null) {
            result = result
                .replace("{{char}}", resolvedChar)
                .replace("<BOT>", resolvedChar)
        }
        return result
    }

    fun hash(prompt: String): String =
        MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Identity of a chat history: edits, deletions, branch switches and regenerations all change it. */
    fun fingerprint(messages: List<UIMessage>): String = hash(messages.joinToString(",") { it.id.toString() })

    /**
     * The chat so far as text for a fresh agent session: newest messages first kept, oldest dropped
     * beyond [budget] characters; tool calls as one line each. Null when there is nothing to carry.
     */
    fun historySeed(
        messages: List<UIMessage>,
        budget: Int = HISTORY_SEED_CHARS,
        filesScope: String? = null,
        exposeUpload: Boolean = false,
    ): String? {
        // `!command` exchanges (the user's `!cmd` and its local card) never reached the agent; they stay out here too.
        fun isLocalCommand(message: UIMessage?) =
            message != null && message.parts.isNotEmpty() && message.parts.all(AcpParts::isLocalCommand)
        val lines = messages.filterIndexed { index, message ->
            !isLocalCommand(message) && !isLocalCommand(messages.getOrNull(index + 1))
        }.mapNotNull { message ->
            val label = when (message.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                else -> return@mapNotNull null
            }
            val body = message.parts.mapNotNull { part ->
                when (part) {
                    is UIMessagePart.Text -> part.text.takeIf { it.isNotBlank() }
                    is UIMessagePart.Tool -> {
                        val name = part.toolName.removePrefix(AcpParts.TOOL_PREFIX)
                        // The agent's title is what the call did (the command, the file); the name alone is only its kind.
                        val title = part.metadata?.obj(AcpParts.META_KEY)?.str("title")
                            ?.replace('\n', ' ')?.trim()?.take(TOOL_TITLE_CHARS)?.takeIf { it.isNotEmpty() }
                        val status = if (part.approvalState is ToolApprovalState.Denied) "denied"
                            else AcpParts.status(part) ?: if (part.isExecuted) "completed" else "not run"
                        "[Tool $name${title?.let { " `$it`" }.orEmpty()}: $status]"
                    }
                    is UIMessagePart.Image -> {
                        val guestPath = resolveCliGuestPath(part, filesScope, exposeUpload)
                        if (guestPath != null) "[Image at $guestPath]" else "[Image]"
                    }
                    is UIMessagePart.Document -> {
                        val guestPath = resolveCliGuestPath(part, filesScope, exposeUpload)
                        if (guestPath != null) "[File ${part.fileName} at $guestPath]" else "[File ${part.fileName}]"
                    }
                    is UIMessagePart.Video -> resolveCliGuestPath(part, filesScope, exposeUpload)?.let { "[Video at $it]" }
                    is UIMessagePart.Audio -> resolveCliGuestPath(part, filesScope, exposeUpload)?.let { "[Audio at $it]" }
                    else -> null
                }
            }.joinToString("\n")
            if (body.isBlank()) null else "$label: $body"
        }
        if (lines.isEmpty()) return null
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length > budget && kept.isNotEmpty()) break
            kept.addFirst(if (line.length > budget) line.takeLast(budget) else line)
            used += line.length
        }
        val omitted = lines.size - kept.size
        return buildString {
            append("This conversation started before your current session, so you do not have its earlier messages. ")
            append("Here they are for context")
            if (omitted > 0) append(" (the oldest $omitted were left out)")
            append(":\n<earlier_conversation>\n")
            append(kept.joinToString("\n\n"))
            append("\n</earlier_conversation>")
        }
    }
}
