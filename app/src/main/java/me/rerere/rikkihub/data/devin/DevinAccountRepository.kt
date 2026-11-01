package me.rerere.rikkihub.data.devin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.providers.devin.DevinApiException
import me.rerere.ai.provider.providers.devin.DevinChatApi
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.uuid.Uuid

/**
 * Devin 多账户仓库.
 *
 * Devin session token 永久有效, 无 refresh 流程; 401 由调用方标 INVALID.
 * 选账户不看配额 (见 [isUsable]); 上游报日/周配额耗尽后按 (账户, 模型) 记冷却,
 * 冷却只影响多账户时的选择顺序, 不会因此拒绝请求.
 * 登录时 best-effort 拉取 `GET /v3/self` (用户名 / userId / orgId) 和
 * `DevinChatApi.getUserStatus` (plan / email / 日周配额), 失败不阻断登录.
 */
class DevinAccountRepository internal constructor(
    private val store: DevinAccountStore,
    private val client: OkHttpClient,
    private val chatApi: DevinChatApi,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read()
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<DevinAccount>> = _accounts.asStateFlow()

    /** accountId → (模型 key → 冷却截止 epoch 毫秒). 只在内存: 重启后最多再撞一次上游报错. */
    private val quotaCooldowns = mutableMapOf<String, MutableMap<String, Long>>()

    suspend fun saveLogin(sessionToken: String): DevinAccount = mutex.withLock {
        val token = formatDevinSessionToken(sessionToken)
        val existing = state.accounts.firstOrNull { it.sessionToken == token }
        val account = DevinAccount(
            id = existing?.id ?: "devin:${Uuid.random()}",
            sessionToken = token,
            enabled = existing?.enabled ?: true,
        )
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == account.id } + account
            )
        )
        // best-effort: 账户信息 / 配额拉取失败不阻断登录
        runCatching { refreshAccountLocked(account.id) }
            .getOrElse { account }
    }

    /**
     * 轮询选一个可用 (启用且 token 有效) 的账户. 给了 [modelId] 时优先跳过该模型
     * 配额冷却中的账户; 全部在冷却也照样返回, 让上游给出真实结果.
     */
    suspend fun acquireAccount(modelId: String? = null): DevinAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Devin account is signed in")
        val key = modelId?.let(::devinCooldownKey)
        val now = System.currentTimeMillis()
        val index = selectDevinAccountIndex(
            accounts = state.accounts,
            startIndex = state.nextAccountIndex,
            isCoolingDown = { key != null && isCoolingDownLocked(it.id, key, now) },
        ) ?: error("No enabled Devin account: all accounts are disabled or need to sign in again")
        updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
        state.accounts[index]
    }

    /**
     * 配额耗尽后换号重试用: 只返回没试过、且该模型不在冷却中的可用账户, 没有则 null.
     */
    suspend fun acquireRetryAccount(modelId: String, tried: Set<String>): DevinAccount? =
        mutex.withLock {
            val key = devinCooldownKey(modelId)
            val now = System.currentTimeMillis()
            val index = selectDevinAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
                isCoolingDown = { it.id in tried || isCoolingDownLocked(it.id, key, now) },
                fallbackToCoolingDown = false,
            ) ?: return@withLock null
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            state.accounts[index]
        }

    /** 上游明确报 [period] 配额耗尽: 该账户的 [modelId] 冷却到重置时间 */
    internal suspend fun markQuotaExhausted(
        accountId: String,
        modelId: String,
        period: DevinQuotaPeriod,
    ) = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId } ?: return@withLock
        quotaCooldowns.getOrPut(accountId) { mutableMapOf() }[devinCooldownKey(modelId)] =
            account.quotaCooldownUntil(period, System.currentTimeMillis())
    }

    private fun isCoolingDownLocked(accountId: String, key: String, nowMillis: Long): Boolean {
        val models = quotaCooldowns[accountId] ?: return false
        val until = models[key] ?: return false
        if (until > nowMillis) return true
        models.remove(key)
        return false
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(status = DevinAccountStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        quotaCooldowns.remove(accountId)
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): DevinAccount = mutex.withLock {
        refreshAccountLocked(accountId)
    }

    suspend fun refreshAll() {
        var lastError: Throwable? = null
        var attempted = false
        accounts.value.forEach { account ->
            attempted = true
            runCatching { refreshAccount(account.id) }
                .onFailure { lastError = it }
        }
        if (attempted) lastError?.let { throw it }
    }

    /**
     * GET /v3/self + GetUserStatus, 各自独立 try.
     * 若 /v3/self 得到的 (userId, orgId) 与另一账户相同 → 合并 (保留旧账户 id,
     * 新 token 写入旧账户, 删除新账户). 返回最终生效的账户.
     */
    private suspend fun refreshAccountLocked(accountId: String): DevinAccount {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Devin account not found")

        var identity: DevinIdentity? = null
        var statusError: Throwable? = null

        // /v3/self: 用户标识 (401 → INVALID)
        runCatching { fetchIdentityLocked(account) }
            .onSuccess { identity = it }
            .onFailure {
                statusError = it
                if (it is DevinUnauthorizedException) {
                    replaceAccount(account.id) { acc ->
                        acc.copy(status = DevinAccountStatus.INVALID)
                    }
                }
            }

        // GetUserStatus: 套餐 / 配额 (401 → INVALID)
        val userStatus = runCatching {
            withContext(Dispatchers.IO) {
                chatApi.getUserStatus(
                    sessionToken = account.sessionToken,
                    deviceSeed = "",
                )
            }
        }.onFailure {
            statusError = it
            if ((it as? DevinApiException)?.statusCode == 401) {
                replaceAccount(account.id) { acc ->
                    acc.copy(status = DevinAccountStatus.INVALID)
                }
            }
        }.getOrNull()

        // 两个源都失败 → 抛错让 UI 提示, 不改账户
        if (identity == null && userStatus == null) {
            throw statusError ?: error("Failed to refresh Devin account")
        }

        val idy = identity
        val duplicate = if (idy != null && idy.userId.isNotBlank() && idy.orgId.isNotBlank()) {
            state.accounts.firstOrNull { other ->
                other.id != account.id &&
                    other.userId == idy.userId && other.orgId == idy.orgId
            }
        } else {
            null
        }

        val target = duplicate ?: account
        val updated = target.copy(
            sessionToken = account.sessionToken,
            userName = userStatus?.userName?.ifBlank { null }
                ?: idy?.userName
                ?: target.userName,
            email = userStatus?.email?.ifBlank { null }
                ?: idy?.email
                ?: target.email,
            userId = idy?.userId ?: userStatus?.userId ?: target.userId,
            orgId = idy?.orgId ?: userStatus?.orgId ?: target.orgId,
            plan = userStatus?.plan?.ifBlank { null } ?: target.plan,
            status = DevinAccountStatus.ACTIVE,
            dailyQuotaRemainingPercent = userStatus?.dailyQuotaRemainingPercent
                ?: target.dailyQuotaRemainingPercent,
            weeklyQuotaRemainingPercent = userStatus?.weeklyQuotaRemainingPercent
                ?: target.weeklyQuotaRemainingPercent,
            dailyQuotaResetAt = userStatus?.dailyQuotaResetAt
                ?: target.dailyQuotaResetAt,
            weeklyQuotaResetAt = userStatus?.weeklyQuotaResetAt
                ?: target.weeklyQuotaResetAt,
        )
        updateState(
            state.copy(
                accounts = state.accounts
                    .filterNot { it.id == account.id || it.id == target.id } + updated
            )
        )
        // 刷新确认日/周配额都已恢复 → 提前解除冷却
        if (userStatus != null &&
            updated.dailyQuotaRemainingPercent > 0 && updated.weeklyQuotaRemainingPercent > 0
        ) {
            quotaCooldowns.remove(account.id)
            quotaCooldowns.remove(target.id)
        }
        return updated
    }

    private suspend fun fetchIdentityLocked(account: DevinAccount): DevinIdentity {
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url("$DEVIN_API_BASE/v3/self")
                    .addHeader("Authorization", "Bearer ${account.sessionToken}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ).await()
        }
        if (!response.isSuccessful) {
            if (response.code == 401) {
                throw DevinUnauthorizedException("Devin token rejected by /v3/self")
            }
            error("Failed to fetch Devin identity: ${response.code}")
        }
        val root = runCatching {
            json.parseToJsonElement(response.body.string()).jsonObject
        }.getOrNull() ?: return DevinIdentity()
        val org = (root["org"] as? JsonObject)
            ?: (root["organization"] as? JsonObject)
            ?: (root["team"] as? JsonObject)
        return DevinIdentity(
            userId = root.stringField("user_id", "userId", "id", "sub"),
            userName = root.stringField("username", "user_name", "name", "display_name"),
            email = root.stringField("email", "email_address"),
            orgId = root.stringField("org_id", "orgId", "organization_id", "team_id")
                .ifBlank { org?.stringField("id", "org_id", "team_id", "uid") ?: "" },
        )
    }

    private fun JsonObject.stringField(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: ""

    private suspend fun replaceAccount(
        accountId: String,
        transform: (DevinAccount) -> DevinAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private suspend fun updateState(newState: DevinAccountState) {
        state = newState
        // AES-GCM encrypt + file write must not run on the caller's (often main) thread.
        withContext(Dispatchers.IO) { store.write(newState) }
        _accounts.value = newState.accounts
    }

    companion object {
        const val DEVIN_API_BASE = "https://api.devin.ai"
    }
}

private class DevinUnauthorizedException(message: String) : Exception(message)

internal data class DevinIdentity(
    val userId: String = "",
    val userName: String = "",
    val email: String = "",
    val orgId: String = "",
)

/**
 * 从 [startIndex] 起轮询: 跳过停用/失效账户, 优先返回 [isCoolingDown] 为 false 的账户;
 * 都在冷却时, [fallbackToCoolingDown] 为 true 则返回第一个可用的, 否则 null.
 */
internal fun selectDevinAccountIndex(
    accounts: List<DevinAccount>,
    startIndex: Int,
    isCoolingDown: (DevinAccount) -> Boolean = { false },
    fallbackToCoolingDown: Boolean = true,
): Int? {
    var fallback: Int? = null
    for (offset in accounts.indices) {
        val index = (startIndex + offset).mod(accounts.size)
        val account = accounts[index]
        if (!account.isUsable()) continue
        if (!isCoolingDown(account)) return index
        if (fallback == null) fallback = index
    }
    return if (fallbackToCoolingDown) fallback else null
}
