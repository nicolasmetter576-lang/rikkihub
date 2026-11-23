package me.rerere.rikkihub.data.ai.book

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.ai.tools.BookPromptLanguage
import me.rerere.rikkihub.data.book.Beat
import me.rerere.rikkihub.data.book.BeatDraft
import me.rerere.rikkihub.data.book.BeatPlacement
import me.rerere.rikkihub.data.book.BookManifest
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.ChapterNotes
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

data class BeatProposal(val text: String, val words: Int)

/** 一章正在做的事。[error]/[aiError] 非空 = 因此停下。 */
data class BeatJobState(
    val splitting: Boolean = false,
    /** 正在写的节拍和到目前为止的文字。 */
    val current: String? = null,
    val text: String = "",
    /** 后面还排着的。 */
    val queue: List<String> = emptyList(),
    /** 拆好、等用户确认的节拍。 */
    val proposal: List<BeatProposal>? = null,
    val error: String? = null,
    val aiError: BookAiException.Reason? = null,
) {
    val running: Boolean get() = splitting || current != null
}

/** 写一个节拍要用的上下文；除了 [written] 只增，其余在一章里基本不变。 */
internal data class BeatContext(
    val bookTitle: String,
    val synopses: List<String>,
    val previousTail: String,
    val chapterTitle: String,
    val synopsis: String,
    val targetWords: Int,
    val ownText: String,
    val beats: List<Beat>,
)

