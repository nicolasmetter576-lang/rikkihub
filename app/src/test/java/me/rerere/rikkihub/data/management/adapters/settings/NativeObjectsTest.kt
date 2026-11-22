package me.rerere.rikkihub.data.management.adapters.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.adapters.NativeCollections
import me.rerere.rikkihub.data.management.adapters.NativeField
import me.rerere.rikkihub.data.management.adapters.NativeViews
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.ports.CollectionPatch
import me.rerere.rikkihub.data.management.ports.SettingsCollectionOp
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.AssemblyOptions
import me.rerere.tavern.prompt.NamesBehavior
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCharacterFilter
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The creator assistant sees and writes the SillyTavern objects with no folding: every key a native object can hold has a field,
 * a Patch of one field changes only that field, and the same list drives validation. These tests fail when a field is added to a
 * native model and the creator surface is not updated.
 */
class NativeObjectsTest {
    private val assistant = Assistant(id = Uuid.random(), name = "A")
    private val book = Lorebook(id = Uuid.random(), name = "B", entries = listOf(WorldInfoEntry(uid = 0, comment = "first", key = listOf("a"))))

    private fun settings() = Settings(assistants = listOf(assistant), lorebooks = listOf(book),
        promptLibrary = listOf(NativeViews.newLibraryEntry("lib-1")))

    private fun op(collection: String, patch: CollectionPatch) = SettingsCollectionOp(collection, patch, emptyList(), "test")

    private fun patch(s: Settings, collection: String, id: String, vararg fields: Pair<String, JsonElement>) =
        SettingsCollectionInterpreter.apply(s, op(collection, CollectionPatch.PatchFields(id, mapOf(*fields))))

    private fun view(s: Settings, collection: String, id: String) =
        SettingsCollectionInterpreter.collectionJson(s, collection).first { it["id"]!!.jsonPrimitive.content == id }

    // ------------------------------------------------------------------ completeness against the native models

    @Test fun everyWorldInfoEntryKeyHasAField() {
        val full = WorldInfoEntry(uid = 3, displayIndex = 5, characterFilter = WorldInfoCharacterFilter(true, listOf("n"), listOf("t")),
            extra = JsonObject(mapOf("unknownKey" to JsonPrimitive(1))))
        val nativeKeys = WorldInfoCodec.encodeEntry(full.copy(layout = null)).keys - "uid" - "unknownKey"
        assertEquals(nativeKeys, NativeViews.ENTRY_FIELDS.map { it.name }.toSet() - "extra")
        assertEquals("unknownKey is reachable through extra", JsonPrimitive(1), NativeViews.entryJson(full).getValue("extra").jsonObject["unknownKey"])
    }

    @Test fun everyPromptEntryKeyHasAField() {
        val full = PromptEntry(identifier = "x", name = "n", role = PromptRole.USER, content = "c", systemPrompt = true, marker = false,
            injectionPosition = 1, injectionDepth = 2, injectionOrder = 3, injectionTrigger = listOf("normal"), forbidOverrides = true,
            extra = JsonObject(mapOf("unknownKey" to JsonPrimitive("u"))))
        val nativeKeys = full.toJson().keys - "identifier" - "unknownKey"
        assertEquals(nativeKeys, NativeViews.PRESET_ENTRY_FIELDS.map { it.name }.toSet() - "enabled" - "extra")
        assertEquals(nativeKeys, NativeViews.LIBRARY_FIELDS.map { it.name }.toSet() - "libraryEnabled" - "libraryAnchor" - "extra")
    }

    @Test fun everyRegexScriptKeyHasAField() {
        val full = RegexScript(id = "i", scriptName = "s", minDepth = 1, maxDepth = 2, extra = JsonObject(mapOf("unknownKey" to JsonPrimitive(1))))
        assertEquals(full.toJson().keys - "id" - "unknownKey", NativeViews.REGEX_FIELDS.map { it.name }.toSet() - "extra")
    }

