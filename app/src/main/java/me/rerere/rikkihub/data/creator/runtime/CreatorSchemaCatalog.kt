package me.rerere.rikkihub.data.creator.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkihub.data.ai.tools.SUBAGENT_MAX_FIELD_CHARS
import me.rerere.rikkihub.data.ai.tools.SUBAGENT_MAX_STEPS
import me.rerere.rikkihub.data.ai.tools.WorkspaceSubagentPresets
import me.rerere.rikkihub.data.creator.session.CreatorObjectKind
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.adapters.skills.SkillCommand
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetDraft
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetPatch
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.PromptInjection
import me.rerere.rikkihub.data.model.QuickMessage
import me.rerere.rikkihub.data.model.Tag
import me.rerere.rikkihub.data.mutation.ConversationMutationCommand
import kotlin.uuid.Uuid

/**
 * What `creator_describe` returns: for one kind, what it is in the app, how it relates to other
 * kinds, its writable fields (types, enum values and defaults generated from the same serializers
 * and whitelists the management chain validates with), every command with its params and a
 * ready-to-copy example, and the pitfalls. Examples are checked by unit tests to decode.
 */
object CreatorSchemaCatalog {

    data class Command(val name: String, val params: String, val example: String, val note: String? = null)

    private class Doc(
        val summary: String,
        val fields: List<String> = emptyList(),
        val commands: List<Command>,
        val notes: List<String> = emptyList(),
    )

    fun describe(kind: CreatorObjectKind, command: String? = null): JsonObject {
        val doc = docs.getValue(kind)
        val commands = if (command == null) doc.commands
            else doc.commands.filter { it.name.equals(command, ignoreCase = true) }.ifEmpty { doc.commands }
        return buildJsonObject {
            put("kind", kind.name)
            put("summary", doc.summary)
            if (doc.fields.isNotEmpty()) put("fields", JsonArray(doc.fields.map(::JsonPrimitive)))
            if (commands.isNotEmpty()) put("commands", JsonArray(commands.map { c ->
                buildJsonObject {
                    put("command", c.name)
                    put("params", c.params)
                    put("example", exampleJson(c.example))
                    c.note?.let { put("note", it) }
                }
            }))
            if (doc.notes.isNotEmpty()) put("notes", JsonArray(doc.notes.map(::JsonPrimitive)))
        }
    }

    /** Every example operation, for tests. */
    fun allExamples(): List<Pair<CreatorObjectKind, Command>> =
        docs.flatMap { (kind, doc) -> doc.commands.map { kind to it } }

    fun exampleJson(example: String): JsonElement = SettingsCollectionInterpreter.strictJson.parseToJsonElement(example)

    // ------------------------------------------------------------------ field lists

    private val strict = SettingsCollectionInterpreter.strictJson

    /**
     * "name: type = default — note" for each whitelisted field, in declaration order. Types come
     * from the serializer descriptor (enum values are the exact stored strings), defaults from a
     * default-constructed object.
     */
    private fun <T> fieldLines(
        serializer: KSerializer<T>, defaults: T, allowed: Set<String>,
        notes: Map<String, String> = emptyMap(), typeOverrides: Map<String, String> = emptyMap(),
    ): List<String> {
        val d = serializer.descriptor
        val defaultJson = strict.encodeToJsonElement(serializer, defaults).jsonObject
        return (0 until d.elementsCount).map { d.getElementName(it) }.filter { it in allowed }.map { name ->
            val type = typeOverrides[name] ?: typeOf(d.getElementDescriptor(d.getElementIndex(name)), 0)
            val default = defaultJson[name]?.toString()?.takeIf { it.length <= 48 && name != "id" }
            buildString {
                append(name).append(": ").append(type)
                if (default != null) append(" = ").append(default)
                notes[name]?.let { append(" — ").append(it) }
            }
        }
    }

    private fun typeOf(d: SerialDescriptor, depth: Int): String {
        if (depth >= 2 && (d.kind == StructureKind.CLASS || d.kind == PolymorphicKind.SEALED) &&
            !d.serialName.startsWith("kotlin.uuid.Uuid")) return "object"
        return when (d.kind) {
            StructureKind.LIST -> {
                val base = "array<${typeOf(d.getElementDescriptor(0), depth + 1)}>"
                if (d.isNullable) "$base|null" else base
            }
            StructureKind.CLASS -> if (d.serialName.startsWith("kotlin.uuid.Uuid")) {
                if (d.isNullable) "string(id)|null" else "string(id)"
            } else {
                val inner = (0 until d.elementsCount).joinToString(", ", "{", "}") { i ->
                    d.getElementName(i) + (if (d.isElementOptional(i)) "?" else "") + ": " + typeOf(d.getElementDescriptor(i), depth + 1)
                }
                if (d.isNullable) "$inner|null" else inner
            }
            else -> CreatorCommandDecoder.typeName(d)
        }
    }

