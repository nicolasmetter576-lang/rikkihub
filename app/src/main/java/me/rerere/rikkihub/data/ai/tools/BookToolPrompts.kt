package me.rerere.rikkihub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.data.workspace.WorkspaceRequestCapabilities

/** 作品模式的工具提示词语言（每个工作区各选一套，`workspaces.book_prompt_language`）。 */
enum class BookPromptLanguage(val id: String) {
    ZH("zh"),
    EN("en");

    companion object {
        fun of(id: String): BookPromptLanguage = entries.firstOrNull { it.id == id } ?: ZH
    }
}

/** 非作品模式为 null。 */
fun WorkspaceEntity.promptLanguage(): BookPromptLanguage? =
    if (bookMode) BookPromptLanguage.of(bookPromptLanguage) else null

/**
 * 作品模式的写作向提示词，中英各一套。工具本身沿用标准模式的稳定 ID（改名设置共用），这里只换说明文字：
 * 每个工具、系统提示各有一条变体规格（[BuiltinToolSpec.variantOf]），可以在「设置-工具」里单独改，
 * 不影响标准模式。作品工具（book_*）的规格自带英文说明，中文另有变体。
 */
object BookToolPrompts {
    fun variantId(language: BookPromptLanguage, toolId: String) = "book_${language.id}.$toolId"

    fun variant(language: BookPromptLanguage, toolId: String): BuiltinToolSpec? =
        BuiltinToolCatalog[variantId(language, toolId)]

    fun reminder(language: BookPromptLanguage): BuiltinToolSpec =
        BuiltinToolCatalog.require(variantId(language, BuiltinToolIds.WORKSPACE_REMINDER))

    /** 参数说明换成 [language] 的文字；没有译文的参数保持原样。 */
    fun describeParameters(language: BookPromptLanguage, toolId: String, schema: InputSchema?): InputSchema? {
        val texts = (if (language == BookPromptLanguage.ZH) ZH_PARAMETERS else EN_PARAMETERS)[toolId] ?: return schema
        val obj = schema as? InputSchema.Obj ?: return schema
        return obj.copy(properties = describe(obj.properties, texts))
    }

    private fun describe(properties: JsonObject, texts: Map<String, String>): JsonObject =
        JsonObject(properties.mapValues { (name, value) -> describeProperty(value, texts[name], texts) })

    private fun describeProperty(value: JsonElement, text: String?, texts: Map<String, String>): JsonElement {
        val property = value as? JsonObject ?: return value
        val described = LinkedHashMap<String, JsonElement>(property)
        if (text != null) described["description"] = JsonPrimitive(text)
        (property["items"] as? JsonObject)?.let { items ->
            (items["properties"] as? JsonObject)?.let { nested ->
                described["items"] = JsonObject(items + ("properties" to describe(nested, texts)))
            }
        }
        return JsonObject(described)
    }

    /** 作品模式「可用工具」块：只列本次请求真实可用的工具，用真实名字。 */
    fun availableTools(language: BookPromptLanguage, capabilities: WorkspaceRequestCapabilities): String {
        val lines = WorkspaceToolEnablement.bookIds.mapNotNull { id ->
            capabilities.wireNameOf(id)?.let { wire -> toolLine(language, id, wire) }
        }
        val zh = language == BookPromptLanguage.ZH
        if (lines.isEmpty()) {
            return if (zh) "- 这个工作区目前没有可用的工具。" else "- Workspace tools are currently unavailable for this workspace."
        }
        return (listOf(if (zh) "- 可用工具：" else "- Available tools:") + lines).joinToString("\n")
    }