    @Test fun everyAssemblyOptionAndWorldInfoSettingHasAField() {
        // every option the preset can store: make each differ from the default so it is written
        val all = AssemblyOptions(contextSize = 1, responseTokens = 2, namesBehavior = NamesBehavior.CONTENT, sendIfEmpty = "a",
            impersonationPrompt = "b", newChatPrompt = "c", newExampleChatPrompt = "d", continueNudgePrompt = "e", wiFormat = "f",
            scenarioFormat = "g", personalityFormat = "h", assistantPrefill = "i", assistantImpersonation = "j",
            squashSystemMessages = true, continuePrefill = true, continuePostfix = "k")
        val stored = AssistantPresets.create(emptyList()).copy(options = all).toJson().keys - "prompts" - "prompt_order"
        assertEquals(stored, NativeViews.OPTION_FIELDS.map { it.name }.toSet())
        assertEquals(NativeViews.OPTION_FIELDS.map { it.name }.toSet(), NativeViews.optionsJson(all).keys)
        val worldInfoKeys = Json { encodeDefaults = true }.encodeToJsonElement(WorldInfoSettings.serializer(), WorldInfoSettings()).jsonObject.keys
        assertEquals(worldInfoKeys, NativeViews.WORLD_INFO_FIELDS.map { it.name }.toSet())
        val noteKeys = Json { encodeDefaults = true }.encodeToJsonElement(me.rerere.rikkihub.data.model.AuthorsNoteDefaults.serializer(),
            me.rerere.rikkihub.data.model.AuthorsNoteDefaults()).jsonObject.keys
        assertEquals(noteKeys, NativeViews.AUTHORS_NOTE_FIELDS.map { it.name }.toSet())
    }

    @Test fun everyTavernSettingsAndBookAndAssistantFieldIsReachable() {
        val tavern = TavernSettings::class.java.declaredFields.map { it.name }.filter { it != "Companion" && !it.startsWith("$") }.toSet()
        assertEquals("regexScripts is REGEX_SCRIPT (global)", tavern - "regexScripts", NativeViews.TAVERN_FIELDS.map { it.name }.toSet())
        val bookFields = Lorebook::class.java.declaredFields.map { it.name }.filter { it != "Companion" && !it.startsWith("$") }.toSet()
        assertEquals("entries are LOREBOOK_ENTRY objects", bookFields - "entries" - "id", NativeViews.BOOK_FIELDS.map { it.name }.toSet())

        // Every serialised Assistant key is either writable, view-mapped, or deliberately kept out (secrets and media the creator cannot set).
        val keys = Json { encodeDefaults = true }.encodeToJsonElement(Assistant.serializer(), Assistant()).jsonObject.keys
        val covered = SettingsCollectionInterpreter.assistantFields + setOf("id", "promptPreset", "libraryEntryIds")
        val deliberately = setOf(
            "customHeaders", "customBodies", // may carry credentials
            "deletedAt", "createdAt",
            "defaultCliAgentId", "defaultCliPermissionMode", // CLI command approval stays the user's choice (CLI panel)
            "cardNote", "cardWide", // assistant card note and width preference stay the user's choice
        )
        val missing = keys - covered - deliberately
        assertTrue("Assistant fields neither exposed nor excluded on purpose: $missing", missing.isEmpty())
    }

    // ------------------------------------------------------------------ one field changes only that field

