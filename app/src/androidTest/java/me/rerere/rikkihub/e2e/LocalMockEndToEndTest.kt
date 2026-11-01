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
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.CompressOrigin
import me.rerere.rikkihub.service.CompressRequest
import me.rerere.rikkihub.service.CompressStatus
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
            modeInjections = s.modeInjections.filterNot { it.name == "Mock batch mode" },
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
        assertEquals(MessageRole.USER, after.modeInjections.single { it.name == "Mock batch mode" }.role)

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
}
