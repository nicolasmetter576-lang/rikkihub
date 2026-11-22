package me.rerere.rikkihub.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ContextCheckpointKind
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.management.proposal.commandDomains
import me.rerere.rikkihub.data.management.runtime.ManagementReadiness
import me.rerere.rikkihub.data.management.runtime.ManagementRuntime
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkihub.service.CARRIED_ORIGINALS_HEADING
import me.rerere.rikkihub.service.CarriedOriginalEdit
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressStatus
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.ai.provider.DEFAULT_MODEL_CONTEXT_LENGTH
import me.rerere.rikkihub.web.dto.toDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * Real app process, real Koin graph, real Room/DataStore/GenerationLoop/OkHttp, against
 * the OFFLINE mock OpenAI server on the host (10.0.2.2:18080). No real or paid model.
 *
 * The creator write gate is opened only by the production mechanism: the management startup barrier.
 * Mock server: scripts/mock-openai/mockctl.sh start.
 */
@RunWith(AndroidJUnit4::class)
class LocalMockEndToEndTest {
    private val koin get() = GlobalContext.get()
    private val settings get() = koin.get<SettingsStore>()
    private val modelId = Uuid.parse("4d0c7e44-1d0a-4c84-9a7b-0d6a1e8a0001")
    private val providerId = Uuid.parse("4d0c7e44-1d0a-4c84-9a7b-0d6a1e8a0002")

    @Before fun configureMockProvider() = runBlocking {
        settings.update { s ->
            val provider = ProviderSetting.OpenAI(
                id = providerId, name = "Local mock", apiKey = "mock-key",
                baseUrl = "http://10.0.2.2:18080/v1",
                models = listOf(Model(modelId = "mock-chat", displayName = "Mock chat", id = modelId,
                    abilities = listOf(ModelAbility.TOOL))),
            )
            s.copy(
                providers = s.providers.filterNot { it.id == providerId } + provider,
                chatModelId = modelId,
                enableSuggestion = false,
                assistants = s.assistants.map { it.copy(chatModelId = modelId, streamOutput = true) },
            )
        }
    }

    /** Real gate only: the app's own management startup barrier. */
    private suspend fun requireRealReadiness() {
        koin.get<ManagementRuntime>().ensureStarted()
        withTimeout(30_000) { while (!ManagementReadiness.mutationBaseAccepted) delay(100) }
    }

    @Test(timeout = 180_000) fun consecutiveTurnsPersistWithoutVersionConflicts() = runBlocking {
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val id = Uuid.random()
        chat.addConversationReference(id)
        try {
            chat.initializeConversation(id)
            for (turn in 1..3) {
                chat.sendMessage(id, listOf(UIMessagePart.Text("turn $turn")))
                withTimeout(60_000) {
                    while (true) {
                        val saved = repo.getConversationById(id)
                        val assistantTexts = saved?.currentMessages?.filter { it.role.name == "ASSISTANT" }
                            ?.map { it.parts.filterIsInstance<UIMessagePart.Text>().joinToString { p -> p.text } }.orEmpty()
                        if (assistantTexts.size == turn && assistantTexts.last().contains("turn $turn") &&
                            chat.getConversationFlow(id).value.currentMessages.size == turn * 2) break
                        delay(250)
                    }
                }
            }
            val saved = checkNotNull(repo.getConversationById(id))
            assertEquals(6, saved.currentMessages.size)
            assertTrue("durable revision advanced with every write", saved.revision >= 3)
            assertEquals(saved.revision, chat.getConversationFlow(id).value.revision)
            // The exact runtime -> Room -> decoded message -> Web DTO path must retain coverage.
            saved.currentMessages.filter { it.role == MessageRole.ASSISTANT }.forEach { message ->
                assertEquals(true, message.usageComplete)
                assertEquals(1, message.stepCount)
                val usage = checkNotNull(message.usage)
                assertEquals(5, usage.completionTokens)
                assertEquals(usage.promptTokens + 5, usage.totalTokens)
                assertEquals(usage, message.lastUsage)
                assertTrue(checkNotNull(message.generationTimeMs) >= 0)
                assertEquals(true, message.toDto().usageComplete)
            }
        } finally {
            chat.removeConversationReference(id)
        }
    }

