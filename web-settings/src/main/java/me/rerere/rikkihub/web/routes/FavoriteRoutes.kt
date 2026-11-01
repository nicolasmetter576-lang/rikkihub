package me.rerere.rikkihub.web.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.first
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.data.db.entity.FavoriteEntity
import me.rerere.rikkihub.data.favorite.NodeFavoriteAdapter
import me.rerere.rikkihub.data.model.FavoriteMeta
import me.rerere.rikkihub.data.model.FavoriteType
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.data.repository.LightConversationEntity
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.dto.FavoriteItemDto
import me.rerere.rikkihub.web.dto.FavoriteListResponse
import me.rerere.rikkihub.web.dto.FavoriteRecordDto

internal const val FAVORITE_LIST_LIMIT = 2000
internal const val FAVORITE_STATUS_OK = "ok"
internal const val FAVORITE_STATUS_CONVERSATION_DELETED = "conversation_deleted"
internal const val FAVORITE_STATUS_NODE_MISSING = "node_missing"
private const val FAVORITE_ID_MAX = 512
private const val FAVORITE_META_MAX = 4096
private const val FAVORITE_SNAPSHOT_MAX = 64 * 1024

/**
 * 收藏列表 / 删除 / 撤销。
 *
 * - 列表只读收藏表 + 批量查会话行与节点行，不加载任何会话。
 * - 删除返回原记录，撤销把它原样写回（与 App 收藏页的撤销相同）。
 * - 删除/撤销只同步**已加载**会话的节点星标；未加载的在加载时由收藏表推出。
 */
fun Route.favoriteRoutes(
    favoriteRepo: FavoriteRepository,
    conversationRepo: ConversationRepository,
    chatService: ChatService,
) {
    route("/favorites") {
        get {
            val entities = favoriteRepo.listByType(FavoriteType.NODE).first()
            call.respond(
                assembleFavoriteList(
                    entities = entities,
                    conversations = { ids -> conversationRepo.getActiveConversationSummaries(ids) },
                    existingNodes = { conversationId, nodeIds -> conversationRepo.existingNodeIds(conversationId, nodeIds) },
                )
            )
        }

        delete("/{id}") {
            val id = call.parameters["id"]?.takeIf { it.isNotBlank() && it.length <= FAVORITE_ID_MAX }
                ?: throw BadRequestException("Invalid favorite id")
            val entity = favoriteRepo.listByType(FavoriteType.NODE).first().firstOrNull { it.id == id }
                ?: throw NotFoundException("Favorite not found")
            val ref = NodeFavoriteAdapter.decodeRef(entity) ?: throw NotFoundException("Favorite not found")
            favoriteRepo.deleteById(entity.id)
            chatService.syncLoadedNodeFavorite(ref.conversationId, ref.nodeId, isFavorite = false)
            call.respond(entity.toRecordDto())
        }

        post("/restore") {
            val entity = validateFavoriteRestore(call.receive<FavoriteRecordDto>())
            val ref = NodeFavoriteAdapter.decodeRef(entity)!!
            // 同一消息已被重新收藏（例如在会话里点了星标）时保留现有记录，撤销视为已完成。
            if (favoriteRepo.getByRefKey(entity.refKey) == null) favoriteRepo.upsert(entity)
            chatService.syncLoadedNodeFavorite(ref.conversationId, ref.nodeId, isFavorite = true)
            call.respond(mapOf("status" to "restored"))
        }
    }
}

internal suspend fun assembleFavoriteList(
    entities: List<FavoriteEntity>,
    conversations: suspend (Collection<String>) -> Map<String, LightConversationEntity>,
    existingNodes: suspend (conversationId: String, nodeIds: Collection<String>) -> Set<String>,
): FavoriteListResponse {
    val decoded = entities.asSequence()
        .filter { it.type == FavoriteType.NODE.value }
        .mapNotNull { entity -> NodeFavoriteAdapter.decodeRef(entity)?.let { entity to it } }
        .toList()
    val kept = decoded.sortedByDescending { it.first.createdAt }.take(FAVORITE_LIST_LIMIT)
    val byConversation = kept.groupBy({ it.second.conversationId.toString() }, { it.second.nodeId.toString() })
    val active = conversations(byConversation.keys)
    val existing = active.keys.associateWith { conversationId ->
        existingNodes(conversationId, byConversation.getValue(conversationId))
    }
    val items = kept.map { (entity, ref) ->
        val conversationId = ref.conversationId.toString()
        val nodeId = ref.nodeId.toString()
        val conversation = active[conversationId]
        val meta = NodeFavoriteAdapter.decodeMeta(entity)
        FavoriteItemDto(
            id = entity.id,
            conversationId = conversationId,
            nodeId = nodeId,
            assistantId = conversation?.assistantId,
            title = conversation?.title?.takeIf { it.isNotBlank() } ?: meta?.title.orEmpty(),
            preview = meta?.previewText.orEmpty(),
            createdAt = entity.createdAt,
            status = when {
                conversation == null -> FAVORITE_STATUS_CONVERSATION_DELETED
                nodeId !in existing[conversationId].orEmpty() -> FAVORITE_STATUS_NODE_MISSING
                else -> FAVORITE_STATUS_OK
            },
        )
    }
    return FavoriteListResponse(items, truncated = decoded.size > FAVORITE_LIST_LIMIT)
}

/** 撤销只接受删除接口交出的那种记录：node 类型、ref 可解析且与 refKey 一致、各字段有界。 */
internal fun validateFavoriteRestore(record: FavoriteRecordDto): FavoriteEntity {
    fun bad(): Nothing = throw BadRequestException("Invalid favorite record")
    if (record.type != FavoriteType.NODE.value) bad()
    if (record.id.isBlank() || record.id.length > FAVORITE_ID_MAX || record.refKey.length > FAVORITE_ID_MAX) bad()
    if (record.refJson.length > FAVORITE_ID_MAX || record.snapshotJson.length > FAVORITE_SNAPSHOT_MAX) bad()
    if (record.createdAt < 0 || record.updatedAt < 0) bad()
    val metaJson = record.metaJson
    if (metaJson != null) {
        if (metaJson.length > FAVORITE_META_MAX) bad()
        runCatching { JsonInstant.decodeFromString<FavoriteMeta>(metaJson) }.getOrElse { bad() }
    }
    val entity = FavoriteEntity(
        id = record.id,
        type = record.type,
        refKey = record.refKey,
        refJson = record.refJson,
        snapshotJson = record.snapshotJson,
        metaJson = metaJson,
        createdAt = record.createdAt,
        updatedAt = record.updatedAt,
    )
    val ref = NodeFavoriteAdapter.decodeRef(entity) ?: bad()
    if (record.refKey != NodeFavoriteAdapter.buildRefKey(ref.conversationId.toString(), ref.nodeId.toString())) bad()
    return entity
}

private fun FavoriteEntity.toRecordDto() = FavoriteRecordDto(
    id = id,
    type = type,
    refKey = refKey,
    refJson = refJson,
    snapshotJson = snapshotJson,
    metaJson = metaJson,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
