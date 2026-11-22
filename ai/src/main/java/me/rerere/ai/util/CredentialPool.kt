package me.rerere.ai.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.provider.CredentialRotationStrategy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.VertexServiceAccount
import me.rerere.ai.provider.displayLabel
import me.rerere.ai.provider.effectiveApiKeyEntries
import me.rerere.ai.provider.effectiveServiceAccounts
import me.rerere.ai.ui.StreamChunk
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class PooledCredential<T>(
    val id: String,
    val displayLabel: String,
    val value: T,
)

enum class CredentialHealthStatus {
    HEALTHY,
    COOLING_DOWN,
    AUTH_ERROR,
}

data class CredentialRuntimeSnapshot(
    val credentialId: String,
    val status: CredentialHealthStatus,
    val cooldownUntil: Long,
    val remainingMillis: Long,
    val consecutiveRateLimits: Int,
    val lastStatusCode: Int?,
    val lastErrorSummary: String?,
    /** modelId (or "default") -> cooldownUntil epoch millis */
    val modelCooldowns: Map<String, Long> = emptyMap(),
)

data class CredentialSwitchEvent(
    val fromLabel: String,
    val toLabel: String,
    val statusCode: Int?,
    val attempt: Int,
    val total: Int,
)

class CredentialSwitchObserver(
    val onSwitch: (CredentialSwitchEvent) -> Unit,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CredentialSwitchObserver>
}

/** 仅附着非敏感标签（备注 / projectId / 脱敏尾号）供请求日志展示。 */
data class CredentialLogTag(val label: String)

class UpstreamStreamRateLimitException(
    message: String,
    val statusCode: Int,
) : IOException(message)

class ServiceAccountAuthException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 内存运行态凭证池调度器：支持轮询（ROUND_ROBIN）与故障转移（FAILOVER），
 * 按 `(providerId, credentialId, modelId)` 隔离 429/503 冷却，按凭证隔离 401/403 与 SA 鉴权异常，
 * 并在请求首包尚未发出前自动无缝切换至下一个健康凭证。
 */
