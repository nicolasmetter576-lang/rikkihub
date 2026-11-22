package me.rerere.rikkihub.web.routes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.model.AuthorsNoteDefaults
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.newLibraryEntry
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class TavernWebSupportTest {
    private fun expectBadRequest(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected BadRequestException")
        } catch (_: BadRequestException) {
        }
    }

    // ---------------------------------------------------------------- JSON 补丁

    @Test
    fun `mergeJsonPatch overlays present keys, keeps the rest and skips frozen keys`() {
        val base = buildJsonObject { put("a", 1); put("b", 2); put("c", 3) }
        val patch = buildJsonObject { put("b", JsonNull); put("c", 9); put("d", 4); put("uid", 7) }
        val merged = mergeJsonPatch(base, patch, frozen = setOf("uid"))
        assertEquals(listOf("a", "b", "c", "d"), merged.keys.toList())
        assertEquals(JsonNull, merged["b"])
        assertEquals(9, merged.getValue("c").jsonPrimitive.content.toInt())
        assertNull(merged["uid"])
    }

    // ---------------------------------------------------------------- 提示词库

    @Test
    fun `a created library entry gets a fresh uuid identifier and is a custom entry`() {
        val body = buildJsonObject {
            put("identifier", "main")
            put("name", "Note")
            put("content", "hello")
            put("system_prompt", true)
            put("marker", true)
        }
        val entry = libraryEntryForCreate(body)
        assertNotEquals("main", entry.identifier)
        Uuid.parse(entry.identifier)
        assertEquals(false, entry.systemPrompt)
        assertEquals(false, entry.marker)
        assertEquals("hello", entry.content)
        // 可选用开关和锚点补齐
        assertTrue(entry.libraryEnabled)
        assertEquals(LibraryEntryMeta.AFTER_SYSTEM, entry.libraryAnchor)
    }

    @Test
    fun `a library entry patch keeps the identifier and untouched fields`() {
        val existing = newLibraryEntry("n").copy(content = "old", injectionDepth = 6)
        val merged = libraryEntryMerged(existing, buildJsonObject {
            put("identifier", "other")
            put("content", "new")
            put("injection_order", 3)
        })
        assertEquals(existing.identifier, merged.identifier)
        assertEquals("new", merged.content)
        assertEquals(6, merged.injectionDepth)
        assertEquals(3, merged.injectionOrder)
        assertEquals(false, merged.systemPrompt)
    }

    @Test
    fun `a library entry patch can turn the entry off through its rikkihub meta`() {
        val existing = newLibraryEntry("n")
        val merged = libraryEntryMerged(existing, buildJsonObject {
            put(LibraryEntryMeta.EXTRA_KEY, buildJsonObject { put("enabled", false); put("anchor", LibraryEntryMeta.BEFORE_HISTORY) })
        })
        assertFalse(merged.libraryEnabled)
        assertEquals(LibraryEntryMeta.BEFORE_HISTORY, merged.libraryAnchor)
    }

    // ---------------------------------------------------------------- 世界书

    private fun book(vararg entries: WorldInfoEntry) = Lorebook(name = "b", description = "d", entries = entries.toList())

    @Test
    fun `lorebook summary has the count and a revision that follows the entries`() {
        val a = book(WorldInfoEntry(uid = 0, comment = "x"))
        val summary = a.summaryJson()
        assertEquals(1, summary.getValue("entryCount").jsonPrimitive.content.toInt())
        assertNull(summary["entries"])
        val same = a.copy().summaryJson().getValue("rev")
        assertEquals(summary.getValue("rev"), same)
        val changed = a.copy(entries = a.entries.map { it.copy(content = "different") }).summaryJson().getValue("rev")
        assertNotEquals(summary.getValue("rev"), changed)
    }

    @Test
    fun `the full lorebook json carries every entry in tavern shape`() {
        val full = book(WorldInfoEntry(uid = 3, key = listOf("k"), comment = "c")).fullJson()
        val entries = full.getValue("entries").jsonArray
        assertEquals(1, entries.size)
        assertEquals(3, entries[0].jsonObject.getValue("uid").jsonPrimitive.content.toInt())
        assertEquals("k", entries[0].jsonObject.getValue("key").jsonArray[0].jsonPrimitive.content)
        assertTrue(entries[0].jsonObject.containsKey("scanDepth"))
    }

    @Test
    fun `an entry patch changes the given fields, keeps the uid and keeps unknown fields`() {
        val withExtra = WorldInfoCodec.decodeEntry(buildJsonObject {
            put("uid", 5); put("comment", "old"); put("content", "body"); put("customThing", "kept")
        })
        val merged = worldInfoEntryMerged(withExtra, buildJsonObject {
            put("uid", 99)
            put("comment", "new")
            put("scanDepth", 7)
            put("caseSensitive", JsonNull)
        })
        assertEquals(5, merged.uid)
        assertEquals("new", merged.comment)
        assertEquals("body", merged.content)
        assertEquals(7, merged.scanDepth)
        assertNull(merged.caseSensitive)
        assertEquals("kept", WorldInfoCodec.encodeEntry(merged).getValue("customThing").jsonPrimitive.content)
    }

    @Test
    fun `a new entry takes the next uid and the given fields`() {
        val (next, created) = book(WorldInfoEntry(uid = 2), WorldInfoEntry(uid = 9)).withNewEntryFrom(
            buildJsonObject { put("comment", "hello"); put("key", JsonArray(listOf(JsonPrimitive("a")))) },
        )
        assertEquals(10, created.uid)
        assertEquals("hello", created.comment)
        assertEquals(listOf("a"), created.key)
        assertEquals(3, next.entries.size)
        assertEquals(0, Lorebook().withNewEntryFrom(null).second.uid)
    }

    @Test
    fun `bulk enabling touches only the named uids or everything`() {
        val b = book(WorldInfoEntry(uid = 0, disable = true), WorldInfoEntry(uid = 1, disable = true), WorldInfoEntry(uid = 2))
        assertEquals(listOf(false, true, true), b.withEntriesEnabled(true, setOf(0)).entries.map { it.disable }.let { listOf(it[0], it[1], it[2].not()) })
        assertTrue(b.withEntriesEnabled(true, null).entries.none { it.disable })
        assertTrue(b.withEntriesEnabled(false, null).entries.all { it.disable })
    }

    @Test
    fun `a book meta patch changes name, description and enabled and nothing else`() {
        val b = book(WorldInfoEntry(uid = 0))
        val next = b.withMetaPatch(buildJsonObject { put("name", "renamed"); put("enabled", false); put("entries", JsonArray(emptyList())) })
        assertEquals("renamed", next.name)
        assertEquals("d", next.description)
        assertFalse(next.enabled)
        assertEquals(1, next.entries.size)
        expectBadRequest { b.withMetaPatch(buildJsonObject { put("name", 3) }) }
        expectBadRequest { b.withMetaPatch(buildJsonObject { put("enabled", "yes") }) }
    }

    // ---------------------------------------------------------------- 全局正则

    @Test
    fun `a created regex script gets an id and a patch keeps the id`() {
        val created = regexScriptForCreate(buildJsonObject { put("scriptName", "s"); put("findRegex", "/a/g") })
        Uuid.parse(created.id)
        // 带了 id 的请求（界面先显示新脚本）用它的 id
        assertEquals("mine", regexScriptForCreate(buildJsonObject { put("id", " mine "); put("scriptName", "x") }).id)
        assertNotEquals("", regexScriptForCreate(buildJsonObject { put("id", "  ") }).id)
        assertEquals("s", created.scriptName)
        val merged = regexScriptMerged(created, buildJsonObject { put("id", "x"); put("disabled", true); put("markdownOnly", true) })
        assertEquals(created.id, merged.id)
        assertTrue(merged.disabled)
        assertTrue(merged.markdownOnly)
        assertEquals("/a/g", merged.findRegex)
    }

    // ---------------------------------------------------------------- 全局设置

    @Test
    fun `world info settings merge by wire key and validate ranges`() {
        val merged = mergeWorldInfoSettings(WorldInfoSettings(), buildJsonObject { put("world_info_depth", 5); put("world_info_recursive", true) })
        assertEquals(5, merged.depth)
        assertTrue(merged.recursive)
        assertEquals(25, merged.budget)
        expectBadRequest { mergeWorldInfoSettings(WorldInfoSettings(), buildJsonObject { put("world_info_budget", 0) }) }
        expectBadRequest { mergeWorldInfoSettings(WorldInfoSettings(), buildJsonObject { put("world_info_depth", -1) }) }
        expectBadRequest { mergeWorldInfoSettings(WorldInfoSettings(), buildJsonObject { put("world_info_depth", "deep") }) }
    }

    @Test
    fun `author's note defaults merge and validate`() {
        val merged = mergeAuthorsNoteDefaults(AuthorsNoteDefaults(), buildJsonObject { put("prompt", "note"); put("depth", 2); put("role", 1) })
        assertEquals("note", merged.prompt)
        assertEquals(2, merged.depth)
        assertEquals(1, merged.role)
        assertEquals(1, merged.position)
        expectBadRequest { mergeAuthorsNoteDefaults(AuthorsNoteDefaults(), buildJsonObject { put("position", 5) }) }
        expectBadRequest { mergeAuthorsNoteDefaults(AuthorsNoteDefaults(), buildJsonObject { put("role", 3) }) }
        expectBadRequest { mergeAuthorsNoteDefaults(AuthorsNoteDefaults(), buildJsonObject { put("depth", -2) }) }
    }

    @Test
    fun `variable names are trimmed and must not be blank`() {
        assertEquals("a b", checkedVariableName(JsonPrimitive("  a b ")))
        expectBadRequest { checkedVariableName(JsonPrimitive("   ")) }
        expectBadRequest { checkedVariableName(null) }
        expectBadRequest { checkedVariableName(JsonPrimitive(3)) }
        expectBadRequest { checkedVariableText(null) }
        assertEquals("", checkedVariableText(JsonPrimitive("")))
    }

    @Test
    fun `a regex script keeps unknown fields through the patch`() {
        val script = RegexScript.fromJson(buildJsonObject { put("id", "i"); put("scriptName", "s"); put("future", 1) })
        val merged = regexScriptMerged(script, buildJsonObject { put("scriptName", "t") })
        assertEquals(1, merged.toJson().getValue("future").jsonPrimitive.content.toInt())
        assertEquals("t", merged.scriptName)
        assertTrue(JsonObject(emptyMap()).isEmpty())
    }

    @Test
    fun `a sparse entry from a tavern file is served with every field and keeps its unknown ones`() {
        val sparse = WorldInfoCodec.decodeEntry(buildJsonObject {
            put("uid", 4); put("key", JsonArray(listOf(JsonPrimitive("a")))); put("content", "c"); put("futureField", "kept")
        })
        // 文件里没写的字段，在原样编码里也没有
        assertFalse(WorldInfoCodec.encodeEntry(sparse).containsKey("triggers"))
        val web = sparse.webJson()
        for (key in listOf("triggers", "scanDepth", "caseSensitive", "keysecondary", "delayUntilRecursion", "groupWeight", "probability", "sticky", "role")) {
            assertTrue("$key missing", web.containsKey(key))
        }
        assertEquals("kept", web.getValue("futureField").jsonPrimitive.content)
        assertEquals(JsonNull, web.getValue("scanDepth"))
        // 编辑之后条目是完整的，没碰的字段不变
        val edited = worldInfoEntryMerged(sparse, buildJsonObject { put("comment", "hi") })
        assertEquals(listOf("a"), edited.key)
        assertEquals("c", edited.content)
        assertEquals("hi", edited.comment)
        assertEquals("kept", WorldInfoCodec.encodeEntry(edited).getValue("futureField").jsonPrimitive.content)
        // 整本书取回时条目是完整的，指纹仍按存储里的写法算（设置快照里编码出来的也是这一份）
        val book = Lorebook(entries = listOf(sparse))
        assertTrue(book.fullJson().getValue("entries").jsonArray[0].jsonObject.containsKey("triggers"))
        assertEquals(book.summaryJson().getValue("rev"), book.fullJson().getValue("rev"))
    }

    @Test
    fun `note fields in a patch override the current ones and the rest stay`() {
        val current = me.rerere.rikkihub.data.ai.tavern.TavernConversationState.NoteFields(
            prompt = "old", interval = 2, depth = 4, position = 1, role = me.rerere.tavern.core.PromptRole.USER,
        )
        val next = mergedNoteFields(current, buildJsonObject { put("prompt", "new"); put("depth", 0) })
        assertEquals("new", next.prompt)
        assertEquals(0, next.depth)
        assertEquals(2, next.interval)
        assertEquals(me.rerere.tavern.core.PromptRole.USER, next.role)
        assertEquals(me.rerere.tavern.core.PromptRole.ASSISTANT, mergedNoteFields(current, buildJsonObject { put("role", 2) }).role)
        expectBadRequest { mergedNoteFields(current, buildJsonObject { put("role", 7) }) }
        expectBadRequest { mergedNoteFields(current, buildJsonObject { put("depth", -1) }) }
        expectBadRequest { mergedNoteFields(current, buildJsonObject { put("position", 3) }) }
        expectBadRequest { mergedNoteFields(current, buildJsonObject { put("prompt", 1) }) }
    }
}
