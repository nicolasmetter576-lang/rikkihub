package me.rerere.rikkihub.data.antigravity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.providers.antigravity.AntigravityChatApi
import me.rerere.ai.provider.providers.antigravity.AntigravityModelCatalog
import me.rerere.ai.provider.providers.antigravity.AntigravityTokens
import me.rerere.ai.util.HttpException
import java.time.Instant

/**
 * Antigravity 多账户仓库：轮询取号、到期前刷新 token、补项目、429 冷却、额度快照。
 * 刷新规则同 CPA `antigravity_executor_auth.go`（提前 5 分钟；refresh 被拒即作废）。
 */
class AntigravityAccountRepository internal constructor(
    private val store: AntigravityCredentialStore,
    private val api: AntigravityChatApi,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (account.status == AntigravityTokenStatus.AVAILABLE && account.expiresAt <= System.currentTimeMillis()) {
                    account.copy(status = AntigravityTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<AntigravityAccount>> = _accounts.asStateFlow()

    suspend fun saveLogin(tokens: AntigravityTokens, email: String, projectId: String): AntigravityAccount =
        mutex.withLock {
            val id = "antigravity:${email.lowercase()}"
            val existing = state.accounts.firstOrNull { it.id == id }
            val account = AntigravityAccount(
                id = id,
                email = email,
                projectId = projectId,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: existing?.refreshToken
                    ?: error("Google did not return a refresh token, please sign in again"),
                expiresAt = System.currentTimeMillis() + tokens.expiresInSeconds * 1000,
                enabled = existing?.enabled ?: true,
                status = AntigravityTokenStatus.AVAILABLE,
                quotas = existing?.quotas.orEmpty(),
                quotaUpdatedAt = existing?.quotaUpdatedAt ?: 0,
            )
            updateState(state.copy(accounts = state.accounts.filterNot { it.id == id } + account))
            account
        }

    /** 轮询取下一个可用账户，并保证 token 新鲜、项目已知。 */
    suspend fun acquireAccount(modelId: String? = null): AntigravityAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Antigravity account is signed in")
        var lastError: Throwable? = null
        val tried = HashSet<String>()
        repeat(state.accounts.size) {
            val index = selectAntigravityAccountIndex(state.accounts, state.nextAccountIndex, modelId)
                ?: return@repeat
            val candidate = state.accounts[index]
            if (!tried.add(candidate.id)) return@repeat
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            runCatching { ensureReadyLocked(candidate) }
                .onSuccess { return it }
                .onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException(noAvailableMessage(modelId))
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(status = AntigravityTokenStatus.INVALID) }
    }

    /** 429：该账户冷却到 [untilMillis]，期间轮询跳过它。 */
    suspend fun markCooldown(accountId: String, untilMillis: Long) = mutex.withLock {
        replaceAccount(accountId) { it.copy(cooldownUntil = maxOf(it.cooldownUntil, untilMillis)) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun updateQuotas(accountId: String, catalog: AntigravityModelCatalog) = mutex.withLock {
        replaceAccount(accountId) { it.withQuotas(catalog) }
    }

    /** 刷新 token（强制）、补项目、拉额度。 */
    suspend fun refreshAccount(accountId: String): AntigravityAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Antigravity account not found")
        val ready = ensureReadyLocked(account, forceRefresh = true)
        val catalog = runCatching {
            api.fetchAvailableModels(ready.accessToken, ready.projectId)
        }.onFailure { error ->
            if ((error as? HttpException)?.statusCode == 401) {
                replaceAccount(ready.id) { it.copy(status = AntigravityTokenStatus.INVALID) }
            }
        }.getOrThrow()
        val updated = ready.withQuotas(catalog).copy(status = AntigravityTokenStatus.AVAILABLE)
        replaceAccount(ready.id) { updated }
        updated
    }

    suspend fun refreshAll() {
        var lastError: Throwable? = null
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }.onFailure { lastError = it }
        }
        lastError?.let { throw it }
    }

    private suspend fun ensureReadyLocked(account: AntigravityAccount, forceRefresh: Boolean = false): AntigravityAccount {
        var current = account
        if (forceRefresh || current.expiresAt <= System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            val tokens = try {
                api.refreshToken(current.refreshToken)
            } catch (error: HttpException) {
                if (isAntigravityRefreshRejected(error)) {
                    replaceAccount(current.id) { it.copy(status = AntigravityTokenStatus.INVALID) }
                }
                throw error
            }
            current = current.copy(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken ?: current.refreshToken,
                expiresAt = System.currentTimeMillis() + tokens.expiresInSeconds * 1000,
                status = AntigravityTokenStatus.AVAILABLE,
            )
            val refreshed = current
            replaceAccount(current.id) { refreshed }
        }
        if (current.projectId.isBlank()) {
            val projectId = api.fetchProjectId(current.accessToken)
            current = current.copy(projectId = projectId)
            val withProject = current
            replaceAccount(current.id) { withProject }
        }
        return current
    }

    private suspend fun replaceAccount(accountId: String, transform: (AntigravityAccount) -> AntigravityAccount) {
        updateState(state.copy(accounts = state.accounts.map { if (it.id == accountId) transform(it) else it }))
    }

    private suspend fun updateState(newState: AntigravityAccountState) {
        state = newState
        // AES-GCM 加密 + 写文件不能在调用方（常是主线程）上做
        withContext(Dispatchers.IO) { store.write(newState) }
        _accounts.value = newState.accounts
    }

    private fun noAvailableMessage(modelId: String?): String =
        if (modelId == null) "No available Antigravity account"
        else "No available Antigravity account for $modelId (disabled, invalid, cooling down or out of quota)"

    private companion object {
        const val REFRESH_MARGIN_MS = 5 * 60 * 1000L
    }
}

/** refresh token 被拒（401，或 400 invalid_grant/invalid_client）→ 账户作废，需要重新登录。 */
internal fun isAntigravityRefreshRejected(error: HttpException): Boolean {
    val code = error.statusCode ?: return false
    if (code == 401) return true
    if (code != 400) return false
    val message = error.message.orEmpty()
    return "invalid_grant" in message || "invalid_client" in message || "unauthorized_client" in message
}

internal fun AntigravityAccount.withQuotas(
    catalog: AntigravityModelCatalog,
    nowMillis: Long = System.currentTimeMillis(),
): AntigravityAccount {
    val quotas = catalog.models.mapNotNull { model ->
        val fraction = model.remainingFraction ?: return@mapNotNull null
        AntigravityModelQuota(
            modelId = model.id,
            displayName = model.displayName,
            remainingFraction = fraction.coerceIn(0.0, 1.0),
            resetAt = model.resetTime?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
        )
    }
    return copy(quotas = quotas, quotaUpdatedAt = nowMillis)
}