    private fun toolLine(language: BookPromptLanguage, id: String, wire: String): String {
        val text = if (language == BookPromptLanguage.ZH) when (id) {
            BuiltinToolIds.BOOK_CONTENTS -> "看全书目录：章节顺序、标题、字数、状态、梗概、文件路径，以及还没编入的文件。"
            BuiltinToolIds.BOOK_READ -> "按章号读正文，可以只读开头或结尾。"
            BuiltinToolIds.BOOK_SEARCH -> "在全书正文里找词句，返回章号和上下文。"
            BuiltinToolIds.BOOK_UPDATE -> "改作品结构：把新文件编入目录、调顺序、分卷、写梗概、标状态。不改正文。"
            BuiltinToolIds.WORKSPACE_READ_FILE -> "读任意文件（设定、大纲、笔记、图片）。"
            BuiltinToolIds.WORKSPACE_WRITE_FILE -> "写新文件，或整篇重写。"
            BuiltinToolIds.WORKSPACE_EDIT_FILE -> "改文件里的一处或几处文字。修稿优先用它。"
            BuiltinToolIds.WORKSPACE_SHELL -> "在 Linux 里跑命令，适合批量处理、统计、格式转换。"
            BuiltinToolIds.SUBAGENT -> "派一个子代理做边界清楚的活（通读找矛盾、整理设定等）。它看不到这段对话。"
            else -> return "  - `$wire`"
        } else when (id) {
            BuiltinToolIds.BOOK_CONTENTS -> "the table of contents: chapter order, titles, word counts, status, synopses, file paths, and files not yet in the book."
            BuiltinToolIds.BOOK_READ -> "read prose by chapter number, whole or only its start or end."
            BuiltinToolIds.BOOK_SEARCH -> "find words or phrases across the whole manuscript, with chapter numbers and context."
            BuiltinToolIds.BOOK_UPDATE -> "change the structure: add a file to the contents, reorder, volumes, synopses, status. Never touches prose."
            BuiltinToolIds.WORKSPACE_READ_FILE -> "read any file (setting notes, outlines, images)."
            BuiltinToolIds.WORKSPACE_WRITE_FILE -> "create a file, or rewrite one completely."
            BuiltinToolIds.WORKSPACE_EDIT_FILE -> "change one or several passages of a file. Preferred for revising."
            BuiltinToolIds.WORKSPACE_SHELL -> "run Linux commands: batch processing, statistics, conversion."
            BuiltinToolIds.SUBAGENT -> "hand a bounded job (read through for contradictions, collect setting facts…) to a nested worker that cannot see this conversation."
            else -> return "  - `$wire`"
        }
        return if (language == BookPromptLanguage.ZH) "  - `$wire`：$text" else "  - `$wire`: $text"
    }

    /** 中文提示里的环境说明（Rootfs、挂载、工作目录），英文沿用标准模式的原文。 */
    fun zhMountLines(exposedTargets: Set<String>, canReadFiles: Boolean): String = buildString {
        val devices = exposedTargets.filter { it.startsWith("/device/") }.sorted()
        if (devices.isNotEmpty()) {
            appendLine("- 已连接的手机目录：" + devices.joinToString("、") { "`$it`" } + "。在里面写入、删除会立刻改动手机上的原文件，没有暂存和回滚，不要越出这些目录。")
        }
        if ("/skills" in exposedTargets && canReadFiles) {
            appendLine("- 技能目录挂载在 `/skills`，每个技能是一个子目录，用之前先读它的 `SKILL.md` 并照做。")
        } else {
            appendLine("- 已安装的技能用 `use_skill` 调用。")
        }
        if ("/builtin_skills" in exposedTargets) {
            appendLine("- 内置技能在 `/builtin_skills/`，只读；要改就先复制到 `/skills`。")
        }
        if ("/upload" in exposedTargets && canReadFiles) {
            appendLine("- 用户上传的文件在 `/upload`，只读；要改就先复制到 `/workspace`。")
        }
        if (WorkspaceRequestCapabilities.TOOL_OUTPUTS_GUEST_PATH in exposedTargets && canReadFiles) {
            appendLine("- 过长的工具结果会被截断，全文存在 `/tool_outputs`，结果里写了路径时可以去读。")
        }
    }

    fun zhRootfsLines(environment: String): String =
        if (environment.isBlank()) "" else "- 已装 Linux 环境，命令行可用。\n"

    fun zhScopeLine(path: String): String =
        "- 这次只能看到 `$path`，`/workspace` 下的其他内容对你不存在；所有改动都放在这里面。\n"

    fun zhCwdLine(cwd: String?): String =
        if (cwd.isNullOrBlank()) "" else "- 当前工作目录：`$cwd`，文件操作和命令默认以它为准。\n"

