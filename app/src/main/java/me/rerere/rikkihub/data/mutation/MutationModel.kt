package me.rerere.rikkihub.data.mutation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable JSON for persisted version tokens and epochs. */
internal val mutationJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 全写通道版本协议。
 *
 * [VersionToken] = storageEpoch + objectGeneration + revision：
 * - epoch：存储域整体换代（备份恢复/损坏重建）+1，旧 epoch 的 token 全部失效；
 * - generation：同 (domain, objectKey) 删除后重建/恢复 +1，防 ABA；
 * - revision：普通修改单调 +1。
 * Token 不透明但不携带内容 hash；校验失败抛 [MutationConflictException]。
 */

enum class MutationDomain {
    /** PreferencesStore / Settings DataStore */
    SETTINGS,

    /** 主 Room 的会话子域（conversationentity + message_node + FTS） */
    ROOM_CONVERSATION,

    /** 主 Room 其他对象（folder/favorite/memory/managed_file/...） */
    ROOM_GENERIC,

    /** 用户 Skills 权威目录树（文件域） */
    SKILLS_FILES,

    /** 受管上传/普通文件域（FilesManager 移交后启用） */
    MANAGED_FILES,
}

@Serializable
data class VersionToken(
    val domain: MutationDomain,
    /** 稳定对象键：`assistant:<uuid>`、`conversation:<uuid>`、`skill:<name>` 等。 */
    val objectKey: String,
    val epoch: Long,
    val generation: Long,
    val revision: Long,
) {
    override fun toString(): String = encode()

    fun encode(): String = mutationJson.encodeToString(VersionToken.serializer(), this)

    companion object {
        fun decode(value: String): VersionToken =
            mutationJson.decodeFromString(VersionToken.serializer(), value)
    }
}

/**
 * 声明式乐观并发期望：进入 mutation 临界区后对**最新持久值**断言。
 * - objects：同 (generation, revision) 才放行；删除态对象永不满足非 tombstone 断言。
 * - collections：集合结构（增/删/排序）基线；新增绑定必须断言被引用集合的 revision，
 *   使"引用扫描后新增绑定"同样冲突。
 */
data class ExpectedVersions(
    val objects: Map<String, VersionToken> = emptyMap(),
    val collections: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = objects.isEmpty() && collections.isEmpty()

    companion object {
        val EMPTY = ExpectedVersions()
    }
}

/** 写入来源；MANAGEMENT/MAINTENANCE 不受普通写准入（fence）限制。 */
enum class MutationActor {
    APP,
    WEB,
    MAINTENANCE,
    MANAGEMENT,
    SYSTEM,
}

/** Result of an idempotent write: [REPLAYED] means the operation id had already committed. */
enum class MutationOutcome { APPLIED, REPLAYED }

/** 版本/集合断言失败、目标被 fence、引用目标不合法等——不写任何存储。 */
class MutationConflictException(message: String) : Exception(message)

/** 目标对象处于 APPLYING/fence 状态：普通写与新 run 不得消费半完成依赖。 */
class MutationFencedException(
    val domain: MutationDomain,
    val objectKey: String,
    val operationId: String,
) : Exception("mutation target fenced: ${domain.name}/$objectKey by $operationId")

/** 会话结构写期间存在在途生成/待审批/排队/提交中——命令层 BUSY 语义。 */
class ConversationBusyException(
    val conversationId: String,
    val reasons: List<String>,
) : Exception("conversation $conversationId is busy: ${reasons.joinToString()}")

/** 目标会话节点集不完整（加载跳页），结构写被拒绝；patch/元数据写仍允许。 */
class ConversationDirtyException(
    val conversationId: String,
) : Exception("conversation $conversationId has an incomplete (dirty) node set; structural writes refused")
