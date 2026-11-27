package me.rerere.rikkihub.ui.pages.extensions.workspace.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.book.BookAiException
import me.rerere.rikkihub.data.ai.book.BookAlias
import me.rerere.rikkihub.data.ai.book.BookAliasFinder
import me.rerere.rikkihub.data.ai.tools.BookPromptLanguage
import me.rerere.rikkihub.data.book.Book
import me.rerere.rikkihub.data.book.BookReplace
import me.rerere.rikkihub.data.book.BookReplacePreview
import me.rerere.rikkihub.data.book.BookReplaceQuery
import me.rerere.rikkihub.data.book.BookReplaceResult
import me.rerere.rikkihub.data.book.BookReplaceTarget
import me.rerere.rikkihub.data.book.BookReplacer
import me.rerere.rikkihub.data.book.BookStore
import me.rerere.rikkihub.data.book.history.BookHistory
import me.rerere.rikkihub.data.book.BookUndoEntry
import me.rerere.rikkihub.data.book.BookUndoResult
import me.rerere.rikkihub.data.book.ReplacePair
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository

/** 一处命中的身份：哪个文件、从哪开始。 */
data class HitKey(val path: String, val start: Int)

/** 书外提到旧名的地方，只提示不改。 */
data class BookMentions(val lorebooks: List<String>, val characters: List<String>) {
    val isEmpty: Boolean get() = lorebooks.isEmpty() && characters.isEmpty()
}

internal suspend fun bookMentions(words: List<String>, settings: Settings, characters: CharacterRepository): BookMentions {
    val lorebooks = settings.lorebooks.filter { book ->
        book.entries.any { entry -> words.any { word -> word in entry.content || entry.key.any { word in it } || entry.keysecondary.any { word in it } } }
    }.map { it.name }
    val ids = words.flatMapTo(HashSet()) { characters.search(it) }
    return BookMentions(lorebooks, if (ids.isEmpty()) emptyList() else characters.summaries().filter { it.id in ids }.map { it.name })
}

data class BookReplaceUiState(
    val book: Book? = null,
    val pairs: List<ReplacePair> = listOf(ReplacePair("", "")),
    val ignoreCase: Boolean = true,
    /** null = 全书。 */
    val volumeId: String? = null,
    val includeUnlisted: Boolean = false,
    val busy: Boolean = false,
    val query: BookReplaceQuery? = null,
    val preview: BookReplacePreview? = null,
    val unselected: Set<HitKey> = emptySet(),
    val mentions: BookMentions? = null,
    val result: BookReplaceResult? = null,
    val undone: BookUndoResult? = null,
    val history: List<BookUndoEntry> = emptyList(),
    val findingAliases: Boolean = false,
    val aliases: List<BookAlias>? = null,
    val error: String? = null,
    val aiError: BookAiException.Reason? = null,
) {
    val canSearch: Boolean get() = !busy && pairs.any { it.find.isNotBlank() }
    val selectedCount: Int get() = preview?.files.orEmpty().filterNot { it.locked }.sumOf { file -> file.hits.count { HitKey(file.path, it.start) !in unselected } }

    /** 落在锁定段落里的命中（整章锁定的章节不算，它们本来就不替换）。 */
    val lockedHits: List<HitKey> get() = preview?.files.orEmpty().filterNot { it.locked }
        .flatMap { file -> file.hits.filter { it.locked }.map { HitKey(file.path, it.start) } }

    /** 有没有把文字直接删掉的组（替换成空）。 */
    val deletes: Boolean get() = query?.pairs.orEmpty().any { it.replace.isEmpty() }
}