    private val TEXT_PART = "text"

    private val assistantFields = fieldLines(Assistant.serializer(), Assistant(),
        SettingsCollectionInterpreter.assistantFields,
        notes = mapOf(
            "name" to "shown in the assistant list",
            "systemPrompt" to "the persona / role prompt (人设); at most 65536 characters. When promptSections is non-empty " +
                "this is their joined text, and patching it edits the matching sections instead",
            "systemPromptMode" to "which editor view the app opens (whole / sections); does not change what is sent",
            "promptSections" to "optional split of systemPrompt: enabled, non-blank sections joined in list order with a blank line " +
                "become systemPrompt (written back for you); title is only a label and is not sent; omit id for new sections, " +
                "keep id for existing ones; replaces the whole list; [] removes the split (send systemPrompt too, or the prompt " +
                "becomes empty); if both fields are sent, promptSections wins; at most 64, content at most 65536 characters in total",
            "chatModelId" to "MODEL_DEFINITION id of a CHAT model; null = the default chat model",
            "temperature" to "null = provider default; 0..2",
            "topP" to "null = provider default; 0..1",
            "maxTokens" to "null = provider default; 1..1000000",
            "contextMessageLimit" to "0 = no limit",
            "messageTemplate" to "wraps each user message; must keep {{ message }}",
            "presetMessages" to "opening messages shown at the start of every new chat (开场白)",
            "tags" to "ASSISTANT_TAG ids; replaces the whole list",
            "lorebookIds" to "LOREBOOK ids bound to this assistant; replaces the whole list (Bind/Unbind on LOREBOOK adds/removes one)",
            "modeInjectionIds" to "MODE_INJECTION ids; replaces the whole list",
            "quickMessageIds" to "QUICK_MESSAGE ids shown above the input box; replaces the whole list",
            "mcpServers" to "MCP_SERVER ids enabled for this assistant",
            "enabledSkills" to "SKILL names (not ids) the assistant may load",
            "localTools" to "device tools; each item is an object with only a type",
            "workspaceId" to "WORKSPACE id or null",
            "avatar" to "Emoji: {\"type\":\"${avatarType("Emoji")}\",\"content\":\"🦊\"}; images cannot be set here",
            "background" to "chat background; cannot be set to a new image here, only cleared with null",
            "regexes" to "find/replace rules on messages; omit id for new rules, keep id for existing ones",
            "enableTimeReminder" to "inject a time note when the user returns after a gap",
        ),
        typeOverrides = mapOf(
            "presetMessages" to "array<{role: \"user\"|\"assistant\"|\"system\", parts: [{type: \"$TEXT_PART\", text: string}]}>",
        ),
    )

    private fun avatarType(name: String): String {
        val d = me.rerere.rikkihub.data.model.Avatar.serializer().descriptor.getElementDescriptor(1)
        return (0 until d.elementsCount).map { d.getElementName(it) }.first { it.endsWith(name) }
    }

