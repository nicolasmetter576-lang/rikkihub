package me.rerere.rikkihub.data.management.ports

import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.common.ObjectRef
import me.rerere.rikkihub.data.management.common.ReadSetEntry
import me.rerere.rikkihub.data.management.common.VersionToken

/**
 * 期望 mutation 提供的 Settings typed mutation 面（PENDING-MUTATION-CONTRACT）。
 *
 * 语义要求：DataStore 原子更新内——从**最新持久值**校验
 * expected 断言、应用 typed patch、+版本、写 receipt，再发布内存；
 * 锁内不得返回锁外算好的整对象；operationId 幂等。
 */
interface SettingsMutationPort {
    /**
     * 校验 expected（对象/集合 token，含“无其他引用”集合断言）后应用
     * ops（全部经 normalization：保留 ID 拒绝、引用有效性、有界大小）。
     * 断言失败抛 [ConcurrencyConflict]。重放 operationId 返回原 receipt。
     */
    suspend fun mutate(
        ops: List<StoreOperation>,
        expected: List<ReadSetEntry>,
        operationId: String,
    ): StoreReceipt

    /** 管理对象读口：锁内最新持久值镜像，返回归一化后的 Settings。 */
    suspend fun settingsSnapshot(): Settings

    suspend fun objectToken(collectionId: String, objectId: String): VersionToken?

    suspend fun collectionToken(collectionId: String): VersionToken

    /** 普通写 fence 检查挂点：coordinator 安装的对象 fence 查询（读路径）。 */
    suspend fun fencedRefs(): List<ObjectRef>
}

/**
 * 期望 mutation 提供的 Room 会话引用面（PENDING-MUTATION-CONTRACT）：
 * 删除级联经 applyConversationCommand/CAS 事务兑现。
 */
interface ConversationRefPort {
    /** 从普通会话剥离指定注入引用；同事务更新版本+receipt。 */
    suspend fun stripRefs(
        field: RefField,
        removedId: String,
        scope: RefScope,
        operationId: String,
    ): StoreReceipt

    /** 引用谓词集合的修订（read-set “无其他引用”断言用）。 */
    suspend fun refScopeToken(field: RefField, scope: RefScope): VersionToken
}