class CredentialPool(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var logicalTick = 0L
    private val lastUsedTick = HashMap<String, MutableMap<String, Long>>()
    private val rateLimits = HashMap<RateLimitKey, RateLimitState>()
    private val authErrors = HashMap<CredKey, AuthErrorState>()

    private val _stateVersion = MutableStateFlow(0L)
    val stateVersion: StateFlow<Long> = _stateVersion.asStateFlow()

    private data class RateLimitKey(
        val providerId: String,
        val credId: String,
        val modelId: String?,
    )

    private data class CredKey(
        val providerId: String,
        val credId: String,
    )

    private data class RateLimitState(
        val consecutive: Int,
        val cooldownUntil: Long,
        val lastStatusCode: Int,
    )

    private data class AuthErrorState(
        val until: Long,
        val statusCode: Int?,
        val summary: String,
    )

    private data class Selection<T>(
        val credential: PooledCredential<T>,
        val isProbe: Boolean,
    )

    private sealed interface FailureKind {
        val statusCode: Int?

        data class RateLimited(
            override val statusCode: Int,
            val retryAfterMillis: Long?,
        ) : FailureKind

        data class AuthError(
            override val statusCode: Int?,
            val summary: String,
        ) : FailureKind
    }

    suspend fun <T, R> executeWithFailover(
        providerId: String,
        modelId: String?,
        candidates: List<PooledCredential<T>>,
        strategy: CredentialRotationStrategy,
        maxSwitches: Int = DEFAULT_MAX_SWITCHES,
        block: suspend (PooledCredential<T>) -> R,
    ): R {
        require(candidates.isNotEmpty()) { "Credential candidates cannot be empty" }
        val excluded = HashSet<String>()
        var selection = selectNext(providerId, modelId, candidates, strategy, excluded)
            ?: error("No credential available")
        var switches = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val result = block(selection.credential)
                recordSuccess(providerId, modelId, selection.credential.id)
                return result
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val failure = classifyFailure(error)
                if (failure != null) {
                    recordFailure(providerId, modelId, selection.credential.id, failure)
                }
                if (failure == null || selection.isProbe || switches >= maxSwitches) {
                    throw error
                }
                excluded.add(selection.credential.id)
                val next = selectNext(providerId, modelId, candidates, strategy, excluded)
                if (next == null || next.isProbe) {
                    throw error
                }
                switches++
                currentCoroutineContext()[CredentialSwitchObserver]?.onSwitch?.invoke(
                    CredentialSwitchEvent(
                        fromLabel = selection.credential.displayLabel,
                        toLabel = next.credential.displayLabel,
                        statusCode = failure.statusCode,
                        attempt = switches + 1,
                        total = candidates.size.coerceAtMost(maxSwitches + 1),
                    )
                )
                selection = next
            }
        }
    }

    fun <T> streamWithFailover(
        providerId: String,
        modelId: String?,
        candidates: List<PooledCredential<T>>,
        strategy: CredentialRotationStrategy,
        maxSwitches: Int = DEFAULT_MAX_SWITCHES,
        streamFactory: suspend (PooledCredential<T>) -> Flow<StreamChunk>,
    ): Flow<StreamChunk> = flow {
        require(candidates.isNotEmpty()) { "Credential candidates cannot be empty" }
        val excluded = HashSet<String>()
        var selection = selectNext(providerId, modelId, candidates, strategy, excluded)
            ?: error("No credential available")
        var switches = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            var emittedAny = false
            try {
                streamFactory(selection.credential).collect { chunk ->
                    emittedAny = true
                    emit(chunk)
                }
                recordSuccess(providerId, modelId, selection.credential.id)
                return@flow
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val failure = classifyFailure(error)
                if (failure != null) {
                    recordFailure(providerId, modelId, selection.credential.id, failure)
                }
                if (emittedAny || failure == null || selection.isProbe || switches >= maxSwitches) {
                    throw error
                }
                excluded.add(selection.credential.id)
                val next = selectNext(providerId, modelId, candidates, strategy, excluded)
                if (next == null || next.isProbe) {
                    throw error
                }
                switches++
                currentCoroutineContext()[CredentialSwitchObserver]?.onSwitch?.invoke(
                    CredentialSwitchEvent(
                        fromLabel = selection.credential.displayLabel,
                        toLabel = next.credential.displayLabel,
                        statusCode = failure.statusCode,
                        attempt = switches + 1,
                        total = candidates.size.coerceAtMost(maxSwitches + 1),
                    )
                )
                selection = next
            }
        }
    }

    fun snapshotsFor(
        providerId: String,
        credentialIds: List<String>,
    ): Map<String, CredentialRuntimeSnapshot> = synchronized(lock) {
        val now = clock()
        pruneStaleLocked(now)
        credentialIds.distinct().associateWith { credId ->
            val auth = authErrors[CredKey(providerId, credId)]?.takeIf { it.until > now }
            val activeRateLimits = rateLimits.entries.filter { (key, state) ->
                key.providerId == providerId && key.credId == credId && state.cooldownUntil > now
            }
            val modelCooldowns = activeRateLimits.associate { (key, state) ->
                (key.modelId?.takeIf { it.isNotBlank() } ?: "default") to state.cooldownUntil
            }
            val maxRateCooldown = activeRateLimits.maxByOrNull { it.value.cooldownUntil }
            when {
                auth != null -> CredentialRuntimeSnapshot(
                    credentialId = credId,
                    status = CredentialHealthStatus.AUTH_ERROR,
                    cooldownUntil = auth.until,
                    remainingMillis = (auth.until - now).coerceAtLeast(0L),
                    consecutiveRateLimits = maxRateCooldown?.value?.consecutive ?: 0,
                    lastStatusCode = auth.statusCode,
                    lastErrorSummary = auth.summary,
                    modelCooldowns = modelCooldowns,
                )

                maxRateCooldown != null -> CredentialRuntimeSnapshot(
                    credentialId = credId,
                    status = CredentialHealthStatus.COOLING_DOWN,
                    cooldownUntil = maxRateCooldown.value.cooldownUntil,
                    remainingMillis = (maxRateCooldown.value.cooldownUntil - now).coerceAtLeast(0L),
                    consecutiveRateLimits = maxRateCooldown.value.consecutive,
                    lastStatusCode = maxRateCooldown.value.lastStatusCode,
                    lastErrorSummary = "HTTP ${maxRateCooldown.value.lastStatusCode}",
                    modelCooldowns = modelCooldowns,
                )

                else -> CredentialRuntimeSnapshot(
                    credentialId = credId,
                    status = CredentialHealthStatus.HEALTHY,
                    cooldownUntil = 0L,
                    remainingMillis = 0L,
                    consecutiveRateLimits = 0,
                    lastStatusCode = null,
                    lastErrorSummary = null,
                    modelCooldowns = emptyMap(),
                )
            }
        }
    }

    fun resetProvider(providerId: String) {
        synchronized(lock) {
            rateLimits.keys.removeIf { it.providerId == providerId }
            authErrors.keys.removeIf { it.providerId == providerId }
            _stateVersion.value += 1
        }
    }

    fun resetCredential(providerId: String, credentialId: String) {
        synchronized(lock) {
            rateLimits.keys.removeIf { it.providerId == providerId && it.credId == credentialId }
            authErrors.remove(CredKey(providerId, credentialId))
            _stateVersion.value += 1
        }
    }

    /** 保存配置后，对内容或启用状态发生变化、或被移除的凭据清理旧冷却/异常态。 */
    fun resetChanged(
        providerId: String,
        previous: ProviderSetting,
        next: ProviderSetting,
    ) {
        fun fingerprints(setting: ProviderSetting): Map<String, String> = buildMap {
            setting.effectiveApiKeyEntries().forEach { entry ->
                put("key:${entry.id}", "${entry.enabled}:${entry.key}")
            }
            if (setting is ProviderSetting.Google) {
                setting.effectiveServiceAccounts().forEach { acc ->
                    put(
                        "sa:${acc.id}",
                        "${acc.enabled}:${acc.projectId}:${acc.serviceAccountEmail}:${acc.location}:${acc.privateKey}",
                    )
                }
            }
        }
        val prevMap = fingerprints(previous)
        val nextMap = fingerprints(next)
        (prevMap.keys + nextMap.keys)
            .filter { key -> prevMap[key] != nextMap[key] }
            .map { key -> key.substringAfter(':') }
            .distinct()
            .forEach { credId -> resetCredential(providerId, credId) }
    }

    private fun <T> selectNext(
        providerId: String,
        modelId: String?,
        candidates: List<PooledCredential<T>>,
        strategy: CredentialRotationStrategy,
        excludedIds: Set<String>,
    ): Selection<T>? = synchronized(lock) {
        val now = clock()
        pruneStaleLocked(now)
        val eligible = candidates.filter { it.id !in excludedIds }
        if (eligible.isEmpty()) return null

        val tickMap = lastUsedTick.getOrPut(providerId) { HashMap() }

        fun activeAuth(credId: String): AuthErrorState? =
            authErrors[CredKey(providerId, credId)]?.takeIf { it.until > now }

        fun activeRateLimit(credId: String): RateLimitState? =
            rateLimits[RateLimitKey(providerId, credId, modelId)]?.takeIf { it.cooldownUntil > now }

        val healthy = eligible.filter { activeAuth(it.id) == null && activeRateLimit(it.id) == null }
        if (healthy.isNotEmpty()) {
            val chosen = when (strategy) {
                CredentialRotationStrategy.ROUND_ROBIN -> healthy.minBy { tickMap[it.id] ?: 0L }
                CredentialRotationStrategy.FAILOVER -> healthy.first()
            }
            tickMap[chosen.id] = ++logicalTick
            return Selection(chosen, isProbe = false)
        }

        // 本轮已经试过至少一个凭证且剩余都在冷却中时，不再继续轰炸其余冷却项
        if (excludedIds.isNotEmpty()) return null

        val rateLimitedOnly = eligible.filter { activeAuth(it.id) == null }
        val probe = if (rateLimitedOnly.isNotEmpty()) {
            rateLimitedOnly.minBy { activeRateLimit(it.id)?.cooldownUntil ?: Long.MAX_VALUE }
        } else {
            eligible.minBy { activeAuth(it.id)?.until ?: Long.MAX_VALUE }
        }
        tickMap[probe.id] = ++logicalTick
        return Selection(probe, isProbe = true)
    }

    private fun recordSuccess(providerId: String, modelId: String?, credId: String) {
        synchronized(lock) {
            val removedRate = rateLimits.remove(RateLimitKey(providerId, credId, modelId)) != null
            val removedAuth = authErrors.remove(CredKey(providerId, credId)) != null
            if (removedRate || removedAuth) {
                _stateVersion.value += 1
            }
        }
    }

    private fun recordFailure(
        providerId: String,
        modelId: String?,
        credId: String,
        failure: FailureKind,
    ) {
        synchronized(lock) {
            val now = clock()
            when (failure) {
                is FailureKind.RateLimited -> {
                    val key = RateLimitKey(providerId, credId, modelId)
                    val prev = rateLimits[key]?.takeIf { now - it.cooldownUntil < STALE_RATE_LIMIT_RETENTION_MS }
                    val consecutive = (prev?.consecutive ?: 0) + 1
                    val stepCooldown = RATE_LIMIT_STEPS_MS[(consecutive - 1).coerceAtMost(RATE_LIMIT_STEPS_MS.lastIndex)]
                    val cooldownMs = failure.retryAfterMillis
                        ?.coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)
                        ?: stepCooldown
                    rateLimits[key] = RateLimitState(
                        consecutive = consecutive,
                        cooldownUntil = now + cooldownMs,
                        lastStatusCode = failure.statusCode,
                    )
                }

                is FailureKind.AuthError -> {
                    authErrors[CredKey(providerId, credId)] = AuthErrorState(
                        until = now + AUTH_ERROR_COOLDOWN_MS,
                        statusCode = failure.statusCode,
                        summary = failure.summary,
                    )
                }
            }
            _stateVersion.value += 1
        }
    }

    private fun pruneStaleLocked(now: Long) {
        authErrors.entries.removeIf { it.value.until <= now }
        rateLimits.entries.removeIf { now - it.value.cooldownUntil >= STALE_RATE_LIMIT_RETENTION_MS }
    }

    private fun classifyFailure(error: Throwable): FailureKind? =
        generateSequence(error) { it.cause }.take(8).firstNotNullOfOrNull { cause ->
            when (cause) {
                is HttpException -> when (cause.statusCode) {
                    429, 503 -> FailureKind.RateLimited(cause.statusCode, cause.retryAfterMillis)
                    401, 403 -> FailureKind.AuthError(cause.statusCode, "HTTP ${cause.statusCode}")
                    else -> null
                }

                is UpstreamStreamRateLimitException ->
                    FailureKind.RateLimited(cause.statusCode, null)

                is ServiceAccountAuthException ->
                    FailureKind.AuthError(cause.statusCode, cause.message.orEmpty().take(80))

                else -> null
            }
        }

    private companion object {
        const val DEFAULT_MAX_SWITCHES = 10
        val RATE_LIMIT_STEPS_MS = longArrayOf(60_000L, 120_000L, 300_000L)
        const val MIN_RETRY_AFTER_MS = 1_000L
        const val MAX_RETRY_AFTER_MS = 600_000L
        const val AUTH_ERROR_COOLDOWN_MS = 300_000L
        const val STALE_RATE_LIMIT_RETENTION_MS = 30 * 60_000L
    }
}

