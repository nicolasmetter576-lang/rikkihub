package me.rerere.rikkihub.data.management.adapters

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.model.regexScripts
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.tavern.prompt.PromptEntry
import me.rerere.tavern.regex.RegexScript
import me.rerere.tavern.worldinfo.WorldInfoEntry
import kotlin.uuid.Uuid

/**
 * A Settings collection whose objects the creator assistant sees as plain JSON objects with an `id`
 * (see [NativeViews]). [read] lists them in order; [write] stores the list a patch produced: objects
 * whose view did not change keep their stored form, the others are rebuilt from the view.
 */
internal class NativeCollection(
    val id: String,
    val label: String,
    /** Writable fields (the `id` is implicit). */
    val fields: List<NativeField>,
    /** Fields only a Create takes; [write] consumes them. */
    val createOnly: Set<String> = emptySet(),
    /** Prefix of the object ref id (`entry:<book>/`), empty for top-level collections. */
    val refPrefix: String = "",
    val singleton: Boolean = false,
    val read: (Settings) -> List<JsonObject>,
    val create: (Settings, String) -> JsonObject,
    val write: (Settings, List<JsonObject>, List<JsonObject>) -> Settings,
    /** Ids that are not UUIDs (world book entries are numbered inside their book). */
    val nextId: ((Settings) -> String)? = null,
) {
    val allowed: Set<String> get() = fields.map { it.name }.toSet() + createOnly
}

internal object NativeCollections {
    const val LIBRARY = "settings.modeInjections"
    const val BOOKS = "settings.lorebooks"
    const val TAVERN = "settings.tavern"
    const val GLOBAL_REGEX = "settings.tavern.regexScripts"
    const val MAX_ENTRIES_PER_BOOK = 20_000
    const val MAX_PRESET_ENTRIES = 1_000
    const val MAX_REGEX_SCRIPTS = 1_000

    private const val UUID_RE = "[0-9a-fA-F-]{36}"
    private val BOOK_ENTRIES = Regex("^settings\\.lorebooks\\.($UUID_RE)\\.entries$")
    private val PRESET_ENTRIES = Regex("^settings\\.assistants\\.($UUID_RE)\\.presetEntries$")
    private val ASSISTANT_REGEX = Regex("^settings\\.assistants\\.($UUID_RE)\\.regexScripts$")

    /** Collection ids of the form `settings.<lorebooks|assistants>.<label>.<part>` that may name an object created earlier in the batch. */
    val NESTED_LABEL = Regex("^settings\\.(lorebooks|assistants|providers)\\.(\\$\\d+)\\.(entries|presetEntries|regexScripts|models)$")

    fun isNative(collectionId: String): Boolean = resolve(collectionId) != null

    fun resolve(collectionId: String): NativeCollection? = when {
        collectionId == LIBRARY -> library()
        collectionId == BOOKS -> books()
        collectionId == TAVERN -> tavern()
        collectionId == GLOBAL_REGEX -> globalRegex()
        else -> BOOK_ENTRIES.matchEntire(collectionId)?.let { entries(Uuid.parse(it.groupValues[1])) }
            ?: PRESET_ENTRIES.matchEntire(collectionId)?.let { presetEntries(Uuid.parse(it.groupValues[1])) }
            ?: ASSISTANT_REGEX.matchEntire(collectionId)?.let { assistantRegex(Uuid.parse(it.groupValues[1])) }
    }

    /** The id an ObjectRef uses for object [id] of [collectionId] (the CAS key is derived from it by the port). */
    fun refId(collectionId: String, id: String): String? = resolve(collectionId)?.let { c ->
        when (collectionId) {
            LIBRARY, BOOKS -> id
            TAVERN -> "setting:tavern"
            GLOBAL_REGEX -> "regex:global/$id"
            else -> {
                BOOK_ENTRIES.matchEntire(collectionId)?.let { "entry:${it.groupValues[1]}/$id" }
                    ?: PRESET_ENTRIES.matchEntire(collectionId)?.let { "preset:${it.groupValues[1]}/$id" }
                    ?: "regex:${ASSISTANT_REGEX.matchEntire(collectionId)!!.groupValues[1]}/$id"
            }
        }.also { check(c.id == collectionId) }
    }