    private fun sample(f: NativeField, current: JsonElement?): JsonElement {
        fun differs(vararg candidates: JsonElement) = candidates.first { it != current }
        return when (f.base) {
            "string" -> f.values?.let { values -> JsonPrimitive(values.first { JsonPrimitive(it) != current }) }
                ?: differs(JsonPrimitive("sample ${f.name}"), JsonPrimitive("other ${f.name}"))
            "boolean" -> differs(JsonPrimitive(true), JsonPrimitive(false))
            "integer" -> {
                val lo = f.min ?: 0
                val hi = f.max ?: 1000
                val pool = f.values?.map { it.toLong() } ?: listOf(lo, minOf(lo + 1, hi), minOf(lo + 2, hi), hi)
                JsonPrimitive(pool.first { JsonPrimitive(it) != current }.toInt())
            }
            "array<string>" -> JsonArray((f.values?.take(2) ?: listOf("one", "two")).map(::JsonPrimitive)).let { if (it == current) JsonArray(listOf(JsonPrimitive("zzz").takeIf { f.values == null } ?: JsonPrimitive(f.values!!.last()))) else it }
            "array<integer>" -> JsonArray((f.values?.take(2)?.map { it.toInt() } ?: listOf(1, 2)).map(::JsonPrimitive)).let { if (it == current) JsonArray(listOf(JsonPrimitive(f.values?.last()?.toInt() ?: 9))) else it }
            "object" -> when (f.name) {
                "characterFilter" -> buildJsonObject { put("isExclude", true); put("names", JsonArray(listOf(JsonPrimitive("n")))); put("tags", JsonArray(listOf(JsonPrimitive("t")))) }
                "extra" -> buildJsonObject { put("sampleKey", "sampleValue") }
                else -> error("no sample for ${f.name}")
            }
            else -> error(f.type)
        }
    }

    private fun checkEachFieldChangesOnlyItself(s: Settings, collection: String, id: String, fields: List<NativeField>, skip: Set<String> = emptySet()) {
        val before = view(s, collection, id)
        for (f in fields) {
            if (f.name in skip) continue
            val value = sample(f, before[f.name])
            val after = try {
                view(patch(s, collection, id, f.name to value), collection, id)
            } catch (e: Exception) {
                fail("$collection.${f.name} = $value: ${e.message}"); return
            }
            assertEquals("$collection.${f.name} reads back what was written", value, after[f.name])
            val others = before.keys - f.name
            others.forEach { key -> assertEquals("$collection: patching ${f.name} must not change $key", before[key], after[key]) }
        }
    }

    @Test fun eachWorldInfoEntryFieldChangesOnlyItself() =
        checkEachFieldChangesOnlyItself(settings(), "settings.lorebooks.${book.id}.entries", "0", NativeViews.ENTRY_FIELDS)

    @Test fun eachLibraryEntryFieldChangesOnlyItself() =
        checkEachFieldChangesOnlyItself(settings(), NativeCollections.LIBRARY, "lib-1", NativeViews.LIBRARY_FIELDS)

    @Test fun eachPresetEntryFieldChangesOnlyItself() =
        checkEachFieldChangesOnlyItself(settings(), "settings.assistants.${assistant.id}.presetEntries", "main", NativeViews.PRESET_ENTRY_FIELDS)

    @Test fun eachBookFieldChangesOnlyItself() =
        checkEachFieldChangesOnlyItself(settings(), NativeCollections.BOOKS, book.id.toString(), NativeViews.BOOK_FIELDS)

    @Test fun eachRegexScriptFieldChangesOnlyItself() {
        val withScript = settings().copy(assistants = listOf(assistant.copy(promptPreset =
            assistant.promptPreset.withRegexScripts(listOf(NativeViews.newRegexScript("r1"))))),
            tavern = TavernSettings(regexScripts = listOf(NativeViews.newRegexScript("g1"))))
        checkEachFieldChangesOnlyItself(withScript, "settings.assistants.${assistant.id}.regexScripts", "r1", NativeViews.REGEX_FIELDS)
        checkEachFieldChangesOnlyItself(withScript, NativeCollections.GLOBAL_REGEX, "g1", NativeViews.REGEX_FIELDS)
    }

    @Test fun eachGlobalSettingChangesOnlyItself() {
        val s = settings()
        val before = view(s, NativeCollections.TAVERN, "tavern")
        for ((group, fields) in listOf("worldInfo" to NativeViews.WORLD_INFO_FIELDS, "authorsNote" to NativeViews.AUTHORS_NOTE_FIELDS)) {
            val current = before.getValue(group).jsonObject
            for (f in fields) {
                val value = sample(f, current[f.name])
                val after = view(patch(s, NativeCollections.TAVERN, "tavern", group to JsonObject(mapOf(f.name to value))), NativeCollections.TAVERN, "tavern")
                val changed = after.getValue(group).jsonObject
                assertEquals("$group.${f.name}", value, changed[f.name])
                (current.keys - f.name).forEach { assertEquals("$group: ${f.name} must not change $it", current[it], changed[it]) }
                (before.keys - group).forEach { assertEquals(before[it], after[it]) }
            }
        }
        val vars = buildJsonObject { put("mood", "calm") }
        assertEquals(vars, view(patch(s, NativeCollections.TAVERN, "tavern", "globalVariables" to vars), NativeCollections.TAVERN, "tavern")["globalVariables"])
        val allowed = JsonArray(listOf(JsonPrimitive("assistant:x")))
        assertEquals(allowed, view(patch(s, NativeCollections.TAVERN, "tavern", "regexAllowed" to allowed), NativeCollections.TAVERN, "tavern")["regexAllowed"])
    }

