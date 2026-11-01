package me.rerere.rikkihub.web.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.rikkihub.data.files.SkillFrontmatterParser
import me.rerere.rikkihub.data.files.SkillManager
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.installWebApiStatusPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillRoutesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun md(name: String, body: String = "body") = "---\nname: $name\ndescription: does $name\n---\n$body"

    private fun manager(): SkillManager {
        val root = tmp.newFolder()
        val builtinRoot = root.resolve("builtin_skills").also { it.mkdir() }
        builtinRoot.resolve("shipped-dir").also {
            it.mkdir(); it.resolve("SKILL.md").writeText(md("shipped"))
        }
        return SkillManager(root, { builtinRoot }) {}
    }

    private fun withApi(
        manager: SkillManager = manager(),
        assistants: List<Assistant> = emptyList(),
        block: suspend (HttpClient, SkillManager) -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            routing { route("/api") { skillRoutes(manager) { assistants } } }
        }
        block(client, manager)
    }

    private suspend fun HttpResponse.json(): JsonObject = JsonInstant.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpClient.putJson(url: String, body: JsonObject) =
        put(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
    private suspend fun HttpClient.postJson(url: String, body: JsonObject) =
        post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }
    private suspend fun HttpClient.upload(url: String, name: String, bytes: ByteArray) =
        submitFormWithBinaryData(url, formData {
            append("file", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$name\"") })
        })

    @Test
    fun `list marks builtin, override and how many assistants use each skill`() {
        val manager = manager()
        manager.saveSkill("mine", md("mine"))
        manager.saveSkill("shipped", md("shipped", "user copy"))
        withApi(manager, listOf(Assistant(enabledSkills = setOf("mine")), Assistant(enabledSkills = setOf("mine", "shipped")))) { client, _ ->
            val list = JsonInstant.parseToJsonElement(client.get("/api/skills").bodyAsText()).jsonArray.map { it.jsonObject }
            val byName = list.associateBy { it["name"]!!.jsonPrimitive.content }
            assertEquals(setOf("mine", "shipped"), byName.keys)
            assertEquals("2", byName.getValue("mine")["enabledByAssistants"]!!.jsonPrimitive.content)
            assertEquals("false", byName.getValue("shipped")["builtin"]!!.jsonPrimitive.content)
            assertEquals("true", byName.getValue("shipped")["overridesBuiltin"]!!.jsonPrimitive.content)
            assertEquals("false", byName.getValue("mine")["overridesBuiltin"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `create writes a parseable template and refuses duplicates or bad names`() = withApi { client, manager ->
        val created = client.postJson("/api/skills", buildJsonObject { put("name", "new: skill"); put("description", "Quote \"me\"") })
        assertEquals(HttpStatusCode.Created, created.status)
        val frontmatter = SkillFrontmatterParser.parse(manager.readSkillContent("new: skill")!!)
        assertEquals("new: skill", frontmatter["name"])
        assertEquals("Quote \"me\"", frontmatter["description"])

        val duplicate = client.postJson("/api/skills", buildJsonObject { put("name", "shipped"); put("description", "x") })
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("SKILL_EXISTS", duplicate.json()["errorCode"]!!.jsonPrimitive.content)
        listOf("a/b", ".hidden", "").forEach { name ->
            val bad = client.postJson("/api/skills", buildJsonObject { put("name", name); put("description", "x") })
            assertEquals(name, HttpStatusCode.BadRequest, bad.status)
        }
    }

    @Test
    fun `edits use revisions, keep the name and stay inside the skill`() {
        val manager = manager()
        manager.saveSkill("mine", md("mine"))
        withApi(manager) { client, _ ->
            val read = client.get("/api/skills/mine/files/content?path=SKILL.md").json()
            val revision = read["revision"]!!.jsonPrimitive.content

            val ok = client.putJson("/api/skills/mine/files/content?path=SKILL.md",
                buildJsonObject { put("content", md("mine", "v2")); put("expectedRevision", revision) })
            assertEquals(HttpStatusCode.OK, ok.status)
            assertTrue(manager.readSkillContent("mine")!!.endsWith("v2"))

            val stale = client.putJson("/api/skills/mine/files/content?path=SKILL.md",
                buildJsonObject { put("content", md("mine", "v3")); put("expectedRevision", revision) })
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("FILE_REVISION_MISMATCH", stale.json()["errorCode"]!!.jsonPrimitive.content)

            val renamed = client.putJson("/api/skills/mine/files/content?path=SKILL.md", buildJsonObject { put("content", md("other")) })
            assertEquals("SKILL_NAME_CHANGED", renamed.json()["errorCode"]!!.jsonPrimitive.content)

            val created = client.putJson("/api/skills/mine/files/content?path=lib/helper.py",
                buildJsonObject { put("content", "print(1)"); put("overwrite", false) })
            assertEquals(HttpStatusCode.OK, created.status)
            val again = client.putJson("/api/skills/mine/files/content?path=lib/helper.py",
                buildJsonObject { put("content", "x"); put("overwrite", false) })
            assertEquals(HttpStatusCode.Conflict, again.status)

            listOf("../escape.txt", "lib/../../x", "/abs").forEach { path ->
                val escape = client.putJson("/api/skills/mine/files/content?path=$path", buildJsonObject { put("content", "x") })
                assertEquals(path, HttpStatusCode.BadRequest, escape.status)
            }

            val files = client.get("/api/skills/mine/files").json()["files"]!!.jsonArray.map { it.jsonObject["path"]!!.jsonPrimitive.content }
            assertEquals(listOf("SKILL.md", "lib/helper.py"), files.sorted())
            assertEquals(HttpStatusCode.BadRequest, client.delete("/api/skills/mine/files?path=SKILL.md").status)
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/skills/mine/files?path=lib/helper.py").status)
        }
    }

    @Test
    fun `builtins are read-only until copied, and deleting the copy reveals the builtin`() = withApi { client, manager ->
        val write = client.putJson("/api/skills/shipped/files/content?path=SKILL.md", buildJsonObject { put("content", md("shipped", "x")) })
        assertEquals(HttpStatusCode.Forbidden, write.status)
        assertEquals("true", client.get("/api/skills/shipped/files").json()["readOnly"]!!.jsonPrimitive.content)

        assertEquals(HttpStatusCode.Created, client.post("/api/skills/shipped/override").status)
        assertFalse(manager.findSkill("shipped")!!.isBuiltin)
        assertEquals("false", client.get("/api/skills/shipped/files").json()["readOnly"]!!.jsonPrimitive.content)

        val deleted = client.delete("/api/skills/shipped").json()
        assertEquals("true", deleted["revealedBuiltin"]!!.jsonPrimitive.content)
        assertTrue(manager.findSkill("shipped")!!.isBuiltin)
    }

    @Test
    fun `imports ask before replacing and reject oversized archives`() {
        val manager = manager()
        manager.saveSkill("mine", md("mine", "v1"))
        withApi(manager) { client, _ ->
            val conflict = client.upload("/api/skills/import", "SKILL.md", md("mine", "v2").toByteArray())
            assertEquals(HttpStatusCode.Conflict, conflict.status)
            assertEquals(listOf("mine"), conflict.json()["names"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(manager.readSkillContent("mine")!!.endsWith("v1"))

            val replaced = client.upload("/api/skills/import?overwrite=true", "SKILL.md", md("mine", "v2").toByteArray())
            assertEquals(HttpStatusCode.Created, replaced.status)
            assertTrue(manager.readSkillContent("mine")!!.endsWith("v2"))

            val bomb = ByteArrayOutputStream().also { out ->
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("big/SKILL.md")); zip.write(md("big").toByteArray()); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("big/zeros")); zip.write(ByteArray(17 * 1024 * 1024)); zip.closeEntry()
                }
            }.toByteArray()
            val tooLarge = client.upload("/api/skills/import", "big.zip", bomb)
            assertEquals(HttpStatusCode.PayloadTooLarge, tooLarge.status)
            assertNull(manager.findSkill("big"))
        }
    }

    @Test
    fun `binary files are listed and downloadable but not opened as text`() {
        val manager = manager()
        manager.saveSkillFileBytesAtomically("mine", mapOf("SKILL.md" to md("mine").toByteArray(), "icon.bin" to byteArrayOf(0, 1, 2)))
        withApi(manager) { client, _ ->
            val file = client.get("/api/skills/mine/files").json()["files"]!!.jsonArray.map { it.jsonObject }
                .single { it["path"]!!.jsonPrimitive.content == "icon.bin" }
            assertEquals("false", file["text"]!!.jsonPrimitive.content)
            assertEquals(HttpStatusCode.UnsupportedMediaType, client.get("/api/skills/mine/files/content?path=icon.bin").status)
            val download = client.get("/api/skills/mine/files/download?path=icon.bin")
            assertEquals(ContentType.Application.OctetStream, download.contentType()?.withoutParameters())
            assertEquals("nosniff", download.headers["X-Content-Type-Options"])
        }
    }

    @Test
    fun `assistant enabledSkills follow field presence`() {
        assertNull(parseEnabledSkillsField(buildJsonObject { put("name", "x") }))
        assertEquals(emptySet<String>(), parseEnabledSkillsField(buildJsonObject { putJsonArray("enabledSkills") {} }))
        assertEquals(setOf("a", "b"), parseEnabledSkillsField(buildJsonObject {
            putJsonArray("enabledSkills") { add(kotlinx.serialization.json.JsonPrimitive("a")); add(kotlinx.serialization.json.JsonPrimitive("b")) }
        }))
        listOf(
            buildJsonObject { put("enabledSkills", "a") },
            buildJsonObject { putJsonArray("enabledSkills") { add(kotlinx.serialization.json.JsonPrimitive(1)) } },
            buildJsonObject { putJsonArray("enabledSkills") { add(kotlinx.serialization.json.JsonPrimitive(" ")) } },
        ).forEach { body -> assertThrows(BadRequestException::class.java) { parseEnabledSkillsField(body) } }

        val existing = Assistant(enabledSkills = setOf("keep"))
        assertEquals(setOf("keep"), mergeAssistant(existing, existing).enabledSkills)
        assertEquals(emptySet<String>(), mergeAssistant(existing, existing, enabledSkills = emptySet()).enabledSkills)
    }

    @Test
    fun `picker update replaces only the target assistant's skills`() {
        val target = Assistant(name = "t", systemPrompt = "keep", enabledSkills = setOf("old"))
        val other = Assistant(name = "o", enabledSkills = setOf("old"))
        val updated = withEnabledSkills(listOf(target, other), target.id, setOf("new"))!!
        assertEquals(setOf("new"), updated[0].enabledSkills)
        assertEquals("keep", updated[0].systemPrompt)
        assertEquals(other, updated[1])
        assertNull(withEnabledSkills(listOf(other), target.id, setOf("new")))
    }

    @Test
    fun `unrecognized folders are listed and can be deleted one by one`() {
        val manager = manager()
        manager.saveSkill("mine", md("mine"))
        val skills = manager.getSkillsDir()
        skills.resolve("empty").mkdirs()
        skills.resolve("no-desc").also { it.mkdirs(); it.resolve("SKILL.md").writeText("---\nname: n\n---\n") }
        withApi(manager) { client, _ ->
            val list = JsonInstant.parseToJsonElement(client.get("/api/skill-invalid-dirs").bodyAsText()).jsonArray
                .associate { it.jsonObject["dirName"]!!.jsonPrimitive.content to it.jsonObject["reason"]!!.jsonPrimitive.content }
            assertEquals(mapOf("empty" to "missing_skill_md", "no-desc" to "missing_description"), list)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/skill-invalid-dirs/empty").status)
            assertFalse(skills.resolve("empty").exists())
            assertTrue(skills.resolve("no-desc").isDirectory)

            // A valid skill is never removed through this route, and a folder that is gone is a conflict.
            val valid = client.delete("/api/skill-invalid-dirs/mine")
            assertEquals(HttpStatusCode.Conflict, valid.status)
            assertEquals("SKILL_DIR_CHANGED", valid.json()["errorCode"]!!.jsonPrimitive.content)
            assertTrue(manager.findSkill("mine") != null)
            assertEquals(HttpStatusCode.Conflict, client.delete("/api/skill-invalid-dirs/empty").status)
        }
    }

    @Test
    fun `a skill called like the invalid-folder route still works`() {
        val manager = manager()
        manager.saveSkill("skill-invalid-dirs", md("skill-invalid-dirs"))
        withApi(manager) { client, _ ->
            assertEquals(HttpStatusCode.OK, client.get("/api/skills/skill-invalid-dirs/files").status)
            assertEquals(HttpStatusCode.OK, client.delete("/api/skills/skill-invalid-dirs").status)
        }
    }
}