    private val REMINDER_ZH = """
        <workspace>
        你在作品工作区「{{workspace_name}}」里工作：这里的文件就是一本书（通常是小说）的书稿。
        - 书稿文件夹挂载在 `/workspace`，传给文件工具的路径都要写绝对路径（如 `/workspace/第1章.md`）。
        - 章节的顺序、标题、状态、梗概记在作品结构里，不由文件名决定；要了解全书先看目录，不要猜。`/workspace/.book/` 由应用维护，不要直接改。
        {{available_tools}}
        写作时：
        - 动笔前先读上一章结尾和相关设定，人物称谓、事实、人称、时态、文风与前文一致；不确定就查，查不到再问。
        - 要你写的正文直接写进文件，聊天里只简短说明写了什么，不要再贴一遍全文，除非用户要看。
        - 修稿只改该改的地方，别的句子、标点、分段、缩进原样留着；不要顺手润色没让你动的段落。
        - 正文里只放正文：不要小标题、批注、“未完待续”、字数统计和创作说明，除非原稿就这样写。
        - 新章节先写文件，再编入目录。文件名沿用已有章节的写法；改名、删除文件前先问用户。
        - 设定、大纲、笔记和正文分开放；正文里新定下的设定，提醒用户要不要记下来。
        {{rootfs_environment}}{{extra_mounts}}{{cwd_line}}</workspace>
    """.trimIndent()

    private val REMINDER_EN = """
        <workspace>
        You are working in the book workspace "{{workspace_name}}": its files are the manuscript of one book, usually a novel.
        - The book's folder is mounted at `/workspace`. Paths passed to file tools must be absolute (for example `/workspace/Chapter 1.md`).
        - Chapter order, titles, status and synopses are kept in the book structure, not in file names. Look at the contents first instead of guessing. `/workspace/.book/` is maintained by the app; never edit it directly.
        {{available_tools}}
        When writing:
        - Before drafting, read the end of the previous chapter and the relevant setting notes. Keep names, facts, person, tense and style consistent. If unsure, look it up; ask only when it cannot be found.
        - Put prose you are asked to write straight into the file and only say briefly in chat what you wrote. Do not paste the whole text again unless the user wants to see it.
        - When revising, change only what needs changing. Leave other sentences, punctuation, paragraph breaks and indentation exactly as they are; do not polish passages you were not asked to touch.
        - The manuscript holds prose only: no subheadings, annotations, "to be continued", word counts or notes about the writing, unless the manuscript already does that.
        - For a new chapter write the file first, then add it to the contents. Follow the naming of the existing chapters; ask before renaming or deleting files.
        - Keep setting notes, outlines and drafts apart from prose. When the prose establishes a new fact, ask whether to record it.
        {{rootfs_environment}}{{extra_mounts}}{{cwd_line}}</workspace>
    """.trimIndent()

    private val REMINDER_PLACEHOLDERS =
        listOf("workspace_name", "cwd_line", "extra_mounts", "available_tools", "rootfs_environment")

    private fun english(id: String, description: String, placeholders: List<String> = emptyList(), systemPrompt: String? = null) =
        variantSpec(BookPromptLanguage.EN, id, description, placeholders, systemPrompt)

    private fun chinese(id: String, description: String, placeholders: List<String> = emptyList(), systemPrompt: String? = null) =
        variantSpec(BookPromptLanguage.ZH, id, description, placeholders, systemPrompt)

    private fun variantSpec(
        language: BookPromptLanguage,
        id: String,
        description: String,
        placeholders: List<String>,
        systemPrompt: String?,
    ) = BuiltinToolSpec(
        id = variantId(language, id),
        category = if (language == BookPromptLanguage.ZH) BuiltinToolCategory.BOOK_ZH else BuiltinToolCategory.BOOK_EN,
        defaultName = "",
        defaultDescription = description.trimIndent(),
        defaultSystemPrompt = systemPrompt,
        placeholders = placeholders,
        isTool = false,
        renamable = false,
        variantOf = id,
    )

    private val BOOK_CONTENTS_EN = """
        Show the table of contents of the book: volumes, chapters in order with number, title, word count, status, synopsis and file path, and the text files that are not part of the book yet.
        Call it first when you need to know what the book contains or which number a chapter has. Chapter numbers change when chapters are moved, so look again after restructuring.
        Use from/to for a range of a long book.
    """.trimIndent()

