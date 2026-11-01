package me.rerere.rikkihub.data.creator.definition

import kotlin.uuid.Uuid

/**
 * 创造资源统一隐藏/拒绝分类。
 *
 * 供 Web 路由与 mutation 写入面复用：所有"是否创造资源"判定收敛到
 * 这里——保留 assistantId、`creator_` wire 名、`creator://` scheme。
 * 判定不区分「保留」与「不存在」：对外一律 404/拒绝，不泄露资源存在性。
 */
object CreatorResourceClassifier {

    fun isReservedAssistantId(id: String): Boolean =
        runCatching { Uuid.parse(id) }.getOrNull()?.let {
            AssistantRegistry.isReservedAssistantId(it)
        } == true

    fun isCreatorWireName(name: String): Boolean =
        CreatorToolNamespace.isCreatorWireName(name)

    /**
     * 统一判定：`kind` 语义化资源类别（assistant/conversation/tool/proposal/…），
     * `id` 为外部传入的原始 id/name；无法判定的 kind 保守返回 false
     * （调用方按普通未知资源处理，不因此放行已知私有标识）。
     */
    fun isCreatorResource(kind: String?, id: String?): Boolean {
        if (id == null) return false
        if (id.startsWith("creator://")) return true
        if (isCreatorWireName(id)) return true
        return when (kind) {
            "assistant", "conversation", "proposal", "run", "session" ->
                isReservedAssistantId(id)
            else -> false
        }
    }
}