    /** A conversation confined to its working directory sees nothing else, end to end. */
    @Test(timeout = 15 * 60_000) fun confinedConversationOnlySeesSelectedDirectory() = runBlocking {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("rootfs") != "false")
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val workspaces = koin.get<me.rerere.rikkihub.data.repository.WorkspaceRepository>()
        val workspace = workspaces.create("itest-scope-chat-${System.currentTimeMillis()}")
        val assistantId = settings.settingsSnapshot().assistantId
        val id = Uuid.random()
        try {
            assertTrue(workspaces.installRootfs(workspace.id,
                me.rerere.rikkihub.data.workspace.defaultRootfsUrl()))
            val files = java.io.File(context.filesDir.canonicalFile, "workspaces/${workspace.root}/files")
            java.io.File(files, "a/b").mkdirs()
            java.io.File(files, "other").mkdirs()
            java.io.File(files, "other/secret.txt").writeText("TOPSECRET")
            settings.update { s -> s.copy(assistants = s.assistants.map {
                if (it.id == assistantId) it.copy(workspaceId = Uuid.parse(workspace.id)) else it
            }) }
            val upload = java.io.File(context.filesDir, "upload/itest-note.txt").apply {
                parentFile?.mkdirs(); writeText("note body")
            }
            chat.addConversationReference(id)
            chat.initializeConversation(id)
            chat.updateConversationMetadata(id, me.rerere.rikkihub.web.dto.ConversationMetadataPatch(
                patchedWorkspaceCwd = true, workspaceCwd = "/workspace/a/b", workspaceCwdScoped = true))
            chat.sendMessage(id, listOf(
                UIMessagePart.Text("probe [[script:scoped_probe]]"),
                UIMessagePart.Document(upload.toURI().toString(), "itest-note.txt", "text/plain"),
            ))
            var parts: List<UIMessagePart> = emptyList()
            withTimeout(180_000) {
                while (true) {
                    parts = repo.getConversationById(id)?.currentMessages?.flatMap { it.parts }.orEmpty()
                    if (parts.any { it is UIMessagePart.Text && it.text.contains("probed") }) break
                    delay(250)
                }
            }
            val tools = parts.filterIsInstance<UIMessagePart.Tool>()
            fun outputOf(index: Int) = tools[index].output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
            assertEquals(4, tools.size)
            assertTrue(tools.indices.none { outputOf(it).contains("TOPSECRET") })
            val shell = outputOf(0)
            assertTrue(shell, shell.contains("/workspace/a/b") && shell.contains("ROOT:\\na\\n"))
            // JSON-encoded stdout: a `find` hit would appear as "...secret.txt\n".
            assertFalse(shell, shell.contains("secret.txt\\n"))
            assertTrue(shell, shell.contains("itest-note.txt")) // attachment copied into the scope
            assertTrue(outputOf(1), outputOf(1).contains("outside the selected working directory"))
            assertTrue(outputOf(2), outputOf(2).contains("outside the selected working directory"))
            assertFalse(java.io.File(files, "other/new.txt").exists())
            assertEquals("made", java.io.File(files, "a/b/made.txt").readText())
            assertEquals("note body", java.io.File(files, "a/b/itest-note.txt").readText())
            val document = parts.filterIsInstance<UIMessagePart.Document>().single()
            assertEquals("/workspace/a/b/itest-note.txt",
                document.metadata?.get("workspacePath")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        } finally {
            chat.removeConversationReference(id)
            settings.update { s -> s.copy(assistants = s.assistants.map {
                if (it.id == assistantId) it.copy(workspaceId = null) else it
            }) }
            koin.get<me.rerere.rikkihub.data.ai.tools.WorkspacePersistentBashSessionManager>().closeWorkspace(workspace.id)
            workspaces.delete(workspace.id)
        }
    }

    /**
     * Runs one turn of the creator assistant, in a fresh conversation or in [existing]; returns the
     * conversation id.
     */
    private suspend fun creatorTurn(
        prompt: String, existing: Uuid? = null, done: (List<UIMessagePart>) -> Boolean,
    ): Uuid {
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val previous = settings.settingsSnapshot().assistantId
        settings.updateAssistant(CreatorAssistantDefinition.ID)
        val id = existing ?: Uuid.random()
        chat.addConversationReference(id)
        try {
            if (existing == null) chat.initializeConversation(id)
            assertEquals(CreatorAssistantDefinition.ID, chat.getConversationFlow(id).value.assistantId)
            chat.sendMessage(id, listOf(UIMessagePart.Text(prompt)))
            withTimeout(90_000) {
                while (true) {
                    val parts = repo.getConversationById(id)?.currentMessages?.flatMap { it.parts }.orEmpty()
                    if (done(parts)) break
                    delay(250)
                }
            }
        } finally {
            chat.removeConversationReference(id)
            settings.updateAssistant(previous)
        }
        return id
    }

