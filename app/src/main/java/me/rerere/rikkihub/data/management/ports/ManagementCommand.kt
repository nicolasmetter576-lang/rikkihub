package me.rerere.rikkihub.data.management.ports

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSet
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.RiskLevel
import me.rerere.rikkihub.data.management.common.VersionToken

/**
 * 全部管理命令的公共协议。每域命令为 @Serializable data class，
 * 序列化后存提案 payloadJson；宿主分配字段（新对象 ID/条目 ID）在
 * DRAFT→PENDING_APPROVAL 时冻结进 payload，执行期不再重新分配。
 */
interface ManagementCommand {
    val domain: ManagementDomain

    /** 影响面：本命令直接触碰的对象 + 级联目标（用于 lease/fence）。 */
    fun affectedRefs(): List<ObjectRef>

    /** 风险分级。 */
    fun risk(): RiskLevel
}

enum class StoreKind {
    SETTINGS_DATASTORE,
    MAIN_ROOM,
    USER_FILES,
    VAULT,
}

/** 跨存储 saga 的最小写单元；operationId 由 coordinator 分配且稳定。 */
interface StoreOperation {
    val store: StoreKind

    /** 本步写涉及的对象断言（执行时在准入临界区内复核）。 */
    val objectRefs: List<ObjectRef>

    /** 人类可读摘要 key（i18n 归 UI 层）。 */
    val summaryKey: String

    /** 序列化判别名（持久化步骤用；各 op 实现给出稳定常量）。 */
    val opKind: String get() = this::class.simpleName ?: "unknown"
}

/** token/读集不符等并发冲突（执行期）→ 提案转 CONFLICT。 */
class ConcurrencyConflict(
    val ref: ObjectRef?,
    val collectionId: String?,
    val expected: VersionToken?,
    val actual: VersionToken?,
) : IllegalStateException(
    "Concurrency conflict on ${ref ?: collectionId}: expected=$expected actual=$actual"
)

/** 对象 + token 对（序列化友好）。 */
@Serializable
data class TokenRef(val ref: ObjectRef, val token: VersionToken)

/** operationId 已被应用过的幂等重放结果（不重复执行业务效果）。 */
@Serializable
data class StoreReceipt(
    val operationId: String,
    val store: StoreKind,
    val appliedAtMillis: Long,
    /** 写入后各对象的新 token（补偿/审计/Undo read-set 的依据）。 */
    val afterTokens: List<TokenRef>,
    val detail: String = "",
    /** Host-computed operation fingerprint; a reused ID may not change payload. */
    val operationFingerprint: String = "",
    val storageEpoch: Long = 0,
)

/** 补偿结果：执行 / 因 token 已变跳过（不覆盖新改动） / 失败。 */
enum class CompensationStatus { APPLIED, SKIPPED_TOKEN_MISMATCH, FAILED }

enum class OperationFactStatus { APPLIED, NOT_APPLIED, UNKNOWN }

data class OperationFact(val status: OperationFactStatus, val receipt: StoreReceipt? = null)

data class CompensationResult(
    val status: CompensationStatus,
    val receipt: StoreReceipt? = null,
    val reason: String? = null,
)

/**
 * 每存储最小非私有参与记录：operationId、storageEpoch、目标、
 * 阶段、未决 fence、receipt。不含 transcript/schema/批准内容；Vault 丢失
 * 也不影响 fence 重建。
 */
@Serializable
data class ParticipationRecord(
    val operationId: String,
    val store: StoreKind,
    val targets: List<ObjectRef>,
    val phase: ParticipationPhase,
    val storageEpoch: Long,
    val fenceHeld: Boolean,
    val receiptOperationId: String?,
    val updatedAtMillis: Long,
)

enum class ParticipationPhase { INTENT, APPLIED, COMPENSATED, RELEASED }

/** 每存储写入口；所有实现必须幂等（operationId receipt）。 */
interface StoreGateway {
    val store: StoreKind

    /**
     * 从最新持久值校验 expected 断言并原子应用本步 + 记 receipt。
     * 同一 operationId 重放返回原 receipt。断言不符抛 [ConcurrencyConflict]。
     * 注意：本方法假定已在 coordinator 的准入临界区（lease+fence）内被调用。
     */
    suspend fun apply(
        op: StoreOperation,
        operationId: String,
        expected: List<ReadSetEntry>,
    ): StoreReceipt

    /** 对象 token（不存在返回 null）。 */
    suspend fun currentToken(ref: ObjectRef): VersionToken?

    suspend fun storageEpoch(): Long

    /** Facts from the owning store, not an executor/self-reported result. */
    suspend fun reconcile(op: StoreOperation, operationId: String): OperationFact

    /** 集合修订 token。 */
    suspend fun collectionToken(collectionId: String): VersionToken

    /**
     * 补偿逆步：仅当目标当前 token == afterToken（即“崩溃后没有用户新改动”）
     * 才执行；否则 SKIPPED_TOKEN_MISMATCH，绝不覆盖新改动、不按路径删同名替换。
     */
    suspend fun compensate(
        op: StoreOperation,
        operationId: String,
        afterTokens: List<TokenRef>,
    ): CompensationResult

    // —— 参与记录（每存储独立持久，不依赖 Vault）——
    suspend fun writeParticipation(record: ParticipationRecord)

    suspend fun readParticipation(operationId: String): ParticipationRecord?

    suspend fun pendingParticipations(): List<ParticipationRecord>

    suspend fun releaseParticipation(operationId: String)
}

data class ManagementSettingsSnapshot(
    val settings: Settings, val epoch: Long,
    val objects: Map<String, VersionToken>, val collections: Map<String, VersionToken>,
)

/**
 * 管理面读口（计划/dry-run/read-set 取数）。真实实现由 mutation 的
 * 锁内最新持久值镜像兑现（PENDING-MUTATION-CONTRACT）；本包用内存实现测试。
 */
interface ManagementReadPort {
    /** 集合的有序对象 id 列表（排序断言/列表展示用）。 */
    suspend fun snapshotIds(collectionId: String): List<String>

    /** 白名单字段投影（模型 read_object 与 diff 计划共用；不含敏感字段）。 */
    suspend fun readObjectJson(collectionId: String, id: String): JsonObject?

    suspend fun atomicSettingsSnapshot(): ManagementSettingsSnapshot

    /** Settings 最新持久值镜像（plan 期构造目标；不得返回锁外旧整包）。 */
    suspend fun settingsSnapshot(): Settings

    suspend fun objectToken(collectionId: String, id: String): VersionToken?

    suspend fun collectionToken(collectionId: String): VersionToken
}

/** 宿主分配新 ID 的唯一通道（冻结进提案 payload）。 */
interface HostIdAllocator {
    fun newObjectId(): String

    /** 条目级（lorebook entry、assistant regex 等）新 id。 */
    fun newEntryId(scope: String): String
}
