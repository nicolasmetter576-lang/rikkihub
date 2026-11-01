package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.tuiqiao.TuiqiaoFormat
import me.rerere.rikkihub.data.ai.zaizao.Zaizao
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoException
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoPrepared
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoProgress
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoRequest
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoRound
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoTurn
import me.rerere.rikkihub.data.ai.zaizao.ZaizaoType
import me.rerere.rikkihub.data.datastore.Settings

internal sealed interface WorkspaceZaizaoStatus {
    data object Loading : WorkspaceZaizaoStatus
    data object Done : WorkspaceZaizaoStatus
    /** Stopped by the user; whatever was written stays usable. */
    data object Stopped : WorkspaceZaizaoStatus
    data class Failed(val reason: ZaizaoException.Reason?, val message: String?) : WorkspaceZaizaoStatus
}

/** One draft of a round: what streamed in so far and the model's whole reply (sent back as history later). */
internal class WorkspaceZaizaoDraft(val round: WorkspaceZaizaoRound, val number: Int, internal val seq: Int) {
    var reasoning by mutableStateOf("")
    var plan by mutableStateOf("")
    var result by mutableStateOf("")
    var output by mutableStateOf("")
    var status: WorkspaceZaizaoStatus by mutableStateOf(WorkspaceZaizaoStatus.Loading)
    internal var job: Job? = null

    /** Has text and is not being written: can be copied, replaced or continued from. */
    val usable: Boolean get() = result.isNotBlank() && status != WorkspaceZaizaoStatus.Loading

    internal fun clear() {
        reasoning = ""
        plan = ""
        result = ""
        output = ""
        status = WorkspaceZaizaoStatus.Loading
    }
}

/** One round: its type and requirement, the draft it builds on ([base], null = the original) and its drafts (换一版 adds more). */
internal class WorkspaceZaizaoRound(val number: Int, val type: ZaizaoType, val requirement: String, val base: WorkspaceZaizaoDraft?) {
    val drafts = mutableStateListOf<WorkspaceZaizaoDraft>()
}

/**
 * One 再造 session of one selection. [source]/[start]/[end] are the document as the first round sent it (every later
 * round replays that first message); they follow the editor until a round is sent. [liveStart]/[liveEnd] are where
 * [original] is in the editor now, for replacing.
 */
internal class WorkspaceZaizaoEntry(
    val fileKey: String,
    val fileName: String,
    source: String,
    range: IntRange,
    includeAfter: Boolean,
    type: ZaizaoType,
) {
    var source: String by mutableStateOf(source)
        private set
    var start by mutableStateOf(range.first)
        private set
    var end by mutableStateOf(range.last + 1)
        private set
    var liveStart by mutableStateOf(range.first)
        private set
    var liveEnd by mutableStateOf(range.last + 1)
        private set
    var includeAfter by mutableStateOf(includeAfter)
    var type by mutableStateOf(type)
    /** The requirement being typed (kept while the sheet is hidden). */
    var draftText by mutableStateOf("")
    /** The draft picked with 接着改这版; null = follow the newest. */
    var pinnedBase: WorkspaceZaizaoDraft? by mutableStateOf(null)
    val rounds = mutableStateListOf<WorkspaceZaizaoRound>()
    private var nextSeq = 0

    val original: String get() = source.substring(start, end)
    val format: TuiqiaoFormat get() = TuiqiaoFormat.of(fileName)
    val started: Boolean get() = rounds.isNotEmpty()
    val busy: Boolean get() = rounds.any { round -> round.drafts.any { it.status == WorkspaceZaizaoStatus.Loading } }

    /**
     * What the next round builds on: the picked draft, else the newest one (also while it is being written),
     * else, when the newest failed, the newest usable one; null = the original.
     */
    val base: WorkspaceZaizaoDraft?
        get() {
            pinnedBase?.takeIf { it.usable }?.let { return it }
            val drafts = rounds.flatMap { it.drafts }
            val newest = drafts.maxByOrNull { it.seq } ?: return null
            if (newest.usable || newest.status == WorkspaceZaizaoStatus.Loading) return newest
            return drafts.filter { it.usable }.maxByOrNull { it.seq }
        }

    /** Whether `text[liveStart, liveEnd)` is still the original. */
    fun matches(text: String): Boolean = liveEnd <= text.length && text.substring(liveStart, liveEnd) == original

    /** The rounds from the original up to [draft], as history for the model. */
    fun history(draft: WorkspaceZaizaoDraft?): List<ZaizaoTurn> =
        generateSequence(draft) { it.round.base }
            .map { ZaizaoTurn(ZaizaoRound(it.round.type, it.round.requirement), it.output) }
            .toList().asReversed()

    internal fun newDraft(round: WorkspaceZaizaoRound): WorkspaceZaizaoDraft =
        WorkspaceZaizaoDraft(round, round.drafts.size + 1, nextSeq++).also { round.drafts += it }

    internal fun moveTo(range: IntRange) {
        liveStart = range.first
        liveEnd = range.last + 1
    }

    /** Before the first round: follow the editor's current text. */
    internal fun refresh(text: String, range: IntRange) {
        if (started) return
        source = text
        start = range.first
        end = range.last + 1
        moveTo(range)
    }

    internal fun cancel() {
        rounds.forEach { round -> round.drafts.forEach { it.job?.cancel() } }
    }
}

/**
 * 再造 sessions kept in memory for as long as the process lives, so a hidden sheet and a reopened file still show
 * them. Keyed by file and the trimmed selected text; the most recently used [PER_FILE] per file and [TOTAL] overall
 * are kept, evicted ones cancelled. Drafts run on [scope] (not the sheet's), so hiding the sheet lets them finish.
 * Main-thread confined like [WorkspaceTuiqiaoCache].
 */