    @Test fun eachAssemblyOptionChangesOnlyItself() {
        val s = settings()
        val current = view(s, "settings.assistants", assistant.id.toString()).getValue("promptOptions").jsonObject
        for (f in NativeViews.OPTION_FIELDS) {
            val value = sample(f, current[f.name])
            val after = view(patch(s, "settings.assistants", assistant.id.toString(), "promptOptions" to JsonObject(mapOf(f.name to value))),
                "settings.assistants", assistant.id.toString()).getValue("promptOptions").jsonObject
            assertEquals(f.name, value, after[f.name])
            (current.keys - f.name).forEach { assertEquals("${f.name} must not change $it", current[it], after[it]) }
        }
    }

    // ------------------------------------------------------------------ nothing is lost by a patch

    @Test fun aPatchKeepsEveryOtherEntryOfTheBookAndTheUnknownKeys() {
        val rich = WorldInfoEntry(uid = 4, comment = "rich", key = listOf("k"), keysecondary = listOf("s"), probability = 40, group = "g",
            sticky = 3, cooldown = 2, delay = 1, scanDepth = 7, triggers = listOf("normal"), displayIndex = 9,
            characterFilter = WorldInfoCharacterFilter(false, listOf("n"), listOf("t")),
            extra = JsonObject(mapOf("extensions" to buildJsonObject { put("keep", "me") })))
        val s = settings().copy(lorebooks = listOf(book.copy(entries = book.entries + rich)))
        val renamed = patch(s, "settings.lorebooks.${book.id}.entries", "4", "comment" to JsonPrimitive("renamed"))
        val after = renamed.lorebooks.single().entries
        assertEquals("the other entry is untouched", book.entries.single(), after.first { it.uid == 0 })
        val e = after.first { it.uid == 4 }
        assertEquals("renamed", e.comment)
        assertEquals(rich.copy(comment = "renamed", layout = null), e.copy(layout = null))
        assertEquals(JsonPrimitive("me"), (e.extra["extensions"] as JsonObject)["keep"])
    }

    @Test fun editingAnAssistantsPromptEntryLeavesItsOtherEntriesOptionsAndScriptsAlone() {
        val preset = assistant.promptPreset.withRegexScripts(listOf(NativeViews.newRegexScript("r1")))
        val s = settings().copy(assistants = listOf(assistant.copy(promptPreset = preset)))
        val after = patch(s, "settings.assistants.${assistant.id}.presetEntries", "main", "content" to JsonPrimitive("Hello"))
        val p = after.assistants.single().promptPreset
        assertEquals("Hello", p.prompts.first { it.identifier == "main" }.content)
        assertEquals(preset.options, p.options)
        assertEquals(preset.regexScripts, p.regexScripts)
        assertEquals(preset.prompts.filter { it.identifier != "main" }, p.prompts.filter { it.identifier != "main" })
        assertEquals(preset.layout().entries.map { it.identifier }, p.layout().entries.map { it.identifier })
    }

