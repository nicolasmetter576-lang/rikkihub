package me.rerere.rikkihub.data.creator

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPreset
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ToolIds
import me.rerere.rikkihub.data.creator.runtime.CreatorCommandDecoder
import me.rerere.rikkihub.data.creator.runtime.CreatorDecodeContext
import me.rerere.rikkihub.data.creator.runtime.CreatorManagementBridge
import me.rerere.rikkihub.data.creator.runtime.CreatorProposalReminderTransformer
import me.rerere.rikkihub.data.creator.runtime.CreatorSchemaCatalog
import me.rerere.rikkihub.data.creator.runtime.CreatorToolSurface
import me.rerere.rikkihub.data.creator.session.CreatorOperation
import me.rerere.rikkihub.data.creator.storage.CreatorConversationRow
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.management.ManagementFixture
import me.rerere.rikkihub.data.management.managementFixture
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import me.rerere.rikkihub.data.model.SystemPromptMode
import me.rerere.rikkihub.data.model.composedSystemPrompt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The creator tools end to end on the JVM (real Settings DataStore, management chain and skill
 * directory): what a model reads can be written back unchanged, one call becomes one combined
 * proposal, calls are all-or-nothing, and rejections say what to do next.
 */
class CreatorToolsIntegrationTest {

    private val conversationId = Uuid.random()

    private class Tools(val f: ManagementFixture, val byName: Map<String, me.rerere.ai.core.Tool>, val surface: CreatorToolSurface)

    private fun tools(f: ManagementFixture): Tools {
        val read = CreatorReadDomain(
            json = JsonInstant,
            settingsProvider = { f.settings.settingsSnapshot() },
            assistantRegistry = AssistantRegistry(),
            managementRead = { f.runtime.readPort },
            conversationLister = { filter, offset, limit ->
                f.room.conversations.values.filter { it.title.contains(filter) }.drop(offset).take(limit).map {
                    CreatorConversationRow(it.id.toString(), it.assistantId.toString(), it.title, 0, it.isPinned, it.deletedAt != null)
                }
            },
        )
        val surface = CreatorToolSurface(read, CreatorManagementBridge({ f.runtime }, JsonInstant))
        return Tools(f, surface.tools(conversationId).associateBy { it.name }, surface)
    }

    private fun test(block: suspend Tools.() -> Unit) = managementFixture { f -> tools(f).block() }

    private suspend fun Tools.call(tool: String, args: JsonElement): JsonElement {
        val parts = byName.getValue(tool).execute(args)
        return JsonInstant.parseToJsonElement((parts.single() as UIMessagePart.Text).text)
    }

    private suspend fun Tools.call(tool: String, args: String) = call(tool, JsonInstant.parseToJsonElement(args))

    private suspend fun Tools.preview(vararg operations: String) =
        call(ToolIds.PREVIEW_CHANGE, """{"operations":[${operations.joinToString(",")}]}""").jsonObject

    private suspend fun Tools.read(kind: String, id: String) =
        call(ToolIds.READ_OBJECT, buildJsonObject { put("kind", kind); put("id", id) }).jsonObject

    private fun JsonObject.proposals() = getValue("proposals").jsonArray.map { it.jsonObject }
    private fun JsonObject.rejected() = getValue("rejected").jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun Tools.approveOnly(result: JsonObject) {
        assertTrue(result.toString(), result.rejected().isEmpty())
        result.proposals().forEach { assertEquals(ProposalStatus.APPLIED, f.approve(it.str("proposalId")).status) }
    }

    private suspend fun Tools.ordinaryAssistant() =
        f.settings.settingsSnapshot().assistants.first { !CreatorAssistantDefinition.isCreator(it.id) }

    // ------------------------------------------------------------------ read = write shape