    // ------------------------------------------------------------------ helpers

    private fun JsonObject.idText(): String = (getValue("id") as JsonPrimitive).content

    private fun <T> rebuild(
        natives: List<T>, oldViews: List<JsonObject>, newViews: List<JsonObject>,
        nativeId: (T) -> String, from: (JsonObject, T?) -> T,
    ): List<T> {
        val byId = LinkedHashMap<String, T>().also { m -> natives.forEach { m.putIfAbsent(nativeId(it), it) } }
        val oldById = oldViews.associateBy { it.idText() }
        require(newViews.map { it.idText() }.distinct().size == newViews.size) { "ids must be unique" }
        return newViews.map { v ->
            val id = v.idText()
            val n = byId[id]
            if (n != null && oldById[id] == v) n else from(v, n)
        }
    }

    private fun requireOrdinary(id: Uuid) {
        runCatching { AssistantRegistry().requireOrdinaryAssistantId(id) }.getOrElse {
            rejectForbidden("The creator assistant cannot be read or changed through these tools")
        }
    }

    private fun book(settings: Settings, id: Uuid): Lorebook =
        settings.lorebooks.firstOrNull { it.id == id } ?: rejectNotFound("No lorebook with id '$id'")

    private fun assistant(settings: Settings, id: Uuid): Assistant {
        requireOrdinary(id)
        return settings.assistants.firstOrNull { it.id == id } ?: rejectNotFound("No assistant with id '$id'")
    }

    // ------------------------------------------------------------------ 提示词库

    private fun library() = NativeCollection(
        id = LIBRARY, label = "prompt library entries", fields = NativeViews.LIBRARY_FIELDS,
        read = { s -> s.promptLibrary.map(NativeViews::libraryJson) },
        create = { _, id -> NativeViews.libraryJson(NativeViews.newLibraryEntry(id)) },
        write = { s, new, old ->
            s.copy(promptLibrary = rebuild(s.promptLibrary, old, new, { it.identifier }) { v, n ->
                NativeViews.libraryFrom(v, n ?: NativeViews.newLibraryEntry(v.idText()))
            })
        },
    )

    // ------------------------------------------------------------------ 世界书与条目

    private fun books() = NativeCollection(
        id = BOOKS, label = "lorebooks", fields = NativeViews.BOOK_FIELDS, createOnly = setOf("copyFrom"),
        read = { s -> s.lorebooks.map(NativeViews::bookJson) },
        create = { _, id -> NativeViews.bookJson(Lorebook(id = Uuid.parse(id))) },
        write = { s, new, old ->
            s.copy(lorebooks = rebuild(s.lorebooks, old, new, { it.id.toString() }) { v, n ->
                val id = Uuid.parse(v.idText())
                val copyFrom = (v["copyFrom"] as? JsonPrimitive)?.content
                val view = JsonObject(v - "copyFrom")
                val built = NativeViews.bookFrom(view, n ?: Lorebook(id = id))
                if (copyFrom == null) built else {
                    require(n == null || n.entries.isEmpty()) { "copyFrom only applies when the lorebook is created" }
                    built.copy(entries = book(s, runCatching { Uuid.parse(copyFrom) }.getOrNull()
                        ?: rejectInvalid("copyFrom '$copyFrom' is not a lorebook id")).entries)
                }
            })
        },
    )