    private val BOOK_READ_EN = """
        Read the prose of a chapter by its number in the table of contents (several consecutive chapters with "to").
        Use part=end to read only how a chapter closes before continuing the story, part=start for its opening.
        A long result is cut at max_chars; continue with offset.
    """.trimIndent()

    private val BOOK_SEARCH_EN = """
        Find a word or phrase in the prose of all chapters, in reading order. Returns chapter number, title, line and surrounding text.
        Use it to check how a name, place, object or fact was written before, or whether a phrase is already overused. Plain text, not a regular expression.
    """.trimIndent()

    private val BOOK_UPDATE_EN = """
        Change the structure of the book; the prose itself is never touched. Pass all changes of one request as a list of operations, applied in order. Chapter numbers always mean the numbers before this call.
        - add: put an existing file into the contents (path; optional after = chapter number, 0 = beginning, default end; title, synopsis, status).
        - set: change a chapter's title, synopsis, status (draft, revising, final) or target_words.
        - move: move a chapter behind another one (after, 0 = beginning).
        - remove: take a chapter out of the contents; its file stays.
        - add_volume: start a volume named title after the given chapter (after, 0 = beginning).
        - set_book: change the book's title or author.
        Write synopses as one or two sentences about what happens. Mark a chapter final only when the user says so.
    """.trimIndent()

    /** 作品工具本体：英文说明就放在规格里，也是英文那一套。 */
    private val toolSpecs = listOf(
        BuiltinToolIds.BOOK_CONTENTS to BOOK_CONTENTS_EN,
        BuiltinToolIds.BOOK_READ to BOOK_READ_EN,
        BuiltinToolIds.BOOK_SEARCH to BOOK_SEARCH_EN,
        BuiltinToolIds.BOOK_UPDATE to BOOK_UPDATE_EN,
    ).map { (id, description) ->
        BuiltinToolSpec(id = id, category = BuiltinToolCategory.BOOK_EN, defaultName = id, defaultDescription = description)
    }

    private val shellPlaceholders = listOf("cwd_clause")