    @Test fun libraryMetadataKeysOutsideTheFieldsSurvive() {
        val entry = NativeViews.newLibraryEntry("lib-9").let {
            it.copy(extra = JsonObject(it.extra + (LibraryEntryMeta.EXTRA_KEY to buildJsonObject {
                put("enabled", true); put("anchor", LibraryEntryMeta.AFTER_SYSTEM); put("futureKey", 1)
            })))
        }
        val s = settings().copy(promptLibrary = listOf(entry))
        val after = patch(s, NativeCollections.LIBRARY, "lib-9", "libraryEnabled" to JsonPrimitive(false)).promptLibrary.single()
        val meta = after.extra[LibraryEntryMeta.EXTRA_KEY] as JsonObject
        assertEquals(JsonPrimitive(false), meta["enabled"])
        assertEquals(JsonPrimitive(1), meta["futureKey"])
        assertEquals(LibraryEntryMeta.AFTER_SYSTEM, after.withLibraryMeta().let { (meta["anchor"] as JsonPrimitive).content })
    }

    @Test fun echoingTheWholeReadContentIsNotAChange() {
        val s = settings()
        for ((collection, id) in listOf(
            "settings.lorebooks.${book.id}.entries" to "0", NativeCollections.LIBRARY to "lib-1",
            "settings.assistants.${assistant.id}.presetEntries" to "main", NativeCollections.BOOKS to book.id.toString(),
        )) {
            val v = view(s, collection, id)
            val after = SettingsCollectionInterpreter.apply(s, op(collection, CollectionPatch.PatchFields(id, v - "id")))
            assertEquals(collection, v, view(after, collection, id))
        }
    }

    // ------------------------------------------------------------------ strict input

    @Test fun wrongTypesRangesAndUnknownFieldsAreRejected() {
        val c = "settings.lorebooks.${book.id}.entries"
        fun rejects(vararg field: Pair<String, JsonElement>) = try {
            patch(settings(), c, "0", *field); fail("expected a rejection of ${field.toList()}")
        } catch (e: Exception) { assertTrue(e.message.orEmpty(), e.message.orEmpty().isNotBlank()) }
        rejects("position" to JsonPrimitive(9))
        rejects("position" to JsonPrimitive("before"))
        rejects("probability" to JsonPrimitive(1.5))
        rejects("key" to JsonPrimitive("single"))
        rejects("disable" to JsonPrimitive("yes"))
        rejects("triggers" to JsonArray(listOf(JsonPrimitive("bogus"))))
        rejects("colour" to JsonPrimitive("red"))
        rejects("characterFilter" to buildJsonObject { put("isExclude", "x") })
        rejects("extra" to buildJsonObject { put("key", "clash") })
    }

    @Test fun choicesAreAcceptedInAnyCase() {
        val fields = SettingsCollectionInterpreter.normalizeFields(NativeCollections.LIBRARY,
            mapOf("role" to JsonPrimitive("USER"), "libraryAnchor" to JsonPrimitive("After_System"),
                "injection_trigger" to JsonArray(listOf(JsonPrimitive("NORMAL")))))
        assertEquals(JsonPrimitive("user"), fields["role"])
        assertEquals(JsonPrimitive("after_system"), fields["libraryAnchor"])
        assertEquals(JsonArray(listOf(JsonPrimitive("normal"))), fields["injection_trigger"])
    }

    // ------------------------------------------------------------------ creating, bulk, reorder

    @Test fun newEntriesGetConsecutiveUidsAndABookCanHoldThousands() {
        val c = "settings.lorebooks.${book.id}.entries"
        val items = (0 until 500).map { i -> CollectionPatch.Create((1 + i).toString(), mapOf(
            "comment" to JsonPrimitive("e$i"), "key" to JsonArray(listOf(JsonPrimitive("k$i"))), "content" to JsonPrimitive("c$i"))) }
        var s = SettingsCollectionInterpreter.apply(settings(), op(c, CollectionPatch.Batch(items)))
        s = SettingsCollectionInterpreter.apply(s, op(c, CollectionPatch.Batch(items.map { CollectionPatch.Create((500 + it.id.toInt()).toString(), it.fields) })))
        val entries = s.lorebooks.single().entries
        assertEquals(1001, entries.size)
        assertEquals((0..1000).toList(), entries.map { it.uid })
        assertEquals("next free uid", "1001", SettingsCollectionInterpreter.nextObjectId(s, c))
        // remove and reorder through the same collection
        val removed = SettingsCollectionInterpreter.apply(s, op(c, CollectionPatch.Remove(listOf("5", "6"))))
        assertEquals(999, removed.lorebooks.single().entries.size)
        val order = removed.lorebooks.single().entries.map { it.uid.toString() }.reversed()
        val reordered = SettingsCollectionInterpreter.apply(removed, op(c, CollectionPatch.Reorder(order)))
        assertEquals(order, reordered.lorebooks.single().entries.map { it.uid.toString() })
    }

