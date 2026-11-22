package me.rerere.rikkihub.web.routes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.character.PresetPorting
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.installWebApiStatusPages
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/** 酒馆预设的网页端导入导出：经 HTTP 进，助手落到设置里，导出的文件再导入得到同一个助手。 */
class PresetRoutesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun newStore(): SettingsStore {
        val root = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        return SettingsStore.forTesting(dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope)
    }

    private fun withApi(block: suspend (HttpClient, SettingsStore) -> Unit) = testApplication {
        val store = newStore()
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            routing { route("/api") { assistantPresetRoutes(store) } }
        }
        block(client, store)
    }

    private fun fixture(name: String) = PresetRoutesTest::class.java.getResource("/presets/$name")!!.readText()

    private suspend fun HttpResponse.obj(): JsonObject = JsonInstant.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun SettingsStore.await(condition: (Settings) -> Boolean): Settings =
        withTimeout(10_000) { settingsFlow.first(condition) }

    private suspend fun HttpClient.importPreset(text: String, name: String = "Night Ferry.json") =
        post("/api/assistants/import-preset?name=${name.replace(" ", "%20")}") { contentType(ContentType.Application.Json); setBody(text) }

    @Test
    fun `a preset becomes a new assistant with typed sampling parameters and a report`() = withApi { client, store ->
        val response = client.importPreset(fixture("night-ferry.json"))
        assertEquals(response.bodyAsText(), HttpStatusCode.Created, response.status)
        val body = response.obj()
        val assistant = body.getValue("assistant").jsonObject
        assertEquals("Night Ferry", assistant.getValue("name").jsonPrimitive.content)
        assertEquals("0.9", assistant.getValue("temperature").jsonPrimitive.content)
        assertEquals("40", assistant.getValue("topK").jsonPrimitive.content)
        assertEquals("42", assistant.getValue("seed").jsonPrimitive.content)

        val report = body.getValue("report").jsonObject
        assertTrue(report.getValue("promptCount").jsonPrimitive.content.toInt() > 0)
        val skipped = report.getValue("connectionKeysSkipped").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("proxy_password" in skipped)
        assertTrue("temperature" in report.getValue("samplingImported").jsonArray.map { it.jsonPrimitive.content })

        // the assistant was appended to the latest settings; no secret from the file is stored
        val saved = store.await { s -> s.assistants.any { it.name == "Night Ferry" } }.assistants.first { it.name == "Night Ferry" }
        assertEquals(42L, saved.seed)
        assertFalse("proxy_password" in saved.promptPreset.toJson())
        // the web projection of the assistant never carries the dropped connection keys either
        assertFalse(assistant.toString().contains("proxy_password"))
    }

    @Test
    fun `an exported assistant imports back as the same preset`() = withApi { client, store ->
        client.importPreset(fixture("night-ferry.json"))
        val saved = store.await { s -> s.assistants.any { it.name == "Night Ferry" } }.assistants.first { it.name == "Night Ferry" }

        val exported = client.get("/api/assistants/${saved.id}/export-preset")
        assertEquals(HttpStatusCode.OK, exported.status)
        assertEquals("application/json", exported.contentType()?.withoutParameters()?.toString())
        assertTrue(exported.headers[HttpHeaders.ContentDisposition]!!.contains("Night Ferry.json"))
        val text = exported.bodyAsText()
        val file = JsonInstant.parseToJsonElement(text).jsonObject
        assertEquals("0.9", file.getValue("temperature").jsonPrimitive.content)
        assertEquals("42", file.getValue("seed").jsonPrimitive.content)
        assertEquals(PresetPorting.export(saved), file)

        val again = client.importPreset(text, "Night Ferry again.json").obj().getValue("assistant").jsonObject
        assertEquals("Night Ferry again", again.getValue("name").jsonPrimitive.content)
        assertEquals("0.9", again.getValue("temperature").jsonPrimitive.content)
        val second = store.await { s -> s.assistants.any { it.name == "Night Ferry again" } }.assistants.first { it.name == "Night Ferry again" }
        // the same prompts, in the same sent order (the array order inside the file may differ, the order list is what counts)
        fun sentOrder(a: me.rerere.rikkihub.data.model.Assistant) =
            a.promptPreset.promptOrder.first { it.characterIdString == me.rerere.tavern.prompt.PromptPreset.GLOBAL_ORDER_ID.toString() }.order.map { it.identifier to it.enabled }
        assertEquals(sentOrder(saved), sentOrder(second))
        assertEquals(saved.promptPreset.prompts.map { it.identifier }.toSet(), second.promptPreset.prompts.map { it.identifier }.toSet())
    }

    @Test
    fun `the upstream default preset imports too`() = withApi { client, _ ->
        val response = client.importPreset(fixture("upstream-default.json"), "Default.json")
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("Default", response.obj().getValue("assistant").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `files that are not presets are refused and nothing is added`() = withApi { client, store ->
        assertEquals(HttpStatusCode.BadRequest, client.importPreset("not json").status)
        assertEquals(HttpStatusCode.BadRequest, client.importPreset("[1,2]").status)
        assertEquals(HttpStatusCode.BadRequest, client.importPreset("""{"hello":"world"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.importPreset("""{"prompts":[],"prompt_order":[]}""").status)
        store.update { it } // let the store finish loading before looking
        assertTrue(store.settingsFlow.first().assistants.none { it.name == "Night Ferry" })
    }

    @Test
    fun `exporting an unknown assistant is 404`() = withApi { client, _ ->
        assertEquals(HttpStatusCode.NotFound, client.get("/api/assistants/${Uuid.random()}/export-preset").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/assistants/nope/export-preset").status)
    }
}
