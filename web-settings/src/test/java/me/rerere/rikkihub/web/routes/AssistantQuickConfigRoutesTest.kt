package me.rerere.rikkihub.web.routes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.rerere.rikkihub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.withEntryEnabled
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.installWebApiStatusPages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 聊天「+」面板的工具 / 提示词方块：每次只改一个助手的一项，其余字段保持存储里的最新值。 */
class AssistantQuickConfigRoutesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private val assistant = Assistant(
        name = "Quick",
        tags = listOf(kotlin.uuid.Uuid.random()),
        localTools = listOf(LocalToolOption.TimeInfo),
        promptPreset = AssistantPresets.withSystemPrompt("be brief"),
    )

    private fun withApi(block: suspend (HttpClient, SettingsStore) -> Unit) = testApplication {
        val root = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        val store = SettingsStore.forTesting(dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope)
        store.update { it.copy(assistants = it.assistants + assistant) }
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            routing { route("/api") { assistantQuickConfigRoutes(store) } }
        }
        block(client, store)
    }

    private suspend fun HttpClient.putJson(path: String, body: String) =
        put("/api/assistants/$path") { contentType(ContentType.Application.Json); setBody(body) }

    // 读回来的条目带 keyOrder（序列化时的键序记录），按 JSON 树比较
    private fun json(assistant: Assistant) = JsonInstant.encodeToJsonElement(Assistant.serializer(), assistant)

    private suspend fun SettingsStore.stored(): Assistant =
        withTimeout(10_000) { settingsFlow.first { s -> s.assistants.any { it.id == assistant.id } } }.assistants.first { it.id == assistant.id }

    @Test
    fun `a prompt entry switch changes that entry only`() = withApi { client, store ->
        val before = store.stored()
        val main = before.promptPreset.layout().entries.first { it.identifier == "main" }
        assertEquals(true, main.enabled)
        val response = client.putJson("${assistant.id}/prompt-entries/main", """{"enabled":false}""")
        assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
        val saved = store.stored()
        val after = saved.promptPreset.layout().entries
        assertEquals(false, after.first { it.identifier == "main" }.enabled)
        // 其余条目的开关不变；整个助手只差这一个开关
        assertEquals(
            before.promptPreset.layout().entries.filter { it.identifier != "main" }.map { it.identifier to it.enabled },
            after.filter { it.identifier != "main" }.map { it.identifier to it.enabled },
        )
                assertEquals(json(before.copy(promptPreset = before.promptPreset.withEntryEnabled("main", false))), json(saved))

        assertEquals(HttpStatusCode.NotFound, client.putJson("${assistant.id}/prompt-entries/nope", """{"enabled":true}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("${assistant.id}/prompt-entries/main", """{"enabled":"true"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("${assistant.id}/prompt-entries/main", "{}").status)
        assertEquals(json(saved), json(store.stored()))
    }
}
