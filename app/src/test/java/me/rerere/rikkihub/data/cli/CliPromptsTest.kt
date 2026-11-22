package me.rerere.rikkihub.data.cli

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.data.workspace.WORKSPACE_PATH_METADATA_KEY
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.tavern.prompt.PromptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CliPromptsTest {
    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())

    private fun acpTool(title: String, status: String, local: Boolean = false, approval: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(
            toolCallId = title,
            toolName = AcpParts.TOOL_PREFIX + "run_command",
            input = "{}",
            approvalState = approval,
            metadata = buildJsonObject {
                putJsonObject(AcpParts.META_KEY) {
                    put("title", title)
                    put("status", status)
                    if (local) put("local", true)
                }
            },
        )

    private fun seed(vararg messages: UIMessage) = CliPrompts.historySeed(messages.toList())!!

    @Test
    fun toolLinesCarryTheAgentTitleAndADenial() {
        val text = seed(
            user("make it"),
            assistant(acpTool("echo \"hi\" > hi.txt", "completed"), UIMessagePart.Text("DONE")),
            user("remove it"),
            assistant(acpTool("rm hi.txt", "failed", approval = ToolApprovalState.Denied("no"))),
        )
        assertTrue(text, text.contains("[Tool run_command `echo \"hi\" > hi.txt`: completed]"))
        assertTrue(text, text.contains("[Tool run_command `rm hi.txt`: denied]"))
    }

    @Test
    fun multiLineAndLongTitlesStayOnOneShortLine() {
        val text = seed(user("go"), assistant(acpTool("cat <<EOF\n" + "x".repeat(500), "completed")))
        val line = text.lines().single { it.startsWith("Assistant: [Tool") }
        assertTrue(line, line.contains("cat <<EOF x"))
        assertTrue(line.length.toString(), line.length < 260)
    }

    @Test
    fun localCommandsAndTheirPromptsAreLeftOut() {
        val text = seed(
            user("first"),
            assistant(UIMessagePart.Text("one")),
            user("!ls -la"),
            assistant(acpTool("ls -la", "completed", local = true)),
            user("second"),
        )
        assertFalse(text, text.contains("ls -la"))
        assertEquals(listOf("User: first", "Assistant: one", "User: second"), text.lines().filter { it.startsWith("User:") || it.startsWith("Assistant:") })
    }

    @Test
    fun onlyLocalCommandsMeansNothingToCarry() {
        assertEquals(null, CliPrompts.historySeed(listOf(user("!pwd"), assistant(acpTool("pwd", "completed", local = true)))))
    }

    @Test
    fun customSystemPromptMergesWithAnchoredPromptLibraryAndExcludesAbsolute() {
        val beforeLowId = Uuid.random()
        val beforeHighId = Uuid.random()
        val afterId = Uuid.random()
        val beforeHistoryId = Uuid.random()
        val disabledId = Uuid.random()
        val absoluteId = Uuid.random()
        val unselectedId = Uuid.random()

        fun entry(
            id: Uuid,
            content: String,
            order: Int = 0,
            anchor: String? = null,
            enabled: Boolean = true,
            position: Int = InjectionPosition.RELATIVE,
        ) = PromptEntry(
            identifier = id.toString(),
            name = content,
            role = PromptRole.SYSTEM,
            content = content,
            injectionPosition = position,
            injectionOrder = order,
        ).withLibraryMeta(enabled = enabled, anchor = anchor)

        val assistant = Assistant(
            promptPreset = AssistantPresets.withSystemPrompt("Assistant default prompt"),
            allowConversationSystemPrompt = true,
            allowConversationPromptInjection = false,
            libraryEntryIds = setOf(beforeLowId, beforeHighId, afterId, beforeHistoryId, disabledId, absoluteId),
        )
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            customSystemPrompt = "  Custom prompt {user}  ",
        )
        val settings = Settings(
            promptLibrary = listOf(
                entry(beforeLowId, "before-low", order = 10, anchor = LibraryEntryMeta.BEFORE_SYSTEM),
                entry(beforeHighId, "before-high", order = 50, anchor = LibraryEntryMeta.BEFORE_SYSTEM),
                entry(afterId, "after-default", order = 1, anchor = null),
                entry(beforeHistoryId, "before-history", order = 5, anchor = LibraryEntryMeta.BEFORE_HISTORY),
                entry(disabledId, "disabled-entry", order = 99, anchor = LibraryEntryMeta.BEFORE_SYSTEM, enabled = false),
                entry(absoluteId, "absolute-entry", order = 99, anchor = LibraryEntryMeta.AFTER_SYSTEM, position = InjectionPosition.ABSOLUTE),
                entry(unselectedId, "unselected-entry", order = 99, anchor = LibraryEntryMeta.BEFORE_SYSTEM),
            ),
        )

        val rendered = CliPrompts.systemPrompt(assistant, conversation, settings)
        assertEquals(
            "before-high\n\nbefore-low\n\nCustom prompt {{user}}\n\nafter-default\n\nbefore-history",
            rendered,
        )
    }

    @Test
    fun conversationInjectionIdsOverrideAssistantLibraryWhenAllowed() {
        val assistantOnlyId = Uuid.random()
        val convOnlyId = Uuid.random()
        val assistant = Assistant(
            promptPreset = AssistantPresets.withSystemPrompt("Base prompt"),
            allowConversationPromptInjection = true,
            libraryEntryIds = setOf(assistantOnlyId),
        )
        val conversation = Conversation(
            assistantId = assistant.id,
            messageNodes = emptyList(),
            modeInjectionIds = setOf(convOnlyId),
        )
        val settings = Settings(
            promptLibrary = listOf(
                PromptEntry(identifier = assistantOnlyId.toString(), content = "assistant-only"),
                PromptEntry(identifier = convOnlyId.toString(), content = "conversation-only"),
            ),
        )

        val rendered = CliPrompts.systemPrompt(assistant, conversation, settings)
        assertEquals("Base prompt\n\nconversation-only", rendered)
    }

    @Test
    fun historySeedIncludesResolvedGuestPathsAndRespectsScope() {
        val uploadImage = UIMessagePart.Image(url = "file:///data/user/0/pkg/files/upload/pic.png")
        val uploadDoc = UIMessagePart.Document(url = "file:///data/user/0/pkg/files/upload/spec.pdf", fileName = "spec.pdf")
        val uploadVideo = UIMessagePart.Video(url = "file:///data/user/0/pkg/files/upload/clip.mp4")
        val uploadAudio = UIMessagePart.Audio(url = "file:///data/user/0/pkg/files/upload/voice.mp3")
        val scopedDoc = UIMessagePart.Document(
            url = "file:///data/user/0/pkg/files/upload/in-scope.txt",
            fileName = "in-scope.txt",
            metadata = buildJsonObject { put(WORKSPACE_PATH_METADATA_KEY, "/workspace/proj/in-scope.txt") },
        )
        val foreignScopedImage = UIMessagePart.Image(
            url = "file:///data/user/0/pkg/files/upload/foreign.png",
            metadata = buildJsonObject { put(WORKSPACE_PATH_METADATA_KEY, "/workspace/other/foreign.png") },
        )

        val msg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(uploadImage, uploadDoc, uploadVideo, uploadAudio, scopedDoc, foreignScopedImage),
        )

        val unscoped = CliPrompts.historySeed(listOf(msg), filesScope = null, exposeUpload = true)!!
        assertTrue(unscoped, unscoped.contains("[Image at /upload/pic.png]"))
        assertTrue(unscoped, unscoped.contains("[File spec.pdf at /upload/spec.pdf]"))
        assertTrue(unscoped, unscoped.contains("[Video at /upload/clip.mp4]"))
        assertTrue(unscoped, unscoped.contains("[Audio at /upload/voice.mp3]"))
        assertTrue(unscoped, unscoped.contains("[File in-scope.txt at /workspace/proj/in-scope.txt]"))
        assertTrue(unscoped, unscoped.contains("[Image at /workspace/other/foreign.png]"))

        val confined = CliPrompts.historySeed(listOf(msg), filesScope = "proj", exposeUpload = true)!!
        assertTrue(confined, confined.contains("[File in-scope.txt at /workspace/proj/in-scope.txt]"))
        assertFalse(confined, confined.contains("/upload/"))
        assertFalse(confined, confined.contains("/workspace/other/foreign.png"))
        assertTrue(confined, confined.contains("[File spec.pdf]"))
        assertTrue(confined, confined.contains("[Image]"))
    }
}
