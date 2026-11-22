package me.rerere.rikkihub.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.ChatService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Real service calls against the local offline mock, with settings restored even after failure. */
@RunWith(AndroidJUnit4::class)
class UsageSourcesInstrumentedTest {
    @Test(timeout = 90_000) fun titleAndSuggestionAreSeparateRequestsButUnwrappedConnectionProbeIsExcluded() = runBlocking {
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsStore>()
        val previous = settings.settingsSnapshot()
        val model = Model(modelId = "mock-chat")
        val provider = ProviderSetting.OpenAI(name = "Usage source test", apiKey = "mock-key",
            baseUrl = "http://10.0.2.2:18080/v1", models = listOf(model))
        val assistant = Assistant(name = "Usage source test", chatModelId = model.id)
        val chat = koin.get<ChatService>()
        val repo = koin.get<ConversationRepository>()
        val dao = koin.get<AppDatabase>().usageRecordDao()
        val id = Uuid.random()
        suspend fun rows() = dao.getGroupedUsage("%Y-%m", 0).filter { it.providerId == provider.id.toString() }
        try {
            settings.update { it.copy(providers = it.providers + provider, assistants = it.assistants + assistant,
                assistantId = assistant.id, fastModelId = model.id, enableAutoTitle = false, enableSuggestion = false) }
            // Both connection-test entry points use the bare provider, not the usage wrapper.
            koin.get<ProviderManager>().getProviderByType(provider).generateText(provider,
                listOf(UIMessage.user("offline connection probe")), TextGenerationParams(model))
            assertTrue(rows().isEmpty())
            chat.addConversationReference(id)
            chat.initializeConversation(id)
            chat.sendMessage(id, listOf(UIMessagePart.Text("offline source acceptance")))
            withTimeout(30_000) {
                while (rows().none { it.source == "CHAT" } ||
                    repo.getConversationById(id)?.currentMessages?.lastOrNull()?.finishedAt == null) delay(100)
            }
            chat.getGenerationJobStateFlow(id).first()?.join()
            val conversation = checkNotNull(repo.getConversationById(id))
            chat.generateTitle(id, conversation, force = true)
            settings.update { it.copy(enableSuggestion = true) }
            chat.generateSuggestion(id, conversation)
            withTimeout(30_000) { while (rows().sumOf { it.requests } < 3) delay(100) }
            val usage = rows()
            assertEquals(setOf("CHAT", "TITLE", "SUGGESTION"), usage.map { it.source }.toSet())
            assertTrue(usage.all { it.requests == 1L && it.incompleteRequests == 0L && it.completionTokens == 5L })
        } finally {
            chat.removeConversationReference(id)
            settings.update { previous }
        }
    }
}