    internal val specs: List<BuiltinToolSpec> = listOf(
        chinese(
            BuiltinToolIds.WORKSPACE_REMINDER,
            "作品工作区的系统提示（中文）。",
            REMINDER_PLACEHOLDERS,
            REMINDER_ZH,
        ),
        chinese(
            BuiltinToolIds.BOOK_CONTENTS,
            """
                看这本书的目录：分卷，按顺序排列的章节（章号、标题、字数、状态、梗概、文件路径），以及还没编入目录的文本文件。
                需要了解全书有什么、某一章是第几章时先调用它。移动章节后章号会变，改完结构要重新看。
                章节很多时用 from / to 只看一段。
            """,
        ),
        chinese(
            BuiltinToolIds.BOOK_READ,
            """
                按目录里的章号读正文（用 to 可以连读几章）。
                接着往下写之前，用 part=end 只读上一章的结尾；part=start 只读开头。
                太长会在 max_chars 处截断，用 offset 接着读。
            """,
        ),
        chinese(
            BuiltinToolIds.BOOK_SEARCH,
            """
                按阅读顺序在全书正文里找一个词或一句话，返回章号、标题、行号和前后文。
                用来核对人名、地名、物件、某个事实以前怎么写的，或者一个说法是不是已经用滥了。按原文查找，不是正则。
            """,
        ),
        chinese(
            BuiltinToolIds.BOOK_UPDATE,
            """
                改作品结构，不碰正文。一次要求里的所有改动放进同一个 operations 列表，按顺序执行；章号一律指这次调用之前的章号。
                - add：把已有文件编入目录（path；可选 after = 排在第几章后面，0 = 最前，不填 = 最后；title、synopsis、status）。
                - set：改一章的 title、synopsis、status（draft 草稿 / revising 修订 / final 定稿）、target_words。
                - move：把一章移到另一章后面（after，0 = 最前）。
                - remove：把一章移出目录，文件还在。
                - add_volume：在某一章后面开始新的一卷（title；after，0 = 最前）。
                - set_book：改书名 title、作者 author。
                梗概写一两句“发生了什么”。用户说了定稿才标 final。
            """,
        ),
        chinese(
            BuiltinToolIds.WORKSPACE_READ_FILE,
            """
                读一个文件：设定、大纲、笔记、正文都可以，也能看图片（png、jpg、jpeg、gif、webp、bmp、svg、heic、heif、avif、ico）。文本须是 UTF-8。
                路径写绝对路径，书稿在 /workspace 下。读章节正文用按章读的工具更方便。
            """,
        ),
        chinese(
            BuiltinToolIds.WORKSPACE_WRITE_FILE,
            """
                写一个 UTF-8 文本文件，用于新建章节、设定、大纲，或整篇重写。路径写绝对路径，书稿在 /workspace 下。
                只改几处时用编辑工具，不要整篇重写，免得动到作者没让改的地方。
                text 里只放文件内容本身。新建的章节写完后还要编入目录。
            """,
        ),
        chinese(
            BuiltinToolIds.WORKSPACE_EDIT_FILE,
            """
                修改一个 UTF-8 文本文件里的文字，修稿优先用它。路径写绝对路径，书稿在 /workspace 下。
                old_text 照抄原文（含标点、全角空格和换行），默认要求在文件里只出现一次，不唯一就多带几个字；replace_all=true 时全部替换。
                找不到完全一致的原文时，会自动尝试忽略空白差异的整行匹配。
                只替换需要改的句子，前后文不要带进来重写。
            """,
        ),
        chinese(
            BuiltinToolIds.WORKSPACE_SHELL,
            """
                在作品工作区的 Linux 里运行命令，书稿在 /workspace。cwd 是相对书稿根目录的路径。{{cwd_clause}}
                适合批量处理、统计、格式转换；改正文用编辑工具，不要用 sed 之类整批替换，除非用户明确要求。需要已安装 Linux 环境。
            """,
            shellPlaceholders,
        ),
        chinese(
            BuiltinToolIds.SUBAGENT,
            """
                派一个子代理完成边界清楚的活，例如通读几章找前后矛盾、整理人物设定、核对时间线。它用当前工作区的工具（不含本工具），最后交回简短报告和改动过的文件。
                prompt 写任务本身，要自成一体；description 是卡片标题（可选）；工作区允许时可用 context 附上它需要看到的片段。
                正文由你自己写，不要让子代理改正文，除非用户这样要求。一步就能查到的事不要派它。
            """,
        ),
        english(
            BuiltinToolIds.WORKSPACE_REMINDER,
            "System prompt of a book workspace (English).",
            REMINDER_PLACEHOLDERS,
            REMINDER_EN,
        ),
    ) + toolSpecs + listOf(
        english(
            BuiltinToolIds.WORKSPACE_READ_FILE,
            """
                Read a file: setting notes, outlines, drafts, prose, or an image (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico). Text must be UTF-8.
                Use absolute paths; the manuscript is under /workspace. For the prose of a chapter, reading by chapter number is easier.
            """,
        ),
        english(
            BuiltinToolIds.WORKSPACE_WRITE_FILE,
            """
                Write a UTF-8 text file: a new chapter, setting notes, an outline, or a complete rewrite. Use absolute paths; the manuscript is under /workspace.
                To change a few passages use the edit tool instead of rewriting the file, so nothing the author did not ask about is altered.
                text holds only the content of the file. After creating a chapter, add it to the contents.
            """,
        ),
        english(
            BuiltinToolIds.WORKSPACE_EDIT_FILE,
            """
                Change passages of a UTF-8 text file; preferred for revising. Use absolute paths; the manuscript is under /workspace.
                Copy old_text exactly from the file (punctuation, full-width spaces and line breaks included). It must occur exactly once unless replace_all=true; include a few more words if it is not unique.
                If no exact match is found, whitespace-tolerant line matching is attempted automatically.
                Replace only the sentences that need to change; do not pull the surrounding text in and rewrite it.
            """,
        ),
        english(
            BuiltinToolIds.WORKSPACE_SHELL,
            """
                Run a command in the book workspace's Linux; the manuscript is at /workspace. cwd is relative to the manuscript root.{{cwd_clause}}
                Good for batch processing, statistics and conversion. Change prose with the edit tool, not with sed-style bulk replacement, unless the user explicitly asks. Requires an installed Linux environment.
            """,
            shellPlaceholders,
        ),
        english(
            BuiltinToolIds.SUBAGENT,
            """
                Hand a bounded job to a nested worker, such as reading several chapters for contradictions, collecting character facts or checking the timeline. It uses the current workspace tools (except this one) and returns a short report plus the files it changed.
                Put a self-contained task in prompt; description is an optional card title; if the workspace allows it, context can carry excerpts the worker should see.
                Write the prose yourself; do not let the worker change prose unless the user asks for that. Do not use it for something one lookup answers.
            """,
        ),
    )