class BookReplaceVM(
    private val workspaceId: String,
    private val store: BookStore,
    private val replacer: BookReplacer,
    private val aliasFinder: BookAliasFinder,
    private val settingsStore: SettingsStore,
    private val workspaceRepository: WorkspaceRepository,
    private val mentions: suspend (List<String>) -> BookMentions,
    private val history: BookHistory,
) : ViewModel() {
    private val _state = MutableStateFlow(BookReplaceUiState())
    val state = _state.asStateFlow()
    private var aliasJob: Job? = null

    init {
        work { _state.update { it.copy(book = store.open(workspaceId), history = replacer.history(workspaceId)) } }
    }

    /** 改了查找条件，旧的结果就不算数了。 */
    private fun changeQuery(transform: (BookReplaceUiState) -> BookReplaceUiState) =
        _state.update { transform(it).copy(preview = null, query = null, unselected = emptySet(), mentions = null, result = null, undone = null) }

    fun setPair(index: Int, pair: ReplacePair) = changeQuery { it.copy(pairs = it.pairs.toMutableList().apply { set(index, pair) }, aliases = null) }

    fun addPair(pair: ReplacePair = ReplacePair("", "")) = changeQuery {
        if (it.pairs.size >= BookReplace.MAX_PAIRS) it else it.copy(pairs = it.pairs + pair)
    }

    fun removePair(index: Int) = changeQuery { state ->
        state.copy(pairs = state.pairs.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(ReplacePair("", "")) }, aliases = null)
    }

    fun setIgnoreCase(value: Boolean) = changeQuery { it.copy(ignoreCase = value) }

    fun setVolume(id: String?) = changeQuery { it.copy(volumeId = id) }

    fun setIncludeUnlisted(value: Boolean) = changeQuery { it.copy(includeUnlisted = value) }

    private fun paths(state: BookReplaceUiState): List<String> {
        val book = state.book ?: return emptyList()
        val chapters = book.entries.filter { !it.missing && (state.volumeId == null || it.volumeId == state.volumeId) }.map { it.path }
        return if (state.includeUnlisted && state.volumeId == null) chapters + book.unlisted else chapters
    }

    fun search() = work { runSearch() }

    private suspend fun runSearch() {
        val book = store.open(workspaceId)
        _state.update { it.copy(book = book, result = null, undone = null) }
        val current = _state.value
        val pairs = current.pairs.filter { it.find.isNotBlank() }.distinctBy { it.find }
        val query = BookReplaceQuery(pairs, current.ignoreCase, paths(current))
        val preview = replacer.preview(workspaceId, query)
        _state.update { it.copy(query = query, preview = preview, unselected = emptySet()) }
        val found = mentions(pairs.map { it.find })
        _state.update { it.copy(mentions = found) }
    }

    fun toggle(key: HitKey) = _state.update {
        it.copy(unselected = if (key in it.unselected) it.unselected - key else it.unselected + key)
    }

    private fun selectable() = _state.value.preview?.files.orEmpty().filterNot { it.locked }

    fun toggleFile(path: String) = toggleAll(selectable().filter { it.path == path }.flatMap { f -> f.hits.map { HitKey(f.path, it.start) } })

    fun toggleEverything() = toggleAll(selectable().flatMap { f -> f.hits.map { HitKey(f.path, it.start) } })

    /** 锁定段落里的命中一键全部取消 / 全部勾选。 */
    fun toggleLocked() = toggleAll(_state.value.lockedHits)

    /** 预览顶部“第 N 章已锁定 · 解锁”：解锁后重新搜索。 */
    fun unlockChapter(path: String) = work {
        history.unlock(workspaceId, path, chapter = true, passageIds = emptySet())
        runSearch()
    }

    private fun toggleAll(keys: List<HitKey>) = _state.update { state ->
        state.copy(unselected = if (keys.any { it in state.unselected }) state.unselected - keys.toSet() else state.unselected + keys)
    }

    fun replace() = work {
        val current = _state.value
        val query = current.query ?: return@work
        val targets = current.preview?.files.orEmpty().filterNot { it.locked }.mapNotNull { file ->
            val starts = file.hits.map { it.start }.filter { HitKey(file.path, it) !in current.unselected }.toSet()
            if (starts.isEmpty()) null else BookReplaceTarget(file.path, file.revision, starts)
        }
        val result = replacer.apply(workspaceId, query, targets)
        _state.update { it.copy(preview = null, query = null, result = result, history = replacer.history(workspaceId)) }
    }

    fun undo(id: String) = work {
        val undone = replacer.undo(workspaceId, id)
        _state.update { it.copy(undone = undone, result = null, preview = null, query = null, history = replacer.history(workspaceId)) }
    }

    fun findAliases(pair: ReplacePair) {
        aliasJob?.cancel()
        aliasJob = viewModelScope.launch {
            _state.update { it.copy(findingAliases = true, aliases = null, error = null, aiError = null) }
            try {
                val language = BookPromptLanguage.of(workspaceRepository.getById(workspaceId)?.bookPromptLanguage.orEmpty())
                val found = aliasFinder.find(settingsStore.settingsFlow.first { !it.init }, workspaceId, language, pair.find, pair.replace, paths(_state.value))
                _state.update { state -> state.copy(aliases = found.filter { alias -> state.pairs.none { it.find == alias.alias } }) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: BookAiException) {
                // 没输出就是没有别称
                _state.update { if (e.reason == BookAiException.Reason.EMPTY_RESULT) it.copy(aliases = emptyList()) else it.copy(aiError = e.reason) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(findingAliases = false) }
            }
        }
    }

    fun cancelAliases() = aliasJob?.cancel()

    fun takeAlias(alias: BookAlias) {
        addPair(ReplacePair(alias.alias, alias.replacement))
        _state.update { state -> state.copy(aliases = state.aliases?.minus(alias)?.ifEmpty { null }) }
    }

    fun clearError() = _state.update { it.copy(error = null, aiError = null) }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