    private val docs: Map<CreatorObjectKind, Doc> = mapOf(
        CreatorObjectKind.ASSISTANT to Doc(
            summary = "An assistant (助手): a persona the user chats with — name, system prompt, model, parameters, " +
                "and which lorebooks, quick messages, mode injections, tags, MCP servers, skills and tools it uses. " +
                "The creator assistant (you) is not listed and cannot be changed.",
            fields = assistantFields,
            commands = listOf(
                Command("Create", "{fields: {<field>: value, ...}} — unspecified fields take their defaults",
                    """{"kind":"ASSISTANT","command":"Create","params":{"fields":{"name":"English Tutor","systemPrompt":"You are a patient English tutor. Correct my mistakes gently.","temperature":0.7,"presetMessages":[{"role":"assistant","parts":[{"type":"$TEXT_PART","text":"Hi! What would you like to practise today?"}]}]}}}"""),
                Command("Patch", "targetId + {fields: {only the fields you change}}",
                    """{"kind":"ASSISTANT","command":"Patch","targetId":"<assistant id>","params":{"fields":{"systemPrompt":"New persona text","lorebookIds":["<lorebook id>"]}}}"""),
                Command("Clone", "targetId + {name}", """{"kind":"ASSISTANT","command":"Clone","targetId":"<assistant id>","params":{"name":"Tutor (copy)"}}"""),
                Command("Delete", "targetId", """{"kind":"ASSISTANT","command":"Delete","targetId":"<assistant id>"}""",
                    note = "Deleting an assistant does not delete its conversations."),
                Command("Reorder", "{orderedIds: every assistant id exactly once}",
                    """{"kind":"ASSISTANT","command":"Reorder","params":{"orderedIds":["<id 1>","<id 2>"]}}"""),
            ),
            notes = listOf(
                "Read the assistant first; creator_read_object returns exactly these fields.",
                "List fields replace the whole list: to add one lorebook, send the old ids plus the new one (or use LOREBOOK Bind).",
                "Tags are separate objects: create them with ASSISTANT_TAG Create, then put their ids in tags.",
            ),
        ),
        CreatorObjectKind.ASSISTANT_TAG to Doc(
            summary = "A tag (标签) used to group assistants in the assistant list. Assistants reference tags by id in their tags field.",
            fields = fieldLines(Tag.serializer(), Tag(Uuid.parse("00000000-0000-0000-0000-000000000000"), ""), setOf("name")),
            commands = listOf(
                Command("Create", "{fields: {name}}", """{"kind":"ASSISTANT_TAG","command":"Create","params":{"fields":{"name":"Work"}}}"""),
                Command("Patch", "targetId + {fields: {name}}", """{"kind":"ASSISTANT_TAG","command":"Patch","targetId":"<tag id>","params":{"fields":{"name":"Office"}}}"""),
                Command("Delete", "targetId — also removes it from every assistant", """{"kind":"ASSISTANT_TAG","command":"Delete","targetId":"<tag id>"}"""),
                Command("Reorder", "{orderedIds}", """{"kind":"ASSISTANT_TAG","command":"Reorder","params":{"orderedIds":["<tag id 1>","<tag id 2>"]}}"""),
            ),
        ),
        CreatorObjectKind.QUICK_MESSAGE to Doc(
            summary = "A quick message (快捷消息): a saved prompt the user can insert with one tap. " +
                "It appears for an assistant only after it is bound to that assistant (Bind).",
            fields = fieldLines(QuickMessage.serializer(), QuickMessage(), setOf("title", "content"),
                notes = mapOf("title" to "button label, at most 1024 characters", "content" to "text inserted into the input box")),
            commands = settingsCommands("QUICK_MESSAGE", "quick message",
                """{"title":"Summarize","content":"Summarize the text above in three bullet points."}""", """{"content":"Summarize in five bullet points."}"""),
            notes = listOf("Create, then Bind to an assistant in the same call: Bind targetId \"\$0\" refers to the Create at index 0."),
        ),
        CreatorObjectKind.MODE_INJECTION to Doc(
            summary = "A mode injection (模式注入): a fixed piece of prompt inserted at a chosen position of every request " +
                "of the assistants (or conversations) it is bound to, e.g. a writing style or output format.",
            fields = fieldLines(PromptInjection.ModeInjection.serializer(), PromptInjection.ModeInjection(),
                SettingsCollectionInterpreter.modeFields,
                notes = mapOf(
                    "position" to "where the text goes; at_depth uses injectDepth",
                    "injectDepth" to "for at_depth: messages counted back from the newest, 0..1000",
                    "role" to "speaker of the injected text: user or assistant",
                    "priority" to "higher goes first when several inject at the same position",
                )),
            commands = settingsCommands("MODE_INJECTION", "mode injection",
                """{"name":"Concise mode","content":"Answer in at most three sentences.","position":"after_system_prompt"}""",
                """{"enabled":false}"""),
        ),
        CreatorObjectKind.LOREBOOK to Doc(
            summary = "A lorebook (世界书): a set of entries; an entry is injected into the prompt when one of its keywords " +
                "appears in recent messages (or always, if constantActive). Bound to assistants (Bind) or single conversations.",
            fields = fieldLines(Lorebook.serializer(), Lorebook(), SettingsCollectionInterpreter.lorebookFields,
                notes = mapOf("entries" to "the WHOLE entry list (see notes)")) + listOf(
                "entries[] item: " + fieldLines(PromptInjection.RegexInjection.serializer(), PromptInjection.RegexInjection(),
                    setOf("id", "name", "enabled", "priority", "position", "content", "injectDepth", "role", "keywords",
                        "useRegex", "caseSensitive", "scanDepth", "constantActive"),
                    notes = mapOf(
                        "id" to "keep it for existing entries, omit it for new ones",
                        "keywords" to "trigger words; any match activates the entry",
                        "scanDepth" to "how many recent messages are scanned",
                        "constantActive" to "true = always injected, no keyword needed",
                    )).joinToString("; "),
            ),
            commands = settingsCommands("LOREBOOK", "lorebook",
                """{"name":"Aurora world","description":"Setting notes for the Aurora story","entries":[{"name":"Capital","keywords":["capital","Aurum"],"content":"Aurum is the floating capital of the Aurora empire."}]}""",
                """{"entries":[{"id":"<existing entry id>","name":"Capital","keywords":["capital","Aurum"],"content":"Updated text"},{"name":"New entry","keywords":["dragon"],"content":"Dragons are extinct."}]}"""),
            notes = listOf(
                "Patch entries REPLACES the whole list: send every entry to keep (with its id) plus new ones (without id); an entry left out is deleted.",
                "Entry ids are assigned by the app; never invent one.",
            ),
        ),
        CreatorObjectKind.MODEL_DEFINITION to Doc(
            summary = "A model entry (模型) under a provider (提供商): which model name to call and what it can do. " +
                "The provider (address, API key) is kind PROVIDER.",
            fields = fieldLines(Model.serializer(), Model(), SettingsCollectionInterpreter.modelFields,
                notes = mapOf(
                    "modelId" to "the provider's model name, e.g. gpt-4o-mini (NOT the id field)",
                    "displayName" to "name shown in the app",
                    "type" to "CHAT for chat models, IMAGE for image generation",
                    "abilities" to "TOOL = supports tool calls, REASONING = thinking model",
                    "tools" to "provider built-in tools; leave as is unless asked",
                    "ccDisguise" to "Claude Code disguise switch; leave as is unless asked",
                    "contextLength" to "context window in tokens (default 1000000); only used to show how full the context is",
                )),
            commands = listOf(
                Command("Create", "{providerId: PROVIDER id, fields: {...}}",
                    """{"kind":"MODEL_DEFINITION","command":"Create","params":{"providerId":"<provider id>","fields":{"modelId":"gpt-4o-mini","displayName":"GPT-4o mini","type":"CHAT","inputModalities":["TEXT","IMAGE"],"abilities":["TOOL"]}}}"""),
                Command("Patch", "targetId (model id) + {fields} — providerId is looked up for you",
                    """{"kind":"MODEL_DEFINITION","command":"Patch","targetId":"<model id>","params":{"fields":{"displayName":"GPT-4o mini (fast)"}}}"""),
                Command("Clone", "targetId + {name}", """{"kind":"MODEL_DEFINITION","command":"Clone","targetId":"<model id>","params":{"name":"Copy"}}"""),
                Command("Delete", "targetId", """{"kind":"MODEL_DEFINITION","command":"Delete","targetId":"<model id>"}""",
                    note = "Refused while a default slot or an assistant still uses the model: in the same call, move them first."),
                Command("Reorder", "{providerId, orderedIds: every model id of that provider}",
                    """{"kind":"MODEL_DEFINITION","command":"Reorder","params":{"providerId":"<provider id>","orderedIds":["<model id 1>","<model id 2>"]}}"""),
            ),
            notes = listOf("Model ids (the id field) are what DEFAULT_SLOT and assistants' chatModelId refer to."),
        ),
        CreatorObjectKind.DEFAULT_SLOT to Doc(
            summary = "Default models (默认模型): CHAT (new chats), FAST (titles, suggestions), TRANSLATE, IMAGE_GENERATION, " +
                "OCR (needs image input). Each slot points to a MODEL_DEFINITION id. Context compression always uses " +
                "the conversation's own chat model.",
            fields = listOf("modelId: string(id) — MODEL_DEFINITION id; IMAGE_GENERATION needs an IMAGE model, the others a CHAT model"),
            commands = listOf(
                Command("SetSlot", "targetId (slot name) + {modelId}",
                    """{"kind":"DEFAULT_SLOT","command":"SetSlot","targetId":"FAST","params":{"modelId":"<model id>"}}"""),
            ),
        ),
        CreatorObjectKind.PROMPT to Doc(
            summary = "Default prompts (默认提示词) the app uses for TITLE generation, TRANSLATE, SUGGESTION (follow-up suggestions), " +
                "and OCR. Read one to see its placeholders before changing it.",
            fields = listOf("text: string — at most 65536 characters; keep the placeholders the current text uses"),
            commands = listOf(
                Command("SetPrompt", "targetId (slot) + {text}",
                    """{"kind":"PROMPT","command":"SetPrompt","targetId":"TITLE","params":{"text":"Write a 3-6 word title for this chat."}}"""),
                Command("ResetPrompt", "targetId (slot) — restore the built-in default",
                    """{"kind":"PROMPT","command":"ResetPrompt","targetId":"TITLE"}"""),
            ),
        ),
        CreatorObjectKind.SUBAGENT_PRESET to Doc(
            summary = "A sub-agent preset (子代理预设): a named worker role (system prompt, model choice, step limit) that a " +
                "workspace's subagent tool can dispatch. Library presets are shared and bound to workspaces (scope); " +
                "workspace-local presets live in one workspace only.",
            fields = listOf("Create draft: " + fieldLines(SubagentPresetDraft.serializer(),
                SubagentPresetDraft(key = "", name = "", description = "", systemPrompt = ""),
                setOf("key", "name", "description", "systemPrompt", "enabled", "modelSelection", "modelId", "generationConfig",
                    "acceptContext", "maxSteps"),
                notes = mapOf(
                    "key" to "lowercase id used by the subagent tool: a-z, 0-9, _ or -, starts with a letter, at most " +
                        "${WorkspaceSubagentPresets.MAX_KEY_CHARS} chars, not \"default\", unique; cannot change later",
                    "name" to "1..${WorkspaceSubagentPresets.MAX_NAME_CHARS} characters",
                    "description" to "1..${WorkspaceSubagentPresets.MAX_DESCRIPTION_CHARS} characters; tells the main model when to use it",
                    "systemPrompt" to "1..$SUBAGENT_MAX_FIELD_CHARS characters",
                    "modelSelection" to "workspace_default, parent (the caller's model) or fixed (then modelId)",
                    "modelId" to "MODEL_DEFINITION id, only with modelSelection fixed",
                    "maxSteps" to "1..$SUBAGENT_MAX_STEPS",
                )).joinToString("; "),
                "Patch: " + fieldLines(SubagentPresetPatch.serializer(), SubagentPresetPatch(),
                    setOf("name", "description", "systemPrompt", "enabled", "modelSelection", "modelId", "clearModelId",
                        "generationConfig", "acceptContext", "maxSteps"),
                    notes = mapOf("clearModelId" to "true removes modelId (when leaving fixed)")).joinToString("; ")),
            commands = listOf(
                Command("CreateLibraryPreset", "{draft: {...}, workspaceIds?: [WORKSPACE ids]}",
                    """{"kind":"SUBAGENT_PRESET","command":"CreateLibraryPreset","params":{"draft":{"key":"reviewer","name":"Reviewer","description":"Reviews changed files and reports problems","systemPrompt":"You review code changes carefully and list concrete problems."},"workspaceIds":["<workspace id>"]}}"""),
                Command("PatchLibraryPreset", "targetId (preset id) + {patch: {only changed fields}}",
                    """{"kind":"SUBAGENT_PRESET","command":"PatchLibraryPreset","targetId":"<preset id>","params":{"patch":{"maxSteps":40}}}"""),
                Command("SetPresetScope", "targetId + {workspaceIds: the full set of workspaces to bind}",
                    """{"kind":"SUBAGENT_PRESET","command":"SetPresetScope","targetId":"<preset id>","params":{"workspaceIds":["<workspace id>"]}}"""),
                Command("DeleteLibraryPreset", "targetId", """{"kind":"SUBAGENT_PRESET","command":"DeleteLibraryPreset","targetId":"<preset id>"}"""),
                Command("ReorderLibrary", "{orderedPresetIds}", """{"kind":"SUBAGENT_PRESET","command":"ReorderLibrary","params":{"orderedPresetIds":["<preset id 1>","<preset id 2>"]}}"""),
                Command("CreateLocalPreset", "{workspaceId, draft}",
                    """{"kind":"SUBAGENT_PRESET","command":"CreateLocalPreset","params":{"workspaceId":"<workspace id>","draft":{"key":"tester","name":"Tester","description":"Runs the tests and reports failures","systemPrompt":"Run the project's tests and summarize failures."}}}"""),
                Command("PatchLocalPreset", "targetId + {patch} — workspaceId is looked up for you",
                    """{"kind":"SUBAGENT_PRESET","command":"PatchLocalPreset","targetId":"<preset id>","params":{"patch":{"enabled":false}}}"""),
                Command("DeleteLocalPreset", "targetId", """{"kind":"SUBAGENT_PRESET","command":"DeleteLocalPreset","targetId":"<preset id>"}"""),
                Command("ReorderLocalPresets", "{workspaceId, orderedPresetIds}",
                    """{"kind":"SUBAGENT_PRESET","command":"ReorderLocalPresets","params":{"workspaceId":"<workspace id>","orderedPresetIds":["<preset id 1>","<preset id 2>"]}}"""),
            ),
            notes = listOf(
                "creator_list_objects shows the scope in each title; Patch/Delete with the wrong Library/Local variant is corrected automatically.",
                "A workspace holds at most ${WorkspaceSubagentPresets.MAX_PRESETS} presets.",
            ),
        ),
        CreatorObjectKind.SKILL to Doc(
            summary = "A skill (技能): a folder with SKILL.md (frontmatter name + description, then instructions) and optional " +
                "files; assistants with the skill in enabledSkills can load it. Built-in skills are read-only.",
            commands = listOf(
                Command("CreateSkill", sig(SkillCommand.serializer(), "CreateSkill") + " — dirName defaults to the frontmatter name",
                    """{"kind":"SKILL","command":"CreateSkill","params":{"skillMd":"---\nname: meeting-notes\ndescription: Turn a transcript into structured meeting notes\n---\n# Meeting notes\nSummarize decisions, owners and deadlines.\n","files":{"templates/notes.md":"## Decisions\n## Action items\n"}}}"""),
                Command("PatchTextFile", "targetId (skill name) + {path, newContent: the WHOLE new file}",
                    """{"kind":"SKILL","command":"PatchTextFile","targetId":"meeting-notes","params":{"path":"SKILL.md","newContent":"---\nname: meeting-notes\ndescription: Turn a transcript into meeting notes\n---\n# Meeting notes\nUpdated instructions.\n"}}""",
                    note = "The frontmatter name cannot change here; use RenameSkill."),
                Command("CreateFile", "targetId + {path, content}",
                    """{"kind":"SKILL","command":"CreateFile","targetId":"meeting-notes","params":{"path":"examples/sample.md","content":"Example"}}"""),
                Command("DeleteFile", "targetId + {path}", """{"kind":"SKILL","command":"DeleteFile","targetId":"meeting-notes","params":{"path":"examples/sample.md"}}"""),
                Command("RenameFile", "targetId + {fromPath, toPath}", """{"kind":"SKILL","command":"RenameFile","targetId":"meeting-notes","params":{"fromPath":"examples/sample.md","toPath":"examples/demo.md"}}"""),
                Command("CreateDirectory", "targetId + {path}", """{"kind":"SKILL","command":"CreateDirectory","targetId":"meeting-notes","params":{"path":"assets"}}"""),
                Command("DeleteDirectory", "targetId + {path} — must be empty", """{"kind":"SKILL","command":"DeleteDirectory","targetId":"meeting-notes","params":{"path":"assets"}}"""),
                Command("RenameDirectory", "targetId + {fromPath, toPath}", """{"kind":"SKILL","command":"RenameDirectory","targetId":"meeting-notes","params":{"fromPath":"assets","toPath":"media"}}"""),
                Command("RenameSkill", "targetId (current name) + {toName} — updates assistants' enabledSkills too",
                    """{"kind":"SKILL","command":"RenameSkill","targetId":"meeting-notes","params":{"toName":"minutes"}}"""),
                Command("DeleteSkill", "targetId", """{"kind":"SKILL","command":"DeleteSkill","targetId":"meeting-notes"}"""),
                Command("CreateBuiltinOverride", "{skillName: built-in skill name, skillMd, files?} — a user copy that replaces the built-in one",
                    """{"kind":"SKILL","command":"CreateBuiltinOverride","params":{"skillName":"<built-in skill name>","skillMd":"---\nname: <built-in skill name>\ndescription: My version\n---\nInstructions\n"}}"""),
            ),
            notes = listOf(
                "Read SKILL.md (id = skill name) and other files (id = \"<skill name>/<path>\") before patching them.",
                "Paths are relative to the skill folder, e.g. SKILL.md or ref/guide.md.",
                "Several changes to the same file in one call conflict: write the final content in one PatchTextFile.",
            ),
        ),
        CreatorObjectKind.CONVERSATION to Doc(
            summary = "An ordinary conversation (会话) with any assistant except you. A conversation is a list of nodes; " +
                "each node holds one or more candidate messages (regenerations) and selectIndex picks the visible one.",
            commands = listOf(
                Command("PatchMetadata", "targetId + " + sig(ConversationMutationCommand.serializer(), "PatchMetadata"),
                    """{"kind":"CONVERSATION","command":"PatchMetadata","targetId":"<conversation id>","params":{"title":"Trip plan","isPinned":true}}""",
                    note = "To clear customSystemPrompt / workspaceCwd / folder set clearCustomSystemPrompt / clearWorkspaceCwd / clearFolder to true; moveToAssistantId moves it to another assistant."),
                Command("CreateOrdinaryConversation", sig(ConversationMutationCommand.serializer(), "CreateOrdinaryConversation"),
                    """{"kind":"CONVERSATION","command":"CreateOrdinaryConversation","params":{"assistantId":"<assistant id>","title":"Practice","initialPlainMessages":["Let's practise ordering food."]}}"""),
                Command("AppendPlainMessage", "targetId + {role: USER|ASSISTANT, text}",
                    """{"kind":"CONVERSATION","command":"AppendPlainMessage","targetId":"<conversation id>","params":{"role":"USER","text":"Summary so far: ..."}}"""),
                Command("EditPlainTextAsNewCandidate", "targetId + {nodeId, text} — adds an edited version, keeps the old one",
                    """{"kind":"CONVERSATION","command":"EditPlainTextAsNewCandidate","targetId":"<conversation id>","params":{"nodeId":"<node id>","text":"Corrected text"}}"""),
                Command("SelectCandidate", "targetId + {nodeId, selectIndex}",
                    """{"kind":"CONVERSATION","command":"SelectCandidate","targetId":"<conversation id>","params":{"nodeId":"<node id>","selectIndex":0}}"""),
                Command("DeleteCandidate", "targetId + {nodeId, messageId}",
                    """{"kind":"CONVERSATION","command":"DeleteCandidate","targetId":"<conversation id>","params":{"nodeId":"<node id>","messageId":"<message id>"}}"""),
                Command("PruneNode", "targetId + {nodeId} — removes the node with all its candidates",
                    """{"kind":"CONVERSATION","command":"PruneNode","targetId":"<conversation id>","params":{"nodeId":"<node id>"}}"""),
                Command("SoftDelete", "targetId — moves it to the trash; restorable for 24 hours",
                    """{"kind":"CONVERSATION","command":"SoftDelete","targetId":"<conversation id>"}"""),
                Command("Restore", "targetId — brings back a deleted conversation", """{"kind":"CONVERSATION","command":"Restore","targetId":"<conversation id>"}"""),
            ),
            notes = listOf(
                "A conversation that is generating or waiting for tool approval is busy; try again later.",
                "Only plain text messages can be added or edited.",
            ),
        ),
        CreatorObjectKind.PROVIDER to Doc(
            summary = "A model provider (提供商 / 供应商): the service models are called through — its API type, address " +
                "(baseUrl) and API key. Its models are MODEL_DEFINITION entries (providerId = this id).",
            fields = providerFieldLines(),
            commands = listOf(
                Command("Create", "{fields: {type, name, baseUrl, apiKey, ...}} — type is required",
                    """{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"openai","name":"My relay","baseUrl":"https://api.example.com/v1","apiKey":"<the key the user gave>"}}}""",
                    note = "Add its models in the same call: MODEL_DEFINITION Create with params.providerId \"${'$'}<index of this Create>\"."),
                Command("Patch", "targetId + {fields: {only the fields you change}}",
                    """{"kind":"PROVIDER","command":"Patch","targetId":"<provider id>","params":{"fields":{"baseUrl":"https://api.example.com/v1","apiKey":"<new key>"}}}"""),
            ),
            notes = listOf(
                "Providers cannot be deleted, cloned or reordered here: the user does that in Settings › Providers.",
                "apiKey, privateKey and custom header values are write-only: a read only says whether they are set. Send a new " +
                    "value to replace one; never ask to see it and never repeat a key in your reply. Several keys may be " +
                    "separated by commas or new lines (they are used in turn).",
                "customHeaders replaces the whole list; an entry without value keeps that header's stored value.",
                "codex, devin and antigravity are built-in providers that sign in from the app: only name and enabled can be patched.",
                "Nothing is saved until the user approves. After that, check the connection with creator_test_provider " +
                    "{providerId, model}; add listModels:true to see the model names the service offers.",
            ),
        ),
        CreatorObjectKind.MCP_SERVER to Doc(
            summary = "An MCP server (MCP). Read-only here: it is added and configured in the app. Put its id in an assistant's mcpServers.",
            commands = emptyList(),
        ),
        CreatorObjectKind.WORKSPACE to Doc(
            summary = "A workspace (工作区): a sandboxed Linux environment an assistant can be bound to. Read-only here; " +
                "its id is used for an assistant's workspaceId and for sub-agent preset scopes.",
            commands = emptyList(),
        ),
    )

