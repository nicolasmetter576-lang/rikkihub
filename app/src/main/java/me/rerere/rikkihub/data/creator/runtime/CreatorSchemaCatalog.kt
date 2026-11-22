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
import me.rerere.rikkihub.data.management.adapters.NativeField
import me.rerere.rikkihub.data.management.adapters.character.CharacterNative
import me.rerere.rikkihub.data.management.adapters.NativeFields
import me.rerere.rikkihub.data.management.adapters.NativeViews
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.adapters.skills.SkillCommand
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetDraft
import me.rerere.rikkihub.data.management.adapters.subagent.SubagentPresetPatch
import me.rerere.rikkihub.data.model.Assistant
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

    private val assistantNotes: Map<String, String> = mapOf(
            "name" to "shown in the assistant list",
            "chatModelId" to "MODEL_DEFINITION id of a CHAT model; null = the default chat model",
            "temperature" to "null = provider default; 0..2",
            "topP" to "null = provider default; 0..1",
            "topK" to "null = not sent; 1..100000. OpenAI-compatible servers, Claude and Gemini send it",
            "frequencyPenalty" to "null = not sent; -2..2. OpenAI-compatible servers and Gemini send it",
            "presencePenalty" to "null = not sent; -2..2. OpenAI-compatible servers and Gemini send it",
            "repetitionPenalty" to "null = not sent; 0.01..3. Only OpenAI-compatible servers send it",
            "minP" to "null = not sent; 0..1. Only OpenAI-compatible servers send it",
            "topA" to "null = not sent; 0..1. Only OpenAI-compatible servers send it",
            "seed" to "null = not sent; 0 or more. OpenAI-compatible servers and Gemini send it",
            "maxTokens" to "null = provider default; 1..1000000",
            "contextMessageLimit" to "0 = no limit",
            "messageTemplate" to "wraps each user message; must keep {{ message }}",
            "presetMessages" to "opening messages shown at the start of every new chat (开场白)",
            "tags" to "ASSISTANT_TAG ids; replaces the whole list",
            "lorebookIds" to "LOREBOOK ids bound to this assistant; replaces the whole list (Bind/Unbind on LOREBOOK adds/removes one)",
            "quickMessageIds" to "QUICK_MESSAGE ids shown above the input box; replaces the whole list",
            "mcpServers" to "MCP_SERVER ids enabled for this assistant",
            "enabled" to "false hides the assistant wherever an assistant is picked (chat drawer, move to assistant, share); " +
                "it still works while it is the current assistant",
            "enabledSkills" to "SKILL names (not ids) the assistant may load",
            "localTools" to "device tools; each item is an object with only a type",
            "workspaceId" to "WORKSPACE id or null",
            "avatar" to "Emoji: {\"type\":\"${avatarType("Emoji")}\",\"content\":\"🦊\"}; images cannot be set here",
            "background" to "chat background; cannot be set to a new image here, only cleared with null",
            "enableTimeReminder" to "inject a time note when the user returns after a gap",
            "enableMessageTitle" to "ask the model to end each reply with a short per-turn title (shown next to the model name, searchable)",
            "tavernMode" to "SillyTavern mode. true = the full prompt layout is used (character card fields, persona, " +
                "author's note, promptOptions); false = a simplified layout sends the main prompt, the user's own text entries, " +
                "selected prompt library entries and manually selected lorebooks (plus memory/tool notes and chat history). " +
                "World info above/below the author's note still requires this mode and an active note. Other settings are kept but not applied. " +
                "New assistants default to false; fromPreset turns it on, copyFrom copies it",
        )
    private val assistantTypeOverrides: Map<String, String> = mapOf(
            "presetMessages" to "array<{role: \"user\"|\"assistant\"|\"system\", parts: [{type: \"$TEXT_PART\", text: string}]}>",
        )
    private val assistantViewOnly = setOf("modeInjectionIds", "promptOptions", "presetExtra", "systemPrompt", "copyFrom", "fromPreset")
    private val assistantFields =
        fieldLines(Assistant.serializer(), Assistant(), SettingsCollectionInterpreter.assistantFields - assistantViewOnly,
            notes = assistantNotes, typeOverrides = assistantTypeOverrides) +
        NativeFields.lines(listOf(
            NativeField("modeInjectionIds", "array<string(id)>", "MODE_INJECTION ids (prompt library entries) this assistant uses; replaces the whole list (Bind/Unbind on MODE_INJECTION adds/removes one)"),
            NativeField("promptOptions", "object", "how this assistant's prompt is assembled (context size, reply tokens, formats, ...); send only the keys to change, see the notes"),
            NativeField("presetExtra", "object", "other keys of the assistant's SillyTavern preset (sampling parameters, model names, extensions) kept as they are; replaced as a whole"),
            NativeField("systemPrompt", "string", "Create only: text of the initial main prompt entry; afterwards edit the prompt with PRESET_ENTRY"),
            NativeField("copyFrom", "string(id)", "Create only: id of an assistant whose prompt entries, options and regex scripts are copied"),
            NativeField("fromPreset", "object", "Create only: a SillyTavern chat-completion preset (the file's JSON object) to start from. Its prompt entries, order, options and sampling parameters are taken; its regex scripts come in switched off and its connection settings (keys, URLs, model names) are left out. Not together with copyFrom or systemPrompt"),
        ), JsonObject(mapOf(
            "modeInjectionIds" to JsonArray(emptyList()),
            "promptOptions" to NativeViews.optionsJson(Assistant().promptPreset.options),
            "presetExtra" to JsonObject(emptyMap()),
        )))

    private fun avatarType(name: String): String {
        val d = me.rerere.rikkihub.data.model.Avatar.serializer().descriptor.getElementDescriptor(1)
        return (0 until d.elementsCount).map { d.getElementName(it) }.first { it.endsWith(name) }
    }

    private val docs: Map<CreatorObjectKind, Doc> = mapOf(
        CreatorObjectKind.ASSISTANT to Doc(
            summary = "An assistant (助手): a persona the user chats with — name, system prompt, model, parameters, " +
                "and which lorebooks, quick messages, prompt library entries (MODE_INJECTION), tags, MCP servers, skills and tools it uses. " +
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
                "The prompt itself is not a field: its text, order and switches are PRESET_ENTRY objects (id <assistant id>/<identifier>; the persona text is <assistant id>/main), its regex scripts are REGEX_SCRIPT objects. Create takes systemPrompt as a shortcut for the first main entry, copyFrom to start from another assistant's prompt, or fromPreset to start from a SillyTavern preset the user pasted.",
                "promptOptions keys: " + NativeFields.lines(NativeViews.OPTION_FIELDS, NativeViews.optionsJson(Assistant().promptPreset.options)).joinToString("; "),
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
            summary = "A prompt library entry (提示词库条目, formerly 模式注入): a reusable piece of prompt text that is inserted at a chosen place " +
                "of every request of the assistants (or conversations) it is selected for, e.g. a writing style or output format. " +
                "Fields are SillyTavern's prompt fields.",
            fields = NativeFields.lines(NativeViews.LIBRARY_FIELDS, NativeViews.libraryJson(NativeViews.newLibraryEntry("x"))),
            commands = settingsCommands("MODE_INJECTION", "prompt library entry",
                """{"name":"Concise mode","content":"Answer in at most three sentences.","role":"system","injection_position":0,"libraryAnchor":"after_system"}""",
                """{"libraryEnabled":false}""") + bulkCommands("MODE_INJECTION", null,
                    """{"name":"Style A","content":"..."},{"name":"Style B","content":"..."}"""),
            notes = listOf(
                "Relative entry (injection_position 0): libraryAnchor says where it goes — before_system, after_system or before_history. " +
                    "Absolute entry (injection_position 1): inserted into the chat injection_depth messages from the newest, with role; libraryAnchor null.",
                "The id is the entry's identifier; assistants and conversations select entries by it (modeInjectionIds).",
            ),
        ),
        CreatorObjectKind.LOREBOOK to Doc(
            summary = "A lorebook (世界书): a named set of entries; an entry is injected into the prompt when one of its keywords " +
                "appears in recent messages (or always, if constant). Bound to assistants (Bind) or single conversations. " +
                "This object is the book itself; its entries are LOREBOOK_ENTRY objects.",
            fields = NativeFields.lines(NativeViews.BOOK_FIELDS, NativeViews.bookJson(me.rerere.rikkihub.data.model.Lorebook(id = Uuid.parse("00000000-0000-0000-0000-000000000000")))) +
                NativeFields.lines(listOf(NativeField("copyFrom", "string(id)", "Create only: id of a lorebook whose entries are copied")), JsonObject(emptyMap())),
            commands = settingsCommands("LOREBOOK", "lorebook",
                """{"name":"Aurora world","description":"Setting notes for the Aurora story"}""",
                """{"description":"Updated notes"}"""),
            notes = listOf(
                "Create the book, then add entries with LOREBOOK_ENTRY Create in the same call: bookId \"\$0\" refers to the Create at index 0.",
                "Deleting a book deletes its entries and unbinds it everywhere; Clone copies the entries too.",
            ),
        ),
        CreatorObjectKind.LOREBOOK_ENTRY to Doc(
            summary = "One entry of a lorebook (世界书条目), with every SillyTavern world info field. Ids are <lorebook id>/<uid>. " +
                "Read, search, create, change, delete and reorder entries one by one — a book of thousands of entries is fine.",
            fields = NativeFields.lines(NativeViews.ENTRY_FIELDS, NativeViews.entryJson(NativeViews.newEntry(0))),
            commands = listOf(
                Command("Create", "{bookId, fields: {...}} — unspecified fields take SillyTavern's defaults; the uid is assigned by the app",
                    """{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"<lorebook id>","fields":{"comment":"Capital","key":["capital","Aurum"],"content":"Aurum is the floating capital of the Aurora empire."}}}"""),
                Command("Create (many)", "{bookId, items: [{fields}, ...]} — up to ${me.rerere.rikkihub.data.management.adapters.SettingsDomainAdapter.MAX_BULK} entries in one operation",
                    """{"kind":"LOREBOOK_ENTRY","command":"Create","params":{"bookId":"<lorebook id>","items":[{"comment":"Dragons","key":["dragon"],"content":"Dragons are extinct."},{"comment":"Weather","key":["rain"],"content":"It rains every Tuesday.","probability":50}]}}"""),
                Command("Patch", "targetId (<lorebook id>/<uid>) + {fields: {only the fields you change}}",
                    """{"kind":"LOREBOOK_ENTRY","command":"Patch","targetId":"<lorebook id>/<uid>","params":{"fields":{"key":["capital","Aurum","city"],"order":200}}}"""),
                Command("Patch (many)", "{bookId, patches: [{id, fields}, ...]}",
                    """{"kind":"LOREBOOK_ENTRY","command":"Patch","params":{"bookId":"<lorebook id>","patches":[{"id":"<lorebook id>/<uid>","fields":{"disable":true}},{"id":"<lorebook id>/<uid>","fields":{"constant":true}}]}}"""),
                Command("Delete", "targetId", """{"kind":"LOREBOOK_ENTRY","command":"Delete","targetId":"<lorebook id>/<uid>"}"""),
                Command("Delete (many)", "{bookId, ids: [...]}",
                    """{"kind":"LOREBOOK_ENTRY","command":"Delete","params":{"bookId":"<lorebook id>","ids":["<lorebook id>/<uid>","<lorebook id>/<uid>"]}}"""),
                Command("Reorder", "{bookId, orderedIds: every uid of the book exactly once} — the list order only matters in the app's editor",
                    """{"kind":"LOREBOOK_ENTRY","command":"Reorder","params":{"bookId":"<lorebook id>","orderedIds":["<lorebook id>/<uid>","<lorebook id>/<uid>"]}}"""),
            ),
            notes = listOf(
                "Find entries with creator_list_objects kind=LOREBOOK_ENTRY and filter \"<lorebook id> <text>\": the text is matched against titles, keywords and content.",
                "key, keysecondary and triggers are replaced as a whole list: send the complete new list.",
                "Which entries are active is decided by the app's world info engine from key/keysecondary/constant/probability/... and the global world info settings (TAVERN_SETTINGS).",
            ),
        ),
        CreatorObjectKind.PRESET_ENTRY to Doc(
            summary = "One entry of an assistant's prompt (SillyTavern prompt manager entry): the persona text, extra instructions, or a placeholder " +
                "such as chatHistory / worldInfoBefore. The list order is the order the entries are sent in. Ids are <assistant id>/<identifier>; " +
                "the persona text of a simple assistant is <assistant id>/main.",
            fields = NativeFields.lines(NativeViews.PRESET_ENTRY_FIELDS, NativeViews.presetEntryJson(NativeViews.newPresetEntry("x"))),
            commands = listOf(
                Command("Create", "{assistantId, fields: {...}} — appended at the end; Reorder to move it",
                    """{"kind":"PRESET_ENTRY","command":"Create","params":{"assistantId":"<assistant id>","fields":{"name":"Style","role":"system","content":"Write in short sentences."}}}"""),
                Command("Patch", "targetId (<assistant id>/<identifier>) + {fields: {only the fields you change}}",
                    """{"kind":"PRESET_ENTRY","command":"Patch","targetId":"<assistant id>/main","params":{"fields":{"content":"You are a patient English tutor."}}}"""),
                Command("Delete", "targetId", """{"kind":"PRESET_ENTRY","command":"Delete","targetId":"<assistant id>/<identifier>"}"""),
                Command("Reorder", "{assistantId, orderedIds: every entry id of the assistant exactly once}",
                    """{"kind":"PRESET_ENTRY","command":"Reorder","params":{"assistantId":"<assistant id>","orderedIds":["<assistant id>/main","<assistant id>/chatHistory"]}}"""),
            ),
            notes = listOf(
                "Entries with marker true have no text: they stand for what the app fills in (chatHistory, worldInfoBefore, worldInfoAfter, dialogueExamples, rikkihubContext, ...). Keep chatHistory enabled or the chat is not sent.",
                "An entry that is not in the sending order is listed last with enabled false.",
                "Assembly options (context size, formats, ...) are the ASSISTANT field promptOptions.",
            ),
        ),
        CreatorObjectKind.REGEX_SCRIPT to Doc(
            summary = "A regex script (正则脚本): find/replace applied to the user's input, the AI's output, reasoning or world info text — for display, for the prompt, or both. " +
                "Global scripts run for every assistant; an assistant's own scripts run for it only. Ids are global/<id> or <assistant id>/<id>.",
            fields = NativeFields.lines(NativeViews.REGEX_FIELDS, NativeViews.regexJson(NativeViews.newRegexScript("x"))),
            commands = listOf(
                Command("Create", "{assistantId?, fields: {...}} — without assistantId the script is global",
                    """{"kind":"REGEX_SCRIPT","command":"Create","params":{"assistantId":"<assistant id>","fields":{"scriptName":"Cat to dog","findRegex":"/cat/g","replaceString":"dog","placement":[2],"markdownOnly":true}}}"""),
                Command("Patch", "targetId (global/<id> or <assistant id>/<id>) + {fields: {only the fields you change}}",
                    """{"kind":"REGEX_SCRIPT","command":"Patch","targetId":"global/<id>","params":{"fields":{"disabled":true}}}"""),
                Command("Delete", "targetId", """{"kind":"REGEX_SCRIPT","command":"Delete","targetId":"<assistant id>/<id>"}"""),
                Command("Reorder", "{assistantId?, orderedIds: every script id of that scope exactly once} — scripts run in list order",
                    """{"kind":"REGEX_SCRIPT","command":"Reorder","params":{"assistantId":"<assistant id>","orderedIds":["<assistant id>/<id>","<assistant id>/<id>"]}}"""),
            ),
            notes = listOf("Global scripts run before an assistant's scripts."),
        ),
        CreatorObjectKind.TAVERN_SETTINGS to Doc(
            summary = "The global SillyTavern settings: world info settings (scan depth, budget, recursion, ...), the author's note defaults, global variables and the regex trust list. One object, id tavern.",
            fields = NativeFields.lines(NativeViews.TAVERN_FIELDS, JsonObject(emptyMap())) +
                listOf("worldInfo keys: " + NativeFields.lines(NativeViews.WORLD_INFO_FIELDS, JsonObject(emptyMap())).joinToString("; "),
                    "authorsNote keys: " + NativeFields.lines(NativeViews.AUTHORS_NOTE_FIELDS, JsonObject(emptyMap())).joinToString("; ")),
            commands = listOf(
                Command("Patch", "{fields: {worldInfo?, authorsNote?, globalVariables?, regexAllowed?, personaDescription?}} — worldInfo and authorsNote merge key by key",
                    """{"kind":"TAVERN_SETTINGS","command":"Patch","targetId":"tavern","params":{"fields":{"worldInfo":{"world_info_depth":4,"world_info_recursive":true},"authorsNote":{"prompt":"Keep it light.","depth":2}}}}"""),
            ),
            notes = listOf("The author's note and variables of a single chat belong to that CONVERSATION."),
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
                    "pricing" to "{input?, cachedInput?, output?} price in USD per million tokens; only used to estimate spending (stats page, provider spending limits)",
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
                    note = "To clear customSystemPrompt / workspaceCwd / folder set clearCustomSystemPrompt / clearWorkspaceCwd / clearFolder to true; moveToAssistantId moves it to another assistant. " +
                        "characterId ties the chat to a CHARACTER (its greeting-time fields, description, personality, scenario and books then apply from the next message) and clearCharacter unties it. " +
                        "tavernMetadata holds this chat's SillyTavern state — author's note (note_prompt, note_interval, note_depth, note_position 0 after prompt / 1 in chat / 2 before prompt, note_role 0 system / 1 user / 2 assistant), " +
                        "chat variables (variables: object) and world info timers (timedWorldInfo); PatchMetadata writes the keys you give and removes keys whose value is null."),
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
        CreatorObjectKind.CHARACTER to Doc(
            summary = "A character (角色 / 角色卡, a SillyTavern character card): who the AI plays in a chat — name, description, personality, scenario, " +
                "greeting, example dialogue, optional prompt overrides and depth prompt, tags, and the world books tied to it. " +
                "A chat with a character starts from its greeting; its fields are sent where an assistant's prompt layout has the " +
                "Character Description / Personality / Scenario markers. Create characters here; the user imports PNG/CHARX/JSON card files in Settings › Characters.",
            fields = NativeFields.lines(CharacterNative.FIELDS, JsonObject(mapOf(
                "tags" to JsonArray(emptyList()), "alternate_greetings" to JsonArray(emptyList()), "fav" to JsonPrimitive(false),
                "extraBookIds" to JsonArray(emptyList()),
            ))),
            commands = listOf(
                Command("Create", "{fields: {name, ...}} — name is required, unspecified fields stay empty",
                    """{"kind":"CHARACTER","command":"Create","params":{"fields":{"name":"Ines Okafor","description":"A harbor cartographer in her forties, dry-witted and patient.","personality":"curious, precise, teasing","scenario":"{{char}} is mapping the tide tables when {{user}} walks in.","first_mes":"*Ines looks up from her charts.* Back again, {{user}}?","tags":["harbor","slice of life"]}}}""",
                    note = "{{char}} and {{user}} work in every text field. A new id is assigned by the app."),
                Command("Patch", "targetId + {fields: {only the fields you change}}",
                    """{"kind":"CHARACTER","command":"Patch","targetId":"<character id>","params":{"fields":{"alternate_greetings":["*Ines hums over her charts.*"],"depth_prompt":{"prompt":"[{{char}} is checking a chart while she talks.]","depth":3}}}}""",
                    note = "List fields (tags, alternate_greetings, extraBookIds) replace the whole list; depth_prompt merges key by key."),
            ),
            notes = listOf(
                "Read the character first; creator_read_object returns exactly these fields (readOnly adds the card's own regex scripts, its source file and revision).",
                "Characters cannot be deleted, cloned or reordered here: the user does that in Settings › Characters. The picture cannot be set here either.",
                "A card's own regex scripts run only after the user allows them: TAVERN_SETTINGS regexAllowed has to list readOnly.regexSource (character:<id>). Do not add it unless the user asked for it.",
                "World books: create them as LOREBOOK objects, then put their ids in extraBookIds. The book that came with an imported card is embeddedBookId (it can only be untied with null).",
                "To start a chat with a character the user picks it in Settings › Characters (or CONVERSATION PatchMetadata {characterId} ties an existing chat to it).",
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
            "apiKeys" to "write-only; array of {id?, key, label?, enabled?}",
            "privateKey" to "write-only; Vertex AI service account private key (PEM)",
            "serviceAccounts" to "write-only; Google Vertex AI array of {id?, label?, projectId, serviceAccountEmail, privateKey, location?, enabled?}",
            "rotationStrategy" to "ROUND_ROBIN or FAILOVER for multi-credential scheduling",
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

    /** Create-many example for a top-level collection kind. */
    private fun bulkCommands(kind: String, parent: String?, items: String): List<Command> = listOf(
        Command("Create (many)", "{items: [{fields}, ...]} — up to ${me.rerere.rikkihub.data.management.adapters.SettingsDomainAdapter.MAX_BULK} in one operation",
            """{"kind":"$kind","command":"Create","params":{"items":[$items]}}"""),
    )

    private fun sig(serializer: KSerializer<*>, simple: String): String {
        val subtypes = serializer.descriptor.getElementDescriptor(1)
        val serialName = (0 until subtypes.elementsCount).map { subtypes.getElementName(it) }
            .first { it == simple || it.endsWith(".$simple") }
        return CreatorCommandDecoder.paramSignature(serializer, serialName)
    }
}