internal class WorkspaceZaizaoCache(
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val generate: (Settings, ZaizaoPrepared) -> Flow<ZaizaoProgress>,
    /** Records a sent requirement in the recent list. */
    private val remember: (String) -> Unit,
) {
    private val entries = LinkedHashMap<Pair<String, String>, WorkspaceZaizaoEntry>(16, 0.75f, true)

    fun find(fileKey: String, original: String): WorkspaceZaizaoEntry? = entries[fileKey to original]

    /** The kept session for `text[range]`, moved to where that text now is; null when there is none. */
    fun lookup(fileKey: String, text: String, range: IntRange): WorkspaceZaizaoEntry? {
        val entry = find(fileKey, text.substring(range.first, range.last + 1)) ?: return null
        entry.refresh(text, range)
        entry.moveTo(range)
        return entry
    }

    /** A new (not yet started) session for `text[range]`, replacing any kept one for the same text. */
    fun create(
        fileKey: String,
        fileName: String,
        text: String,
        range: IntRange,
        includeAfter: Boolean,
        type: ZaizaoType,
    ): WorkspaceZaizaoEntry {
        val entry = WorkspaceZaizaoEntry(fileKey, fileName, text, range, includeAfter, type)
        entries.remove(fileKey to entry.original)?.cancel()
        entries[fileKey to entry.original] = entry
        evict(fileKey)
        return entry
    }

    /**
     * Sends [requirement] as a new round on the entry's current base. Returns why it was not sent: a draft is still
     * being written (null reason, nothing happens), the requirement is empty or too long, or the chain is too long.
     */
    fun send(entry: WorkspaceZaizaoEntry, requirement: String): ZaizaoException.Reason? {
        if (entry.busy) return null
        try {
            Zaizao.checkRequirement(requirement)
        } catch (e: ZaizaoException) {
            return e.reason
        }
        val base = entry.base
        if (entry.history(base).size + 1 > Zaizao.MAX_ROUNDS) return ZaizaoException.Reason.TOO_MANY_ROUNDS
        val text = requirement.trim()
        val round = WorkspaceZaizaoRound(entry.rounds.size + 1, entry.type, text, base)
        entry.rounds += round
        entry.draftText = ""
        entry.pinnedBase = null
        run(entry, entry.newDraft(round))
        remember(text)
        return null
    }

    /** 换一版: another draft of [round] (same base, type and requirement), told to differ from the drafts so far. */
    fun another(entry: WorkspaceZaizaoEntry, round: WorkspaceZaizaoRound) {
        if (entry.busy || round !in entry.rounds) return
        val avoid = round.drafts.filter { it.usable }.map { it.result }
        run(entry, entry.newDraft(round), avoid)
    }

    /** Writes [draft] again (after it failed or was stopped). */
    fun retry(entry: WorkspaceZaizaoEntry, draft: WorkspaceZaizaoDraft) {
        if (entry.busy) return
        val avoid = draft.round.drafts.filter { it !== draft && it.usable }.map { it.result }
        draft.clear()
        run(entry, draft, avoid)
    }

    /** Stops whatever is being written in [entry]; what was written so far stays. */
    fun stop(entry: WorkspaceZaizaoEntry) {
        entry.rounds.forEach { round ->
            round.drafts.filter { it.status == WorkspaceZaizaoStatus.Loading }.forEach { draft ->
                draft.status = WorkspaceZaizaoStatus.Stopped
                draft.job?.cancel()
            }
        }
    }

    /** Drops [entry] (e.g. a narrower selection that was expanded before its first round), cancelling its drafts. */
    fun remove(entry: WorkspaceZaizaoEntry) {
        val key = entry.fileKey to entry.original
        if (entries[key] === entry) entries.remove(key)
        entry.cancel()
    }

    internal val size: Int get() = entries.size

    private fun evict(fileKey: String) {
        val sameFile = entries.entries.filter { it.key.first == fileKey }
        sameFile.take((sameFile.size - PER_FILE).coerceAtLeast(0)).forEach { drop(it.key) }
        entries.keys.take((entries.size - TOTAL).coerceAtLeast(0)).forEach { drop(it) }
    }

    private fun drop(key: Pair<String, String>) {
        entries.remove(key)?.cancel()
    }

    private fun run(entry: WorkspaceZaizaoEntry, draft: WorkspaceZaizaoDraft, avoid: List<String> = emptyList()) {
        val round = draft.round
        val request = ZaizaoRequest(
            fileName = entry.fileName,
            source = entry.source,
            start = entry.start,
            end = entry.end,
            includeAfter = entry.includeAfter,
            history = entry.history(round.base),
            round = ZaizaoRound(round.type, round.requirement),
            avoid = avoid,
        )
        draft.status = WorkspaceZaizaoStatus.Loading
        draft.job = scope.launch {
            try {
                val current = settings()
                generate(current, Zaizao.prepare(current.zaizao, request)).collect { progress ->
                    draft.reasoning = progress.reasoning
                    draft.plan = progress.plan
                    draft.result = progress.result
                    draft.output = progress.output
                    if (progress.done) draft.status = WorkspaceZaizaoStatus.Done
                }
                if (draft.status == WorkspaceZaizaoStatus.Loading) draft.status = WorkspaceZaizaoStatus.Done
            } catch (cancelled: CancellationException) {
                if (draft.status == WorkspaceZaizaoStatus.Loading) draft.status = WorkspaceZaizaoStatus.Stopped
                throw cancelled
            } catch (e: ZaizaoException) {
                draft.status = WorkspaceZaizaoStatus.Failed(e.reason, null)
            } catch (e: Exception) {
                draft.status = WorkspaceZaizaoStatus.Failed(null, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val PER_FILE = 10
        const val TOTAL = 50
    }
}
