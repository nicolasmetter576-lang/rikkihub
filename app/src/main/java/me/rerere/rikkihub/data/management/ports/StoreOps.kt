package me.rerere.rikkihub.data.management.ports

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkihub.data.management.common.ObjectRef

/**
 * Settings 集合补丁：typed、可审计、可序列化。gateway 把补丁
 * 应用到“最新持久值”上——补丁语义是字段级覆盖/删除/重排，不是任意 lambda。
 */
@Serializable
sealed interface CollectionPatch {

    /** 新对象（host 已分配 id）。fields 为该对象完整白名单字段。 */
    @Serializable
    data class Create(val id: String, val fields: Map<String, JsonElement>) : CollectionPatch

    /** 删除指定对象。 */
    @Serializable
    data class Remove(val ids: List<String>) : CollectionPatch

    /** 对指定对象做字段级设置（只写白名单字段）。 */
    @Serializable
    data class PatchFields(val id: String, val fields: Map<String, JsonElement>) : CollectionPatch

    /** 整表重排（全部现有 id 恰好一次；gateway 校验集合一致性）。 */
    @Serializable
    data class Reorder(val orderedIds: List<String>) : CollectionPatch

    /** 有序多补丁：同一原子事务内顺序应用（创建+绑定+删引用的复合步）。 */
    @Serializable
    data class Batch(val patches: List<CollectionPatch>) : CollectionPatch
}

/**
 * Settings 域最小操作原语：一次 DataStore 原子更新内校验 + patch +
 * +version + receipt + 发布内存。
 */
@Serializable
data class SettingsCollectionOp(
    /**
     * 目标集合（冻结集合 ID 表）：
     *  - "settings.quickMessages"   → Settings.quickMessages
     *  - "settings.modeInjections"  → Settings.modeInjections
     *  - "settings.lorebooks"       → Settings.lorebooks
     *  - "settings.assistants"      → Settings.assistants（对象字段白名单见 AssistantFieldWhitelist）
     *  - "settings.assistantTags"   → Settings.assistantTags
     *  - "settings.providers.<providerId>.models" → 指定 provider 下 models 子集合
     */
    val collectionId: String,
    val patch: CollectionPatch,
    override val objectRefs: List<ObjectRef>,
    override val summaryKey: String,
) : StoreOperation {
    override val store: StoreKind get() = StoreKind.SETTINGS_DATASTORE
}

/**
 * 标量字段步：六个默认槽 / 五提示词 / 其他单值白名单字段。
 * value=null → 显式清除；缺省值路径必须由命令侧保证（不写伪 UUID）。
 */
@Serializable
data class SettingsFieldOp(
    val fieldId: String, // "settings.defaults.slot.CHAT" / "settings.defaults.prompt.TITLE" ...
    val value: JsonElement,
    override val objectRefs: List<ObjectRef>,
    override val summaryKey: String,
) : StoreOperation {
    override val store: StoreKind get() = StoreKind.SETTINGS_DATASTORE
}

/** All Settings changes in one proposal step commit with one atomic receipt. */
@Serializable
data class SettingsTransactionOp(
    /** Closed, versioned envelopes produced by StoreOperationCodec, not model JSON. */
    val operations: List<String>,
    override val objectRefs: List<ObjectRef>,
    override val summaryKey: String,
) : StoreOperation {
    override val store: StoreKind get() = StoreKind.SETTINGS_DATASTORE
}

/** Opaque encrypted before-image reference, produced only by host Undo/recovery. */
@Serializable
data class SettingsRestoreOp(
    val payloadRef: String,
    override val objectRefs: List<ObjectRef>,
    override val summaryKey: String = "restore-settings",
) : StoreOperation {
    override val store: StoreKind get() = StoreKind.SETTINGS_DATASTORE
}

/** 会话引用字段（世界书/模式注入删除的 Room 侧级联）。 */
enum class RefField { LOREBOOK_IDS, MODE_INJECTION_IDS }

@Serializable
sealed interface RefScope {
    @Serializable
    data object AllOrdinary : RefScope

    @Serializable
    data class ByIds(val ids: List<String>) : RefScope
}

/**
 * 删除世界书/模式注入时，普通会话引用的级联清理（跨 DataStore/Room saga）。
 * 这是 Room 步而非“会话域命令”——经 mutation 的
 * applyConversationCommand/CAS 兑现，不归属 adapters。
 */
@Serializable
data class ConversationRefStripOp(
    val field: RefField,
    val removedId: String,
    val scope: RefScope,
    override val objectRefs: List<ObjectRef>,
    override val summaryKey: String = "strip_conversation_refs",
) : StoreOperation {
    override val store: StoreKind get() = StoreKind.MAIN_ROOM
}

/**
 * One typed ordinary-conversation command, executed only through
 * ChatService.applyConversationCommand (session lock + BUSY + Room CAS + receipt).
 * Host IDs are frozen at plan time; [expected] is the planned conversation version.
 */
@Serializable
data class ConversationCommandOp(
    val conversationId: String,
    val expected: me.rerere.rikkihub.data.management.common.VersionToken?,
    val command: me.rerere.rikkihub.data.mutation.ConversationMutationCommand,
    val hostIds: me.rerere.rikkihub.data.mutation.ConversationHostIds,
    override val objectRefs: List<ObjectRef>,
    override val summaryKey: String = "conversation_command",
) : StoreOperation {
    override val store: StoreKind get() = StoreKind.MAIN_ROOM
}
