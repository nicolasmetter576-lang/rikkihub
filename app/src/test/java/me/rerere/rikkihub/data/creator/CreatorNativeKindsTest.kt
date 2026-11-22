package me.rerere.rikkihub.data.creator

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ToolIds
import me.rerere.rikkihub.data.creator.runtime.CreatorManagementBridge
import me.rerere.rikkihub.data.creator.runtime.CreatorToolSurface
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.management.ManagementFixture
import me.rerere.rikkihub.data.management.managementFixture
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** The native creator kinds through the tool surface: lorebook entries, regex scripts, global tavern settings, the prompt library. */
class CreatorNativeKindsTest {
    private val conversationId = Uuid.random()

    private class Tools(val f: ManagementFixture, val byName: Map<String, me.rerere.ai.core.Tool>)

    private fun test(block: suspend Tools.() -> Unit) = managementFixture { f ->
        val read = CreatorReadDomain(JsonInstant, { f.settings.settingsSnapshot() }, AssistantRegistry(), { f.runtime.readPort })
        val surface = CreatorToolSurface(read, CreatorManagementBridge({ f.runtime }, JsonInstant))
        Tools(f, surface.tools(conversationId).associateBy { it.name }).block()
    }

    private suspend fun Tools.call(tool: String, args: JsonElement): JsonObject {
        val parts = byName.getValue(tool).execute(args)
        return JsonInstant.parseToJsonElement((parts.single() as UIMessagePart.Text).text).jsonObject
    }

    private suspend fun Tools.preview(vararg operations: String) =
        call(ToolIds.PREVIEW_CHANGE, JsonInstant.parseToJsonElement("""{"operations":[${operations.joinToString(",")}]}"""))

    private suspend fun Tools.list(kind: String, filter: String = "", pageSize: Int = 40, token: String? = null) =
        call(ToolIds.LIST_OBJECTS, buildJsonObject {
            put("kind", kind); put("filter", filter); put("pageSize", pageSize); token?.let { put("pageToken", it) }
        })

    private suspend fun Tools.read(kind: String, id: String) =
        call(ToolIds.READ_OBJECT, buildJsonObject { put("kind", kind); put("id", id) })

    private fun JsonObject.proposals() = getValue("proposals").jsonArray.map { it.jsonObject }
    private fun JsonObject.rejected() = getValue("rejected").jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun Tools.approve(result: JsonObject) {
        assertTrue(result.toString(), result.rejected().isEmpty())
        result.proposals().forEach {
            val outcome = f.approve(it.str("proposalId"))
            assertEquals(outcome.toString(), ProposalStatus.APPLIED, outcome.status)
        }
    }

    private suspend fun Tools.assistantId() =
        f.settings.settingsSnapshot().assistants.first { !CreatorAssistantDefinition.isCreator(it.id) }.id.toString()

    @Test(timeout = 60_000) fun aBookAndItsEntriesAreCreatedInOneCallAndFoundByKeywordOrContent() = test {
        val result = preview(
            """{"kind":"LOREBOOK","command":"Create","params":{"fields":{"name":"Aurora"}}}""",
            """{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"${'$'}0","items":[
                {"comment":"Capital","key":["capital","Aurum"],"content":"Aurum floats."},
                {"comment":"Dragons","key":["dragon"],"keysecondary":["fire"],"selective":true,"selectiveLogic":0,"content":"Dragons breathe fire.","probability":60}]}}""",
        )
        approve(result)
        val bookId = f.settings.settingsSnapshot().lorebooks.single { it.name == "Aurora" }.id.toString()
        val entries = f.settings.settingsSnapshot().lorebooks.single { it.name == "Aurora" }.entries
        assertEquals(listOf(0, 1), entries.map { it.uid })
        assertEquals(60, entries[1].probability)
        assertEquals(listOf("fire"), entries[1].keysecondary)

        val byKeyword = list("LOREBOOK_ENTRY", "$bookId dragon").getValue("items").jsonArray
        assertEquals(1, byKeyword.size)
        assertEquals("$bookId/1", byKeyword.single().jsonObject.str("id"))
        val byContent = list("LOREBOOK_ENTRY", "$bookId floats").getValue("items").jsonArray
        assertEquals("$bookId/0", byContent.single().jsonObject.str("id"))
        assertEquals("both entries when only the book is named", 2, list("LOREBOOK_ENTRY", bookId).getValue("items").jsonArray.size)

        val entry = read("LOREBOOK_ENTRY", "$bookId/1")
        val content = entry.getValue("contentJson").jsonObject
        assertEquals("fire", content.getValue("keysecondary").jsonArray.single().jsonPrimitive.content)
        assertEquals("60", content.str("probability"))
        assertFalse("entries carry no id inside contentJson", content.containsKey("id"))
        assertEquals("2", read("LOREBOOK", bookId).getValue("readOnly").jsonObject.str("entryCount"))
    }

