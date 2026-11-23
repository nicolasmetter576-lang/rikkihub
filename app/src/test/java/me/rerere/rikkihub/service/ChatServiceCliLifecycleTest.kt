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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.cli.AcpParts
import me.rerere.rikkihub.data.cli.CliPermissionMode
import me.rerere.rikkihub.data.cli.CliPrompts
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.data.workspace.WORKSPACE_PATH_METADATA_KEY
import me.rerere.rikkihub.ui.components.message.extractEditedFilePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
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

    private fun acpTool(
        id: String,
        toolName: String = "acp_run_command",
        title: String = "run cmd",
        kind: String = "execute",
        status: String = "in_progress",
        executed: Boolean = false,
        approvalState: ToolApprovalState = ToolApprovalState.Auto,
        startedAt: Instant? = null,
        finishedAt: Instant? = null,
        input: String = "{}",
    ): UIMessagePart.Tool = UIMessagePart.Tool(
        toolCallId = id,
        toolName = toolName,
        input = input,
        output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
        approvalState = approvalState,
        startedAt = startedAt,
        finishedAt = finishedAt,
        metadata = buildJsonObject {
            putJsonObject(AcpParts.META_KEY) {
                put("title", title)
                put("kind", kind)
                put("status", status)
            }
        },
    )

    @Test
    fun `finishStaleCliState closes unfinished ACP tools and open reasoning without mislabeling Denied or faking duration`() = runBlocking {
        val baseTime = Instant.fromEpochSeconds(1_700_000_000L)
        val killedAt = baseTime + 12.seconds
        val reopenedAt = baseTime + 3.hours
        val interrupted = "CLI session interrupted"

        val doneTool = acpTool(
            id = "done-1",
            title = "finished step",
            status = "completed",
            executed = true,
            startedAt = baseTime,
            finishedAt = killedAt,
        )
        val autoRunning = acpTool(
            id = "auto-2",
            toolName = "acp_edit_file",
            title = "edit main.kt",
            kind = "edit",
            status = "in_progress",
            approvalState = ToolApprovalState.Auto,
            startedAt = baseTime + 5.seconds,
            input = """{"path":"/workspace/main.kt"}""",
        )
        val approvedRunning = acpTool(
            id = "approved-3",
            title = "approved cmd",
            status = "in_progress",
            approvalState = ToolApprovalState.Approved,
            startedAt = baseTime + 6.seconds,
        )
        val pendingTool = acpTool(
            id = "pending-4",
            title = "need approval",
            status = "pending",
            approvalState = ToolApprovalState.Pending,
            startedAt = baseTime + 7.seconds,
        )
        val askQuestion = acpTool(
            id = "ask-5",
            toolName = AcpParts.ASK_QUESTION,
            title = "Pick an option",
            kind = "other",
            status = "pending",
            approvalState = ToolApprovalState.Auto,
        )
        val openReasoning = UIMessagePart.Reasoning(
            reasoning = "halfway thought",
            createdAt = baseTime + 2.seconds,
            finishedAt = null,
        )
        val currentReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(doneTool, openReasoning, autoRunning, approvedRunning, pendingTool, askQuestion),
            finishedAt = null,
        )
        // Inactive branch still holding a dead Pending ACP card must also be cleared so hasPendingToolApprovals unblocks.
        val inactiveBranchReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                acpTool(
                    id = "inactive-pending",
                    title = "old branch pending",
                    status = "pending",
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )
        val userNode = UIMessage.user("go").toMessageNode()
        val assistantNode = currentReply.toMessageNode().copy(
            messages = listOf(currentReply, inactiveBranchReply),
            selectIndex = 0,
        )
        val conversation = Conversation.ofId(Uuid.random()).copy(
            cliSession = ConversationCliSession(agentId = "agy", acpSessionId = "s1"),
            messageNodes = listOf(userNode, assistantNode),
        )

        assertTrue("stale CLI conversation should trigger fast-path check", conversation.hasStaleCliState())
        val cleaned = conversation.finishStaleCliState(interrupted, now = reopenedAt)
        assertFalse("cleaned CLI conversation must clear fast-path check", cleaned.hasStaleCliState())
        val cleanedReply = cleaned.currentMessages.last()
        assertNull("UIMessage.finishedAt stays null", cleanedReply.finishedAt)

        val cleanedDone = cleanedReply.parts[0] as UIMessagePart.Tool
        assertEquals(doneTool, cleanedDone)

        val cleanedReasoning = cleanedReply.parts[1] as UIMessagePart.Reasoning
        assertNotNull("open reasoning must be sealed", cleanedReasoning.finishedAt)
        assertTrue(
            "sealed reasoning must stay bounded by message activity, not 3h later: ${cleanedReasoning.finishedAt}",
            cleanedReasoning.finishedAt!! <= killedAt && cleanedReasoning.finishedAt!! >= openReasoning.createdAt,
        )

        val cleanedAuto = cleanedReply.parts[2] as UIMessagePart.Tool
        assertTrue(cleanedAuto.isExecuted)
        assertFalse(cleanedAuto.isPending)
        assertEquals(ToolApprovalState.Auto, cleanedAuto.approvalState)
        assertEquals(listOf(UIMessagePart.Text(interrupted)), cleanedAuto.output)
        assertEquals("interrupted", AcpParts.status(cleanedAuto))
        assertNull("interrupted tool must not forge 3h finishedAt", cleanedAuto.finishedAt)

        val cleanedApproved = cleanedReply.parts[3] as UIMessagePart.Tool
        assertTrue(cleanedApproved.isExecuted)
        assertEquals(ToolApprovalState.Approved, cleanedApproved.approvalState)
        assertEquals("interrupted", AcpParts.status(cleanedApproved))
        assertNull(cleanedApproved.finishedAt)

        val cleanedPending = cleanedReply.parts[4] as UIMessagePart.Tool
        assertTrue(cleanedPending.isExecuted)
        assertFalse("non-empty output clears isPending without faking Denied", cleanedPending.isPending)
        assertEquals(ToolApprovalState.Pending, cleanedPending.approvalState)
        assertEquals(listOf(UIMessagePart.Text(interrupted)), cleanedPending.output)
        assertEquals("interrupted", AcpParts.status(cleanedPending))
        assertNull(cleanedPending.finishedAt)

        val cleanedQuestion = cleanedReply.parts[5] as UIMessagePart.Tool
        assertTrue(cleanedQuestion.isExecuted)
        assertFalse(cleanedQuestion.isPending)
        assertEquals(ToolApprovalState.Auto, cleanedQuestion.approvalState)
        assertEquals(listOf(UIMessagePart.Text("")), cleanedQuestion.output)
        assertEquals("pending", AcpParts.status(cleanedQuestion))
        assertNull(cleanedQuestion.finishedAt)

        // Inactive branch pending card is also closed -> ConversationSession.hasPendingToolApprovals is false.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = ConversationSession(cleaned.id, cleaned, scope, {})
        try {
            assertFalse(session.hasPendingToolApprovals())
        } finally {
            session.cleanup()
            scope.cancel()
        }

        // Idempotence: second pass returns exact same instance.
        assertSame(cleaned, cleaned.finishStaleCliState(interrupted, now = reopenedAt))

        // History seed & EditedFiles contract checks.
        val seed = CliPrompts.historySeed(cleaned.currentMessages)!!
        assertTrue("seed should report interrupted, got:\n$seed", seed.contains("[Tool edit_file `edit main.kt`: interrupted]"))
        assertFalse("seed must never report denied for interrupted tools, got:\n$seed", seed.contains(": denied]"))
        assertTrue("ask_question keeps pending status in seed, got:\n$seed", seed.contains("[Tool ask_question `Pick an option`: pending]"))
        assertEquals(emptyList<String>(), extractEditedFilePaths(cleanedReply.parts))
    }

    @Test
    fun `finishStaleCliState cleans exited-CLI leftovers but leaves ordinary conversations untouched`() {
        val baseTime = Instant.fromEpochSeconds(1_700_000_000L)
        val now = baseTime + 1.hours

        // 1. Exited CLI (cliSession == null) but message still has ACP tool + open reasoning
        val exitedReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("cli thought", createdAt = baseTime, finishedAt = null),
                acpTool(id = "leftover", approvalState = ToolApprovalState.Pending, status = "pending"),
            ),
        )
        val exitedConv = Conversation.ofId(Uuid.random()).copy(
            cliSession = null,
            messageNodes = listOf(exitedReply.toMessageNode()),
        )
        val cleanedExited = exitedConv.finishStaleCliState("interrupted", now = now)
        val exitedParts = cleanedExited.currentMessages.single().parts
        assertEquals(baseTime, (exitedParts[0] as UIMessagePart.Reasoning).finishedAt)
        assertTrue((exitedParts[1] as UIMessagePart.Tool).isExecuted)
        assertEquals("interrupted", AcpParts.status(exitedParts[1] as UIMessagePart.Tool))

        // 2. Ordinary non-CLI conversation with a legitimate Pending workspace tool and reasoning stays untouched
        val ordinaryReply = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning("normal thought", createdAt = baseTime, finishedAt = null),
                UIMessagePart.Tool(
                    toolCallId = "ws-1",
                    toolName = "workspace_shell",
                    input = """{"command":"ls"}""",
                    output = emptyList(),
                    approvalState = ToolApprovalState.Pending,
                ),
            ),
        )
        val ordinaryConv = Conversation.ofId(Uuid.random()).copy(
            cliSession = null,
            messageNodes = listOf(ordinaryReply.toMessageNode()),
        )
        assertFalse("ordinary non-CLI conversation must not trigger stale CLI cleanup", ordinaryConv.hasStaleCliState())
        assertSame(ordinaryConv, ordinaryConv.finishStaleCliState("interrupted", now = now))
    }
}
