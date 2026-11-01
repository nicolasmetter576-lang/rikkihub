package me.rerere.rikkihub.data.management.recovery

import kotlinx.serialization.Serializable

/**
 * 删除/覆盖类提案的恢复材料存储：
 *  - 含秘密的 before-image 进加密回收 payload（[PayloadCipher]），审计只记引用；
 *  - 保留 30 天；到期前核对未决恢复义务（仍 NEEDS_RECOVERY 的提案不删）；
 *  - cipher 不可用时不静默丢材料承诺 Undo——提案 retention 标
 *    `recoveryMaterialsIncomplete=true`。
 */
interface PayloadCipher {
    val available: Boolean

    suspend fun encrypt(plain: ByteArray): ByteArray

    suspend fun decrypt(cipher: ByteArray): ByteArray
}

/** JVM/fake 用直通实现；生产接 Keystore AES-GCM（跨包请求项）。 */
class NoopPayloadCipher : PayloadCipher {
    override val available: Boolean = false

    override suspend fun encrypt(plain: ByteArray): ByteArray = plain

    override suspend fun decrypt(cipher: ByteArray): ByteArray = cipher
}

@Serializable
data class RecyclePayloadMeta(
    val proposalId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    /** cipher 不可用 → true（材料不完整，禁完全 Undo 宣称）。 */
    val incomplete: Boolean,
    /** body 是否经 PayloadCipher 加密。 */
    val encrypted: Boolean = false,
    /** 内容 sha256（完整性核对；不进模型可见面）。 */
    val contentSha256: String,
    val sizeBytes: Long,
)

interface RecycleStore {
    /**
     * 写入恢复材料。含秘密（containsSecrets=true）的 payload 在 cipher 不可用
     * 时**拒绝落明文**——返回 meta.incomplete=true 且 body 不保存；非秘密材料
     * 可落明文由审计引用。
     */
    suspend fun store(proposalId: String, payload: ByteArray, containsSecrets: Boolean): RecyclePayloadMeta

    suspend fun load(proposalId: String): ByteArray?

    suspend fun meta(proposalId: String): RecyclePayloadMeta?

    suspend fun listMetas(): List<RecyclePayloadMeta>

    suspend fun deletePayload(proposalId: String)
}

/** 回收/审计保留常数（回收 30 天、审计 90 天 + 容量上限）。 */
object ManagementRetentionPolicy {
    const val RECYCLE_DAYS = 30
    const val AUDIT_DAYS = 90

    /** 审计日志容量上限（条目数）；超限拒绝新写而非静默删。 */
    const val AUDIT_MAX_ENTRIES = 50_000

    /** 回收目录容量上限（字节）；超限拒绝新写。 */
    const val RECYCLE_MAX_BYTES = 64L * 1024 * 1024
}