    private val ZH_PARAMETERS: Map<String, Map<String, String>> = mapOf(
        BuiltinToolIds.WORKSPACE_READ_FILE to mapOf("path" to "绝对路径，书稿在 /workspace 下"),
        BuiltinToolIds.WORKSPACE_WRITE_FILE to mapOf(
            "path" to "绝对路径，书稿在 /workspace 下",
            "text" to "要写入的全部内容（UTF-8）",
            "overwrite" to "文件已存在时是否覆盖，默认 true",
        ),
        BuiltinToolIds.WORKSPACE_EDIT_FILE to mapOf(
            "path" to "绝对路径，书稿在 /workspace 下",
            "old_text" to "要替换的原文，照抄",
            "new_text" to "替换成的文字",
            "replace_all" to "是否替换全部出现处，默认 false",
        ),
        BuiltinToolIds.WORKSPACE_SHELL to mapOf(
            "command" to "要运行的命令",
            "cwd" to "相对书稿根目录的工作目录，默认根目录或会话的工作目录",
            "timeout" to "超时秒数，默认 30，最多 600",
        ),
        BuiltinToolIds.SUBAGENT to mapOf(
            "prompt" to "交给子代理的任务，写完整",
            "description" to "卡片标题（可选）",
            "context" to "附给子代理看的片段（可选）",
        ),
        BuiltinToolIds.BOOK_CONTENTS to mapOf(
            "from" to "从第几章开始，默认 1",
            "to" to "到第几章为止，默认最后",
            "synopses" to "是否带梗概，默认 true",
        ),
        BuiltinToolIds.BOOK_READ to mapOf(
            "chapter" to "章号，见目录",
            "to" to "连读到第几章（可选）",
            "part" to "all 全章（默认）、start 只读开头、end 只读结尾",
            "max_chars" to "最多返回多少字，默认 20000；part 为 start / end 时默认 2000",
            "offset" to "从第几个字开始，接着上次截断处读，默认 0",
        ),
        BuiltinToolIds.BOOK_SEARCH to mapOf(
            "query" to "要找的原文",
            "case_sensitive" to "是否区分大小写，默认 false",
            "from" to "从第几章开始找",
            "to" to "找到第几章为止",
            "max_results" to "最多返回几处，默认 30，最多 200",
        ),
        BuiltinToolIds.BOOK_UPDATE to mapOf(
            "operations" to "按顺序执行的改动",
            "op" to "操作：add、set、move、remove、add_volume、set_book",
            "chapter" to "章号（set、move、remove）",
            "path" to "要编入的文件（add），如 /workspace/第12章.md",
            "after" to "排在第几章后面，0 = 最前",
            "title" to "章名、卷名或书名",
            "synopsis" to "一两句梗概",
            "status" to "draft、revising 或 final",
            "target_words" to "目标字数，0 = 不设",
            "author" to "作者（set_book）",
        ),
    )

    private val EN_PARAMETERS: Map<String, Map<String, String>> = mapOf(
        BuiltinToolIds.WORKSPACE_READ_FILE to mapOf("path" to "Absolute path; the manuscript is under /workspace"),
        BuiltinToolIds.WORKSPACE_WRITE_FILE to mapOf(
            "path" to "Absolute path; the manuscript is under /workspace",
            "text" to "The complete content to write (UTF-8)",
        ),
        BuiltinToolIds.WORKSPACE_EDIT_FILE to mapOf(
            "path" to "Absolute path; the manuscript is under /workspace",
            "old_text" to "The exact passage to replace, copied from the file",
            "new_text" to "The text that replaces it",
        ),
    )
}
