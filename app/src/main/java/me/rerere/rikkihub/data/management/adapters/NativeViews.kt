package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkihub.data.model.AuthorsNoteDefaults
import me.rerere.rikkihub.data.model.LibraryEntryMeta
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.rikkihub.data.model.libraryAnchor
import me.rerere.rikkihub.data.model.libraryEnabled
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.data.model.withLibraryMeta
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.prompt.AssemblyOptions
import me.rerere.tavern.prompt.NamesBehavior
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.prompt.PromptOrderEntry
import me.rerere.tavern.prompt.PromptOrderList
import me.rerere.tavern.prompt.PromptPreset
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoCodec
import me.rerere.tavern.worldinfo.WorldInfoEntry
import me.rerere.tavern.worldinfo.WorldInfoSettings

/*
 * 创造助手看到的酒馆对象：字段名、取值与酒馆一致，不做任何折叠或改名。每种对象一张字段表（[NativeField]），
 * 读取（native → 视图）把表里每个字段都带上，写入（视图 → native）先按表严格校验类型与范围。
 * 酒馆文件里本应用没有对应字段的键放在 `extra` 里，整体可读可写。
 */
internal object NativeViews {
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun str(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull
    private fun int(n: Int?): JsonElement = n?.let(::JsonPrimitive) ?: JsonNull
    private fun bool(b: Boolean?): JsonElement = b?.let(::JsonPrimitive) ?: JsonNull
    private fun strings(list: List<String>?): JsonElement = list?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull

    private val EXTRA = NativeField("extra", "object",
        "keys a SillyTavern file carries that this app has no field for; kept as they are. Sending extra replaces the whole object")
    private val GENERATION_TYPES = GenerationType.entries.map { it.wire }.toSet()

    private fun extraOf(view: JsonObject, known: Set<String>): JsonObject {
        val extra = view["extra"] as? JsonObject ?: return JsonObject(emptyMap())
        val clash = extra.keys.filter { it in known }
        require(clash.isEmpty()) { "extra must not repeat named fields: $clash" }
        return extra
    }

    // ------------------------------------------------------------------ 世界书（书本身）

    val BOOK_FIELDS = listOf(
        NativeField("name", "string", "book title", max = 1024),
        NativeField("description", "string", "free-text description", max = 8192),
        NativeField("enabled", "boolean", "false = the whole book is never used, even where it is bound"),
        EXTRA,
    )

    fun bookJson(book: Lorebook): JsonObject = obj(
        "id" to JsonPrimitive(book.id.toString()), "name" to JsonPrimitive(book.name),
        "description" to JsonPrimitive(book.description), "enabled" to JsonPrimitive(book.enabled), "extra" to book.extra,
    )

    /** Book fields onto [existing] (or a new book); the entries stay as they are — they are LOREBOOK_ENTRY objects. */
    fun bookFrom(view: JsonObject, existing: Lorebook): Lorebook {
        validate("lorebook", view, BOOK_FIELDS)
        return existing.copy(
            name = (view["name"] as JsonPrimitive).content, description = (view["description"] as JsonPrimitive).content,
            enabled = (view["enabled"] as JsonPrimitive).booleanOrNull ?: true,
            extra = view["extra"] as? JsonObject ?: existing.extra,
        )
    }

    // ------------------------------------------------------------------ 世界书条目

    private val ENTRY_KEYS = linkedSetOf("uid", "key", "keysecondary", "comment", "content", "constant", "vectorized", "selective",
        "selectiveLogic", "addMemo", "order", "position", "disable", "ignoreBudget", "excludeRecursion",
        "preventRecursion", "matchPersonaDescription", "matchCharacterDescription", "matchCharacterPersonality",
        "matchCharacterDepthPrompt", "matchScenario", "matchCreatorNotes", "delayUntilRecursion", "probability",
        "useProbability", "depth", "outletName", "group", "groupOverride", "groupWeight", "scanDepth", "caseSensitive",
        "matchWholeWords", "useGroupScoring", "automationId", "role", "sticky", "cooldown", "delay", "triggers",
        "displayIndex", "characterFilter")

    private const val NEEDS_CARD = "stored and exported, but only has an effect on a character card; this app has no character cards yet"

    val ENTRY_FIELDS = listOf(
        NativeField("key", "array<string>", "primary trigger keywords: any one match activates the entry. A keyword written /pattern/flags is a regular expression"),
        NativeField("keysecondary", "array<string>", "secondary keywords, used only when selective is true (see selectiveLogic)"),
        NativeField("comment", "string", "entry title / memo; shown in lists, never sent to the model", max = 1024),
        NativeField("content", "string", "the text injected when the entry is active"),
        NativeField("constant", "boolean", "true = always active, no keyword needed"),
        NativeField("vectorized", "boolean", "vector-matching flag kept for SillyTavern files; this app's engine does not use vectors"),
        NativeField("selective", "boolean", "true = when keysecondary is not empty the entry also needs them to match (selectiveLogic)"),
        NativeField("selectiveLogic", "integer", "how keysecondary combine with key: 0 = AND ANY, 1 = NOT ALL, 2 = NOT ANY, 3 = AND ALL", 0, 3),
        NativeField("addMemo", "boolean", "editor flag from SillyTavern (comment is a memo); no effect on what is sent"),
        NativeField("order", "integer", "sort key among active entries at the same position; the entry with the higher order is inserted later (closer to the chat)"),
        NativeField("position", "integer", "where it goes: 0 = before the character definitions (world info before), 1 = after them (world info after), 2 = top of the author's note, 3 = bottom of the author's note, 4 = in the chat at depth, 5 = top of example messages, 6 = bottom of example messages, 7 = outlet", 0, 7),
        NativeField("disable", "boolean", "true = switched off"),
        NativeField("ignoreBudget", "boolean", "true = counted even when the world info token budget is used up"),
        NativeField("excludeRecursion", "boolean", "true = never activated by another entry's text (only by the chat)"),
        NativeField("preventRecursion", "boolean", "true = this entry's text is not scanned to activate other entries"),
        NativeField("matchPersonaDescription", "boolean", "also scan the persona description; $NEEDS_CARD"),
        NativeField("matchCharacterDescription", "boolean", "also scan the character description; $NEEDS_CARD"),
        NativeField("matchCharacterPersonality", "boolean", "also scan the character personality; $NEEDS_CARD"),
        NativeField("matchCharacterDepthPrompt", "boolean", "also scan the character depth prompt; $NEEDS_CARD"),
        NativeField("matchScenario", "boolean", "also scan the scenario; $NEEDS_CARD"),
        NativeField("matchCreatorNotes", "boolean", "also scan the creator notes; $NEEDS_CARD"),
        NativeField("delayUntilRecursion", "integer", "0 = no delay; n = only active from recursion level n on", 0, 1000),
        NativeField("probability", "integer", "chance in percent that an otherwise active entry is used (needs useProbability)", 0, 100),
        NativeField("useProbability", "boolean", "true = apply probability"),
        NativeField("depth", "integer", "for position 4: how many messages back from the newest the text is inserted", 0, 10000),
        NativeField("outletName", "string", "for position 7: the outlet name referenced by an outlet macro", max = 1024),
        NativeField("group", "string", "inclusion group: of the active entries sharing a group only one is used (comma separated names)", max = 1024),
        NativeField("groupOverride", "boolean", "true = wins its group over entries without override"),
        NativeField("groupWeight", "integer", "weight when several entries of a group compete", 0, 100000),
        NativeField("scanDepth", "integer|null", "messages scanned for keywords; null = the global world info setting", 0, 1000),
        NativeField("caseSensitive", "boolean|null", "null = the global setting"),
        NativeField("matchWholeWords", "boolean|null", "null = the global setting"),
        NativeField("useGroupScoring", "boolean|null", "null = the global setting"),
        NativeField("automationId", "string", "SillyTavern quick-reply automation id; no effect here", max = 1024),
        NativeField("role", "integer|null", "for position 4: 0 = system, 1 = user, 2 = assistant", 0, 2),
        NativeField("sticky", "integer|null", "stays active for this many messages after it triggered; null = off", 0, 100000),
        NativeField("cooldown", "integer|null", "cannot trigger again for this many messages after it triggered; null = off", 0, 100000),
        NativeField("delay", "integer|null", "not active until the chat has this many messages; null = off", 0, 100000),
        NativeField("triggers", "array<string>", "generation types that may activate the entry (normal, continue, impersonate, swipe, regenerate, quiet); empty = all", values = GENERATION_TYPES),
        NativeField("displayIndex", "integer|null", "position in the SillyTavern editor list; no effect on what is sent", 0, 1_000_000),
        NativeField("characterFilter", "object|null", "{isExclude: boolean, names: array<string>, tags: array<string>}; $NEEDS_CARD"),
        EXTRA,
    )

    fun entryJson(e: WorldInfoEntry): JsonObject {
        val canon = WorldInfoCodec.encodeEntry(e.copy(layout = null))
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(e.uid.toString())
        ENTRY_FIELDS.forEach { f -> out[f.name] = if (f.name == "extra") e.extra else canon[f.name] ?: JsonNull }
        return JsonObject(out)
    }

    /** [view] (a merged entry view carrying every field) → entry. */
    fun entryFrom(view: JsonObject, uid: Int): WorldInfoEntry {
        validateEntry(view)
        val raw = LinkedHashMap<String, JsonElement>()
        ENTRY_FIELDS.forEach { f -> if (f.name != "extra") view[f.name]?.let { raw[f.name] = it } }
        extraOf(view, ENTRY_KEYS).forEach { (k, v) -> raw.putIfAbsent(k, v) }
        raw["uid"] = JsonPrimitive(uid)
        return WorldInfoCodec.decodeEntry(JsonObject(raw), uid)
    }

    private fun validateEntry(view: JsonObject) {
        validate("lorebook entry", view, ENTRY_FIELDS)
        extraOf(view, ENTRY_KEYS)
        (view["characterFilter"] as? JsonObject)?.let { filter ->
            validate("characterFilter", filter, listOf(
                NativeField("isExclude", "boolean"), NativeField("names", "array<string>"), NativeField("tags", "array<string>")))
        }
    }

    /** A fresh entry (every field at SillyTavern's default). */
    fun newEntry(uid: Int): WorldInfoEntry = WorldInfoEntry(uid = uid, selective = true)

    // ------------------------------------------------------------------ 提示词库条目 / 预设条目

    private val PROMPT_ST_FIELDS = listOf(
        NativeField("name", "string|null", "title shown in lists", max = 1024),
        NativeField("role", "string|null", "speaker of the text: system, user or assistant", values = PromptRole.entries.map { it.wire }.toSet()),
        NativeField("content", "string|null", "the prompt text; may use {{macros}}"),
        NativeField("system_prompt", "boolean|null", "true marks a built-in prompt (main, jailbreak, ...); leave false/null for your own entries"),
        NativeField("marker", "boolean|null", "true = a placeholder the app fills (chatHistory, worldInfoBefore, ...); it has no text of its own"),
        NativeField("injection_position", "integer|null", "0 = relative (placed in the layout / by its anchor), 1 = absolute (inserted into the chat at injection_depth)", 0, 1),
        NativeField("injection_depth", "integer|null", "for position 1: messages counted back from the newest", 0, 10000),
        NativeField("injection_order", "integer|null", "sort key among entries inserted at the same place"),
        NativeField("injection_trigger", "array<string>|null", "generation types that may use the entry (normal, continue, impersonate, swipe, regenerate, quiet); empty or null = all", values = GENERATION_TYPES),
        NativeField("forbid_overrides", "boolean|null", "SillyTavern flag: a character card may not override this entry"),
    )
    private val PROMPT_ST_KEYS = PROMPT_ST_FIELDS.map { it.name }.toSet()
    private val PROMPT_KEYS = PROMPT_ST_KEYS + "identifier"

    val LIBRARY_FIELDS = PROMPT_ST_FIELDS + listOf(
        NativeField("libraryEnabled", "boolean", "false = the entry is hidden from the pick lists and never sent"),
        NativeField("libraryAnchor", "string|null", "for a relative entry: where it goes — before_system (before the system prompt), after_system, before_history (top of the chat); null for absolute entries",
            values = setOf(LibraryEntryMeta.BEFORE_SYSTEM, LibraryEntryMeta.AFTER_SYSTEM, LibraryEntryMeta.BEFORE_HISTORY)),
        EXTRA,
    )

    val PRESET_ENTRY_FIELDS = PROMPT_ST_FIELDS + listOf(
        NativeField("enabled", "boolean", "false = skipped when the prompt is assembled (the entry stays in the list)"),
        EXTRA,
    )

    private fun promptFields(e: PromptEntry): LinkedHashMap<String, JsonElement> = linkedMapOf(
        "name" to str(e.name), "role" to str(e.role?.wire), "content" to str(e.content),
        "system_prompt" to bool(e.systemPrompt), "marker" to bool(e.marker),
        "injection_position" to int(e.injectionPosition), "injection_depth" to int(e.injectionDepth),
        "injection_order" to int(e.injectionOrder), "injection_trigger" to strings(e.injectionTrigger),
        "forbid_overrides" to bool(e.forbidOverrides),
    )

    fun libraryJson(e: PromptEntry): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(e.identifier)
        out.putAll(promptFields(e))
        out["libraryEnabled"] = JsonPrimitive(e.libraryEnabled)
        out["libraryAnchor"] = str(e.libraryAnchor)
        out["extra"] = JsonObject(e.extra - LibraryEntryMeta.EXTRA_KEY)
        return JsonObject(out)
    }