    private fun entries(bookId: Uuid) = NativeCollection(
        id = "settings.lorebooks.$bookId.entries", label = "the entries of this lorebook", fields = NativeViews.ENTRY_FIELDS,
        refPrefix = "entry:$bookId/",
        read = { s -> book(s, bookId).entries.map(NativeViews::entryJson) },
        create = { _, id -> NativeViews.entryJson(NativeViews.newEntry(id.toIntOrNull() ?: rejectInvalid("Entry ids are numbers"))) },
        nextId = { s -> ((book(s, bookId).entries.maxOfOrNull { it.uid } ?: -1) + 1).toString() },
        write = { s, new, old ->
            require(new.size <= MAX_ENTRIES_PER_BOOK) { "a lorebook holds at most $MAX_ENTRIES_PER_BOOK entries" }
            book(s, bookId)
            s.copy(lorebooks = s.lorebooks.map { b ->
                if (b.id != bookId) b else b.copy(entries = rebuild<WorldInfoEntry>(b.entries, old, new, { it.uid.toString() }) { v, _ ->
                    NativeViews.entryFrom(v, v.idText().toIntOrNull() ?: rejectInvalid("Entry ids are numbers"))
                })
            })
        },
    )

    // ------------------------------------------------------------------ 助手预设

    private fun presetEntries(assistantId: Uuid) = NativeCollection(
        id = "settings.assistants.$assistantId.presetEntries", label = "the prompt entries of this assistant",
        fields = NativeViews.PRESET_ENTRY_FIELDS, refPrefix = "preset:$assistantId/",
        read = { s -> NativeViews.presetEntryViews(assistant(s, assistantId).promptPreset) },
        create = { _, id -> NativeViews.presetEntryJson(NativeViews.newPresetEntry(id)) },
        write = { s, new, _ ->
            require(new.size <= MAX_PRESET_ENTRIES) { "an assistant's prompt holds at most $MAX_PRESET_ENTRIES entries" }
            assistant(s, assistantId)
            s.copy(assistants = s.assistants.map { a ->
                if (a.id != assistantId) a else a.copy(promptPreset = NativeViews.applyPresetEntries(a.promptPreset, new))
            })
        },
    )

    private fun regexList(
        id: String, label: String, refPrefix: String, load: (Settings) -> List<RegexScript>, store: (Settings, List<RegexScript>) -> Settings,
    ) = NativeCollection(
        id = id, label = label, fields = NativeViews.REGEX_FIELDS, refPrefix = refPrefix,
        read = { s -> load(s).map(NativeViews::regexJson) },
        create = { _, rid -> NativeViews.regexJson(NativeViews.newRegexScript(rid)) },
        write = { s, new, old ->
            require(new.size <= MAX_REGEX_SCRIPTS) { "at most $MAX_REGEX_SCRIPTS regex scripts" }
            store(s, rebuild(load(s), old, new, { it.id }) { v, _ -> NativeViews.regexFrom(v) })
        },
    )

    private fun assistantRegex(assistantId: Uuid) = regexList(
        id = "settings.assistants.$assistantId.regexScripts", label = "the regex scripts of this assistant",
        refPrefix = "regex:$assistantId/",
        load = { s -> assistant(s, assistantId).promptPreset.regexScripts },
        store = { s, scripts ->
            s.copy(assistants = s.assistants.map { a ->
                if (a.id != assistantId) a else a.copy(promptPreset = a.promptPreset.withRegexScripts(scripts))
            })
        },
    )

    // ------------------------------------------------------------------ 全局酒馆设置

    private fun globalRegex() = regexList(
        id = GLOBAL_REGEX, label = "the global regex scripts", refPrefix = "regex:global/",
        load = { s -> s.tavern.regexScripts },
        store = { s, scripts -> s.copy(tavern = s.tavern.copy(regexScripts = scripts)) },
    )

    private fun tavern() = NativeCollection(
        id = TAVERN, label = "the global tavern settings", fields = NativeViews.TAVERN_FIELDS, singleton = true,
        read = { s -> listOf(NativeViews.tavernJson(s.tavern)) },
        create = { _, _ -> rejectForbidden("The global tavern settings exist once; patch them") },
        write = { s, new, old ->
            require(new.size == 1 && new.single().idText() == "tavern") { "The global tavern settings cannot be created, deleted or reordered" }
            s.copy(tavern = if (new == old) s.tavern else NativeViews.tavernFrom(new.single(), s.tavern))
        },
    )
}
