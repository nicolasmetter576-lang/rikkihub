package me.rerere.rikkihub.data.export

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.InjectionPosition
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.worldinfo.WorldInfoBook
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSerializerTest {
    // ---------------------------------------------------------------- 模式注入（提示词库条目）

    @Test fun modeInjectionRoundTripsInTheNewFormat() {
        val entry = PromptEntry(
            identifier = "11111111-1111-1111-1111-111111111111", name = "Brief", role = PromptRole.ASSISTANT,
            content = "Be brief.", systemPrompt = false, marker = false,
            injectionPosition = InjectionPosition.ABSOLUTE, injectionDepth = 3, injectionOrder = 7,
        )
        val exported = ModeInjectionSerializer.exportToJson(entry)
        val imported = ModeInjectionSerializer.tryImportNative(exported)!!
        assertNotEquals("导入给新 id，免得撞已有条目", entry.identifier, imported.identifier)
        assertEquals(entry.copy(identifier = imported.identifier, keyOrder = imported.keyOrder), imported)
    }

    @Test fun modeInjectionFilesFromVersion232StillImport() {
        val old = """{"version":1,"type":"mode_injection","data":{"type":"mode","id":"22222222-2222-2222-2222-222222222222",
            "name":"Old","enabled":false,"priority":4,"position":"at_depth","content":"for {user}","injectDepth":6,"role":"assistant"}}"""
        val entry = ModeInjectionSerializer.tryImportNative(old)!!
        assertEquals("Old", entry.name)
        assertEquals("for {{user}}", entry.content)
        assertEquals(InjectionPosition.ABSOLUTE, entry.injectionPosition)
        assertEquals(6, entry.injectionDepth)
        assertEquals(4, entry.injectionOrder)
        assertEquals(PromptRole.ASSISTANT, entry.role)
        assertEquals(false, entry.libraryEnabled)
        assertNull(entry.libraryAnchor)
    }

    @Test fun otherFileTypesAreNotModeInjections() {
        assertNull(ModeInjectionSerializer.tryImportNative("""{"version":1,"type":"lorebook","data":{}}"""))
        assertNull(ModeInjectionSerializer.tryImportNative("not json"))
    }

    // ---------------------------------------------------------------- 世界书

    private fun book() = Lorebook(
        name = "Aurora", description = "notes",
        entries = listOf(
            WorldInfoEntry(uid = 1, key = listOf("dragon", "/wyrm/i"), comment = "Dragon", content = "Extinct.", position = WorldInfoPosition.AFTER, order = 40),
            WorldInfoEntry(uid = 2, key = listOf("sword"), content = "Sharp.", constant = true, position = WorldInfoPosition.AT_DEPTH, depth = 2, role = 2),
        ),
    )

    @Test fun lorebookRoundTripsInTheNewFormat() {
        val book = book()
        val imported = LorebookSerializer.tryImportNative(LorebookSerializer.exportToJson(book))!!
        assertNotEquals(book.id, imported.id)
        assertEquals(book.entries.map { it.uid to it.key to it.content }, imported.entries.map { it.uid to it.key to it.content })
        assertEquals(listOf(40, 100), imported.entries.map { it.order })
        assertEquals("Aurora", imported.name)
    }

    @Test fun lorebookFilesFromVersion232StillImportThroughTheMigrationRules() {
        val old = """{"version":1,"type":"lorebook","data":{"id":"33333333-3333-3333-3333-333333333333","name":"Old","description":"d","enabled":true,
            "entries":[{"type":"regex","id":"44444444-4444-4444-4444-444444444444","name":"Dragon","priority":9,"position":"bottom_of_chat",
            "content":"hi {char}","injectDepth":4,"role":"assistant","keywords":["dr.gon"],"useRegex":true,"caseSensitive":false,"scanDepth":3,"constantActive":false}]}}"""
        val imported = LorebookSerializer.tryImportNative(old)!!
        val entry = imported.entries.single()
        assertEquals(listOf("/dr.gon/i"), entry.key)
        assertEquals("hi {{char}}", entry.content)
        assertEquals(WorldInfoPosition.AT_DEPTH, entry.position)
        assertEquals(1, entry.depth)
        assertEquals(2, entry.role)
        assertEquals(9, entry.order)
        assertEquals(3, entry.scanDepth)
    }

    private val sillyTavernBook = """{
        "name": "From ST", "description": "d", "extensions": {"x": 1},
        "entries": {
            "0": {"uid": 0, "key": ["castle"], "keysecondary": ["night"], "comment": "Castle", "content": "Dark.", "constant": false,
                  "selective": true, "selectiveLogic": 3, "order": 50, "position": 4, "depth": 2, "role": 1, "probability": 80,
                  "useProbability": true, "group": "g1", "sticky": 2, "someFutureKey": {"keep": true}},
            "5": {"uid": 5, "key": [], "content": "Always.", "constant": true, "position": 0, "order": 10}
        }
    }"""

    @Test fun sillyTavernWorldBookFilesImportWithAllFields() {
        val imported = LorebookSerializer.tryImportSillyTavern(sillyTavernBook, "fallback")!!
        assertEquals("From ST", imported.name)
        assertEquals(listOf(0, 5), imported.entries.map { it.uid })
        val first = imported.entries.first()
        assertEquals(listOf("night"), first.keysecondary)
        assertEquals(3, first.selectiveLogic)
        assertEquals(80, first.probability)
        assertEquals("g1", first.group)
        assertEquals(2, first.sticky)
        assertEquals(WorldInfoPosition.AT_DEPTH, first.position)
        assertTrue("unknown keys survive", first.extra.containsKey("someFutureKey"))
        assertTrue(imported.extra.containsKey("extensions"))
    }

    @Test fun sillyTavernExportIsAWorldBookFileThatReimportsLosslessly() {
        val imported = LorebookSerializer.tryImportSillyTavern(sillyTavernBook, null)!!
        val file = LorebookSerializer.exportSillyTavern(imported)
        val parsed = WorldInfoBook.fromJson(file)
        assertEquals(imported.entries.map { it.uid }, parsed.entries.map { it.uid })
        assertEquals(imported.entries.first().extra, parsed.entries.first().extra)
        assertEquals("From ST", parsed.name)
        val again = LorebookSerializer.tryImportSillyTavern(file, null)!!
        assertEquals(imported.entries.map { it.content }, again.entries.map { it.content })
    }

    @Test fun nameFallsBackToTheFileNameForNamelessWorldBooks() {
        val nameless = """{"entries":{"0":{"uid":0,"key":["a"],"content":"x"}}}"""
        assertEquals("my-book", LorebookSerializer.tryImportSillyTavern(nameless, "my-book")!!.name)
    }

    @Test fun nonWorldBookJsonIsRejected() {
        assertNull(LorebookSerializer.tryImportSillyTavern("""{"hello":"world"}""", "x"))
        assertNotNull(LorebookSerializer.tryImportSillyTavern("""{"entries":{}}""", "x"))
    }

    @Test fun exportedFilesCarryTheirTypeAndVersion() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(LorebookSerializer.exportToJson(book())).jsonObject
        assertEquals("lorebook", json["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), json["version"])
    }
}