    @Test(timeout = 60_000) fun readContentPatchesBackUnchangedForSettingsKinds() = test {
        val book = Lorebook(name = "World", entries = listOf(me.rerere.rikkihub.data.model.PromptInjection.RegexInjection(
            name = "Capital", keywords = listOf("capital"), content = "Aurum")))
        val quick = QuickMessage(title = "Hi", content = "Hello")
        f.settings.update { it.copy(lorebooks = listOf(book), quickMessages = listOf(quick)) }
        val assistant = ordinaryAssistant()

        // The model echoes the whole read content with one change: only the change is proposed.
        fun JsonObject.with(key: String, value: JsonElement) = JsonObject(getValue("contentJson").jsonObject + (key to value))
        val ops = listOf(
            Triple("ASSISTANT", assistant.id.toString(), "name" to JsonPrimitive("Renamed")),
            Triple("LOREBOOK", book.id.toString(), "description" to JsonPrimitive("Setting")),
            Triple("QUICK_MESSAGE", quick.id.toString(), "content" to JsonPrimitive("Hello there")),
        ).map { (kind, id, change) ->
            val fields = read(kind, id).with(change.first, change.second)
            buildJsonObject {
                put("kind", kind); put("command", "Patch"); put("targetId", id)
                put("params", buildJsonObject { put("fields", fields) })
            }.toString()
        }
        val result = preview(*ops.toTypedArray())
        val proposal = result.proposals().single()
        assertEquals(listOf(0, 1, 2), proposal.getValue("operations").jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("one diff line per real change", 3, proposal.getValue("diff").jsonArray.size)
        approveOnly(result)
        val after = f.settings.settingsSnapshot()
        assertEquals("Renamed", after.assistants.first { it.id == assistant.id }.name)
        assertEquals("Setting", after.lorebooks.single().description)
        assertEquals(book.entries.single().id, after.lorebooks.single().entries.single().id)
        assertEquals("Hello there", after.quickMessages.single().content)
    }

    @Test(timeout = 60_000) fun oneCallCreatesBindsAndPatchesInOneProposal() = test {
        val assistant = ordinaryAssistant()
        val result = preview(
            """{"kind":"LOREBOOK","command":"Create","params":{"fields":{"name":"Aurora","entries":[{"name":"Dragon","keywords":["dragon"],"content":"Extinct."}]}}}""",
            """{"kind":"LOREBOOK","command":"Bind","targetId":"${'$'}0","params":{"assistantId":"${assistant.id}"}}""",
            """{"kind":"ASSISTANT","command":"Patch","targetId":"${assistant.id}","params":{"fields":{"name":"Storyteller"}}}""",
            """{"kind":"ASSISTANT","command":"Patch","targetId":"${assistant.id}","params":{"fields":{"systemPrompt":"Tell stories."}}}""",
            """{"kind":"ASSISTANT_TAG","command":"Create","params":{"fields":{"name":"Fiction"}}}""",
            """{"kind":"ASSISTANT","command":"Patch","targetId":"${assistant.id}","params":{"fields":{"tags":["${'$'}4"]}}}""",
        )
        val proposal = result.proposals().single()
        val created = proposal.getValue("createdIds").jsonObject
        val bookId = created.getValue("\$0").jsonPrimitive.content
        val tagId = created.getValue("\$4").jsonPrimitive.content
        approveOnly(result)
        val after = f.settings.settingsSnapshot()
        val patched = after.assistants.first { it.id == assistant.id }
        assertEquals("Storyteller", patched.name)
        assertEquals("Tell stories.", patched.systemPrompt)
        assertTrue(patched.lorebookIds.map { it.toString() }.contains(bookId))
        assertEquals(listOf(tagId), patched.tags.map { it.toString() })
        assertEquals("Aurora", after.lorebooks.single { it.id.toString() == bookId }.name)
    }

    @Test(timeout = 60_000) fun promptSectionsAreWritableAndKeepTheirIds() = test {
        val assistant = ordinaryAssistant()
        val created = preview(
            """{"kind":"ASSISTANT","command":"Patch","targetId":"${assistant.id}","params":{"fields":{"systemPromptMode":"SECTIONS","promptSections":[{"title":"Role","content":"You are A."},{"title":"Off","content":"x","enabled":false}]}}}""",
        )
        approveOnly(created)
        val first = f.settings.settingsSnapshot().assistants.first { it.id == assistant.id }
        assertEquals(SystemPromptMode.SECTIONS, first.systemPromptMode)
        assertEquals(2, first.promptSections.map { it.id }.distinct().size)
        assertEquals("You are A.", first.composedSystemPrompt())

        // read shows the fields in the shape Patch accepts, and points at promptSections
        val read = read("ASSISTANT", assistant.id.toString())
        assertTrue(read.toString(), read.str("hint").contains("promptSections"))
        val sections = read.getValue("contentJson").jsonObject.getValue("promptSections").jsonArray
        // echo existing ids (reordered, second enabled) plus one new section without id
        val edited = buildJsonArray {
            add(JsonObject(sections[1].jsonObject + ("enabled" to JsonPrimitive(true))))
            add(sections[0])
            add(buildJsonObject { put("title", "New"); put("content", "Be brief.") })
        }
        approveOnly(preview(buildJsonObject {
            put("kind", "ASSISTANT"); put("command", "Patch"); put("targetId", assistant.id.toString())
            put("params", buildJsonObject { put("fields", buildJsonObject { put("promptSections", edited) }) })
        }.toString()))
        val second = f.settings.settingsSnapshot().assistants.first { it.id == assistant.id }
        assertEquals(listOf(first.promptSections[1].id, first.promptSections[0].id), second.promptSections.take(2).map { it.id })
        assertEquals("x\n\nYou are A.\n\nBe brief.", second.composedSystemPrompt())

        val unknownId = preview(
            """{"kind":"ASSISTANT","command":"Patch","targetId":"${assistant.id}","params":{"fields":{"promptSections":[{"id":"${Uuid.random()}","content":"y"}]}}}""",
        )
        assertTrue(unknownId.toString(), unknownId.rejected().single().str("detail").contains("omit id"))
        val tooLong = preview(buildJsonObject {
            put("kind", "ASSISTANT"); put("command", "Patch"); put("targetId", assistant.id.toString())
            put("params", buildJsonObject { put("fields", buildJsonObject { put("promptSections", buildJsonArray {
                repeat(2) { add(buildJsonObject { put("content", "z".repeat(40_000)) }) }
            }) }) })
        }.toString())
        assertTrue(tooLong.toString(), tooLong.rejected().single().str("detail").contains("65536 characters in total"))

        // whole and sections are one prompt: systemPrompt is the join, and patching it edits the matching section
        assertEquals(second.composedSystemPrompt(), second.systemPrompt)
        approveOnly(preview(buildJsonObject {
            put("kind", "ASSISTANT"); put("command", "Patch"); put("targetId", assistant.id.toString())
            put("params", buildJsonObject { put("fields", buildJsonObject { put("systemPrompt", "x\n\nYou are B.\n\nBe brief.") }) })
        }.toString()))
        val third = f.settings.settingsSnapshot().assistants.first { it.id == assistant.id }
        assertEquals(second.promptSections.map { it.id }, third.promptSections.map { it.id })
        assertEquals(listOf("x", "You are B.", "Be brief."), third.promptSections.map { it.content })
        assertEquals("x\n\nYou are B.\n\nBe brief.", third.systemPrompt)
    }

    @Test(timeout = 60_000) fun aCallIsAllOrNothingAndRejectionsCarryHints() = test {
        val result = preview(
            """{"kind":"QUICK_MESSAGE","command":"Create","params":{"fields":{"title":"ok","content":"fine"}}}""",
            """{"kind":"MODE_INJECTION","command":"Create","params":{"fields":{"name":"x","colour":"red"}}}""",
        )
        assertTrue(result.proposals().isEmpty())
        val rejection = result.rejected().single()
        assertEquals(1, rejection.str("index").toInt())
        assertEquals("FORBIDDEN_FIELD", rejection.str("code"))
        assertTrue(rejection.str("detail"), rejection.str("detail").contains("writable fields"))
        assertTrue(rejection.str("hint").contains("creator_describe"))
        assertTrue("nothing stored", f.runtime.list().isEmpty())
        assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
    }

    @Test(timeout = 60_000) fun enumValuesAndCommandNamesAreCaseInsensitive() = test {
        val result = preview(
            """{"kind":"mode_injection","command":"create","params":{"fields":{"name":"Brief","content":"Be brief.","position":"AFTER_SYSTEM_PROMPT","role":"USER"}}}""",
        )
        // The diff shows what will be stored, not the spelling the model used.
        val diff = result.proposals().single().getValue("diff").toString()
        assertTrue(diff, diff.contains("after_system_prompt") && diff.contains("\\\"user\\\""))
        assertFalse(diff, diff.contains("AFTER_SYSTEM_PROMPT"))
        approveOnly(result)
        // A fresh store already holds the built-in Learning Mode.
        val mode = f.settings.settingsSnapshot().modeInjections.single { it.name == "Brief" }
        // Re-sending the same values in another spelling is not a change.
        val same = preview("""{"kind":"MODE_INJECTION","command":"Patch","targetId":"${mode.id}","params":{"fields":{"role":"USER","position":"after_system_prompt"}}}""")
        assertEquals("INVALID_VALUE", same.rejected().single().str("code"))
    }

    @Test(timeout = 60_000) fun referencesMustNameAnEarlierCreate() = test {
        val assistant = ordinaryAssistant()
        val result = preview(
            """{"kind":"LOREBOOK","command":"Bind","targetId":"${'$'}1","params":{"assistantId":"${assistant.id}"}}""",
            """{"kind":"LOREBOOK","command":"Create","params":{"fields":{"name":"Later"}}}""",
        )
        assertEquals("BAD_REFERENCE", result.rejected().single().str("code"))
        val subagent = preview(
            """{"kind":"ASSISTANT","command":"Create","params":{"fields":{"name":"New"}}}""",
            """{"kind":"CONVERSATION","command":"CreateOrdinaryConversation","params":{"assistantId":"${'$'}0"}}""",
        )
        assertEquals("BAD_REFERENCE", subagent.rejected().single().str("code"))
    }

    @Test(timeout = 60_000) fun unknownIdsPointToTheListTool() = test {
        val result = preview("""{"kind":"QUICK_MESSAGE","command":"Delete","targetId":"${Uuid.random()}"}""")
        val rejection = result.rejected().single()
        assertTrue(rejection.toString(), rejection.str("hint").contains("creator_list_objects"))
    }

    // ------------------------------------------------------------------ models and defaults

    @Test(timeout = 60_000) fun modelsAndDefaultSlotsWorkWithoutProviderIds() = test {
        val m1 = Model(modelId = "a-1", displayName = "A1")
        val m2 = Model(modelId = "b-2", displayName = "B2")
        f.settings.update { s ->
            val p = s.providers.first()
            s.copy(providers = listOf(p.copyProvider(models = p.models + m1 + m2)) + s.providers.drop(1), fastModelId = m1.id)
        }
        val listed = call(ToolIds.LIST_OBJECTS, """{"kind":"MODEL_DEFINITION"}""").jsonObject.getValue("items").jsonArray
        assertTrue(listed.any { it.jsonObject.str("id") == m1.id.toString() })
        val slot = read("DEFAULT_SLOT", "FAST")
        assertEquals(m1.id.toString(), slot.getValue("contentJson").jsonObject.str("modelId"))

        val inUse = preview("""{"kind":"MODEL_DEFINITION","command":"Delete","targetId":"${m1.id}"}""")
        assertEquals("MISSING_REPLACEMENT_PLAN", inUse.rejected().single().str("code"))

        val result = preview(
            """{"kind":"DEFAULT_SLOT","command":"SetSlot","targetId":"FAST","params":{"modelId":"${m2.id}"}}""",
            """{"kind":"MODEL_DEFINITION","command":"Patch","targetId":"${m2.id}","params":{"fields":{"displayName":"B2 fast"}}}""",
            """{"kind":"MODEL_DEFINITION","command":"Delete","targetId":"${m1.id}"}""",
        )
        approveOnly(result)
        val after = f.settings.settingsSnapshot()
        assertEquals(m2.id, after.fastModelId)
        assertEquals("B2 fast", after.providers.flatMap { it.models }.single { it.id == m2.id }.displayName)
        assertFalse(after.providers.flatMap { it.models }.any { it.id == m1.id })
    }

    @Test(timeout = 60_000) fun promptSlotsReadAndWriteByName() = test {
        val prompt = read("PROMPT", "title")
        assertEquals(true, prompt.getValue("readOnly").jsonObject.getValue("isDefault").jsonPrimitive.content.toBoolean())
        approveOnly(preview("""{"kind":"PROMPT","command":"Patch","targetId":"TITLE","params":{"fields":{"text":"Short title."}}}"""))
        assertEquals("Short title.", f.settings.settingsSnapshot().titlePrompt)
        val same = preview("""{"kind":"PROMPT","command":"SetPrompt","targetId":"TITLE","params":{"text":"Short title."}}""")
        assertEquals("INVALID_VALUE", same.rejected().single().str("code"))
    }

    // ------------------------------------------------------------------ other stores

    @Test(timeout = 60_000) fun skillsAreListedReadAndPatchedByName() = test {
        val dir = File(f.filesRoot, "skills/notes").also { it.mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: notes\ndescription: Take notes\n---\nBody\n")
        File(dir, "ref").mkdirs(); File(dir, "ref/guide.md").writeText("guide")
        val items = call(ToolIds.LIST_OBJECTS, """{"kind":"SKILL"}""").jsonObject.getValue("items").jsonArray
        assertEquals("notes", items.single().jsonObject.str("id"))
        val skill = read("SKILL", "notes")
        assertTrue(skill.getValue("contentJson").jsonObject.str("content").contains("Take notes"))
        assertEquals("guide", read("SKILL", "notes/ref/guide.md").getValue("contentJson").jsonObject.str("content"))
        approveOnly(preview(
            """{"kind":"SKILL","command":"Patch","targetId":"notes","params":{"path":"ref/guide.md","newContent":"better guide"}}""",
        ))
        assertEquals("better guide", File(dir, "ref/guide.md").readText())
        val twice = preview(
            """{"kind":"SKILL","command":"PatchTextFile","targetId":"notes","params":{"path":"SKILL.md","newContent":"---\nname: notes\ndescription: a\n---\n"}}""",
            """{"kind":"SKILL","command":"PatchTextFile","targetId":"notes","params":{"path":"SKILL.md","newContent":"---\nname: notes\ndescription: b\n---\n"}}""",
        )
        assertEquals("SAME_TARGET", twice.rejected().single().str("code"))
    }

    @Test(timeout = 60_000) fun conversationsAreListedReadAndPatched() = test {
        val assistant = ordinaryAssistant()
        val id = Uuid.random()
        val user = UIMessage.user("Plan a trip")
        f.room.conversations[id] = Conversation(id = id, assistantId = assistant.id, title = "Trip",
            messageNodes = listOf(MessageNode(messages = listOf(user))))
        val items = call(ToolIds.LIST_OBJECTS, """{"kind":"CONVERSATION"}""").jsonObject.getValue("items").jsonArray
        assertEquals(id.toString(), items.single().jsonObject.str("id"))
        val read = read("CONVERSATION", id.toString())
        val nodes = read.getValue("readOnly").jsonObject.getValue("nodes").jsonArray
        assertEquals("Plan a trip", nodes.single().jsonObject.getValue("candidates").jsonArray.single().jsonObject.str("text"))
        approveOnly(preview("""{"kind":"CONVERSATION","command":"Patch","targetId":"$id","params":{"fields":{"title":"Japan trip"}}}"""))
        assertEquals("Japan trip", f.room.conversations.getValue(id).title)
    }

    @Test(timeout = 60_000) fun libraryPresetsAreReadAndPatchedWithGenericCommands() = test {
        val preset = WorkspaceSubagentPreset(key = "reviewer", name = "Reviewer", description = "Reviews", systemPrompt = "Review.")
        f.room.libraryPresets += preset
        val title = call(ToolIds.LIST_OBJECTS, """{"kind":"SUBAGENT_PRESET"}""").jsonObject
            .getValue("items").jsonArray.single().jsonObject.str("title")
        assertTrue(title, title.startsWith("[library]"))
        val content = read("SUBAGENT_PRESET", preset.id).getValue("contentJson").jsonObject
        assertEquals("reviewer", content.str("key"))
        // Echoing the read content (including the unchanged key) as a generic Patch works.
        val fields = JsonObject(content + ("maxSteps" to JsonPrimitive(40)))
        val result = preview(buildJsonObject {
            put("kind", "SUBAGENT_PRESET"); put("command", "Patch"); put("targetId", preset.id)
            put("params", buildJsonObject { put("fields", fields) })
        }.toString())
        assertTrue(result.toString(), result.rejected().isEmpty())
        assertEquals("SUBAGENT_PRESET", result.proposals().single().str("domain"))
    }

    // ------------------------------------------------------------------ status feedback

    @Test(timeout = 60_000) fun statusListsProposalsAndTheReminderReportsResults() = test {
        val before = kotlin.time.Clock.System.now()
        val result = preview("""{"kind":"QUICK_MESSAGE","command":"Create","params":{"fields":{"title":"t","content":"c"}}}""")
        val proposalId = result.proposals().single().str("proposalId")
        val listed = call(ToolIds.GET_CHANGE_STATUS, "{}").jsonArray
        assertEquals(proposalId, listed.single().jsonObject.str("proposalId"))
        approveOnly(result)

        val tz = TimeZone.currentSystemDefault()
        val first = UIMessage.user("make a quick message").copy(createdAt = before.minus(1.seconds).toLocalDateTime(tz))
        val reply = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Proposed.")))
        val second = UIMessage.user("done?").copy(createdAt = kotlin.time.Clock.System.now().plus(1.seconds).toLocalDateTime(tz))
        val reminder = surface.proposalReminder(conversationId) as CreatorProposalReminderTransformer
        val out = reminder.apply(listOf(first, reply, second))
        assertEquals(4, out.size)
        val note = out[2]
        assertTrue(note.isSynthetic)
        val text = (note.parts.single() as UIMessagePart.Text).text
        assertTrue(text, text.contains(proposalId) && text.contains("APPLIED"))
        // Deterministic: the same history yields the same notes on the next request.
        assertEquals(out.map { it.parts }, reminder.apply(listOf(first, reply, second)).map { it.parts })
    }

    // ------------------------------------------------------------------ describe

    @Test fun everyDescribeExampleDecodes() {
        val ctx = object : CreatorDecodeContext {
            override fun providerOfModel(modelId: String) = "00000000-0000-0000-0000-000000000001"
            override fun workspaceOfLocalPreset(presetId: String) = "00000000-0000-0000-0000-000000000002"
        }
        CreatorSchemaCatalog.allExamples().forEach { (kind, command) ->
            val op = JsonInstant.decodeFromJsonElement(CreatorOperation.serializer(), CreatorSchemaCatalog.exampleJson(command.example))
            assertEquals(command.example, kind.name, op.kind)
            assertNotNull(command.example, runCatching { CreatorCommandDecoder.decode(op, ctx) }.getOrElse {
                throw AssertionError("${kind.name} ${command.name}: ${it.message}", it)
            })
        }
    }

    @Test fun legacyDefaultPromptIsUpgradedButEditedOnesAreKept() {
        val legacy = CreatorAssistantDefinition.LEGACY_SYSTEM_PROMPTS
        assertEquals(3, legacy.distinct().size)
        assertFalse(CreatorAssistantDefinition.SYSTEM_PROMPT in legacy)
        // 2.9.0 – 2.21.x still listed the COMPRESS slot; 2.22.0 – 2.27.0 had read-only providers.
        assertTrue(legacy[1].contains("OCR, COMPRESS)"))
        assertTrue(legacy[2].contains("Read-only references for looking up ids: providers [PROVIDER]"))
        legacy.forEach { assertEquals(CreatorAssistantDefinition.SYSTEM_PROMPT, CreatorAssistantDefinition.upgradedSystemPrompt(it)) }
        assertEquals("my own", CreatorAssistantDefinition.upgradedSystemPrompt("my own"))
    }

    @Test(timeout = 60_000) fun settingsLoadUpgradesAnUneditedCreatorPrompt() = test {
        val legacy = CreatorAssistantDefinition.LEGACY_SYSTEM_PROMPTS.last()
        f.settings.update { s -> s.copy(assistants = s.assistants.map {
            if (CreatorAssistantDefinition.isCreator(it.id)) it.copy(systemPrompt = legacy) else it }) }
        val creator = f.settings.settingsSnapshot().assistants.single { CreatorAssistantDefinition.isCreator(it.id) }
        assertEquals(CreatorAssistantDefinition.SYSTEM_PROMPT, creator.systemPrompt)
    }
}
