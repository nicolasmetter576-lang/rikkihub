package me.rerere.rikkihub.data.ai.book

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.tools.BookPromptLanguage
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.datastore.Settings

/** 一个别称候选：[count] 是它在书里出现的次数，[replacement] 是模型建议的新称呼（可空）。 */
data class BookAlias(val alias: String, val replacement: String, val count: Int)

/**
 * 角色改名时找别称（小林、林哥）。只发含姓名的片段，不发整本；候选必须真的在书里出现过，一律由用户确认。
 */
class BookAliasFinder(
    private val store: BookStore,
    private val ai: BookAi,
) {
    suspend fun find(
        settings: Settings,
        workspaceId: String,
        language: BookPromptLanguage,
        name: String,
        newName: String,
        paths: List<String>,
    ): List<BookAlias> {
        val texts = paths.mapNotNull { path -> runCatching { store.read(workspaceId, path).content }.getOrNull() }
        val output = ai.complete(settings, listOf(UIMessage.user(prompt(language, name, newName, snippets(texts, name)))))
        return parse(output, name) { alias -> texts.sumOf { count(it, alias) } }
    }

    companion object {
        private const val MAX_SNIPPETS = 60
        private const val RADIUS = 100
        private const val MAX_ALIAS_CHARS = 20
        private const val MAX_ALIASES = 20

        internal fun count(text: String, word: String): Int {
            var n = 0
            var at = text.indexOf(word)
            while (at >= 0) {
                n++
                at = text.indexOf(word, at + word.length)
            }
            return n
        }

        /** 姓名前后的片段，全书均匀取样；挨得近的合并成一段。 */
        internal fun snippets(texts: List<String>, name: String): List<String> {
            val all = texts.flatMap { text ->
                buildList {
                    var at = text.indexOf(name)
                    var until = -1
                    while (at >= 0) {
                        val start = maxOf(0, at - RADIUS)
                        if (start >= until) {
                            until = minOf(text.length, at + name.length + RADIUS)
                            add(text.substring(start, until).trim { it == '�' || it.isWhitespace() })
                        }
                        at = text.indexOf(name, at + name.length)
                    }
                }
            }
            if (all.size <= MAX_SNIPPETS) return all
            return List(MAX_SNIPPETS) { all[it * all.size / MAX_SNIPPETS] }
        }

        internal fun prompt(language: BookPromptLanguage, name: String, newName: String, snippets: List<String>): String {
            val body = snippets.joinToString("\n---\n")
            return if (language == BookPromptLanguage.ZH) """
                小说里的角色「$name」要改名为「$newName」。下面是书里出现这个名字的片段。
                请找出书里指同一个人的其他叫法（小名、简称、敬称、绰号），并给出改名后对应的叫法。
                只列片段里真的出现过的叫法；泛称（他、她、师父、公子这类谁都能用的称呼）不要列；拿不准的不要列。
                每行输出一个 JSON，不要写别的：{"alias":"原叫法","to":"新叫法"}
                没有就什么都不输出。

                片段：
            """.trimIndent() + "\n" + body
            else """
                In a novel the character "$name" is being renamed to "$newName". Below are passages where the name appears.
                List other ways the book refers to the same person (nicknames, short forms, titles made from the name) and what each should become.
                Only list forms that really occur in the passages. Leave out generic words that could mean anyone (he, the master, sir), and anything you are unsure about.
                Output one JSON object per line and nothing else: {"alias":"old form","to":"new form"}
                If there are none, output nothing.

                Passages:
            """.trimIndent() + "\n" + body
        }

        /** 逐行解析；书里没出现过的、和姓名相同的、重复的都丢掉。 */
        internal fun parse(output: String, name: String, count: (String) -> Int): List<BookAlias> {
            val seen = HashSet<String>()
            return jsonLines(output).mapNotNull { row ->
                val alias = row.text("alias")?.trim().orEmpty()
                if (alias.isEmpty() || alias.length > MAX_ALIAS_CHARS || '\n' in alias || alias == name || !seen.add(alias)) return@mapNotNull null
                val times = count(alias)
                if (times == 0) null else BookAlias(alias, row.text("to")?.trim().orEmpty(), times)
            }.take(MAX_ALIASES)
        }
    }
}

/** 一行一个 JSON 对象的输出：代码围栏、列表符号、半截的行都跳过，完整的行照用。 */
internal fun jsonLines(output: String): List<JsonObject> = output.lineSequence().mapNotNull { line ->
    val start = line.indexOf('{')
    val end = line.lastIndexOf('}')
    if (start < 0 || end <= start) null
    else runCatching { Json.parseToJsonElement(line.substring(start, end + 1)) as? JsonObject }.getOrNull()
}.toList()

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
