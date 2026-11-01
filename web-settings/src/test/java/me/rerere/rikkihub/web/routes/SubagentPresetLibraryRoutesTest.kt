package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkihub.data.ai.tools.*
import me.rerere.rikkihub.data.repository.SubagentPresetLibraryState
import me.rerere.rikkihub.web.ApiException
import org.junit.Assert.*
import org.junit.Test

class SubagentPresetLibraryRoutesTest {
    private val role = WorkspaceSubagentPreset(
        id = "00000000-0000-4000-8000-000000000001", key = "reviewer", name = "Reviewer",
        description = "Review code", systemPrompt = "Review only the delegated change.", version = 3,
    )
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private class Store(initial: WorkspaceSubagentPreset) : WebSubagentPresetLibraryStore {
        var state = SubagentPresetLibraryState(presets = listOf(initial), corrupt = false)
        var workspaces = emptyMap<String, String>()
        var writes = 0
        var targets: Set<String>? = null
        var expected: Long? = null
        var enabled: Boolean? = null
        var workspaceId: String? = null
        var saved: WorkspaceSubagentPreset? = null
        var failure: RuntimeException? = null
        private fun write() { failure?.let { throw it }; writes++ }
        override suspend fun list() = state
        override suspend fun workspacePresets() = workspaces
        override suspend fun create(preset: WorkspaceSubagentPreset, workspaceIds: Set<String>): WorkspaceSubagentPreset {
            write(); saved = preset; targets = workspaceIds; return preset
        }
        override suspend fun update(preset: WorkspaceSubagentPreset, expectedVersion: Long, workspaceIds: Set<String>?): WorkspaceSubagentPreset {
            write(); saved = preset; targets = workspaceIds; expected = expectedVersion; return preset
        }
        override suspend fun delete(presetId: String, expectedVersion: Long) { write(); expected = expectedVersion }
        override suspend fun setWorkspaceEnabled(workspaceId: String, presetId: String, enabled: Boolean, expectedVersion: Long): WorkspaceSubagentPreset {
            write(); this.workspaceId = workspaceId; this.enabled = enabled; expected = expectedVersion
            return state.presets!!.first { it.id == presetId }
        }
        override suspend fun reset() { write(); state = SubagentPresetLibraryState(presets = emptyList(), corrupt = false) }
    }