    @Test(timeout = 60_000) fun hundredsOfEntriesAreWrittenPatchedAndRemovedInBulk() = test {
        approve(preview("""{"kind":"LOREBOOK","command":"Create","params":{"fields":{"name":"Big"}}}"""))
        val bookId = f.settings.settingsSnapshot().lorebooks.single { it.name == "Big" }.id.toString()
        fun items(from: Int, n: Int) = (from until from + n).joinToString(",") { """{"comment":"e$it","key":["k$it"],"content":"text $it"}""" }
        approve(preview("""{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"$bookId","items":[${items(0, 200)}]}}"""))
        approve(preview("""{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"$bookId","items":[${items(200, 200)}]}}"""))
        assertEquals(400, f.settings.settingsSnapshot().lorebooks.single { it.name == "Big" }.entries.size)

        // paging reaches every entry
        var seen = 0
        var token: String? = null
        do {
            val page = list("LOREBOOK_ENTRY", bookId, pageSize = 100, token = token)
            seen += page.getValue("items").jsonArray.size
            token = page["nextPageToken"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
        } while (token != null)
        assertEquals(400, seen)

        val patches = (0 until 150).joinToString(",") { """{"id":"$bookId/$it","fields":{"disable":true}}""" }
        approve(preview("""{"kind":"LOREBOOK_ENTRY","command":"Patch","params":{"bookId":"$bookId","patches":[$patches]}}"""))
        val entries = f.settings.settingsSnapshot().lorebooks.single { it.name == "Big" }.entries
        assertEquals(150, entries.count { it.disable })
        assertEquals("other fields kept", "text 3", entries[3].content)

        val ids = (0 until 100).joinToString(",") { "\"$bookId/$it\"" }
        approve(preview("""{"kind":"LOREBOOK_ENTRY","command":"Delete","params":{"bookId":"$bookId","ids":[$ids]}}"""))
        assertEquals(300, f.settings.settingsSnapshot().lorebooks.single { it.name == "Big" }.entries.size)

        val tooMany = preview("""{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"$bookId","items":[${items(1000, 201)}]}}""")
        assertTrue(tooMany.toString(), tooMany.proposals().isEmpty())
    }

    @Test(timeout = 60_000) fun anEntryCanBeUndoneAndAStaleEditIsRefused() = test {
        approve(preview("""{"kind":"LOREBOOK","command":"Create","params":{"fields":{"name":"U"}}}"""))
        val bookId = f.settings.settingsSnapshot().lorebooks.single { it.name == "U" }.id.toString()
        approve(preview("""{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"$bookId","fields":{"comment":"one","key":["x"],"content":"v1"}}}"""))
        val edit = preview("""{"kind":"LOREBOOK_ENTRY","command":"Patch","targetId":"$bookId/0","params":{"fields":{"content":"v2"}}}""")
        val appliedId = edit.proposals().single().str("proposalId")
        approve(edit)
        assertEquals("v2", f.settings.settingsSnapshot().lorebooks.single { it.name == "U" }.entries.single().content)
        val undo = call(ToolIds.PROPOSE_UNDO, buildJsonObject { put("proposalId", appliedId) })
        approve(undo)
        assertEquals("v1", f.settings.settingsSnapshot().lorebooks.single { it.name == "U" }.entries.single().content)

        // a plan made before somebody else changed the entry is refused at approval
        val stale = preview("""{"kind":"LOREBOOK_ENTRY","command":"Patch","targetId":"$bookId/0","params":{"fields":{"content":"v3"}}}""")
        f.settings.update { s -> s.copy(lorebooks = s.lorebooks.map { b ->
            if (b.name != "U") b else b.copy(entries = b.entries.map { it.copy(content = "changed elsewhere") })
        }) }
        val outcome = f.approve(stale.proposals().single().str("proposalId"))
        assertTrue(outcome.toString(), outcome.status != ProposalStatus.APPLIED)
        assertEquals("changed elsewhere", f.settings.settingsSnapshot().lorebooks.single { it.name == "U" }.entries.single().content)
    }

    @Test(timeout = 60_000) fun regexScriptsAreGlobalOrPerAssistantAndListedByScope() = test {
        val a = assistantId()
        approve(preview(
            """{"kind":"REGEX_SCRIPT","command":"Create","params":{"fields":{"scriptName":"Global one","findRegex":"/cat/g","replaceString":"dog","placement":[2]}}}""",
            """{"kind":"REGEX_SCRIPT","command":"Create","params":{"assistantId":"$a","fields":{"scriptName":"Mine","findRegex":"/x/","replaceString":"y","placement":[1],"promptOnly":true,"minDepth":0,"maxDepth":3}}}""",
        ))
        val settings = f.settings.settingsSnapshot()
        assertEquals(listOf("Global one"), settings.tavern.regexScripts.map { it.scriptName })
        val mine = settings.assistants.first { it.id.toString() == a }.promptPreset.regexScripts
        assertEquals(listOf("Mine"), mine.map { it.scriptName })
        assertEquals(3, mine.single().maxDepth)

        assertEquals(1, list("REGEX_SCRIPT", "global").getValue("items").jsonArray.size)
        assertEquals(1, list("REGEX_SCRIPT", a).getValue("items").jsonArray.size)
        assertEquals(2, list("REGEX_SCRIPT").getValue("items").jsonArray.size)

        val gid = "global/${settings.tavern.regexScripts.single().id}"
        approve(preview("""{"kind":"REGEX_SCRIPT","command":"Patch","targetId":"$gid","params":{"fields":{"disabled":true}}}"""))
        assertTrue(f.settings.settingsSnapshot().tavern.regexScripts.single().disabled)
        assertEquals("true", read("REGEX_SCRIPT", gid).getValue("contentJson").jsonObject.str("disabled"))
        approve(preview("""{"kind":"REGEX_SCRIPT","command":"Delete","targetId":"$gid"}"""))
        assertTrue(f.settings.settingsSnapshot().tavern.regexScripts.isEmpty())
    }

    @Test(timeout = 60_000) fun globalTavernSettingsAreReadAndMergedKeyByKey() = test {
        val before = read("TAVERN_SETTINGS", "tavern").getValue("contentJson").jsonObject
        assertEquals(setOf("worldInfo", "authorsNote", "globalVariables", "regexAllowed", "personaDescription"), before.keys)
        approve(preview("""{"kind":"TAVERN_SETTINGS","command":"Patch","targetId":"tavern","params":{"fields":{"worldInfo":{"world_info_depth":6},"authorsNote":{"prompt":"Be kind.","depth":2},"globalVariables":{"mood":"calm"},"personaDescription":"{{user}} is a retired navigator."}}}"""))
        val tavern = f.settings.settingsSnapshot().tavern
        assertEquals("{{user}} is a retired navigator.", tavern.personaDescription)
        assertEquals(6, tavern.worldInfo.depth)
        assertEquals(25, tavern.worldInfo.budget)
        assertEquals("Be kind.", tavern.authorsNote.prompt)
        assertEquals(2, tavern.authorsNote.depth)
        assertEquals("calm", tavern.globalVariables["mood"]?.jsonPrimitive?.content)
        val bad = preview("""{"kind":"TAVERN_SETTINGS","command":"Patch","params":{"fields":{"worldInfo":{"world_info_depth":-1}}}}""")
        assertTrue(bad.toString(), bad.rejected().single().str("detail").contains("world_info_depth"))
        val creating = preview("""{"kind":"TAVERN_SETTINGS","command":"Create","params":{"fields":{"worldInfo":{}}}}""")
        assertTrue(creating.toString(), creating.rejected().isNotEmpty())
    }

    @Test(timeout = 60_000) fun promptLibraryEntriesAreFullAndCanBeBoundToAnAssistant() = test {
        val a = assistantId()
        approve(preview(
            """{"kind":"MODE_INJECTION","command":"Create","params":{"fields":{"name":"At depth","content":"Stay in character.","role":"user","injection_position":1,"injection_depth":2,"injection_order":50,"injection_trigger":["normal","swipe"],"libraryAnchor":null}}}""",
            """{"kind":"MODE_INJECTION","command":"Bind","targetId":"${'$'}0","params":{"assistantId":"$a"}}""",
        ))
        val s = f.settings.settingsSnapshot()
        val entry = s.promptLibrary.single { it.name == "At depth" }
        assertEquals(1, entry.injectionPosition)
        assertEquals(2, entry.injectionDepth)
        assertEquals(50, entry.injectionOrder)
        assertEquals(listOf("normal", "swipe"), entry.injectionTrigger)
        assertTrue(s.assistants.first { it.id.toString() == a }.libraryEntryIds.contains(Uuid.parse(entry.identifier)))
        val assistant = read("ASSISTANT", a).getValue("contentJson").jsonObject
        assertTrue(assistant.getValue("modeInjectionIds").jsonArray.any { it.jsonPrimitive.content == entry.identifier })
        assertFalse(assistant.toString(), assistant.containsKey("promptPreset") || assistant.containsKey("systemPrompt"))
    }

    @Test(timeout = 60_000) fun cloningABookOrAnAssistantCopiesWhatLivesInsideIt() = test {
        approve(preview("""{"kind":"LOREBOOK","command":"Create","params":{"fields":{"name":"Src"}}}""",
            """{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"${'$'}0","fields":{"comment":"c","key":["k"],"content":"t"}}}"""))
        val src = f.settings.settingsSnapshot().lorebooks.single { it.name == "Src" }
        approve(preview("""{"kind":"LOREBOOK","command":"Clone","targetId":"${src.id}","params":{"name":"Dup"}}"""))
        val dup = f.settings.settingsSnapshot().lorebooks.single { it.name == "Dup" }
        assertEquals(src.entries.map { it.content }, dup.entries.map { it.content })

        val a = assistantId()
        approve(preview("""{"kind":"PRESET_ENTRY","command":"Patch","targetId":"$a/main","params":{"fields":{"content":"Persona text"}}}""",
            """{"kind":"ASSISTANT","command":"Clone","targetId":"$a","params":{"name":"Twin"}}"""))
        val twin = f.settings.settingsSnapshot().assistants.single { it.name == "Twin" }
        assertEquals("Persona text", twin.promptPreset.prompts.first { it.identifier == "main" }.content)
    }

    @Test(timeout = 60_000) fun aCharacterIsCreatedListedReadAndPatchedThroughTheTools() = test {
        val created = preview(
            """{"kind":"CHARACTER","command":"Create","params":{"fields":{"name":"Ines Okafor","description":"A harbor cartographer.",
                "first_mes":"Back again, {{user}}?","tags":["harbor","maps"],"alternate_greetings":["*looks up*"]}}}""",
        )
        approve(created)
        val record = f.room.characters.values.single()
        assertEquals("Ines Okafor", record.name)

        val listed = list("CHARACTER").getValue("items").jsonArray.single().jsonObject
        assertEquals(record.id.toString(), listed.str("id"))
        assertTrue(listed.str("title").contains("Ines Okafor") && listed.str("title").contains("harbor"))
        assertEquals(1, list("CHARACTER", "cartographer").getValue("items").jsonArray.size)
        assertEquals(0, list("CHARACTER", "nobody like this").getValue("items").jsonArray.size)

        val read = read("CHARACTER", record.id.toString())
        val content = read.getValue("contentJson").jsonObject
        assertEquals("Back again, {{user}}?", content.str("first_mes"))
        assertEquals(listOf("harbor", "maps"), content.getValue("tags").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("the id is the call's argument, not a field", content.containsKey("id"))
        assertEquals("character:${record.id}", read.getValue("readOnly").jsonObject.str("regexSource"))

        // what the read returned can be edited back: patch one field
        approve(preview("""{"kind":"CHARACTER","command":"Patch","targetId":"${record.id}","params":{"fields":{"personality":"dry-witted"}}}"""))
        assertEquals("dry-witted", f.room.characters.getValue(record.id).card.personality)

        // deleting is not offered
        val refused = preview("""{"kind":"CHARACTER","command":"Delete","targetId":"${record.id}"}""")
        assertEquals(1, refused.rejected().size)
        assertEquals(1, f.room.characters.size)
    }
}
