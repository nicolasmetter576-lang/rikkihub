package me.rerere.rikkihub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.rikkihub.data.ai.QuotaTracker
import me.rerere.rikkihub.data.ai.UsageRange
import me.rerere.rikkihub.data.ai.UsageSource
import me.rerere.rikkihub.data.ai.UsageStatsReport
import me.rerere.rikkihub.data.ai.UsageStatsService
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.dao.ConversationDAO
import me.rerere.rikkihub.data.db.dao.MessageNodeDAO
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val failed: Boolean = false,
    val isRefreshing: Boolean = false,
    val range: UsageRange = UsageRange.D30,
    val report: UsageStatsReport? = null,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    /** 每天的对话请求数（热力图） */
    val requestsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val usageRecordDAO: UsageRecordDAO,
    private val statsService: UsageStatsService,
    private val quotaTracker: QuotaTracker,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()
    private var reportJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                usageRecordDAO.observeRevision(),
                settingsStore.settingsFlow.map { it.providers }.distinctUntilChanged(),
            ) { _, _ -> Unit }.collect {
                loadReport()
                try {
                    loadOverview()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    _stats.update { it.copy(failed = true) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (_stats.value.isRefreshing) return
        viewModelScope.launch {
            _stats.update { it.copy(isRefreshing = true) }
            try {
                quotaTracker.refreshAllIfStale()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _stats.update { it.copy(failed = true) }
            } finally {
                _stats.update { it.copy(isRefreshing = false) }
            }
            loadReport()
        }
    }

    fun setRange(range: UsageRange) {
        if (range == _stats.value.range) return
        _stats.update { it.copy(range = range) }
        loadReport()
    }

    fun delete(deletion: StatsDeletion) {
        viewModelScope.launch {
            val providers = settingsStore.settingsFlow.first { !it.init }.providers
            when (deletion) {
                is StatsDeletion.Model -> statsService.deleteModelUsage(deletion.key)
                is StatsDeletion.Provider -> statsService.deleteProviderUsage(providers, deletion.providerId)
            }
            // 用量表行数变化会触发重新加载；只删了额度读数、行数恰好不变时也刷新一次
            loadReport()
        }
    }

    private fun loadReport() {
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            val range = _stats.value.range
            _stats.update { it.copy(isLoading = true, failed = false) }
            try {
                val settings = settingsStore.settingsFlow.first { !it.init }
                val report = statsService.report(range, settings.providers)
                _stats.update { it.copy(isLoading = false, failed = false, report = report) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _stats.update { it.copy(isLoading = false, failed = true) }
            }
        }
    }

    private suspend fun loadOverview() {
        val today = LocalDate.now()
        val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).minusWeeks(52)
        val since = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val perDay = usageRecordDAO.getRequestCountPerDay(
            listOf(UsageSource.CHAT.name, UsageSource.CLI.name),
            since,
        )
            .mapNotNull { entry -> runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull() }
            .toMap()
        val conversations = conversationDAO.countAll()
        val messages = messageNodeDAO.countAll()
        val launchCount = settingsStore.launchCountFlow.first()
        _stats.update {
            it.copy(
                totalConversations = conversations,
                totalMessages = messages,
                requestsPerDay = perDay,
                launchCount = launchCount,
            )
        }
    }
}