    @Test fun anEntryWithSixtyFourKilobytesOfContentRoundTrips() {
        val big = "x".repeat(65_536)
        val s = patch(settings(), "settings.lorebooks.${book.id}.entries", "0", "content" to JsonPrimitive(big))
        assertEquals(big, s.lorebooks.single().entries.single().content)
        assertNotEquals(0, NativeViews.entryJson(s.lorebooks.single().entries.single()).toString().length)
    }

    @Test fun presetEntriesCanBeCreatedMovedAndRemovedWithoutTouchingTheRest() {
        val c = "settings.assistants.${assistant.id}.presetEntries"
        var s = SettingsCollectionInterpreter.apply(settings(), op(c, CollectionPatch.Create("extra-1",
            mapOf("name" to JsonPrimitive("Style"), "content" to JsonPrimitive("Short.")))))
        val ids = view(s, "settings.assistants", assistant.id.toString()).let {
            SettingsCollectionInterpreter.collectionJson(s, c).map { v -> v["id"]!!.jsonPrimitive.content }
        }
        assertEquals("extra-1", ids.last())
        s = SettingsCollectionInterpreter.apply(s, op(c, CollectionPatch.Reorder(listOf("extra-1") + ids.dropLast(1))))
        assertEquals("extra-1", s.assistants.single().promptPreset.layout().entries.first().identifier)
        s = SettingsCollectionInterpreter.apply(s, op(c, CollectionPatch.Remove(listOf("extra-1"))))
        assertEquals(ids.dropLast(1), s.assistants.single().promptPreset.layout().entries.map { it.identifier })
        assertTrue(s.assistants.single().promptPreset.prompts.none { it.identifier == "extra-1" })
        assertEquals(JsonNull, JsonNull)
    }

    private fun presetFixture(name: String): JsonObject =
        Json.parseToJsonElement(NativeObjectsTest::class.java.getResource("/presets/$name")!!.readText()).jsonObject

    @Test fun anAssistantCanBeCreatedFromASillyTavernPreset() {
        val newId = Uuid.random()
        val preset = presetFixture("night-ferry.json")
        val s = SettingsCollectionInterpreter.apply(settings(),
            op("settings.assistants", CollectionPatch.Create(newId.toString(), mapOf("name" to JsonPrimitive("Ferry"), "fromPreset" to preset))))
        val made = s.assistants.first { it.id == newId }
        assertEquals("Ferry", made.name)
        // the preset's sampling parameters become the typed fields, and its prompts and order come in
        assertEquals(0.9f, made.temperature)
        assertEquals(40, made.topK)
        assertEquals(42L, made.seed)
        assertTrue(made.promptPreset.prompts.any { it.identifier == "ferryLog" })
        // connection settings never come in, and the preset's scripts start off
        assertTrue("proxy_password" !in made.promptPreset.toJson())
        assertTrue(made.promptPreset.regexScripts.all { it.disabled })
        // a SillyTavern preset is meant for the full assembly
        assertTrue(made.tavernMode)
    }

    @Test fun fromPresetIsCreateOnlyAndNotMixedWithOtherPromptSources() {
        val preset = presetFixture("night-ferry.json")
        val other = Uuid.random().toString()
        for (extra in listOf("copyFrom" to JsonPrimitive(assistant.id.toString()), "systemPrompt" to JsonPrimitive("x"))) {
            try {
                SettingsCollectionInterpreter.apply(settings(), op("settings.assistants",
                    CollectionPatch.Create(other, mapOf("name" to JsonPrimitive("N"), "fromPreset" to preset, extra))))
                fail("expected a refusal for ${extra.first}")
            } catch (e: Exception) {
                assertTrue(e.message!!, e.message!!.contains("fromPreset"))
            }
        }
        try {
            SettingsCollectionInterpreter.apply(settings(), op("settings.assistants",
                CollectionPatch.Create(other, mapOf("fromPreset" to JsonObject(mapOf("hello" to JsonPrimitive("world")))))))
            fail("a file that is not a preset is refused")
        } catch (e: Exception) {
            assertTrue(e.message!!, e.message!!.contains("preset"))
        }
    }

