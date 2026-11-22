package me.rerere.rikkihub.data.character

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.data.repository.CharacterRepository
import kotlin.uuid.Uuid

/** 角色列表的顺序与筛选：角色库页和聊天抽屉共用，两处看到的顺序一样。 */
object CharacterListing {
    /** 抽屉头像条最多放几个收藏的角色。 */
    const val QUICK_LIMIT = 12

    /** 收藏的在前，其余按名字（不分大小写）；[matching] 不为 null 时只留 id 在里面的。 */
    fun ordered(all: List<CharacterSummary>, matching: Set<Uuid>? = null): List<CharacterSummary> =
        all.filter { matching == null || it.id in matching }
            .sortedWith(compareByDescending<CharacterSummary> { it.fav }.thenBy { it.name.lowercase() })

    /** 头像条里的角色：只放收藏的，顺序同 [ordered]，最多 [limit] 个。 */
    fun favourites(all: List<CharacterSummary>, limit: Int = QUICK_LIMIT): List<CharacterSummary> =
        ordered(all.filter { it.fav }).take(limit)

    /**
     * 按 [order]（一次排好的 id 顺序）排 [latest] 里最新的角色：点星标只换星的样子，行不跟着重排；
     * 已经不在了的 id 略过，[order] 里没有的新角色排在最后。
     */
    fun inOrder(order: List<Uuid>, latest: List<CharacterSummary>): List<CharacterSummary> {
        val byId = latest.associateBy { it.id }
        val known = order.toHashSet()
        return order.mapNotNull { byId[it] } + latest.filter { it.id !in known }
    }
}

/**
 * 随搜索词变化的角色列表（顺序见 [CharacterListing.ordered]）；搜索词为空就是全部。
 * 库里有变化或搜索词变了就重算，搜索还没出结果时换了词会丢掉旧的那次。
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun CharacterRepository.observeListing(query: Flow<String>): Flow<List<CharacterSummary>> =
    combine(observeSummaries(), query) { list, q -> list to q }
        .flatMapLatest { (list, q) ->
            flow {
                val ids = if (q.isBlank()) null else withContext(Dispatchers.IO) { search(q) }
                emit(CharacterListing.ordered(list, ids))
            }
        }

/** 收藏或取消收藏；和角色详情页里的星标是同一处写入（读最新、写前比版本）。 */
suspend fun CharacterRepository.setFavorite(id: Uuid, fav: Boolean) {
    update(id) { it.copy(card = it.card.withExtension("fav", JsonPrimitive(fav))) }
}