/** 节拍写作：拆节拍、逐个生成。一章同时只做一件事；离开面板不影响，写完就存进笔记。 */
class BeatWriter(
    private val store: BookStore,
    private val ai: BookAi,
    private val repository: WorkspaceRepository,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
) {
    data class Key(val workspaceId: String, val chapterId: String)

    private val _states = MutableStateFlow<Map<Key, BeatJobState>>(emptyMap())
    val states = _states.asStateFlow()
    private val jobs = HashMap<Key, Job>()

    /** 按顺序写这些节拍；锁定的跳过。 */
    fun write(key: Key, beatIds: List<String>, note: String = "", modelId: Uuid? = null): Boolean =
        start(key, BeatJobState(current = beatIds.firstOrNull(), queue = beatIds.drop(1)).takeIf { beatIds.isNotEmpty() }) {
            beatIds.forEachIndexed { index, id ->
                set(key, BeatJobState(current = id, queue = beatIds.drop(index + 1)))
                writeOne(key, id, note, modelId)
            }
        }

    /** 把还没有稿的节拍都写出来。 */
    suspend fun writeAll(key: Key): Boolean =
        write(key, store.notes(key.workspaceId, key.chapterId).beats.filter { it.drafts.isEmpty() && !it.locked }.map { it.id })

    fun split(key: Key): Boolean = start(key, BeatJobState(splitting = true)) {
        val (language, context) = context(key, store.notes(key.workspaceId, key.chapterId))
        val beats = parseProposal(ai.complete(settings(), BeatPrompts.split(language, context)))
        if (beats.isEmpty()) throw BookAiException(BookAiException.Reason.EMPTY_RESULT)
        set(key, BeatJobState(proposal = beats))
    }

    /** 用拆好的节拍换掉还没动过的（没有稿、没锁定、没写进正文）。 */
    suspend fun adoptProposal(key: Key, proposal: List<BeatProposal>, replace: Boolean) {
        store.editNotes(key.workspaceId, key.chapterId) { notes ->
            val kept = if (replace) notes.beats.filter { it.drafts.isNotEmpty() || it.locked || it.inserted.isNotEmpty() } else notes.beats
            notes.copy(beats = kept + proposal.map { Beat(BookManifest.newId(), it.text, it.words) })
        }
        dismiss(key)
    }

    @Synchronized
    fun stop(key: Key) {
        jobs[key]?.cancel()
        _states.update { it - key }
    }

    /** 收起提示或拆节拍的结果。 */
    fun dismiss(key: Key) = _states.update { map -> if (map[key]?.running == true) map else map - key }

    private fun set(key: Key, state: BeatJobState) = _states.update { it + (key to state) }

    @Synchronized
    private fun start(key: Key, initial: BeatJobState?, block: suspend () -> Unit): Boolean {
        val previous = jobs[key]
        if (initial == null || previous?.isActive == true) return false
        set(key, initial)
        jobs[key] = scope.launch {
            previous?.join()
            try {
                block()
                _states.update { map -> if (map[key]?.proposal != null) map else map - key }
            } catch (cancelled: CancellationException) {
                _states.update { it - key }
                throw cancelled
            } catch (failure: BookAiException) {
                set(key, BeatJobState(aiError = failure.reason))
            } catch (failure: Exception) {
                set(key, BeatJobState(error = failure.message ?: failure.javaClass.simpleName))
            }
        }
        return true
    }

    private suspend fun writeOne(key: Key, beatId: String, note: String, modelId: Uuid?) {
        val notes = store.notes(key.workspaceId, key.chapterId)
        val beat = notes.beats.firstOrNull { it.id == beatId }?.takeUnless { it.locked } ?: return
        val (language, context) = context(key, notes)
        val basis = notes.basisOf(beatId)
        val current = settings()
        val model = BookAi.resolveModel(current, modelId)?.displayName.orEmpty()
        var text = ""
        suspend fun save() = store.editNotes(key.workspaceId, key.chapterId) {
            it.addDraft(beatId, BeatDraft(Uuid.random().toString(), text.trim(), model, note.trim(), System.currentTimeMillis(), basis))
        }
        try {
            ai.stream(current, BeatPrompts.write(language, context, beat.id, note), modelId).collect { partial ->
                text = partial
                _states.update { map -> map[key]?.let { map + (key to it.copy(text = partial)) } ?: map }
            }
        } catch (cancelled: CancellationException) {
            // 停下时看得见的文字留成一稿
            if (text.isNotBlank()) withContext(NonCancellable) { save() }
            throw cancelled
        }
        save()
    }

    private suspend fun context(key: Key, notes: ChapterNotes): Pair<BookPromptLanguage, BeatContext> {
        val book = store.open(key.workspaceId)
        val entry = book.entry(key.chapterId) ?: throw NoSuchElementException("Chapter not found")
        val language = BookPromptLanguage.of(repository.getById(key.workspaceId)?.bookPromptLanguage.orEmpty())
        val before = book.entries.takeWhile { it.id != entry.id }
        val synopses = before.filter { it.chapter.synopsis.isNotBlank() && !synopsisStale(key.workspaceId, it.id, it.chapter.synopsis, it.file?.sha256) }
            .map { "${it.number}. ${it.title}：${it.chapter.synopsis}" }
        val previous = before.lastOrNull { !it.missing }?.let { runCatching { store.read(key.workspaceId, it.path).content }.getOrNull() }.orEmpty()
        val own = entry.takeUnless { it.missing }?.let { runCatching { store.read(key.workspaceId, it.path).content }.getOrNull() }.orEmpty()
        return language to BeatContext(
            bookTitle = book.manifest.title,
            synopses = takeLastChars(synopses, MAX_SYNOPSES_CHARS),
            previousTail = tail(previous, MAX_TAIL_CHARS),
            chapterTitle = entry.title,
            synopsis = entry.chapter.synopsis,
            targetWords = entry.chapter.targetWords,
            ownText = tail(BeatPlacement.ownText(own, notes), MAX_TAIL_CHARS),
            beats = notes.beats,
        )
    }

    /** 梗概是从正文提取的、而正文后来改了，就不带。手写的一直带。 */
    private suspend fun synopsisStale(workspaceId: String, chapterId: String, synopsis: String, sha256: String?): Boolean {
        val outline = runCatching { store.notes(workspaceId, chapterId) }.getOrNull()?.outline ?: return false
        return outline.synopsis == synopsis && outline.hash != sha256
    }

    companion object {
        private const val MAX_TAIL_CHARS = 3000
        private const val MAX_SYNOPSES_CHARS = 8000
        private const val MAX_PROPOSALS = 30

        /** 最后 [limit] 个字，从一段的开头起。 */
        internal fun tail(text: String, limit: Int): String {
            val trimmed = text.trim()
            if (trimmed.length <= limit) return trimmed
            val cut = trimmed.takeLast(limit).dropWhile { it.isLowSurrogate() }
            // 只为了从段首起而丢掉一大半就不值了
            return cut.substringAfter('\n').takeIf { it.length * 2 >= limit }?.trim() ?: cut
        }

        private fun takeLastChars(lines: List<String>, limit: Int): List<String> {
            var used = 0
            return lines.takeLastWhile { line ->
                used += line.length
                used <= limit
            }
        }

        internal fun parseProposal(output: String): List<BeatProposal> = jsonLines(output).mapNotNull { row ->
            val text = row.text("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            BeatProposal(text, ((row["words"] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, 100_000))
        }.take(MAX_PROPOSALS)
    }
}

/** 顺序固定：要求 → 前情 → 本章节拍 → 已写好的节拍 → 现在写哪个。依次写下去时前面只增不改，前缀缓存一直有效。 */
internal object BeatPrompts {
    fun write(language: BookPromptLanguage, context: BeatContext, beatId: String, note: String): List<UIMessage> {
        val zh = language == BookPromptLanguage.ZH
        val messages = ArrayList<UIMessage>()
        messages += UIMessage.system(if (zh) SYSTEM_ZH else SYSTEM_EN)
        var opening = background(zh, context)
        context.beats.forEachIndexed { index, beat ->
            if (beat.id == beatId) {
                val request = ask(zh, index + 1, beat) + (if (note.isBlank()) "" else if (zh) "\n这次的要求：${note.trim()}" else "\nFor this one: ${note.trim()}")
                messages += UIMessage.user(opening + request)
                return messages
            }
            val text = beat.chosenDraft?.text ?: return@forEachIndexed
            messages += UIMessage.user(opening + ask(zh, index + 1, beat))
            messages += UIMessage.assistant(text)
            opening = ""
        }
        return messages
    }

    fun split(language: BookPromptLanguage, context: BeatContext): List<UIMessage> {
        val zh = language == BookPromptLanguage.ZH
        return listOf(
            UIMessage.system(if (zh) SPLIT_ZH else SPLIT_EN),
            UIMessage.user(
                background(zh, context) + if (zh) "请把这一章拆成节拍。" else "Break this chapter into beats.",
            ),
        )
    }

    private fun background(zh: Boolean, context: BeatContext): String = buildString {
        fun section(titleZh: String, titleEn: String, body: String) {
            if (body.isBlank()) return
            append("## ").append(if (zh) titleZh else titleEn).append('\n').append(body).append("\n\n")
        }
        section("作品", "Book", context.bookTitle)
        section("前几章梗概", "Earlier chapters", context.synopses.joinToString("\n"))
        section("上一章结尾", "End of the previous chapter", context.previousTail)
        section(
            "这一章", "This chapter",
            listOfNotNull(
                context.chapterTitle,
                context.synopsis.ifBlank { null },
                context.targetWords.takeIf { it > 0 }?.let { if (zh) "目标 $it 字" else "About $it words" },
            ).joinToString("\n"),
        )
        section("作者已经写好的内容", "Already written by the author", context.ownText)
        section(
            "这一章的节拍", "Beats of this chapter",
            context.beats.withIndex().joinToString("\n") { (index, beat) ->
                val words = if (beat.words <= 0) "" else if (zh) "（约 ${beat.words} 字）" else " (about ${beat.words} words)"
                "${index + 1}. ${beat.text.replace('\n', ' ')}$words"
            },
        )
    }

    private fun ask(zh: Boolean, number: Int, beat: Beat): String {
        val words = if (beat.words <= 0) "" else if (zh) "约 ${beat.words} 字。" else " About ${beat.words} words."
        return if (zh) "现在写第 $number 个节拍：${beat.text}\n$words" else "Now write beat $number: ${beat.text}\n$words"
    }

    private val SYSTEM_ZH = """
        你在为一部小说写正文。用户会给出前情和这一章的全部节拍，然后让你一个一个地写。
        - 只写指定的那个节拍，接着已经写好的正文往下写。不重复前文，不提前写后面的节拍，不在末尾总结或升华。
        - 人物、称呼、设定、人称、时态与已有正文一致，文风贴近上一章结尾和已写好的部分。
        - 把节拍写成场景：有动作、对话和具体的细节，不要复述节拍的句子。
        - 标了字数就尽量接近。只输出正文，不要标题、编号、说明或 Markdown 标记，每段一行。
    """.trimIndent()

    private val SYSTEM_EN = """
        You are writing the text of a novel. The user gives you what came before and all beats of this chapter, then asks for them one at a time.
        - Write only the beat asked for, continuing from the text so far. Do not repeat earlier text, do not run ahead into later beats, and do not sum up at the end.
        - Keep characters, names, setting, person and tense consistent; stay close to the style of the previous chapter's end and of what is already written.
        - Turn the beat into a scene with action, dialogue and concrete detail; do not paraphrase the beat itself.
        - Come close to the length if one is given. Output only the text: no title, number, remark or Markdown, one paragraph per line.
    """.trimIndent()

    private val SPLIT_ZH = """
        你在帮作者把小说的一章拆成节拍。每个节拍是一小段连续的戏：谁在什么处境下做了什么，局面因此怎样变化。
        - 用户已经写了节拍的，把它们当作计划：太大的拆开，缺的过渡补上，不改原意。没有就按这一章的梗概和前情来写。
        - 一章通常 3 到 8 个节拍，按发生顺序，前后有因果。给出目标字数时，各节拍字数之和约等于它。
        - 每行输出一个 JSON 对象，不要输出别的：{"text":"这个节拍写什么","words":字数}
    """.trimIndent()

    private val SPLIT_EN = """
        You help an author break a chapter of a novel into beats. A beat is one continuous piece of action: who does what in which situation, and how that changes things.
        - If the user already wrote beats, treat them as the plan: split what is too large, add missing transitions, keep the meaning. Otherwise work from the synopsis and what came before.
        - Usually 3 to 8 beats per chapter, in order, each leading to the next. If a target length is given, the beats add up to about that.
        - Output one JSON object per line and nothing else: {"text":"what this beat does","words":number}
    """.trimIndent()
}