    /** Fields of each provider type, generated from the same serializers and whitelist the planner uses. */
    private fun providerFieldLines(): List<String> {
        val notes = mapOf(
            "name" to "shown in the provider list",
            "enabled" to "disabled providers are hidden from model pickers",
            "customHeaders" to "extra HTTP headers sent with every request; values are write-only",
            "balanceOption" to "balance lookup shown on the provider page; leave as is unless asked",
            "apiKey" to "write-only",
            "privateKey" to "write-only; Vertex AI service account private key (PEM)",
            "baseUrl" to "API address; OpenAI-compatible relays usually end in /v1",
            "useResponseApi" to "use the OpenAI Responses API instead of Chat Completions",
            "vertexAI" to "call Vertex AI instead of the Gemini API",
        )
        val headers = mapOf("customHeaders" to "array<{name: string, value?: string}>")
        val common = SettingsCollectionInterpreter.providerCommonFields
        fun <T : ProviderSetting> lines(type: String, serializer: KSerializer<T>, defaults: T, fields: Set<String>) =
            fieldLines(serializer, defaults, fields, notes, headers).map { "[$type] $it" }
        return listOf("type: \"openai\"|\"google\"|\"claude\" — Create only, cannot change later (openai also covers " +
            "OpenAI-compatible services and relays)") +
            lines("all", ProviderSetting.OpenAI.serializer(), ProviderSetting.OpenAI(), common) +
            lines("openai", ProviderSetting.OpenAI.serializer(), ProviderSetting.OpenAI(),
                SettingsCollectionInterpreter.providerFields("openai") - common) +
            lines("google", ProviderSetting.Google.serializer(), ProviderSetting.Google(),
                SettingsCollectionInterpreter.providerFields("google") - common) +
            lines("claude", ProviderSetting.Claude.serializer(), ProviderSetting.Claude(),
                SettingsCollectionInterpreter.providerFields("claude") - common)
    }

