package me.rerere.rikkihub.web.routes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import me.rerere.rikkihub.data.db.entity.WorkspaceEntity
import me.rerere.rikkihub.web.dto.WorkspaceUpdateEvent

/**
 * `workspace_update` 相邻快照 diff（EventsRoutes `/api/events` 的纯逻辑部分，抽出以便单测）。
 *
 * [previous] = 上一快照的 id -> entity 映射；null = 首个快照（首帧不算变更，
 * 页面加载时已自行拉过列表）。返回受影响 workspaceIds：
 *  - null        -> 无变化（包括首帧），不发事件
 *  - 非空 list   -> 新增 / 删除 / 任一行列变化的 id
 *
 * 比较用 data class 全字段相等：任何列改动（含 name、toolEnablement、
 * expose*、subagent* 等写路径）都会使该行进入 changedIds。
 */
internal fun workspaceChangedIds(
    previous: Map<String, WorkspaceEntity>?,
    next: List<WorkspaceEntity>,
): List<String>? {
    if (previous == null) return null
    val nextById = next.associateBy { it.id }
    val changedIds = buildSet {
        for ((id, entity) in nextById) {
            if (previous[id] != entity) add(id)
        }
        for (id in previous.keys) {
            if (id !in nextById) add(id)
        }
    }
    return changedIds.takeIf { it.isNotEmpty() }?.toList()
}

/**
 * 把 listFlow 快照流折叠成 `workspace_update` 事件流：每个 collector 的 previous
 * 缓存都是 collect 闭包内局部变量（首帧 = 订阅那一刻的当前快照），所以并发订阅
 * （JWT 开/关两个 /api/events 端点、多个浏览器连接）互不共享 diff 状态。
 */
internal fun workspaceUpdateEvents(
    listFlow: Flow<List<WorkspaceEntity>>,
): Flow<WorkspaceUpdateEvent> = flow {
    var previousById: Map<String, WorkspaceEntity>? = null
    listFlow.collect { workspaces ->
        val changedIds = workspaceChangedIds(previousById, workspaces)
        previousById = workspaces.associateBy { it.id }
        if (changedIds != null) {
            emit(
                WorkspaceUpdateEvent(
                    workspaceIds = changedIds,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }
}