    @Test(timeout = 180_000) fun creatorConversationProposesAndOnlyNativeApprovalApplies() = runBlocking {
        requireRealReadiness()
        val management = koin.get<ManagementRuntime>()
        settings.update { s -> s.copy(quickMessages = s.quickMessages.filterNot { it.title == "Mock greeting" }) }

        val id = creatorTurn("please add a greeting [[script:creator_create_qm]]") { parts ->
            parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("Please review and approve") }
        }
        assertFalse("the model turn must not apply anything",
            settings.settingsSnapshot().quickMessages.any { it.title == "Mock greeting" })

        // Proposals belong to the creator conversation (sessionId = conversation id).
        val proposal = management.list().single { it.sessionId == id.toString() && it.status == ProposalStatus.PENDING_APPROVAL }
        val (_, review) = management.review(proposal.proposalId)
        val applied = management.approveAndApply(review)
        assertEquals(ProposalStatus.APPLIED, applied.status)
        assertTrue(settings.settingsSnapshot().quickMessages.any { it.title == "Mock greeting" })
    }

    @Test(timeout = 240_000) fun creatorBatchIsOneProposalAndItsResultReachesTheModel() = runBlocking {
        requireRealReadiness()
        val management = koin.get<ManagementRuntime>()
        settings.update { s -> s.copy(
            quickMessages = s.quickMessages.filterNot { it.title == "Mock batch greeting" },
            promptLibrary = s.promptLibrary.filterNot { it.name == "Mock batch mode" },
        ) }
        val id = creatorTurn("set up a greeting and a brief mode [[script:creator_batch]]") { parts ->
            parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("Please review and approve") }
        }
        // Two Settings operations in one call are one proposal, approved once.
        val proposal = management.list().single { it.sessionId == id.toString() && it.status == ProposalStatus.PENDING_APPROVAL }
        assertEquals(setOf(ManagementDomain.QUICK_MESSAGE, ManagementDomain.MODE_INJECTION), proposal.commandDomains().toSet())
        assertEquals(ProposalStatus.APPLIED, management.approveAndApply(management.review(proposal.proposalId).second).status)
        val after = settings.settingsSnapshot()
        assertTrue(after.quickMessages.any { it.title == "Mock batch greeting" })
        assertEquals(me.rerere.tavern.core.PromptRole.USER, after.promptLibrary.single { it.name == "Mock batch mode" }.role)

        // The next request carries the six creator tools and a note that the proposal was applied.
        creatorTurn("[[probe:creator]]", existing = id) { parts ->
            parts.filterIsInstance<UIMessagePart.Text>().any { it.text.startsWith("probe tools=") }
        }
        val probe = koin.get<ConversationRepository>().getConversationById(id)!!.currentMessages.last()
            .parts.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
        assertEquals(probe, "probe tools=${CreatorAssistantDefinition.ToolIds.ALL.sorted().joinToString(",")}; updates=present", probe)
    }

    @Test(timeout = 120_000) fun creatorConversationCannotRunUndeclaredTool() = runBlocking {
        requireRealReadiness()
        val id = creatorTurn("[[script:creator_forged_tool]]") { parts ->
            parts.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("done") }
        }
        val repo = koin.get<ConversationRepository>()
        val forged = checkNotNull(repo.getConversationById(id)).currentMessages.flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>().filter { it.toolName == "workspace_bash" }
        assertTrue("an undeclared tool never runs a command",
            forged.all { part -> part.output.filterIsInstance<UIMessagePart.Text>().none { it.text.contains("uid=") } })
    }

    /** Sends [text] and waits until the saved conversation has [expectedMessages] messages, the last from the assistant. */
    private suspend fun sendAndAwait(id: Uuid, text: String, expectedMessages: Int) {
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        chat.sendMessage(id, listOf(UIMessagePart.Text(text)))
        withTimeout(60_000) {
            while (true) {
                val saved = repo.getConversationById(id)?.currentMessages.orEmpty()
                val idle = chat.getGenerationJobStateFlow(id).first() == null &&
                    chat.getMessageQueueFlow(id).value.messages.isEmpty()
                if (idle && saved.size == expectedMessages && saved.last().role == MessageRole.ASSISTANT) break
                delay(250)
            }
        }
    }

    /**
     * Compression runs as a service task. A message sent meanwhile waits in the queue and goes out
     * after it. Nothing is deleted: a summary checkpoint is inserted after the last node, and the next
     * request starts from it (the summary request itself is the chat request plus one instruction).
     */
    @Test(timeout = 240_000) fun backgroundCompressionQueuesMessagesAndInsertsCheckpoint() = runBlocking {
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val id = Uuid.random()
        chat.addConversationReference(id)
        try {
            chat.initializeConversation(id)
            sendAndAwait(id, "first", 2)
            sendAndAwait(id, "second", 4)
            sendAndAwait(id, "third", 6)
            val before = checkNotNull(repo.getConversationById(id))
            val originalIds = before.messageNodes.map { it.id }

            // The mock holds a summary request carrying this marker back ~4 s.
            chat.startCompression(id, CompressRequest(additionalPrompt = "[[compress:slow]]"), CompressOrigin.APP)
            assertEquals(CompressStatus.RUNNING, chat.currentCompressState(id)?.status)
            chat.sendMessage(id, listOf(UIMessagePart.Text("after compression")))
            delay(1_000)
            assertEquals(CompressStatus.RUNNING, chat.currentCompressState(id)?.status)
            assertEquals("held while compressing", 1, chat.getMessageQueueFlow(id).value.messages.size)
            assertEquals(6, chat.getConversationFlow(id).value.currentMessages.size)

            withTimeout(60_000) { while (chat.currentCompressState(id)?.status == CompressStatus.RUNNING) delay(100) }
            assertEquals(chat.currentCompressState(id)?.errorMessage, CompressStatus.SUCCEEDED, chat.currentCompressState(id)?.status)

            // 6 originals + checkpoint + the released message and its reply
            withTimeout(60_000) {
                while (true) {
                    val saved = repo.getConversationById(id)?.currentMessages.orEmpty()
                    if (saved.size == 9 && saved.last().role == MessageRole.ASSISTANT) break
                    delay(250)
                }
            }
            val after = checkNotNull(repo.getConversationById(id))
            assertEquals(originalIds, after.messageNodes.take(6).map { it.id })
            val summary = after.messageNodes[6].currentMessage
            assertTrue(summary.isContextCheckpoint)
            assertEquals(MessageRole.USER, summary.role)
            // the summary request saw the whole history (3 user + 3 assistant messages)
            assertTrue(summary.toText(), summary.toText().contains("summary ready (3 user / 3 assistant messages)"))
            val reply = after.currentMessages.last().toText()
            assertTrue(reply, reply.contains("after compression"))
        } finally {
            chat.removeConversationReference(id)
        }
    }

    /**
     * Runs [block] with [config] as the global compression setting and the mock model's context window set
     * to [contextLength] (the mock reports 100 prompt tokens per message), then restores both.
     */
    private suspend fun withCompression(
        config: CompressionConfig,
        contextLength: Int = DEFAULT_MODEL_CONTEXT_LENGTH,
        block: suspend (ChatService, ConversationRepository, Uuid) -> Unit,
    ) {
        suspend fun window(length: Int) = settings.update { s ->
            s.copy(providers = s.providers.map { provider ->
                if (provider.id != providerId) provider
                else provider.copyProvider(models = provider.models.map { it.copy(contextLength = length) })
            })
        }
        settings.update { it.copy(compression = config) }
        window(contextLength)
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val id = Uuid.random()
        chat.addConversationReference(id)
        try {
            chat.initializeConversation(id)
            block(chat, repo, id)
        } finally {
            chat.removeConversationReference(id)
            settings.update { it.copy(compression = CompressionConfig()) }
            window(DEFAULT_MODEL_CONTEXT_LENGTH)
        }
    }

    /** Waits until the saved conversation has [size] messages, the last from the assistant. */
    private suspend fun awaitSaved(repo: ConversationRepository, id: Uuid, size: Int) = withTimeout(60_000) {
        var saved = repo.getConversationById(id)?.currentMessages.orEmpty()
        while (saved.size != size || saved.last().role != MessageRole.ASSISTANT) {
            delay(250)
            saved = repo.getConversationById(id)?.currentMessages.orEmpty()
        }
        saved
    }

    /**
     * Automatic mode: once the last reply's reported usage plus the pending message crosses the trigger,
     * sending compresses first (the message waits in the queue) and goes out after the checkpoint.
     * Budget = 2000 - 1000 output reserve - 100 margin = 900; after two turns the mock reports >= 305 tokens.
     */
    @Test(timeout = 240_000) fun automaticCompressionRunsBeforeTheMessageThatCrossesTheTrigger() = runBlocking {
        withCompression(CompressionConfig(mode = AutoCompressMode.AUTO, triggerPercent = 30, targetPercent = 20, keepRecent = 0), 2_000) { chat, repo, id ->
            sendAndAwait(id, "first", 2)
            sendAndAwait(id, "second", 4)
            chat.sendMessage(id, listOf(UIMessagePart.Text("third")))
            val saved = awaitSaved(repo, id, 7)
            assertTrue("checkpoint before the third message", saved[4].isContextCheckpoint)
            assertTrue(saved[4].toText(), saved[4].toText().contains("summary ready (2 user / 2 assistant messages)"))
            assertTrue(saved[6].toText(), saved[6].toText().contains("third"))
            assertEquals(CompressOrigin.AUTO, chat.currentCompressState(id)?.origin)
        }
    }

    /**
     * Notify-only mode: the same crossing raises a hint and the message is sent without compressing.
     * While the hint is open it follows the usage; once dismissed it stays away until the next checkpoint.
     */
    @Test(timeout = 180_000) fun notifyOnlyModeRaisesAHintAndSendsAsUsual() = runBlocking {
        withCompression(CompressionConfig(mode = AutoCompressMode.NOTIFY, triggerPercent = 30, targetPercent = 20), 2_000) { chat, repo, id ->
            sendAndAwait(id, "first", 2)
            sendAndAwait(id, "second", 4)
            assertEquals(null, chat.compressSuggestionFlow(id).value)
            sendAndAwait(id, "third", 6)
            val hint = chat.compressSuggestionFlow(id).value
            assertTrue("hint after crossing the trigger: $hint", hint != null && hint.percent >= 30 && hint.exact)
            assertTrue(repo.getConversationById(id)!!.currentMessages.none { it.isContextCheckpoint })
            sendAndAwait(id, "fourth", 8)
            val updated = chat.compressSuggestionFlow(id).value
            assertTrue("open hint follows the usage: $hint -> $updated", updated != null && updated.percent > hint!!.percent)
            chat.dismissCompressSuggestion(id)
            assertEquals(null, chat.compressSuggestionFlow(id).value)
            sendAndAwait(id, "fifth", 10)
            assertEquals("dismissed hint stays away", null, chat.compressSuggestionFlow(id).value)
        }
    }

    /** Review before applying: the summary waits; applying inserts the edited text, discarding changes nothing. */
    @Test(timeout = 240_000) fun reviewedSummaryIsInsertedOnlyWhenApplied() = runBlocking {
        withCompression(CompressionConfig()) { chat, repo, id ->
            sendAndAwait(id, "first", 2)
            chat.startCompression(id, CompressRequest(review = true), CompressOrigin.APP)
            withTimeout(60_000) { while (chat.currentCompressState(id)?.status != CompressStatus.AWAITING_REVIEW) delay(100) }
            val pending = chat.currentCompressState(id)!!
            assertTrue(pending.reviewSummary.orEmpty().contains("summary ready"))
            assertEquals("still holds the conversation", 2, repo.getConversationById(id)!!.currentMessages.size)
            assertTrue(chat.resolveCompressionReview(id, null))
            withTimeout(30_000) { while (chat.currentCompressState(id)?.status == CompressStatus.AWAITING_REVIEW) delay(100) }
            assertEquals(CompressStatus.CANCELLED, chat.currentCompressState(id)?.status)
            assertEquals(2, repo.getConversationById(id)!!.currentMessages.size)

            chat.startCompression(id, CompressRequest(review = true, templateId = "roleplay"), CompressOrigin.APP)
            withTimeout(60_000) { while (chat.currentCompressState(id)?.status != CompressStatus.AWAITING_REVIEW) delay(100) }
            assertTrue(chat.resolveCompressionReview(id, "EDITED SUMMARY"))
            withTimeout(30_000) { while (chat.currentCompressState(id)?.status != CompressStatus.SUCCEEDED) delay(100) }
            val saved = repo.getConversationById(id)!!.currentMessages
            assertEquals(3, saved.size)
            assertTrue(saved[2].isContextCheckpoint)
            assertEquals("EDITED SUMMARY", saved[2].toText())
            assertFalse("nothing left to resolve", chat.resolveCompressionReview(id, "again"))
        }
    }

    /**
     * Automatic mode: a reply that overflows the context window (HTTP 400 context_length_exceeded) is
     * compressed and regenerated once; the mock accepts the request once it carries the summary.
     */
    @Test(timeout = 240_000) fun overflowedReplyIsCompressedAndRetriedOnce() = runBlocking {
        withCompression(CompressionConfig(mode = AutoCompressMode.AUTO)) { chat, repo, id ->
            sendAndAwait(id, "first", 2)
            chat.sendMessage(id, listOf(UIMessagePart.Text("[[overflow]] second")))
            val saved = awaitSaved(repo, id, 5)
            assertTrue(saved[2].isContextCheckpoint)
            assertTrue(saved[2].toText(), saved[2].toText().contains("summary ready (1 user / 1 assistant messages)"))
            assertTrue(saved[3].toText().contains("[[overflow]] second"))
            assertTrue(saved[4].toText(), saved[4].toText().contains("second"))
            assertEquals(CompressStatus.SUCCEEDED, chat.currentCompressState(id)?.status)
        }
    }

    @Test(timeout = 240_000) fun manualCarryOriginalsEditsCopyExcludesSlotAndLeavesOriginalUntouched() = runBlocking {
        withCompression(CompressionConfig()) { chat, repo, id ->
            sendAndAwait(id, "turn 1", 2)
            sendAndAwait(id, "turn 2", 4)
            sendAndAwait(id, "turn 3", 6)
            val nodes = checkNotNull(repo.getConversationById(id)).messageNodes
            val n2 = nodes[2].id
            val n3 = nodes[4].id
            chat.startCompression(
                id,
                CompressRequest(
                    keepRecent = 0,
                    carryMode = CarryOriginalsMode.RECENT,
                    carryCount = 2,
                    carryIncludeReplies = true,
                    carryExcludedNodeIds = setOf(n3),
                    carryEdits = mapOf(n2 to CarriedOriginalEdit(userText = "EDITED TURN 2")),
                ),
                CompressOrigin.APP,
            )
            withTimeout(60_000) { while (chat.currentCompressState(id)?.status == CompressStatus.RUNNING) delay(100) }
            val state = chat.currentCompressState(id)!!
            assertEquals("compress failed: $state", CompressStatus.SUCCEEDED, state.status)
            assertEquals(1, state.carriedCount)
            assertEquals(1, state.requestCount)
            assertFalse(state.chunked)
            assertFalse(state.cacheBypassed)

            val saved = checkNotNull(repo.getConversationById(id))
            val cp = saved.currentMessages.last()
            assertTrue(cp.isContextCheckpoint)
            val body = cp.toText()
            assertTrue(body, body.contains(CARRIED_ORIGINALS_HEADING))
            assertTrue(body, body.contains("### 1. User\nEDITED TURN 2"))
            assertTrue(body, body.contains("### 1. Assistant"))
            assertTrue(body, body.contains("turn 2"))
            assertEquals("turn 2", saved.messageNodes[2].currentMessage.toText())
        }
    }

    @Test(timeout = 240_000) fun fromOriginalResummarizesSkippingEarlierCheckpointsWhileKeepingHistory() = runBlocking {
        withCompression(CompressionConfig()) { chat, repo, id ->
            sendAndAwait(id, "first", 2)
            sendAndAwait(id, "second", 4)
            chat.startCompression(id, CompressRequest(keepRecent = 0), CompressOrigin.APP)
            withTimeout(60_000) { while (chat.currentCompressState(id)?.status != CompressStatus.SUCCEEDED) delay(100) }
            sendAndAwait(id, "after cp", 7)

            chat.startCompression(
                id,
                CompressRequest(
                    fromOriginal = true,
                    carryMode = CarryOriginalsMode.RECENT,
                    carryCount = 1,
                    carryIncludeReplies = false,
                ),
                CompressOrigin.APP,
            )
            withTimeout(60_000) { while (chat.currentCompressState(id)?.status == CompressStatus.RUNNING) delay(100) }
            val state = chat.currentCompressState(id)!!
            assertEquals("compress failed: $state", CompressStatus.SUCCEEDED, state.status)
            assertTrue(state.fromOriginal)
            assertTrue(state.cacheBypassed)
            assertEquals(1, state.carriedCount)

            val saved = checkNotNull(repo.getConversationById(id)).currentMessages
            assertEquals(8, saved.size)
            assertTrue(saved[4].isContextCheckpoint)
            assertTrue(saved[7].isContextCheckpoint)
            val body = saved[7].toText()
            assertTrue(body, body.contains("chunked summary ready (6 msgs, prev=no)"))
            assertTrue(body, body.contains("### 1. User\nafter cp"))
        }
    }

    @Test(timeout = 240_000) fun singlePassOverflowFallsBackToStandaloneChunkedCompression() = runBlocking {
        withCompression(CompressionConfig(maxChunkRequests = 8)) { chat, repo, id ->
            sendAndAwait(id, "[[compress:chunk_overflow]] one", 2)
            sendAndAwait(id, "two", 4)
            sendAndAwait(id, "three", 6)
            chat.startCompression(id, CompressRequest(keepRecent = 0, carryMode = CarryOriginalsMode.OFF), CompressOrigin.APP)
            withTimeout(60_000) { while (chat.currentCompressState(id)?.status == CompressStatus.RUNNING) delay(100) }
            val state = chat.currentCompressState(id)!!
            assertEquals("compress failed: $state", CompressStatus.SUCCEEDED, state.status)
            assertTrue("expected chunked fallback: $state", state.chunked)
            assertTrue("expected cacheBypassed: $state", state.cacheBypassed)
            assertTrue("expected >= 3 requests: $state", state.requestCount >= 3)
            assertTrue("expected >= 2 chunks: $state", state.completedChunks >= 2)
            assertTrue("expected usage: $state", (state.totalUsage?.totalTokens ?: 0) > 0)

            val cp = checkNotNull(repo.getConversationById(id)).currentMessages.last()
            assertTrue(cp.isContextCheckpoint)
            assertTrue(cp.toText(), cp.toText().contains("chunked summary ready"))
        }
    }

    /** A real chart_display call is validated server-side and shipped to the web as MessageDto.charts. */
    @Test(timeout = 120_000) fun chartDisplayResultIsProjectedForTheWeb() = runBlocking {
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val assistantId = settings.settingsSnapshot().assistantId
        val previousTools = settings.settingsSnapshot().assistants.first { it.id == assistantId }.localTools
        settings.update { s -> s.copy(assistants = s.assistants.map {
            if (it.id == assistantId) it.copy(localTools = (it.localTools + LocalToolOption.ChartDisplay).distinct()) else it
        }) }
        val id = Uuid.random()
        chat.addConversationReference(id)
        try {
            chat.initializeConversation(id)
            chat.sendMessage(id, listOf(UIMessagePart.Text("draw it [[script:chart_line]]")))
            withTimeout(90_000) {
                while (true) {
                    val parts = repo.getConversationById(id)?.currentMessages?.flatMap { it.parts }.orEmpty()
                    if (parts.any { it is UIMessagePart.Text && it.text.contains("chart shown") }) break
                    delay(250)
                }
            }
            val message = checkNotNull(repo.getConversationById(id)).currentMessages
                .first { message -> message.parts.any { it is UIMessagePart.Tool && it.toolName == "chart_display" } }
            val toolIndex = message.parts.indexOfFirst { it is UIMessagePart.Tool && it.toolName == "chart_display" }
            val chart = checkNotNull(message.toDto().charts) { "no chart projected: ${message.parts}" }.single()
            assertEquals(toolIndex, chart.partIndex)
            assertEquals("line", chart.spec.style)
            assertEquals("E2E chart", chart.spec.title)
            assertEquals(listOf("a", "b", "c"), chart.spec.xAxis.data)
            assertEquals(listOf(1.0, 2.5, 3.0), chart.spec.series.single().values)
        } finally {
            chat.removeConversationReference(id)
            settings.update { s -> s.copy(assistants = s.assistants.map {
                if (it.id == assistantId) it.copy(localTools = previousTools) else it
            }) }
        }
    }

    @Test(timeout = 240_000) fun clearContextTruncatesWithoutSendingEmptyUserAndRecordsUndo() = runBlocking {
        withCompression(CompressionConfig()) { chat, repo, id ->
            sendAndAwait(id, "PRE_CLEAR_UNIQUE_TOKEN", 2)
            val record = chat.clearContext(id)
            assertTrue(record.isClear)
            assertEquals(ContextCheckpointKind.CLEAR, record.meta?.kind)

            val (records, canClear) = chat.contextRecordsSnapshot(id)
            assertEquals(1, records.size)
            assertEquals(record.nodeId, records[0].nodeId)
            assertTrue(records[0].isClear)
            assertFalse(canClear)

            sendAndAwait(id, "after clear msg", 5)
            val saved = checkNotNull(repo.getConversationById(id)).currentMessages
            assertEquals(5, saved.size)
            assertTrue(saved[2].isClearContextCheckpoint)
            assertTrue(saved[4].toText(), saved[4].toText().contains("after clear msg"))
            assertFalse(saved[4].toText(), saved[4].toText().contains("PRE_CLEAR_UNIQUE_TOKEN"))

            val clearedBreakdown = chat.contextUsageBreakdown(id)
            assertFalse(clearedBreakdown.hasActiveCheckpoint)
            assertEquals(0, clearedBreakdown.historyCheckpointTokens)
            assertEquals(2, clearedBreakdown.historyMessageCount)

            val removed = chat.revokeCheckpoints(id, record.nodeId, cascadeLater = false)
            assertEquals(listOf(record.nodeId), removed)
            assertTrue(chat.contextRecords(id).isEmpty())
            assertEquals(4, checkNotNull(repo.getConversationById(id)).currentMessages.size)
        }
    }

    @Test(timeout = 240_000) fun compressToNewConversationLeavesOriginalUntouchedAndBreakdownWorks() = runBlocking {
        withCompression(CompressionConfig()) { chat, repo, id ->
            sendAndAwait(id, "turn-one", 2)
            sendAndAwait(id, "turn-two", 4)
            sendAndAwait(id, "turn-three", 6)

            chat.startCompression(
                id,
                CompressRequest(keepRecent = 2, createNewConversation = true),
                CompressOrigin.APP,
            )
            withTimeout(60_000) {
                while (chat.currentCompressState(id)?.status != CompressStatus.SUCCEEDED) delay(100)
            }
            val targetId = chat.currentCompressState(id)?.targetConversationId
            assertTrue("targetConversationId should differ from source: $targetId", targetId != null && targetId != id)

            val sourceMessages = checkNotNull(repo.getConversationById(id)).currentMessages
            assertEquals(6, sourceMessages.size)
            assertTrue(sourceMessages.none { it.isContextCheckpoint })

            val targetMessages = checkNotNull(repo.getConversationById(targetId!!)).currentMessages
            assertEquals(3, targetMessages.size)
            assertTrue(targetMessages[0].isContextCheckpoint)
            assertEquals(ContextCheckpointKind.MANUAL, targetMessages[0].checkpointMeta?.kind)
            assertEquals("turn-three", targetMessages[1].toText())
            assertEquals(MessageRole.ASSISTANT, targetMessages[2].role)

            val withDraft = chat.contextUsageBreakdown(targetId, listOf(UIMessagePart.Text("draft check payload")))
            assertFalse(withDraft.totalExact)
            assertEquals(null, withDraft.reportedContextTokens)
            assertTrue(withDraft.draftTokens > 0)
            assertTrue(withDraft.historyCheckpointTokens > 0)
            assertTrue(withDraft.historyMessagesTokens > 0)
            assertEquals(2, withDraft.historyMessageCount)
            assertTrue(withDraft.hasActiveCheckpoint)
            assertEquals(withDraft.estimatedTotalTokens, withDraft.totalTokens)

            chat.addConversationReference(targetId)
            try {
                chat.initializeConversation(targetId)
                sendAndAwait(targetId, "turn-four", 5)

                val noDraftAfter = chat.contextUsageBreakdown(targetId)
                assertEquals(0, noDraftAfter.draftTokens)
                val reported = checkNotNull(noDraftAfter.reportedContextTokens)
                assertTrue(reported > 0)
                assertTrue(noDraftAfter.totalExact)
                assertEquals(reported, noDraftAfter.totalTokens)

                val withDraftAfter = chat.contextUsageBreakdown(targetId, listOf(UIMessagePart.Text("draft after reply")))
                assertFalse(withDraftAfter.totalExact)
                assertTrue(withDraftAfter.draftTokens > 0)
                assertEquals(reported + withDraftAfter.draftTokens, withDraftAfter.totalTokens)
            } finally {
                chat.removeConversationReference(targetId)
            }
        }
    }
}
