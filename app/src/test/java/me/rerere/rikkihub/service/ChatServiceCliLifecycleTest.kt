package me.rerere.rikkihub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.cli.CliPermissionMode
import me.rerere.rikkihub.data.cli.CliPrompts
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.data.workspace.WORKSPACE_PATH_METADATA_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

class ChatServiceCliLifecycleTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `PendingCliOverride Disabled does not fall back to defaultCliAgentId`() {
        val assistant = Assistant(
            workspaceId = Uuid.random(),
            defaultCliAgentId = "claude",
            defaultCliPermissionMode = CliPermissionMode.AUTO,
        )

        val disabled = resolveInitialCliSession(
            hasCharacter = false,
            override = PendingCliOverride.Disabled,
            assistant = assistant,
        )
        assertNull(disabled)

        val defaulted = resolveInitialCliSession(
            hasCharacter = false,
            override = null,
            assistant = assistant,
        )
        assertEquals("claude", defaulted?.agentId)
        assertEquals(CliPermissionMode.AUTO, defaulted?.permissionMode)
    }

    @Test
    fun `character conversation ignores defaultCliAgentId and enabled override`() {
        val assistant = Assistant(
            workspaceId = Uuid.random(),
            defaultCliAgentId = "claude",
        )
        val override = PendingCliOverride.Enabled(ConversationCliSession(agentId = "codex"))

        assertNull(resolveInitialCliSession(hasCharacter = true, override = null, assistant = assistant))
        assertNull(resolveInitialCliSession(hasCharacter = true, override = override, assistant = assistant))
    }

    @Test
    fun `editMessage in CLI overwrites single branch in place and rotates message id`() {
        val originalId = Uuid.random()
        val original = UIMessage(
            id = originalId,
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("before")),
        )
        val extraBranch = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("alt")),
        )
        val node = original.toMessageNode().copy(
            messages = listOf(original, extraBranch),
            selectIndex = 1,
        )

        val (updatedNodes, edited) = applyEditedMessageNodes(
            nodes = listOf(node),
            messageId = originalId,
            processedParts = listOf(UIMessagePart.Text("after")),
            isCli = true,
        )

        assertTrue(edited)
        assertEquals(1, updatedNodes.size)
        val updatedNode = updatedNodes.single()
        assertEquals(0, updatedNode.selectIndex)
        assertEquals(1, updatedNode.messages.size)
        val editedMessage = updatedNode.messages.single()
        assertNotEquals(originalId, editedMessage.id)
        assertEquals("after", (editedMessage.parts.single() as UIMessagePart.Text).text)
        assertNotEquals(
            CliPrompts.fingerprint(listOf(original)),
            CliPrompts.fingerprint(listOf(editedMessage)),
        )
    }

    @Test
    fun `moveConversationToAssistant clears cliSession when moving to no-workspace assistant`() {
        val sourceAssistant = Uuid.random()
        val targetAssistant = Uuid.random()
        val conversation = Conversation(
            assistantId = sourceAssistant,
            messageNodes = emptyList(),
            folderId = Uuid.random(),
            workspaceCwd = "sub/dir",
            workspaceCwdScoped = false,
            cliSession = ConversationCliSession(
                agentId = "claude",
                acpSessionId = "acp-123",
                systemPromptHash = "hash",
                historyFingerprint = "fp",
            ),
        )

        val moved = applyMoveConversationToAssistant(
            current = conversation,
            targetAssistantId = targetAssistant,
            workspaceChanged = true,
            newWorkspaceId = null,
        )

        assertEquals(targetAssistant, moved.assistantId)
        assertNull(moved.folderId)
        assertNull(moved.workspaceCwd)
        assertTrue(moved.workspaceCwdScoped)
        assertNull(moved.cliSession)
    }

    @Test
    fun `moveConversationToAssistant resets acpSessionId and cwd when moving across workspaces`() {
        val sourceAssistant = Uuid.random()
        val targetAssistant = Uuid.random()
        val targetWorkspace = Uuid.random()
        val conversation = Conversation(
            assistantId = sourceAssistant,
            messageNodes = emptyList(),
            folderId = Uuid.random(),
            workspaceCwd = "sub/dir",
            workspaceCwdScoped = false,
            cliSession = ConversationCliSession(
                agentId = "claude",
                acpSessionId = "acp-123",
                systemPromptHash = "hash",
                historyFingerprint = "fp",
                permissionMode = CliPermissionMode.AUTO,
            ),
        )

        val moved = applyMoveConversationToAssistant(
            current = conversation,
            targetAssistantId = targetAssistant,
            workspaceChanged = true,
            newWorkspaceId = targetWorkspace,
        )

        assertEquals(targetAssistant, moved.assistantId)
        assertNull(moved.folderId)
        assertNull(moved.workspaceCwd)
        assertTrue(moved.workspaceCwdScoped)
        assertEquals("claude", moved.cliSession?.agentId)
        assertEquals(CliPermissionMode.AUTO, moved.cliSession?.permissionMode)
        assertNull(moved.cliSession?.acpSessionId)
        assertNull(moved.cliSession?.systemPromptHash)
        assertEquals("fp", moved.cliSession?.historyFingerprint)
        assertTrue(moved.cliSession?.needsHistorySeed == true)
    }

    @Test
    fun `exitCliToOrdinaryChat clears cliSession while preserving message history`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val msg = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello")))
        val initial = Conversation.ofId(id).copy(
            messageNodes = listOf(msg.toMessageNode()),
            cliSession = ConversationCliSession(agentId = "claude", acpSessionId = "acp-1"),
        )
        val session = ConversationSession(id, initial, scope, {})
        var persistedCli: ConversationCliSession? = initial.cliSession
        try {
            session.updateMetadata(
                update = { it.copy(cliSession = null) },
                persist = { persistedCli = it.cliSession },
            )
            assertNull(session.state.value.cliSession)
            assertNull(persistedCli)
            assertEquals(1, session.state.value.messageNodes.size)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `cliPromptBlocks sanitizes paths, extracts unmounted documents, and rejects out-of-bounds images`() = runBlocking {
        val uploadDir = tempFolder.newFolder("upload")
        val outsideDir = tempFolder.newFolder("outside")
        val allowedImg = File(uploadDir, "shot.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val outsideImg = File(outsideDir, "secret.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val docFile = File(uploadDir, "notes.txt").apply { writeText("hello from doc") }

        val scopedImage = UIMessagePart.Image(
            url = allowedImg.toURI().toString(),
            metadata = buildJsonObject {
                put(WORKSPACE_PATH_METADATA_KEY, JsonPrimitive("/workspace/shot.png"))
            },
        )
        val uploadedImage = UIMessagePart.Image(url = allowedImg.toURI().toString())
        val outOfBoundsImage = UIMessagePart.Image(url = outsideImg.toURI().toString())
        val unmountedDoc = UIMessagePart.Document(
            url = docFile.toURI().toString(),
            fileName = "notes.txt",
            mime = "text/plain",
        )
        val mountedDoc = UIMessagePart.Document(
            url = docFile.toURI().toString(),
            fileName = "notes.txt",
            mime = "text/plain",
            metadata = buildJsonObject {
                put(WORKSPACE_PATH_METADATA_KEY, JsonPrimitive("/workspace/notes.txt"))
            },
        )
        val uploadedVideo = UIMessagePart.Video(
            url = File(uploadDir, "clip.mp4").apply { writeBytes(byteArrayOf(1)) }.toURI().toString(),
        )

        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(scopedImage, uploadedImage, outOfBoundsImage, unmountedDoc, mountedDoc, uploadedVideo),
        )

        val blocks = cliPromptBlocks(
            message = message,
            images = true,
            filesScope = null,
            exposeUpload = false,
            allowedImageDirs = listOf(uploadDir),
            readDocumentText = { doc -> File(URI(doc.url)).readText() },
        )

        val types = blocks.map { it["type"]?.jsonPrimitive?.contentOrNull }
        val texts = blocks.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }

        // Out-of-bounds image must NOT produce an image block or leak outsideDir host path
        assertFalse(types.contains("image"))
        assertFalse(texts.any { outsideDir.absolutePath in it || uploadDir.absolutePath in it })
        assertTrue(texts.any { it == "[Image at /workspace/shot.png]" })
        assertTrue(texts.any { it == "[Image: shot.png]" })
        assertTrue(texts.any { it == "[Image: secret.png]" })
        // Unmounted document is extracted inline; mounted document uses guest workspacePath
        assertTrue(texts.any { it.contains("<UploadFile name=\"notes.txt\">") && it.contains("hello from doc") })
        assertTrue(texts.any { it == "[File: notes.txt at /workspace/notes.txt]" })
        // Video without exposeUpload or workspacePath is omitted
        assertFalse(texts.any { "clip.mp4" in it })

        // When exposeUpload = true, uploadDir image and video resolve to /upload/...
        val mountedBlocks = cliPromptBlocks(
            message = UIMessage(role = MessageRole.USER, parts = listOf(uploadedImage, uploadedVideo)),
            images = false,
            filesScope = null,
            exposeUpload = true,
            allowedImageDirs = listOf(uploadDir),
        )
        val mountedTexts = mountedBlocks.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        assertEquals(listOf("[Image at /upload/shot.png]", "[Video: clip.mp4 at /upload/clip.mp4]"), mountedTexts)
    }
}
