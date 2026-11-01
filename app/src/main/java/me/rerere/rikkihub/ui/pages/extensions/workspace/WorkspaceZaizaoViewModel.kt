package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.tuiqiao.Tuiqiao
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.zaizao.Zaizao
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.datastore.SettingsStore

/**
 * 再造 sheet of one file: which kept session it shows and whether the sheet is up. Sessions live in
 * [WorkspaceZaizaoCache]: hiding the sheet keeps them (drafts being written finish), and selecting the same
 * text again shows the same conversation.
 */
internal class WorkspaceZaizaoViewModel(
    private val cache: WorkspaceZaizaoCache,
    private val settingsStore: SettingsStore,
    private val fileKey: String,
) : ViewModel() {
    var entry: WorkspaceZaizaoEntry? by mutableStateOf(null)
        private set
    var visible by mutableStateOf(false)
        private set

    /** The shown session; null while the sheet is hidden. */
    val shown: WorkspaceZaizaoEntry? get() = entry?.takeIf { visible }

    /** Shows 再造 for `source[start, end)`: the kept session for this text, else a new one. Returns why the selection is unusable. */
    fun open(fileName: String, source: String, start: Int, end: Int): ZaizaoException.Reason? {
        val range = try {
            Zaizao.range(source, start, end)
        } catch (e: ZaizaoException) {
            return e.reason
        }
        val config = settingsStore.settingsFlow.value.zaizao
        entry = cache.lookup(fileKey, source, range)
            ?: cache.create(fileKey, fileName, source, range, config.includeAfter, config.defaultType)
        visible = true
        return null
    }

    fun expandToSentence(source: String) = expand(source) { s, e, format -> Tuiqiao.expandToSentence(source, s, e, format) }

    fun expandToParagraph(source: String) = expand(source) { s, e, format -> Tuiqiao.expandToParagraph(source, s, e, format) }

    /**
     * Before the first round only: grows the selection; shows the kept session for the larger text or a new one
     * carrying over the typed requirement, type and 带后文. Returns why the larger selection is unusable.
     */
    private fun expand(source: String, grow: (Int, Int, TuiqiaoFormat) -> IntRange): ZaizaoException.Reason? {
        val current = shown ?: return null
        if (current.started || !current.matches(source)) return null
        val grown = grow(current.liveStart, current.liveEnd, current.format)
        val range = try {
            Zaizao.range(source, grown.first, grown.last + 1)
        } catch (e: ZaizaoException) {
            return e.reason
        }
        if (range.first == current.liveStart && range.last + 1 == current.liveEnd) return null
        val next = cache.lookup(fileKey, source, range)
            ?: cache.create(fileKey, current.fileName, source, range, current.includeAfter, current.type).also {
                it.draftText = current.draftText
            }
        cache.remove(current)
        entry = next
        return null
    }

    /** Sends the typed requirement as the next round; returns why it was not sent. */
    fun send(): ZaizaoException.Reason? {
        val current = shown ?: return null
        return cache.send(current, current.draftText)
    }

    fun another(round: WorkspaceZaizaoRound) {
        shown?.let { cache.another(it, round) }
    }

    fun retry(draft: WorkspaceZaizaoDraft) {
        shown?.let { cache.retry(it, draft) }
    }

    fun stop() {
        shown?.let(cache::stop)
    }

    fun continueFrom(draft: WorkspaceZaizaoDraft) {
        shown?.pinnedBase = draft
    }

    fun forgetRecent(requirement: String) {
        viewModelScope.launch { settingsStore.update { it.copy(zaizao = it.zaizao.forget(requirement)) } }
    }

    /** Hides the sheet; the session stays in the cache (also after replacing, so undo + select again finds it). */
    fun close() {
        visible = false
    }
}