    fun libraryFrom(view: JsonObject, base: PromptEntry): PromptEntry {
        validate("prompt library entry", view, LIBRARY_FIELDS)
        val extra = extraOf(view, PROMPT_KEYS + LibraryEntryMeta.EXTRA_KEY)
        val raw = promptRaw(view, base.identifier, extra)
        // 库的总开关与锚点由字段表达；rikkihub 元数据里别的键原样保留
        val prior = (base.extra[LibraryEntryMeta.EXTRA_KEY] as? JsonObject) ?: JsonObject(emptyMap())
        raw[LibraryEntryMeta.EXTRA_KEY] = buildJsonObject {
            prior.forEach { (k, v) -> if (k != "enabled" && k != "anchor") put(k, v) }
            put("enabled", (view["libraryEnabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
            (view["libraryAnchor"] as? JsonPrimitive)?.contentOrNull?.let { put("anchor", it) }
        }
        return PromptEntry.fromJson(JsonObject(raw)).copy(keyOrder = base.keyOrder)
    }

    private fun promptRaw(view: JsonObject, identifier: String, extra: JsonObject): LinkedHashMap<String, JsonElement> {
        val raw = LinkedHashMap<String, JsonElement>()
        raw["identifier"] = JsonPrimitive(identifier)
        PROMPT_ST_FIELDS.forEach { f -> view[f.name]?.takeIf { it !is JsonNull }?.let { raw[f.name] = it } }
        extra.forEach { (k, v) -> raw.putIfAbsent(k, v) }
        return raw
    }

    /** A new library entry: relative, after the system prompt, system role, usable. */
    fun newLibraryEntry(identifier: String): PromptEntry = PromptEntry(
        identifier = identifier, name = "", role = PromptRole.SYSTEM, content = "", systemPrompt = false, marker = false,
        injectionPosition = 0, injectionDepth = 4, injectionOrder = 0,
    ).withLibraryMeta(enabled = true, anchor = LibraryEntryMeta.AFTER_SYSTEM)

    // ----- 助手预设里的条目（位置 = 发送顺序）

    private fun globalOrder(preset: PromptPreset): PromptOrderList? =
        preset.promptOrder.firstOrNull { it.characterIdString == PromptPreset.GLOBAL_ORDER_ID.toString() }

    /** 条目视图，按发送顺序；不在顺序里的提示词排在最后，`enabled` 为 false（不会被发送，和停用等价）。 */
    fun presetEntryViews(preset: PromptPreset): List<JsonObject> {
        val layout = preset.layout().entries
        val inLayout = layout.map { it.identifier }.toSet()
        val orphans = preset.prompts.distinctBy { it.identifier }.filter { it.identifier !in inLayout }.map { it.copy(enabled = false) }
        return (layout + orphans).map(::presetEntryJson)
    }

    fun presetEntryJson(e: PromptEntry): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(e.identifier)
        out.putAll(promptFields(e))
        out["enabled"] = JsonPrimitive(e.enabled)
        out["extra"] = e.extra
        return JsonObject(out)
    }

    fun presetEntryFrom(view: JsonObject, base: PromptEntry?): PromptEntry {
        validate("preset entry", view, PRESET_ENTRY_FIELDS)
        val id = (view["id"] as JsonPrimitive).content
        val extra = extraOf(view, PROMPT_KEYS + "enabled")
        val entry = PromptEntry.fromJson(JsonObject(promptRaw(view, id, extra)),
            enabled = (view["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true)
        return if (base != null) entry.copy(keyOrder = base.keyOrder) else entry
    }

    fun newPresetEntry(identifier: String): PromptEntry = PromptEntry(
        identifier = identifier, name = "", role = PromptRole.SYSTEM, content = "", systemPrompt = false, marker = false,
    )

    /**
     * 把条目视图列表（顺序即发送顺序）写回预设：没变的条目保持原对象，变了的从视图重建，视图里没有的条目从提示词和所有顺序里去掉，
     * 新条目加进提示词。预设的其他部分（选项、`extra`、别的顺序表）不动。
     */
    fun applyPresetEntries(preset: PromptPreset, views: List<JsonObject>): PromptPreset {
        val oldViews = presetEntryViews(preset).associateBy { (it["id"] as JsonPrimitive).content }
        val oldPrompts = preset.prompts.groupBy { it.identifier }.mapValues { it.value.first() }
        val keptIds = LinkedHashSet<String>()
        val replaced = HashMap<String, PromptEntry>()
        val order = ArrayList<PromptOrderEntry>()
        val oldOrder = globalOrder(preset)?.order.orEmpty().groupBy { it.identifier }.mapValues { it.value.first() }
        views.forEach { view ->
            val id = (view["id"] as JsonPrimitive).content
            require(keptIds.add(id)) { "preset entries must be unique (duplicate id '$id')" }
            val old = oldPrompts[id]
            val entry = if (old != null && oldViews[id] == view) old.copy(enabled = (view["enabled"] as JsonPrimitive).booleanOrNull ?: true)
            else presetEntryFrom(view, old)
            replaced[id] = entry
            order += (oldOrder[id]?.copy(enabled = entry.enabled) ?: PromptOrderEntry(id, entry.enabled))
        }
        val prompts = buildList {
            val seen = HashSet<String>()
            preset.prompts.forEach { p ->
                if (p.identifier !in keptIds) return@forEach
                // 重复的标识符（只有第一个生效）只留第一个
                if (seen.add(p.identifier)) add(replaced.getValue(p.identifier).copy(enabled = true))
            }
            keptIds.filter { it !in seen }.forEach { add(replaced.getValue(it).copy(enabled = true)) }
        }
        val strayOrder = globalOrder(preset)?.order.orEmpty().filter { it.identifier !in oldPrompts && it.identifier !in keptIds }
        val globalId = PromptPreset.GLOBAL_ORDER_ID.toString()
        val lists = if (globalOrder(preset) == null)
            preset.promptOrder + PromptOrderList(JsonPrimitive(PromptPreset.GLOBAL_ORDER_ID), order + strayOrder)
        else preset.promptOrder.map { list ->
            if (list.characterIdString == globalId) list.copy(order = order + strayOrder)
            else list.copy(order = list.order.filter { it.identifier in keptIds || it.identifier !in oldPrompts })
        }
        return preset.copy(prompts = prompts, promptOrder = lists)
    }

    // ------------------------------------------------------------------ 正则脚本

    val REGEX_FIELDS = listOf(
        NativeField("scriptName", "string", "script title", max = 1024),
        NativeField("findRegex", "string", "pattern; /pattern/flags or a bare pattern (the g flag is implied unless the script uses a single match)", max = 4096),
        NativeField("replaceString", "string", "replacement; \$1, {{match}}, {{macro}} work"),
        NativeField("trimStrings", "array<string>", "strings removed from every captured match before it is substituted"),
        NativeField("placement", "array<integer>", "where it runs: 1 = user input, 2 = AI output, 3 = slash command, 5 = world info text, 6 = reasoning (0 = old markdown display, ignored)", values = setOf("0", "1", "2", "3", "5", "6")),
        NativeField("disabled", "boolean", "true = switched off"),
        NativeField("markdownOnly", "boolean", "true = only changes what is displayed; the stored/sent text stays the same"),
        NativeField("promptOnly", "boolean", "true = only changes what is sent to the model; the stored/displayed text stays the same"),
        NativeField("runOnEdit", "boolean", "true = also runs when an existing message is edited"),
        NativeField("substituteRegex", "integer", "macros in findRegex: 0 = not replaced, 1 = replaced as they are, 2 = replaced and regex-escaped", 0, 2),
        NativeField("minDepth", "integer|null", "only messages at least this many back from the newest (0 = the newest); null = no limit", 0, 100000),
        NativeField("maxDepth", "integer|null", "only messages at most this many back from the newest; null = no limit", 0, 100000),
        EXTRA,
    )
    private val REGEX_KEYS = setOf("id", "scriptName", "findRegex", "replaceString", "trimStrings", "placement", "disabled",
        "markdownOnly", "promptOnly", "runOnEdit", "substituteRegex", "minDepth", "maxDepth")

    fun regexJson(s: RegexScript): JsonObject {
        val full = s.toJson()
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(s.id)
        REGEX_FIELDS.forEach { f -> out[f.name] = if (f.name == "extra") s.extra else full[f.name] ?: JsonNull }
        return JsonObject(out)
    }

    fun regexFrom(view: JsonObject): RegexScript {
        validate("regex script", view, REGEX_FIELDS)
        val raw = LinkedHashMap<String, JsonElement>()
        extraOf(view, REGEX_KEYS).forEach { (k, v) -> raw[k] = v }
        REGEX_FIELDS.forEach { f -> if (f.name != "extra") view[f.name]?.let { raw[f.name] = it } }
        raw["id"] = view["id"]!!
        return RegexScript.fromJson(JsonObject(raw))
    }

    fun newRegexScript(id: String) = RegexScript(id = id, placement = listOf(2, 6))

    // ------------------------------------------------------------------ 助手：组装选项与预设其余部分

    val OPTION_FIELDS = listOf(
        NativeField("openai_max_context", "integer", "context size in tokens the history is trimmed to; 1000000 or more = unlimited", 1, 100_000_000),
        NativeField("openai_max_tokens", "integer", "tokens reserved for the reply", 1, 10_000_000),
        NativeField("names_behavior", "integer", "how speaker names are sent: -1 = none, 0 = default, 1 = completion, 2 = content", -1, 2),
        NativeField("send_if_empty", "string", "text sent when the user message is empty"),
        NativeField("impersonation_prompt", "string", "prompt used when writing as the user"),
        NativeField("new_chat_prompt", "string", "marker message put before a new chat; empty = none"),
        NativeField("new_example_chat_prompt", "string", "marker message put before example chats"),
        NativeField("continue_nudge_prompt", "string", "instruction appended when continuing a reply"),
        NativeField("wi_format", "string", "wrapper for world info text; {0} is the text"),
        NativeField("scenario_format", "string", "wrapper for the scenario"),
        NativeField("personality_format", "string", "wrapper for the personality"),
        NativeField("assistant_prefill", "string", "text the reply must start with (providers that support prefill)"),
        NativeField("assistant_impersonation", "string", "prefill used when writing as the user"),
        NativeField("squash_system_messages", "boolean", "true = merge consecutive system messages into one"),
        NativeField("continue_prefill", "boolean", "true = continue by prefilling the previous reply"),
        NativeField("continue_postfix", "string", "text added after a continued reply"),
    )

    fun optionsJson(o: AssemblyOptions): JsonObject = obj(
        "openai_max_context" to JsonPrimitive(o.contextSize), "openai_max_tokens" to JsonPrimitive(o.responseTokens),
        "names_behavior" to JsonPrimitive(o.namesBehavior.code), "send_if_empty" to JsonPrimitive(o.sendIfEmpty),
        "impersonation_prompt" to JsonPrimitive(o.impersonationPrompt), "new_chat_prompt" to JsonPrimitive(o.newChatPrompt),
        "new_example_chat_prompt" to JsonPrimitive(o.newExampleChatPrompt),
        "continue_nudge_prompt" to JsonPrimitive(o.continueNudgePrompt), "wi_format" to JsonPrimitive(o.wiFormat),
        "scenario_format" to JsonPrimitive(o.scenarioFormat), "personality_format" to JsonPrimitive(o.personalityFormat),
        "assistant_prefill" to JsonPrimitive(o.assistantPrefill), "assistant_impersonation" to JsonPrimitive(o.assistantImpersonation),
        "squash_system_messages" to JsonPrimitive(o.squashSystemMessages), "continue_prefill" to JsonPrimitive(o.continuePrefill),
        "continue_postfix" to JsonPrimitive(o.continuePostfix),
    )

    /** [patch] 里给到的选项键套到 [current] 上（只认 [OPTION_FIELDS]）。 */
    fun optionsFrom(patch: JsonObject, current: AssemblyOptions): AssemblyOptions {
        validate("promptOptions", patch, OPTION_FIELDS)
        val merged = JsonObject(optionsJson(current) + patch)
        fun s(k: String) = (merged.getValue(k) as JsonPrimitive).content
        fun n(k: String) = (merged.getValue(k) as JsonPrimitive).content.toInt()
        fun b(k: String) = (merged.getValue(k) as JsonPrimitive).booleanOrNull!!
        return current.copy(
            contextSize = n("openai_max_context"), responseTokens = n("openai_max_tokens"),
            namesBehavior = NamesBehavior.fromCode(n("names_behavior")) ?: current.namesBehavior,
            sendIfEmpty = s("send_if_empty"), impersonationPrompt = s("impersonation_prompt"), newChatPrompt = s("new_chat_prompt"),
            newExampleChatPrompt = s("new_example_chat_prompt"), continueNudgePrompt = s("continue_nudge_prompt"),
            wiFormat = s("wi_format"), scenarioFormat = s("scenario_format"), personalityFormat = s("personality_format"),
            assistantPrefill = s("assistant_prefill"), assistantImpersonation = s("assistant_impersonation"),
            squashSystemMessages = b("squash_system_messages"), continuePrefill = b("continue_prefill"),
            continuePostfix = s("continue_postfix"),
        )
    }

    private val PRESET_OWNED_KEYS = OPTION_FIELDS.map { it.name }.toSet() + setOf("prompts", "prompt_order")

    /** 预设里除提示词、顺序、选项和助手作用域正则之外的键（采样参数、各家模型名……），酒馆预设原样带来的那部分。 */
    fun presetExtraJson(preset: PromptPreset): JsonObject {
        val extensions = preset.extra["extensions"] as? JsonObject
        val rest = preset.extra.filterKeys { it !in PRESET_OWNED_KEYS && it != "extensions" }
        val keptExtensions = extensions?.let { JsonObject(it - "regex_scripts") }?.takeIf { it.isNotEmpty() }
        return JsonObject(rest + (if (keptExtensions != null) mapOf("extensions" to keptExtensions) else emptyMap()))
    }

    fun withPresetExtra(preset: PromptPreset, extra: JsonObject): PromptPreset {
        val bad = extra.keys.filter { it in PRESET_OWNED_KEYS }
        require(bad.isEmpty()) { "presetExtra must not hold $bad (they have their own fields / objects)" }
        val extensions = extra["extensions"]
        require(extensions == null || extensions is JsonObject) { "presetExtra.extensions must be an object" }
        require((extensions as? JsonObject)?.containsKey("regex_scripts") != true) {
            "presetExtra.extensions.regex_scripts is managed through REGEX_SCRIPT objects"
        }
        val scripts = preset.regexScripts
        return preset.copy(extra = JsonObject(extra)).withRegexScripts(scripts)
    }

    // ------------------------------------------------------------------ 全局酒馆设置

    val TAVERN_FIELDS = listOf(
        NativeField("worldInfo", "object", "global world info settings; send only the keys to change"),
        NativeField("authorsNote", "object", "defaults of the author's note; send only the keys to change"),
        NativeField("globalVariables", "object", "global variables ({{getglobalvar}} / {{setglobalvar}}); an object value is stored as a JSON string, a scalar as it is. Sending it replaces all of them"),
        NativeField("regexAllowed", "array<string>", "sources whose bundled regex scripts may run: character:<id> for a character card's own scripts (they run only once listed here); every assistant's own scripts run regardless"),
        NativeField("personaDescription", "string", "the user's persona description (sent where the Persona Description marker sits in an assistant's prompt layout; the name is the user nickname in preferences). Macros work"),
    )

    val WORLD_INFO_FIELDS = listOf(
        NativeField("world_info_depth", "integer", "messages scanned for keywords", 0, 1000),
        NativeField("world_info_min_activations", "integer", "keep scanning deeper until at least this many entries are active (0 = off)", 0, 1000),
        NativeField("world_info_min_activations_depth_max", "integer", "scan depth limit for min activations (0 = chat length)", 0, 1000),
        NativeField("world_info_budget", "integer", "token budget in percent of the context", 1, 100),
        NativeField("world_info_include_names", "boolean", "scan text includes speaker names"),
        NativeField("world_info_recursive", "boolean", "active entries' text can activate more entries"),
        NativeField("world_info_overflow_alert", "boolean", "warn when the budget is exceeded"),
        NativeField("world_info_case_sensitive", "boolean", "keyword matching is case sensitive"),
        NativeField("world_info_match_whole_words", "boolean", "keywords match whole words only"),
        NativeField("world_info_use_group_scoring", "boolean", "inclusion groups pick the best-scoring entry"),
        NativeField("world_info_character_strategy", "integer", "order of character and global books: 0 = evenly, 1 = character first, 2 = global first", 0, 2),
        NativeField("world_info_budget_cap", "integer", "absolute token cap (0 = none)", 0, 100_000_000),
        NativeField("world_info_max_recursion_steps", "integer", "maximum recursion steps (0 = unlimited)", 0, 1000),
    )

    val AUTHORS_NOTE_FIELDS = listOf(
        NativeField("prompt", "string", "the note text"),
        NativeField("interval", "integer", "inserted every n user messages (1 = every message)", 0, 100000),
        NativeField("depth", "integer", "for position 1: messages back from the newest", 0, 10000),
        NativeField("position", "integer", "0 = after the prompt, 1 = in the chat at depth, 2 = before the prompt", 0, 2),
        NativeField("role", "integer", "0 = system, 1 = user, 2 = assistant", 0, 2),
    )

    fun tavernJson(t: TavernSettings): JsonObject = obj(
        "id" to JsonPrimitive("tavern"),
        "worldInfo" to json.encodeToJsonElement(WorldInfoSettings.serializer(), t.worldInfo),
        "authorsNote" to json.encodeToJsonElement(AuthorsNoteDefaults.serializer(), t.authorsNote),
        "globalVariables" to t.globalVariables,
        "regexAllowed" to JsonArray(t.regexAllowed.sorted().map(::JsonPrimitive)),
        "personaDescription" to JsonPrimitive(t.personaDescription),
    )

    fun tavernFrom(view: JsonObject, current: TavernSettings): TavernSettings {
        validate("tavern settings", view, TAVERN_FIELDS)
        fun merged(key: String, existing: JsonObject, fields: List<NativeField>): JsonObject {
            val patch = view[key] as? JsonObject ?: return existing
            validate(key, patch, fields)
            return JsonObject(existing + patch)
        }
        val worldInfo = merged("worldInfo", json.encodeToJsonElement(WorldInfoSettings.serializer(), current.worldInfo).jsonObject, WORLD_INFO_FIELDS)
        val note = merged("authorsNote", json.encodeToJsonElement(AuthorsNoteDefaults.serializer(), current.authorsNote).jsonObject, AUTHORS_NOTE_FIELDS)
        val allowed = (view["regexAllowed"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: current.regexAllowed
        return current.copy(
            worldInfo = json.decodeFromJsonElement(WorldInfoSettings.serializer(), worldInfo),
            authorsNote = json.decodeFromJsonElement(AuthorsNoteDefaults.serializer(), note),
            globalVariables = view["globalVariables"] as? JsonObject ?: current.globalVariables,
            regexAllowed = allowed,
            personaDescription = (view["personaDescription"] as? JsonPrimitive)?.content ?: current.personaDescription,
        )
    }

    private fun validate(label: String, json: JsonObject, fields: List<NativeField>) = NativeFields.validate(label, json, fields)

    /** 字段表的键集合（完整性测试用）。 */
    val KNOWN_ENTRY_KEYS: Set<String> get() = ENTRY_KEYS
    val KNOWN_PROMPT_KEYS: Set<String> get() = PROMPT_KEYS
    val KNOWN_REGEX_KEYS: Set<String> get() = REGEX_KEYS
}
