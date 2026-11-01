package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkihub.data.ai.tools.*
import me.rerere.rikkihub.web.ApiException
import me.rerere.rikkihub.web.BadRequestException
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSubagentPresetRoutesTest {
    private val role = WorkspaceSubagentPreset(
        id = "00000000-0000-4000-8000-000000000001", key = "reviewer", name = "Reviewer",
        description = "Review a delegated change", systemPrompt = "Review only the delegated scope.",
        generationConfig = SubagentPresetGenerationConfig(0.7f, 0.9f, 1000, ReasoningLevel.HIGH),
        version = 3, executionRevision = 2,
    )
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun rejected(body: String, existing: WorkspaceSubagentPreset? = role) {
        assertThrows(IllegalArgumentException::class.java) { mergeWebSubagentPreset(json(body), existing) }
    }

    @Test fun patchKeepsPrivateAndOmittedFields() {
        val next = mergeWebSubagentPreset(json("""{"name":"New name","generationConfig":{"temperature":null}}"""), role)
        assertEquals(role.id, next.id)
        assertEquals(role.key, next.key)
        assertEquals(role.version, next.version)
        assertEquals(role.executionRevision, next.executionRevision)
        assertEquals(role.systemPrompt, next.systemPrompt)
        assertNull(next.generationConfig.temperature)
        assertEquals(role.generationConfig.topP, next.generationConfig.topP)
        assertEquals(role.generationConfig.maxTokens, next.generationConfig.maxTokens)
        assertEquals(ReasoningLevel.HIGH, next.generationConfig.reasoningLevel)
    }

    @Test fun generationNullClearsOnlyExplicitNullableFields() {
        val next = mergeWebSubagentPreset(json("""{"generationConfig":{"topP":null,"maxTokens":null}}"""), role)
        assertEquals(0.7f, next.generationConfig.temperature)
        assertNull(next.generationConfig.topP)
        assertNull(next.generationConfig.maxTokens)
        assertEquals(ReasoningLevel.HIGH, next.generationConfig.reasoningLevel)
        val reset = mergeWebSubagentPreset(json("""{"generationConfig":null}"""), role)
        assertEquals(SubagentPresetGenerationConfig(), reset.generationConfig)
    }

    @Test fun immutableAndServerOwnedFieldsAreRejected() {
        listOf("id", "version", "executionRevision", "sortOrder", "libraryPresetId", "workspaceIds", "apiKey", "unknown").forEach {
            rejected("""{"name":"x","$it":1}""")
        }
        rejected("""{"key":"changed"}""")
        rejected("""{"key":"default","name":"x","description":"x","systemPrompt":"x"}""", null)
        rejected("""{"expectedVersion":3}""")
        assertEquals(role.key, mergeWebSubagentPreset(json("""{"key":"reviewer"}"""), role).key)
        assertThrows(BadRequestException::class.java) { parseWorkspaceUpdate(json("""{"name":"x","subagentPresets":[]}""")) }
        assertThrows(BadRequestException::class.java) { parseWorkspaceUpdate(json("""{"subagent_presets":[]}""")) }
    }

    @Test fun nonLiteralAndInvalidNestedValuesAreRejected() {
        listOf(
            """{"enabled":"true"}""", """{"acceptContext":null}""", """{"name":123}""",
            """{"maxSteps":"2"}""", """{"maxSteps":1.5}""", """{"maxSteps":121}""",
            """{"generationConfig":{"temperature":"1"}}""", """{"generationConfig":{"temperature":2.1}}""",
            """{"generationConfig":{"temperature":1e100}}""", """{"generationConfig":{"topP":0}}""",
            """{"generationConfig":{"maxTokens":0}}""", """{"generationConfig":{"maxTokens":1.5}}""",
            """{"generationConfig":{"maxTokens":2147483648}}""", """{"generationConfig":{"reasoningLevel":"HIGH"}}""",
            """{"generationConfig":{"reasoningLevel":null}}""",
            """{"generationConfig":{"apiKey":"secret"}}""", """{"modelSelection":"FIXED"}""",
            """{"modelId":12}""",
        ).forEach { rejected(it) }
        listOf("null", "\"3\"", "3.0", "0", "-1", "9223372036854775808").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { presetExpectedVersion(Json.parseToJsonElement(value)) }
        }
        assertEquals(3L, presetExpectedVersion(JsonPrimitive(3)))
    }

    @Test fun boundedStrictObjectBody() {
        assertEquals("x", parseWebPresetBody("""{"name":"x"}""".encodeToByteArray())["name"]?.jsonPrimitive?.content)
        listOf("[]", "null", "broken", "{name:'x'}").forEach {
            assertThrows(IllegalArgumentException::class.java) { parseWebPresetBody(it.encodeToByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { parseWebPresetBody(ByteArray(MAX_PRESET_BODY_BYTES.toInt() + 1)) }
        assertThrows(IllegalArgumentException::class.java) { parseWebPresetBody(byteArrayOf(0xff.toByte())) }
    }

    private class Store(var raw: String?) : WebSubagentPresetStore {
        var writes = 0
        var expected: Long? = null
        var saved: WorkspaceSubagentPreset? = null
        var order: List<String>? = null
        var versions: Map<String, Long>? = null
        var failure: RuntimeException? = null
        override suspend fun read(workspaceId: String) = raw
        private fun writing() { failure?.let { throw it }; writes++ }
        override suspend fun create(workspaceId: String, preset: WorkspaceSubagentPreset): WorkspaceSubagentPreset {
            writing(); saved = preset; return preset
        }
        override suspend fun update(workspaceId: String, preset: WorkspaceSubagentPreset, expectedVersion: Long): WorkspaceSubagentPreset {
            writing(); saved = preset; expected = expectedVersion; return preset
        }
        override suspend fun delete(workspaceId: String, presetId: String, expectedVersion: Long) { writing(); expected = expectedVersion }
        override suspend fun reorder(workspaceId: String, ids: List<String>, expectedVersions: Map<String, Long>): List<WorkspaceSubagentPreset> {
            writing(); order = ids; versions = expectedVersions; return emptyList()
        }
        override suspend fun reset(workspaceId: String) { writing(); raw = "[]" }
    }

    @Test fun fixedModelCheckedOnCreateOrChangeButStaleReferenceSurvivesUnrelatedEdits() = runBlocking {
        val fixed = role.copy(modelSelection = SubagentPresetModelSelection.FIXED, modelId = "00000000-0000-4000-8000-000000000002")
        val store = Store(WorkspaceSubagentPresets.encode(listOf(fixed)))
        val checked = mutableListOf<String>()
        val service = WebSubagentPresetService(store) { checked += it; throw IllegalArgumentException("missing model") }
        service.update("ws", role.id, json("""{"name":"New","expectedVersion":3}"""))
        assertTrue(checked.isEmpty())
        assertEquals(fixed.modelId, store.saved?.modelId)
        assertEquals(3L, store.expected)
        try {
            service.update("ws", role.id, json("""{"modelId":"00000000-0000-4000-8000-000000000003","expectedVersion":3}"""))
            fail("Invalid newly selected model accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, checked.size)
        assertEquals(1, store.writes)
        service.update("ws", role.id, json("""{"modelSelection":"parent","expectedVersion":3}"""))
        assertNull(store.saved?.modelId)
    }

    @Test fun conflictAndCorruptionNeverWriteOrAutoReset() = runBlocking {
        val store = Store(WorkspaceSubagentPresets.encode(listOf(role)))
        val service = WebSubagentPresetService(store) { }
        try { service.update("ws", role.id, json("""{"name":"New","expectedVersion":2}""")); fail("Stale edit accepted") }
        catch (_: SubagentPresetConflictException) { }
        assertEquals(0, store.writes)
        store.raw = "corrupt"
        assertTrue(service.list("ws").corrupt)
        assertNull(service.list("ws").presets)
        try { service.create("ws", json("""{"key":"new","name":"x","description":"x","systemPrompt":"x"}""")); fail("Corrupt collection replaced") }
        catch (_: SubagentPresetConflictException) { }
        assertEquals(0, store.writes)
        try { service.reset("ws", json("""{"confirm":"true"}""")); fail("Nonliteral confirmation accepted") }
        catch (_: IllegalArgumentException) { }
        service.reset("ws", json("""{"confirm":true}"""))
        assertEquals(1, store.writes)
        assertEquals(emptyList<WorkspaceSubagentPreset>(), service.list("ws").presets)
        try { service.reset("ws", json("""{"confirm":true}""")); fail("Valid catalogue reset") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun emptyReadDoesNotCreateRolesAndReorderForwardsExactCasMap() = runBlocking {
        val store = Store("[]")
        val service = WebSubagentPresetService(store) { }
        assertFalse(service.list("ws").corrupt)
        assertTrue(service.list("ws").presets!!.isEmpty())
        assertEquals(0, store.writes)
        store.raw = WorkspaceSubagentPresets.encode(listOf(role))
        service.reorder("ws", json("""{"presetIds":["${role.id}"],"expectedVersions":{"${role.id}":3}}"""))
        assertEquals(listOf(role.id), store.order)
        assertEquals(mapOf(role.id to 3L), store.versions)
        service.delete("ws", role.id, 3)
        assertEquals(3L, store.expected)
        val before = store.writes
        try { service.reorder("ws", json("""{"presetIds":[],"expectedVersions":{}}""")); fail("Incomplete order accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, store.writes)
    }

    @Test fun linkedWorkspaceCopiesCannotUseLegacyEditOrDelete() = runBlocking {
        val linked = role.copy(libraryPresetId = "00000000-0000-4000-8000-000000000002")
        val store = Store(WorkspaceSubagentPresets.encode(listOf(linked)))
        val service = WebSubagentPresetService(store) { }
        try { presetApiOperation { service.update("ws", role.id, json("""{"name":"Local overwrite","expectedVersion":3}""")) }; fail("Shared edit accepted") }
        catch (api: ApiException) { assertEquals(HttpStatusCode.Conflict, api.status) }
        try { presetApiOperation { service.delete("ws", role.id, 3) }; fail("Shared delete accepted") }
        catch (api: ApiException) { assertEquals(HttpStatusCode.Conflict, api.status) }
        assertEquals(0, store.writes)
    }

    @Test fun operationErrorsMapToProtocolStatus() = runBlocking {
        suspend fun status(cause: RuntimeException): HttpStatusCode = try {
            presetApiOperation<Unit> { throw cause }; error("Expected exception")
        } catch (api: ApiException) { api.status }
        assertEquals(HttpStatusCode.Conflict, status(SubagentPresetConflictException()))
        assertEquals(HttpStatusCode.BadRequest, status(IllegalArgumentException("invalid")))
        assertEquals(HttpStatusCode.BadRequest, try {
            presetApiOperation { parseWebPresetBody(byteArrayOf(0xff.toByte())) }; error("Expected invalid UTF-8")
        } catch (api: ApiException) { api.status })
        assertEquals(HttpStatusCode.NotFound, status(NoSuchElementException("missing")))
        val store = Store(null)
        assertEquals(HttpStatusCode.NotFound, try {
            presetApiOperation { WebSubagentPresetService(store) { }.list("missing") }; error("Expected missing workspace")
        } catch (api: ApiException) { api.status })
    }
}
