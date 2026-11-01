package me.rerere.rikkihub.web.dto

import kotlinx.serialization.Serializable

/**
 * 收藏列表 DTO。只列 node 类型；旧 message 类型与 App 收藏页一致不显示。
 */
@Serializable
data class FavoriteItemDto(
    val id: String,
    val conversationId: String,
    val nodeId: String,
    /** 会话已删除时为 null。 */
    val assistantId: String? = null,
    /** 会话现名；会话已删除时用收藏时记下的标题。 */
    val title: String,
    val preview: String,
    val createdAt: Long,
    /** ok | conversation_deleted | node_missing */
    val status: String,
)

@Serializable
data class FavoriteListResponse(
    val items: List<FavoriteItemDto>,
    /** 超过上限被截断（按收藏时间倒序保留最新的）。 */
    val truncated: Boolean = false,
)

/** 删除接口返回的原记录，撤销时原样交回 restore。 */
@Serializable
data class FavoriteRecordDto(
    val id: String,
    val type: String,
    val refKey: String,
    val refJson: String,
    val snapshotJson: String = "",
    val metaJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