    private fun settingsCommands(kind: String, label: String, createFields: String, patchFields: String): List<Command> = listOf(
        Command("Create", "{fields: {...}}", """{"kind":"$kind","command":"Create","params":{"fields":$createFields}}"""),
        Command("Patch", "targetId + {fields: {only the fields you change}}",
            """{"kind":"$kind","command":"Patch","targetId":"<$label id>","params":{"fields":$patchFields}}"""),
        Command("Bind", "targetId (the $label) + {assistantId} — makes it active for that assistant",
            """{"kind":"$kind","command":"Bind","targetId":"<$label id>","params":{"assistantId":"<assistant id>"}}"""),
        Command("Unbind", "targetId + {assistantId}",
            """{"kind":"$kind","command":"Unbind","targetId":"<$label id>","params":{"assistantId":"<assistant id>"}}"""),
        Command("Clone", "targetId + {name}", """{"kind":"$kind","command":"Clone","targetId":"<$label id>","params":{"name":"Copy"}}"""),
        Command("Delete", "targetId — also unbinds it from every assistant", """{"kind":"$kind","command":"Delete","targetId":"<$label id>"}"""),
        Command("Reorder", "{orderedIds: every id exactly once}",
            """{"kind":"$kind","command":"Reorder","params":{"orderedIds":["<id 1>","<id 2>"]}}"""),
    )

    private fun sig(serializer: KSerializer<*>, simple: String): String {
        val subtypes = serializer.descriptor.getElementDescriptor(1)
        val serialName = (0 until subtypes.elementsCount).map { subtypes.getElementName(it) }
            .first { it == simple || it.endsWith(".$simple") }
        return CreatorCommandDecoder.paramSignature(serializer, serialName)
    }
}