    @Test fun selectorsCountLinkedDisabledCopiesButNotSameKeyLocals() = runBlocking {
        val disabled = role.copy(enabled = false)
        val linked = disabled.copy(id = "00000000-0000-4000-8000-000000000002", libraryPresetId = role.id)
        val store = Store(disabled)
        store.workspaces = mapOf(
            "z" to WorkspaceSubagentPresets.encode(listOf(linked)),
            "a" to WorkspaceSubagentPresets.encode(listOf(linked)),
            "local" to WorkspaceSubagentPresets.encode(listOf(role)), "corrupt" to "broken",
        )
        val result = WebSubagentPresetLibraryService(store) { }.list()
        assertEquals(listOf("a", "z"), result.presets!!.single()["workspaceIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(result.presets!!.single()["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(0, store.writes)
    }

    @Test fun targetAbsencePreservesEmptyClearsAndScopeOnlyUpdatesWork() = runBlocking {
        val store = Store(role)
        val service = WebSubagentPresetLibraryService(store) { }
        service.update(role.id, json("""{"name":"Updated","expectedVersion":3}"""))
        assertNull(store.targets)
        assertEquals(role.systemPrompt, store.saved!!.systemPrompt)
        service.update(role.id, json("""{"expectedVersion":3,"workspaceIds":[]}"""))
        assertEquals(emptySet<String>(), store.targets)
        service.update(role.id, json("""{"expectedVersion":3,"workspaceIds":["w1","w2"]}"""))
        assertEquals(setOf("w1", "w2"), store.targets)
        assertEquals(3L, store.expected)
        service.enabled("w1", role.id, json("""{"enabled":false,"expectedVersion":3}"""))
        assertEquals(false, store.enabled)
        assertEquals("w1", store.workspaceId)
        service.enabled("w2", role.id, json("""{"enabled":true,"expectedVersion":3}"""))
        assertEquals(true, store.enabled)
        service.delete(role.id, json("""{"expectedVersion":3}"""))
        assertEquals(3L, store.expected)
    }

    @Test fun strictScopeAndServerMetadataAreNeverWritable() = runBlocking {
        val store = Store(role)
        val service = WebSubagentPresetLibraryService(store) { }
        for (body in listOf(
            """{"expectedVersion":3,"workspaceIds":null}""", """{"expectedVersion":3,"workspaceIds":"w"}""",
            """{"expectedVersion":3,"workspaceIds":[1]}""", """{"expectedVersion":3,"workspaceIds":[""]}""",
            """{"expectedVersion":3,"workspaceIds":["w","w"]}""", """{"expectedVersion":"3","workspaceIds":[]}""",
            """{"expectedVersion":3,"name":"x","libraryPresetId":null}""",
            """{"expectedVersion":3,"name":"x","version":4}""", """{"expectedVersion":3}""",
        )) {
            try { service.update(role.id, json(body)); fail("Accepted $body") } catch (_: IllegalArgumentException) { }
        }
        for (body in listOf("""{"enabled":"true","expectedVersion":3}""", """{"enabled":true,"expectedVersion":3,"workspaceIds":[]}""", """{"enabled":true,"expectedVersion":null}""")) {
            try { service.enabled("w", role.id, json(body)); fail("Accepted $body") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, store.writes)
    }

    @Test fun max120Accepted121RejectedAndCreateUsesExplicitTargets() = runBlocking {
        assertEquals(120, mergeWebSubagentPreset(json("""{"maxSteps":120}"""), role).maxSteps)
        assertThrows(IllegalArgumentException::class.java) { mergeWebSubagentPreset(json("""{"maxSteps":121}"""), role) }
        val store = Store(role)
        val service = WebSubagentPresetLibraryService(store) { }
        service.create(json("""{"key":"new","name":"New","description":"Work","systemPrompt":"Do work","maxSteps":120,"workspaceIds":["w"]}"""))
        assertEquals(setOf("w"), store.targets)
        assertEquals(120, store.saved!!.maxSteps)
        assertNull(store.saved!!.libraryPresetId)
    }

    @Test fun bindingChangesInvalidateOldScopeCasAndRepositoryConflictsMap409() = runBlocking {
        val store = Store(role.copy(version = 4))
        val service = WebSubagentPresetLibraryService(store) { }
        for (operation in listOf<suspend () -> Unit>(
            { service.update(role.id, json("""{"name":"Stale","expectedVersion":3,"workspaceIds":[]}""")); Unit },
            { service.enabled("w", role.id, json("""{"enabled":false,"expectedVersion":3}""")); Unit },
        )) {
            try { presetApiOperation { operation() }; fail("Stale scope edit accepted") }
            catch (error: ApiException) { assertEquals(HttpStatusCode.Conflict, error.status) }
        }
        assertEquals(0, store.writes)
        store.failure = SubagentPresetConflictException("workspace local key conflict")
        try { presetApiOperation { service.update(role.id, json("""{"expectedVersion":4,"workspaceIds":["w"]}""")) }; fail("Conflict ignored") }
        catch (error: ApiException) { assertEquals(HttpStatusCode.Conflict, error.status) }
        assertEquals(0, store.writes)
    }

    @Test fun modelSelectorsValidateNewFixedReferencesWithoutFallback() = runBlocking {
        val fixed = role.copy(modelSelection = SubagentPresetModelSelection.FIXED, modelId = "00000000-0000-4000-8000-000000000003")
        val store = Store(fixed)
        val checked = mutableListOf<String>()
        val service = WebSubagentPresetLibraryService(store) { checked += it; throw IllegalArgumentException("Not a CHAT + TOOL model") }
        service.update(role.id, json("""{"name":"Repair later","expectedVersion":3}"""))
        assertEquals(fixed.modelId, store.saved!!.modelId)
        assertTrue(checked.isEmpty())
        try { service.update(role.id, json("""{"modelId":"00000000-0000-4000-8000-000000000004","expectedVersion":3}""")); fail("Invalid model accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, checked.size)
        service.update(role.id, json("""{"modelSelection":"parent","expectedVersion":3}"""))
        assertNull(store.saved!!.modelId)
        service.update(role.id, json("""{"modelSelection":"workspace_default","expectedVersion":3}"""))
        assertNull(store.saved!!.modelId)
    }

    @Test fun libraryLimitAndDeleteResetAllowlistsRejectBeforeWriting() = runBlocking {
        val store = Store(role)
        val service = WebSubagentPresetLibraryService(store) { }
        store.state = SubagentPresetLibraryState(presets = List(64) { role.copy(key = "role_$it") }, corrupt = false)
        try { service.create(json("""{"key":"new","name":"New","description":"Work","systemPrompt":"Work"}""")); fail("Library limit bypassed") }
        catch (_: IllegalArgumentException) { }
        for (body in listOf("""{"expectedVersion":"3"}""", """{"expectedVersion":3,"workspaceIds":[]}""", """{"expectedVersion":3,"libraryPresetId":null}""")) {
            try { service.delete(role.id, json(body)); fail("Accepted $body") } catch (_: IllegalArgumentException) { }
        }
        for (body in listOf("""{"confirm":false}""", """{"confirm":true,"presets":[]}""")) {
            try { service.reset(json(body)); fail("Accepted $body") } catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, store.writes)
        store.state = SubagentPresetLibraryState(presets = listOf(role), corrupt = false)
        store.failure = NoSuchElementException("Unknown workspace target")
        try { presetApiOperation { service.update(role.id, json("""{"expectedVersion":3,"workspaceIds":["missing"]}""")) }; fail("Unknown target accepted") }
        catch (error: ApiException) { assertEquals(HttpStatusCode.NotFound, error.status) }
        assertEquals(0, store.writes)
    }

    @Test fun corruptResetIsBoundedLiteralExplicitAndCorruptOnly(): Unit = runBlocking {
        val store = Store(role)
        val service = WebSubagentPresetLibraryService(store) { }
        try { service.reset(json("""{"confirm":true}""")); fail("Healthy library reset") } catch (_: IllegalArgumentException) { }
        store.state = SubagentPresetLibraryState(presets = null, corrupt = true)
        assertNull(service.list().presets)
        try { service.reset(json("""{"confirm":"true"}""")); fail("String confirmation accepted") } catch (_: IllegalArgumentException) { }
        try { service.update(role.id, json("""{"name":"x","expectedVersion":3}""")); fail("Corrupt library overwritten") } catch (_: SubagentPresetConflictException) { }
        assertEquals(0, store.writes)
        service.reset(json("""{"confirm":true}"""))
        assertEquals(1, store.writes)
        assertFalse(service.list().corrupt)
        assertTrue(service.list().presets!!.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { parseWebPresetBody(ByteArray(MAX_PRESET_BODY_BYTES.toInt() + 1)) }
    }
}