    @Test fun samplingParametersAreAssistantFieldsWithTheAppsRanges() {
        val s = patch(settings(), "settings.assistants", assistant.id.toString(),
            "topK" to JsonPrimitive(50), "frequencyPenalty" to JsonPrimitive(0.5), "seed" to JsonPrimitive(7))
        val a = s.assistants.first { it.id == assistant.id }
        assertEquals(50, a.topK)
        assertEquals(0.5f, a.frequencyPenalty)
        assertEquals(7L, a.seed)
        // null sends nothing again
        val cleared = patch(s, "settings.assistants", assistant.id.toString(), "topK" to JsonNull)
        assertEquals(null, cleared.assistants.first { it.id == assistant.id }.topK)
        for ((key, value) in listOf("topK" to 0, "topK" to 100001, "frequencyPenalty" to 3, "repetitionPenalty" to 0, "minP" to 2, "topA" to -1, "seed" to -5)) {
            try {
                patch(settings(), "settings.assistants", assistant.id.toString(), key to JsonPrimitive(value))
                fail("$key=$value should be refused")
            } catch (e: Exception) {
                assertTrue(e.message!!, e.message!!.contains(key))
            }
        }
    }

    @Test fun anAssistantCanBeCreatedFromAnotherOnesPrompt() {
        val withScript = assistant.copy(promptPreset = AssistantPresets.withSystemPrompt("Persona").withRegexScripts(listOf(NativeViews.newRegexScript("r1"))))
        val newId = Uuid.random()
        val s = SettingsCollectionInterpreter.apply(settings().copy(assistants = listOf(withScript)),
            op("settings.assistants", CollectionPatch.Create(newId.toString(), mapOf("name" to JsonPrimitive("Copy"), "copyFrom" to JsonPrimitive(withScript.id.toString())))))
        val copy = s.assistants.first { it.id == newId }
        assertEquals(withScript.promptPreset.toJson(), copy.promptPreset.toJson())
        assertEquals("Copy", copy.name)
        assertFalse(copy.tavernMode)
        val shortcut = SettingsCollectionInterpreter.apply(settings(),
            op("settings.assistants", CollectionPatch.Create(newId.toString(), mapOf("name" to JsonPrimitive("N"), "systemPrompt" to JsonPrimitive("Hello")))))
        assertEquals("Hello", shortcut.assistants.first { it.id == newId }.promptPreset.prompts.first { it.identifier == "main" }.content)
    }

    @Test fun tavernModeIsAFieldAndFollowsCopyFromUnlessSet() {
        val source = assistant.copy(tavernMode = true)
        val base = settings().copy(assistants = listOf(source))
        val copied = Uuid.random()
        val s = SettingsCollectionInterpreter.apply(base,
            op("settings.assistants", CollectionPatch.Create(copied.toString(), mapOf("name" to JsonPrimitive("C"), "copyFrom" to JsonPrimitive(source.id.toString())))))
        assertTrue(s.assistants.first { it.id == copied }.tavernMode)
        val fresh = Uuid.random()
        val created = SettingsCollectionInterpreter.apply(base,
            op("settings.assistants", CollectionPatch.Create(fresh.toString(), mapOf("name" to JsonPrimitive("N"), "tavernMode" to JsonPrimitive(true)))))
        assertTrue(created.assistants.first { it.id == fresh }.tavernMode)
        val patched = SettingsCollectionInterpreter.apply(base,
            op("settings.assistants", CollectionPatch.PatchFields(source.id.toString(), mapOf("tavernMode" to JsonPrimitive(false)))))
        assertFalse(patched.assistants.first { it.id == source.id }.tavernMode)
    }
}
