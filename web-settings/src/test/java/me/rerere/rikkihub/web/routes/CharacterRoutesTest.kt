package me.rerere.rikkihub.web.routes

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.character.CharacterService
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.FakeCharacterDAO
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.installWebApiStatusPages
import me.rerere.tavern.card.CardCodec
import me.rerere.tavern.card.CardFiles
import me.rerere.tavern.card.CharacterCard
import me.rerere.tavern.card.PngCards
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/** 网页端角色接口：真实的设置存储和角色仓库，经 HTTP 进、落到仓库和设置里出。 */
class CharacterRoutesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private class Env(val store: SettingsStore, val repository: CharacterRepository, val service: CharacterService)

    private fun newEnv(): Env {
        val root = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        val store = SettingsStore.forTesting(dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope)
        val repository = CharacterRepository(FakeCharacterDAO(), File(root, "characters"))
        val service = CharacterService(repository, { store.settingsFlow.value }, { fn -> store.update(fn) })
        return Env(store, repository, service)
    }

    private fun withApi(env: Env = newEnv(), block: suspend (HttpClient, Env) -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            routing {
                route("/api") {
                    tavernSettingsRoutes(env.store, null)
                    characterRoutes(env.store, env.repository, env.service, chatService = null)
                }
            }
        }
        block(client, env)
    }

    private fun fixture(name: String) = CharacterRoutesTest::class.java.getResource("/cards/$name")!!.readBytes()

    private suspend fun HttpResponse.obj(): JsonObject = JsonInstant.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpClient.putJson(url: String, body: JsonElement) =
        put(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
    private suspend fun HttpClient.postJson(url: String, body: JsonElement) =
        post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }

    private suspend fun HttpClient.importCard(bytes: ByteArray, name: String): JsonObject {
        val response = post("/api/characters/import?name=$name") { contentType(ContentType.Application.OctetStream); setBody(bytes) }
        assertEquals(response.bodyAsText(), HttpStatusCode.Created, response.status)
        return response.obj()
    }

    private suspend fun HttpClient.detail(id: String): JsonObject = get("/api/characters/$id").obj()

    private suspend fun SettingsStore.await(condition: (Settings) -> Boolean): Settings =
        withTimeout(10_000) { settingsFlow.first(condition) }

    // ---------------------------------------------------------------- 导入与列表

    @Test
    fun `importing a card returns a report and the character shows up in the list and detail`() = withApi { client, env ->
        val imported = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json")
        val id = imported.getValue("id").jsonPrimitive.content
        assertEquals("Ines Okafor", imported.getValue("name").jsonPrimitive.content)
        val report = imported.getValue("report").jsonObject
        assertEquals("v2", report.getValue("cardFormat").jsonPrimitive.content)
        assertEquals(3, report.getValue("greetings").jsonPrimitive.int)
        assertEquals(1, report.getValue("regexScripts").jsonPrimitive.int)
        assertEquals("created", report.getValue("book").jsonObject.getValue("outcome").jsonPrimitive.content)

        val list = client.get("/api/characters").bodyAsText().let { JsonInstant.parseToJsonElement(it).jsonArray }
        assertEquals(listOf(id), list.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("cartography", "fixture"), list.single().jsonObject.getValue("tags").jsonArray.map { it.jsonPrimitive.content })

        env.store.await { it.lorebooks.isNotEmpty() }
        val detail = client.detail(id)
        val fields = detail.getValue("fields").jsonObject
        assertEquals("Ines Okafor", fields.getValue("name").jsonPrimitive.content)
        assertEquals(2, fields.getValue("alternate_greetings").jsonArray.size)
        assertEquals(3, fields.getValue("depth_prompt").jsonObject.getValue("depth").jsonPrimitive.int)
        assertEquals("ines.json", detail.getValue("sourceName").jsonPrimitive.content)
        // the card brings one regex script and one embedded book; the script waits for the user's permission
        assertEquals("Bracket asides", detail.getValue("regexScripts").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(detail.getValue("regexAllowed").jsonPrimitive.boolean)
        val book = detail.getValue("books").jsonArray.single().jsonObject
        assertEquals("Coastline Notes", book.getValue("name").jsonPrimitive.content)
        assertTrue(book.getValue("embedded").jsonPrimitive.boolean)
        assertEquals(book.getValue("id").jsonPrimitive.content, detail.getValue("embeddedBookId").jsonPrimitive.content)
    }

    @Test
    fun `search narrows the list and favourites come first`() = withApi { client, _ ->
        val a = client.importCard(fixture("v2-harbor-cartographer.json"), "a.json").getValue("id").jsonPrimitive.content
        val b = client.importCard(fixture("v3-clockwork-orchard.json"), "b.json").getValue("id").jsonPrimitive.content
        suspend fun ids(response: HttpResponse) = parseBody(response).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf(a, b), ids(client.get("/api/characters"))) // Ines before Tobias
        assertEquals(listOf(b), ids(client.get("/api/characters?query=orchard")))
        client.putJson("/api/characters/$b", buildJsonObject { put("fav", true) })
        assertEquals(listOf(b, a), ids(client.get("/api/characters")))
    }

    private suspend fun parseBody(response: HttpResponse): JsonElement = JsonInstant.parseToJsonElement(response.bodyAsText())

    @Test
    fun `a png card imports and its picture becomes the avatar with a cacheable address`() = withApi { client, _ ->
        val card = CardCodec.parse(String(fixture("v3-clockwork-orchard.json"))).card
        val png = CardFiles.toPng(card, null)
        val id = client.importCard(png, "tobias.png").getValue("id").jsonPrimitive.content
        val detail = client.detail(id)
        val avatarUrl = detail.getValue("avatarUrl").jsonPrimitive.content
        assertTrue(avatarUrl, avatarUrl.startsWith("/api/characters/$id/avatar?v=avatar-"))

        val response = client.get(avatarUrl)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("image/png", response.contentType()?.withoutParameters()?.toString())
        assertTrue(response.headers[HttpHeaders.CacheControl]!!.contains("immutable"))
        assertTrue(PngCards.isPng(response.bodyAsBytes()))
        // an address with a stale version still answers, but is revalidated every time
        val stale = client.get("/api/characters/$id/avatar?v=old.png")
        assertEquals(HttpStatusCode.OK, stale.status)
        assertEquals("private, no-cache", stale.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `import rejects empty bodies and files that are not cards`() = withApi { client, env ->
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/characters/import") { setBody(ByteArray(0)) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/characters/import") { setBody("not a card".toByteArray()) }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/characters/import") { setBody("""{"hello":"world"}""".toByteArray()) }.status)
        assertEquals(0, env.repository.count())
    }

    @Test
    fun `a blank character can be created`() = withApi { client, _ ->
        val created = client.postJson("/api/characters", buildJsonObject { put("name", "Nova") })
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("Nova", created.obj().getValue("name").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/characters", JsonArray(emptyList())).status)
        assertEquals(HttpStatusCode.BadRequest, client.postJson("/api/characters", buildJsonObject { put("name", 5) }).status)
    }

    // ---------------------------------------------------------------- 编辑

    @Test
    fun `a patch changes only the fields it names and bumps the revision`() = withApi { client, _ ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val before = client.detail(id)
        val revision = before.getValue("revision").jsonPrimitive.content.toLong()

        val response = client.putJson("/api/characters/$id", buildJsonObject {
            put("description", "Maps the coast.")
            put("tags", "harbor, maps , harbor")
            put("alternate_greetings", buildJsonArray { add(JsonPrimitive("Ahoy.")) })
            put("fav", true)
            put("depth_prompt", buildJsonObject { put("depth", 7); put("role", "user") })
        })
        assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
        val after = response.obj()
        assertEquals(revision + 1, after.getValue("revision").jsonPrimitive.content.toLong())
        val fields = after.getValue("fields").jsonObject
        assertEquals("Maps the coast.", fields.getValue("description").jsonPrimitive.content)
        assertEquals(listOf("Ahoy."), fields.getValue("alternate_greetings").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("harbor", "maps"), after.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(after.getValue("fav").jsonPrimitive.boolean)
        val depth = fields.getValue("depth_prompt").jsonObject
        assertEquals(7, depth.getValue("depth").jsonPrimitive.int)
        assertEquals("user", depth.getValue("role").jsonPrimitive.content)
        // the prompt text of the depth prompt was not named, so it stays
        assertEquals(before.getValue("fields").jsonObject.getValue("depth_prompt").jsonObject.getValue("prompt"), depth.getValue("prompt"))
        // untouched fields are untouched
        assertEquals(before.getValue("fields").jsonObject.getValue("first_mes"), fields.getValue("first_mes"))
    }

    @Test
    fun `fields the page cannot edit survive an edit and the exported card keeps them`() = withApi { client, _ ->
        val id = client.importCard(fixture("v3-clockwork-orchard.json"), "tobias.json").getValue("id").jsonPrimitive.content
        client.putJson("/api/characters/$id", buildJsonObject { put("name", "Tobias R.") })
        val exported = client.get("/api/characters/$id/export?format=json")
        assertEquals(HttpStatusCode.OK, exported.status)
        assertTrue(exported.headers[HttpHeaders.ContentDisposition]!!.startsWith("attachment;"))
        val card = CardCodec.parse(exported.bodyAsText()).card
        assertEquals("Tobias R.", card.name)
        assertNotNull(card.data["nickname"])
        assertTrue(card.assets.isNotEmpty())
        assertNotNull(card.data["creator_notes_multilingual"])
    }

    @Test
    fun `bad patches are refused with 400 and change nothing`() = withApi { client, env ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val revision = client.detail(id).getValue("revision").jsonPrimitive.content
        val bad = listOf(
            buildJsonObject { put("extensions", buildJsonObject { }) }, // not an editable field
            buildJsonObject { put("name", 3) },
            buildJsonObject { put("name", JsonNull) },
            buildJsonObject { put("tags", 3) },
            buildJsonObject { put("alternate_greetings", "one") },
            buildJsonObject { put("fav", "yes") },
            buildJsonObject { put("depth_prompt", buildJsonObject { put("role", "robot") }) },
            buildJsonObject { put("depth_prompt", buildJsonObject { put("depth", -1) }) },
            buildJsonObject { put("depth_prompt", "deep") },
            buildJsonObject { put("expectedRevision", "x") },
        )
        for (body in bad) assertEquals(body.toString(), HttpStatusCode.BadRequest, client.putJson("/api/characters/$id", body).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/characters/$id", JsonArray(emptyList())).status)
        assertEquals(revision, client.detail(id).getValue("revision").jsonPrimitive.content)
        assertEquals(1, env.repository.count())
    }

    @Test
    fun `an edit based on an old revision is a conflict`() = withApi { client, _ ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val revision = client.detail(id).getValue("revision").jsonPrimitive.content.toLong()
        assertEquals(HttpStatusCode.OK, client.putJson("/api/characters/$id", buildJsonObject { put("creator", "me"); put("expectedRevision", revision) }).status)
        // the same base revision again: somebody else got in first
        assertEquals(HttpStatusCode.Conflict, client.putJson("/api/characters/$id", buildJsonObject { put("creator", "you"); put("expectedRevision", revision) }).status)
        assertEquals("me", client.detail(id).getValue("fields").jsonObject.getValue("creator").jsonPrimitive.content)
    }

    @Test
    fun `unknown characters are 404 on every route`() = withApi { client, _ ->
        val id = Uuid.random()
        assertEquals(HttpStatusCode.NotFound, client.get("/api/characters/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.putJson("/api/characters/$id", buildJsonObject { put("name", "x") }).status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/characters/$id").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/characters/$id/avatar").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/characters/$id/export").status)
        assertEquals(HttpStatusCode.NotFound, client.putJson("/api/characters/$id/regex-allowed", buildJsonObject { put("allowed", true) }).status)
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/characters/$id/chat", buildJsonObject { }).status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/characters/not-a-uuid").status)
    }

    // ---------------------------------------------------------------- 头像

    @Test
    fun `an avatar upload is checked by its bytes not by what the request claims`() = withApi { client, _ ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.NotFound, client.get("/api/characters/$id/avatar").status)

        val notAnImage = client.put("/api/characters/$id/avatar") { contentType(ContentType.Image.PNG); setBody("<html>".toByteArray()) }
        assertEquals(HttpStatusCode.BadRequest, notAnImage.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.put("/api/characters/$id/avatar") { setBody(ByteArray(9 * 1024 * 1024)) }.status)

        val png = PngCards.placeholder("ines", 16, 16)
        val ok = client.put("/api/characters/$id/avatar") { contentType(ContentType.Application.OctetStream); setBody(png) }
        assertEquals(ok.bodyAsText(), HttpStatusCode.OK, ok.status)
        val url = ok.obj().getValue("avatarUrl").jsonPrimitive.content
        assertEquals(png.toList(), client.get(url).bodyAsBytes().toList())
    }

    // ---------------------------------------------------------------- 导出

    @Test
    fun `export gives a png json or charx that reads back as the same card`() = withApi { client, _ ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val original = CardCodec.parse(String(fixture("v2-harbor-cartographer.json"))).card

        val png = client.get("/api/characters/$id/export?format=png").bodyAsBytes()
        assertEquals("Ines Okafor", CardCodec.parse(PngCards.read(png)).card.name)
        val json = client.get("/api/characters/$id/export?format=json")
        assertEquals("application/json", json.contentType()?.withoutParameters()?.toString())
        assertEquals("Ines Okafor", CardCodec.parse(json.bodyAsText()).card.name)
        val charx = client.get("/api/characters/$id/export?format=charx").bodyAsBytes()
        assertEquals("Ines Okafor", CardFiles.read(charx).card.name)
        // the default format is png
        assertTrue(PngCards.isPng(client.get("/api/characters/$id/export").bodyAsBytes()))
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/characters/$id/export?format=zip").status)

        // the embedded book is written back into the exported card
        val exported = CardCodec.parse(client.get("/api/characters/$id/export?format=json").bodyAsText()).card
        assertEquals(original.characterBook!!["entries"]!!.jsonArray.size, exported.characterBook!!["entries"]!!.jsonArray.size)
        // someone else's favourite mark is not exported
        assertFalse(exported.fav)
    }

    // ---------------------------------------------------------------- 角色书与卡内正则

    @Test
    fun `books can be attached, detached and the embedded one unlinked`() = withApi { client, env ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val extra = Lorebook(name = "Extra")
        env.store.update { it.copy(lorebooks = it.lorebooks + extra) }
        env.store.await { s -> s.lorebooks.any { it.id == extra.id } }
        val embeddedId = client.detail(id).getValue("embeddedBookId").jsonPrimitive.content

        val attached = client.putJson("/api/characters/$id/books", buildJsonObject {
            put("extraBookIds", buildJsonArray { add(JsonPrimitive(extra.id.toString())); add(JsonPrimitive(extra.id.toString())); add(JsonPrimitive(embeddedId)) })
        })
        assertEquals(attached.bodyAsText(), HttpStatusCode.OK, attached.status)
        val books = attached.obj().getValue("books").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("Coastline Notes", "Extra"), books) // duplicates and the embedded id are not listed twice
        assertEquals(listOf(extra.id.toString()), attached.obj().getValue("extraBookIds").jsonArray.map { it.jsonPrimitive.content })

        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/characters/$id/books", buildJsonObject {
            put("extraBookIds", buildJsonArray { add(JsonPrimitive(Uuid.random().toString())) })
        }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/characters/$id/books", buildJsonObject { put("extraBookIds", "x") }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/characters/$id/books", buildJsonObject { put("embeddedBookId", Uuid.random().toString()) }).status)

        val detached = client.putJson("/api/characters/$id/books", buildJsonObject { put("embeddedBookId", JsonNull) }).obj()
        assertEquals(JsonNull, detached.getValue("embeddedBookId"))
        assertEquals(listOf("Extra"), detached.getValue("books").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
        // the book itself is still there
        assertTrue(env.store.settingsFlow.value.lorebooks.any { it.id.toString() == embeddedId })
    }

    @Test
    fun `card scripts run only after the user allows them, and deleting the character drops the permission`() = withApi { client, env ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val key = "character:$id"

        val allowed = client.putJson("/api/characters/$id/regex-allowed", buildJsonObject { put("allowed", true) })
        assertEquals(allowed.bodyAsText(), HttpStatusCode.OK, allowed.status)
        assertTrue(allowed.obj().getValue("regexAllowed").jsonPrimitive.boolean)
        env.store.await { key in it.tavern.regexAllowed }
        assertTrue(client.detail(id).getValue("regexAllowed").jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/characters/$id/regex-allowed", buildJsonObject { put("allowed", "yes") }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/characters/$id/regex-allowed", buildJsonObject { }).status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/characters/$id").status)
        env.store.await { key !in it.tavern.regexAllowed }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/characters/$id").status)
    }

    @Test
    fun `deleting can take the embedded book along`() = withApi { client, env ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        val bookId = client.detail(id).getValue("embeddedBookId").jsonPrimitive.content
        env.store.await { s -> s.lorebooks.any { it.id.toString() == bookId } }
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/characters/$id?deleteBook=true").status)
        env.store.await { s -> s.lorebooks.none { it.id.toString() == bookId } }
        assertEquals(0, env.repository.count())
    }

    @Test
    fun `starting a chat needs the chat service`() = withApi { client, _ ->
        val id = client.importCard(fixture("v2-harbor-cartographer.json"), "ines.json").getValue("id").jsonPrimitive.content
        assertEquals(HttpStatusCode.NotFound, client.postJson("/api/characters/$id/chat", buildJsonObject { }).status)
    }

    // ---------------------------------------------------------------- 用户设定

    @Test
    fun `the persona is saved on the latest settings and checked`() = withApi { client, env ->
        val response = client.putJson("/api/tavern/persona", buildJsonObject { put("nickname", "  Sam  "); put("description", "A quiet courier.") })
        assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
        assertEquals("Sam", response.obj().getValue("nickname").jsonPrimitive.content)
        val settings = env.store.await { it.tavern.personaDescription == "A quiet courier." }
        assertEquals("Sam", settings.displaySetting.userNickname)

        // naming one field leaves the other alone
        client.putJson("/api/tavern/persona", buildJsonObject { put("description", "Louder now.") })
        val later = env.store.await { it.tavern.personaDescription == "Louder now." }
        assertEquals("Sam", later.displaySetting.userNickname)

        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/tavern/persona", buildJsonObject { put("title", "x") }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/tavern/persona", buildJsonObject { put("nickname", 4) }).status)
        assertEquals(HttpStatusCode.BadRequest, client.putJson("/api/tavern/persona", buildJsonObject { put("nickname", "x".repeat(101)) }).status)
    }

    @Test
    fun `switching the character of a conversation is a metadata change that needs a fresh snapshot`() {
        val conversation = me.rerere.rikkihub.data.model.Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        assertTrue(conversation.sameMetadataAs(conversation.copy()))
        assertFalse(conversation.sameMetadataAs(conversation.copy(characterId = Uuid.random())))
    }

    @Test
    fun `the editable fields helper reads a card the way the page edits it`() {
        val card = CharacterCard.blank("Zed")
        val fields = card.editableFields()
        assertEquals("Zed", fields.getValue("name").jsonPrimitive.content)
        assertEquals("", fields.getValue("description").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), fields.getValue("alternate_greetings"))
        assertEquals(4, fields.getValue("depth_prompt").jsonObject.getValue("depth").jsonPrimitive.int)
        assertEquals(listOf("png", "jpg", "webp", "gif", null), listOf(
            imageExtension(PngCards.placeholder("x", 4, 4)),
            imageExtension(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())),
            imageExtension("RIFF0000WEBPVP8 ".toByteArray()),
            imageExtension("GIF89a....".toByteArray()),
            imageExtension("hello".toByteArray()),
        ))
    }
}
