package me.rerere.rikkihub.web.routes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.newLibraryEntry
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.installWebApiStatusPages
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.rikkihub.data.mutation.MutationRuntime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/** 网页端酒馆接口的端到端检查：真实的设置存储，经 HTTP 进、落到设置里出。 */
class TavernRoutesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime = TavernRuntime()

    @After
    fun tearDown() {
        runtime.close()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun newStore(): SettingsStore {
        val root = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        return SettingsStore.forTesting(dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope)
    }

    private fun withApi(store: SettingsStore = newStore(), block: suspend (HttpClient, SettingsStore) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            routing {
                route("/api") {
                    tavernLibraryRoutes(store)
                    tavernSettingsRoutes(store, runtime)
                }
            }
        }
        block(client, store)
    }

    private suspend fun SettingsStore.await(condition: (Settings) -> Boolean): Settings =
        withTimeout(10_000) { settingsFlow.first(condition) }

    private suspend fun HttpResponse.obj(): JsonObject = JsonInstant.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpClient.putJson(url: String, body: JsonElement) =
        put(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
    private suspend fun HttpClient.postJson(url: String, body: JsonElement) =
        post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }

    // ---------------------------------------------------------------- 提示词库

    @Test
    fun `prompt library entries are created, patched and deleted on the latest settings`() = withApi { client, store ->
        val created = client.postJson("/api/prompt-library", buildJsonObject { put("name", "Style"); put("content", "be brief") })
        assertEquals(HttpStatusCode.Created, created.status)
        val id = created.obj().getValue("identifier").jsonPrimitive.content
        Uuid.parse(id)
        assertEquals("be brief", store.await { it.promptLibrary.any { e -> e.identifier == id } }.promptLibrary.first { it.identifier == id }.content)

        val patched = client.putJson("/api/prompt-library/$id", buildJsonObject { put("content", "be long"); put("injection_depth", 2) })
        assertEquals(HttpStatusCode.OK, patched.status)
        val entry = store.await { s -> s.promptLibrary.first { it.identifier == id }.content == "be long" }.promptLibrary.first { it.identifier == id }
        assertEquals("Style", entry.name)
        assertEquals(2, entry.injectionDepth)

        // 助手选用了它：删库条目时一并去掉引用
        store.update { it.copy(assistants = listOf(Assistant(name = "a", libraryEntryIds = setOf(Uuid.parse(id))))) }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/prompt-library/$id").status)
        val after = store.await { s -> s.promptLibrary.none { it.identifier == id } }
        assertTrue(after.assistants.all { id.let(Uuid::parse) !in it.libraryEntryIds })
    }

    @Test
    fun `prompt library rejects unknown ids and non object bodies`() = withApi { client, _ ->
        assertEquals(HttpStatusCode.NotFound, client.putJson("/api/prompt-library/${Uuid.random()}", buildJsonObject { put("name", "x") }).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/prompt-library/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/prompt-library", JsonArray(emptyList())).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/prompt-library/not-a-uuid", buildJsonObject { }).status)
    }

    // ---------------------------------------------------------------- 世界书

    @Test
    fun `a lorebook is created, edited entry by entry and deleted`() = withApi { client, store ->
        val created = client.postJson("/api/lorebooks", buildJsonObject { put("name", "Realm"); put("description", "lore") })
        assertEquals(HttpStatusCode.Created, created.status)
        val bookId = created.obj().getValue("id").jsonPrimitive.content

        val first = client.postJson("/api/lorebooks/$bookId/entries", buildJsonObject {
            put("comment", "dragons"); put("content", "fire"); put("key", buildJsonArray { add(JsonPrimitive("dragon")) })
        })
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(0, first.obj().getValue("entry").jsonObject.getValue("uid").jsonPrimitive.content.toInt())
        val second = client.postJson("/api/lorebooks/$bookId/entries", buildJsonObject { })
        assertEquals(1, second.obj().getValue("entry").jsonObject.getValue("uid").jsonPrimitive.content.toInt())

        // 只改请求里的字段，另一个字段不被盖掉
        val edit = client.putJson("/api/lorebooks/$bookId/entries/0", buildJsonObject { put("content", "ice"); put("scanDepth", 9) })
        assertEquals(HttpStatusCode.OK, edit.status)
        val editBody = edit.obj()
        assertEquals("ice", editBody.getValue("entry").jsonObject.getValue("content").jsonPrimitive.content)
        val book = store.await { s -> s.lorebooks.single().entries.firstOrNull()?.content == "ice" }.lorebooks.single()
        assertEquals("dragons", book.entries[0].comment)
        assertEquals(listOf("dragon"), book.entries[0].key)
        assertEquals(9, book.entries[0].scanDepth)

        // 整本取回：摘要加全部条目；改动回包里的指纹和整本取回的一致
        val full = client.get("/api/lorebooks/$bookId").obj()
        assertEquals(editBody.getValue("rev"), full.getValue("rev"))
        assertEquals(2, full.getValue("entryCount").jsonPrimitive.content.toInt())
        assertEquals(2, full.getValue("entries").jsonArray.size)

        // 批量启停
        val bulk = client.putJson("/api/lorebooks/$bookId/entries", buildJsonObject {
            put("enabled", false); put("uids", buildJsonArray { add(JsonPrimitive(1)) })
        })
        assertEquals(HttpStatusCode.OK, bulk.status)
        val toggled = store.await { s -> s.lorebooks.single().entries.any { it.disable } }.lorebooks.single()
        assertEquals(listOf(false, true), toggled.entries.map { it.disable })
        assertEquals(client.get("/api/lorebooks/$bookId").obj().getValue("rev"), bulk.obj().getValue("rev"))

        // 书自己的字段
        val meta = client.putJson("/api/lorebooks/$bookId", buildJsonObject { put("name", "Realm 2"); put("enabled", false) })
        assertEquals(HttpStatusCode.OK, meta.status)
        assertEquals(2, meta.obj().getValue("entryCount").jsonPrimitive.content.toInt())

        val removed = client.delete("/api/lorebooks/$bookId/entries/0")
        assertEquals(HttpStatusCode.OK, removed.status)
        assertEquals(client.get("/api/lorebooks/$bookId").obj().getValue("rev"), removed.obj().getValue("rev"))
        assertEquals(1, store.await { it.lorebooks.single().entries.size == 1 }.lorebooks.single().entries.size)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/lorebooks/$bookId/entries/0").status)

        store.update { it.copy(assistants = listOf(Assistant(name = "a", lorebookIds = setOf(Uuid.parse(bookId))))) }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/lorebooks/$bookId").status)
        val gone = store.await { it.lorebooks.isEmpty() }
        assertTrue(gone.assistants.all { it.lorebookIds.isEmpty() })
    }

    @Test
    fun `lorebook routes answer bad requests precisely`() = withApi { client, store ->
        val book = Lorebook(name = "b", entries = listOf(WorldInfoEntry(uid = 0)))
        store.update { it.copy(lorebooks = listOf(book)) }
        store.await { it.lorebooks.isNotEmpty() }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/lorebooks/${Uuid.random()}").status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/lorebooks/${book.id}/entries/x", buildJsonObject { }).status)
        assertEquals(HttpStatusCode.NotFound, client.putJson("/api/lorebooks/${book.id}/entries/42", buildJsonObject { }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/lorebooks/${book.id}/entries", buildJsonObject { put("uids", JsonArray(emptyList())) }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/lorebooks/${book.id}", buildJsonObject { put("enabled", "no") }).status)
        // uid 改不了
        client.putJson("/api/lorebooks/${book.id}/entries/0", buildJsonObject { put("uid", 77); put("comment", "c") })
        assertEquals(listOf(0), store.await { it.lorebooks.single().entries.single().comment == "c" }.lorebooks.single().entries.map { it.uid })
    }

    @Test
    fun `a tavern world book file imports and exports in both formats`() = withApi { client, store ->
        val tavernFile = """{"entries":{"0":{"uid":0,"key":["moon"],"keysecondary":[],"comment":"c","content":"silver","order":50,"position":1,"disable":false}}}"""
        val imported = client.post("/api/lorebooks/import?name=Moon.json") { contentType(ContentType.Application.Json); setBody(tavernFile) }
        assertEquals(HttpStatusCode.Created, imported.status)
        val body = imported.obj()
        assertEquals("Moon", body.getValue("name").jsonPrimitive.content)
        assertEquals(50, body.getValue("entries").jsonArray[0].jsonObject.getValue("order").jsonPrimitive.content.toInt())
        val id = body.getValue("id").jsonPrimitive.content
        store.await { it.lorebooks.size == 1 }

        // 本应用格式导出再导入：条目不丢，书是新的
        val native = client.get("/api/lorebooks/$id/export").bodyAsText()
        assertTrue(native.contains("\"type\":\"lorebook\""))
        val again = client.post("/api/lorebooks/import") { contentType(ContentType.Application.Json); setBody(native) }
        assertEquals(HttpStatusCode.Created, again.status)
        assertNotEquals(id, again.obj().getValue("id").jsonPrimitive.content)
        assertEquals(1, again.obj().getValue("entryCount").jsonPrimitive.content.toInt())

        // 酒馆格式：entries 是以 uid 为键的对象
        val st = JsonInstant.parseToJsonElement(client.get("/api/lorebooks/$id/export?format=st").bodyAsText()).jsonObject
        assertTrue(st.getValue("entries") is JsonObject)
        assertEquals("silver", st.getValue("entries").jsonObject.getValue("0").jsonObject.getValue("content").jsonPrimitive.content)

        assertEquals(HttpStatusCode.BadRequest, client.get("/api/lorebooks/$id/export?format=zip").status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/lorebooks/import") { setBody("not json at all") }.status)
    }

    // ---------------------------------------------------------------- 全局设置

    @Test
    fun `global world info settings patch, validate and reset`() = withApi { client, store ->
        val ok = client.putJson("/api/tavern/world-info", buildJsonObject { put("world_info_depth", 6); put("world_info_recursive", true) })
        assertEquals(HttpStatusCode.OK, ok.status)
        val saved = store.await { it.tavern.worldInfo.depth == 6 }.tavern.worldInfo
        assertTrue(saved.recursive)
        assertEquals(25, saved.budget)

        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/tavern/world-info", buildJsonObject { put("world_info_budget", 500) }).status)
        assertEquals(6, store.settingsFlow.value.tavern.worldInfo.depth)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/tavern/world-info").status)
        assertEquals(2, store.await { it.tavern.worldInfo.depth == 2 }.tavern.worldInfo.depth)
    }

    @Test
    fun `global author's note defaults patch, validate and reset`() = withApi { client, store ->
        assertEquals(HttpStatusCode.OK, client.putJson("/api/tavern/authors-note", buildJsonObject { put("prompt", "tone"); put("depth", 3) }).status)
        val note = store.await { it.tavern.authorsNote.prompt == "tone" }.tavern.authorsNote
        assertEquals(3, note.depth)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/tavern/authors-note", buildJsonObject { put("position", 9) }).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/tavern/authors-note").status)
        assertEquals("", store.await { it.tavern.authorsNote.prompt.isEmpty() }.tavern.authorsNote.prompt)
    }

    @Test
    fun `global regex scripts are created, patched, ordered and deleted`() = withApi { client, store ->
        val a = client.postJson("/api/tavern/regex", buildJsonObject { put("scriptName", "a"); put("findRegex", "/x/g") }).obj()
        val b = client.postJson("/api/tavern/regex", buildJsonObject { put("scriptName", "b") }).obj()
        // 同一个 id 不能建两次
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/tavern/regex", buildJsonObject { put("id", a.getValue("id").jsonPrimitive.content) }).status)
        val aId = a.getValue("id").jsonPrimitive.content
        val bId = b.getValue("id").jsonPrimitive.content
        assertNotEquals(aId, bId)

        assertEquals(HttpStatusCode.OK, client.putJson("/api/tavern/regex/$aId", buildJsonObject { put("markdownOnly", true); put("replaceString", "y") }).status)
        val patched = store.await { s -> s.tavern.regexScripts.firstOrNull { it.id == aId }?.markdownOnly == true }.tavern.regexScripts.first { it.id == aId }
        assertEquals("/x/g", patched.findRegex)
        assertEquals("y", patched.replaceString)

        assertEquals(HttpStatusCode.OK, client.putJson("/api/tavern/regex/order", buildJsonObject {
            put("ids", buildJsonArray { add(JsonPrimitive(bId)); add(JsonPrimitive(aId)) })
        }).status)
        assertEquals(listOf(bId, aId), store.await { s -> s.tavern.regexScripts.firstOrNull()?.id == bId }.tavern.regexScripts.map { it.id })

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/tavern/regex/$aId").status)
        assertEquals(listOf(bId), store.await { it.tavern.regexScripts.size == 1 }.tavern.regexScripts.map { it.id })
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/tavern/regex/$aId").status)
    }

    @Test
    fun `regex find strings are checked and scripts are tried out with the real engine`() = withApi { client, _ ->
        suspend fun check(find: String, substitute: Int = 0) =
            client.postJson("/api/tavern/regex/check", buildJsonObject { put("findRegex", find); put("substituteRegex", substitute) }).obj()

        assertEquals("empty", check("  ").getValue("status").jsonPrimitive.content)
        assertEquals("ok", check("/cat/gi").getValue("status").jsonPrimitive.content)
        assertEquals("ok", check("cat").getValue("status").jsonPrimitive.content)
        val bad = check("/(/g")
        assertEquals("invalid", bad.getValue("status").jsonPrimitive.content)
        assertTrue(bad.containsKey("message"))
        assertEquals("skipped", check("{{user}}", substitute = 1).getValue("status").jsonPrimitive.content)

        val preview = client.postJson("/api/tavern/regex/preview", buildJsonObject {
            put("script", buildJsonObject { put("scriptName", "t"); put("findRegex", "/cat/g"); put("replaceString", "dog"); put("disabled", true) })
            put("sample", "a cat sat on the cat")
        })
        assertEquals("a dog sat on the dog", preview.obj().getValue("text").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/tavern/regex/preview", buildJsonObject { put("sample", "x") }).status)
    }

    @Test
    fun `global variables keep their type and can be removed`() = withApi { client, store ->
        store.update { it.copy(tavern = TavernSettings(globalVariables = buildJsonObject { put("count", 3) })) }
        store.await { it.tavern.globalVariables.isNotEmpty() }

        assertEquals(HttpStatusCode.OK, client.putJson("/api/tavern/variables", buildJsonObject { put("name", " count "); put("text", "7") }).status)
        val settings = store.await { (it.tavern.globalVariables["count"] as? JsonPrimitive)?.content == "7" }
        assertFalse((settings.tavern.globalVariables["count"] as JsonPrimitive).isString) // 原来是数字，新文字也是数字 → 仍是数字

        assertEquals(HttpStatusCode.OK, client.putJson("/api/tavern/variables", buildJsonObject { put("name", "mood"); put("text", "calm") }).status)
        assertTrue((store.await { it.tavern.globalVariables.containsKey("mood") }.tavern.globalVariables["mood"] as JsonPrimitive).isString)

        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/tavern/variables", buildJsonObject { put("name", "  "); put("text", "x") }).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/tavern/variables?name=mood").status)
        assertFalse(store.await { !it.tavern.globalVariables.containsKey("mood") }.tavern.globalVariables.containsKey("mood"))
        assertEquals(HttpStatusCode.BadRequest, client.delete("/api/tavern/variables").status)
    }

    @Test
    fun `library entries made by the app are reachable through the web routes`() = withApi { client, store ->
        val entry = newLibraryEntry("from app")
        store.update { it.copy(promptLibrary = listOf(entry)) }
        store.await { it.promptLibrary.isNotEmpty() }
        assertEquals(HttpStatusCode.OK, client.putJson("/api/prompt-library/${entry.identifier}", buildJsonObject { put("content", "edited on web") }).status)
        assertEquals("edited on web", store.await { s -> s.promptLibrary.first { it.identifier == entry.identifier }.content == "edited on web" }.promptLibrary.first { it.identifier == entry.identifier }.content)
    }
}