internal fun ProviderSetting.apiKeyCandidates(): List<PooledCredential<String>> {
    val all = effectiveApiKeyEntries()
    if (all.isEmpty()) {
        return listOf(PooledCredential(id = "empty", displayLabel = "", value = ""))
    }
    val enabled = all.mapIndexedNotNull { index, entry ->
        if (!entry.enabled) return@mapIndexedNotNull null
        if (entry.key.isBlank()) return@mapIndexedNotNull null
        PooledCredential(
            id = entry.id,
            displayLabel = entry.displayLabel(index),
            value = entry.key,
        )
    }
    if (enabled.isNotEmpty()) return enabled
    if (all.any { it.enabled && it.key.isBlank() } && all.all { it.key.isBlank() }) {
        return listOf(PooledCredential(id = "empty", displayLabel = "", value = ""))
    }
    error("No enabled API key configured")
}

internal fun ProviderSetting.Google.vertexServiceAccountCandidates(): List<PooledCredential<VertexServiceAccount>> {
    val all = effectiveServiceAccounts()
    val enabled = all.mapIndexedNotNull { index, account ->
        if (!account.enabled) return@mapIndexedNotNull null
        PooledCredential(
            id = account.id,
            displayLabel = account.displayLabel(index),
            value = account,
        )
    }
    if (enabled.isEmpty()) {
        error("No enabled Vertex service account configured")
    }
    return enabled
}
