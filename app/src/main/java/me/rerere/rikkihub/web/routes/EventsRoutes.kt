package me.rerere.rikkihub.web.routes

import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.emptyFlow
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.data.repository.SubagentPresetRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.projectSettingsForWeb
import me.rerere.rikkihub.web.dto.ConversationListInvalidateEvent
import me.rerere.rikkihub.web.dto.FolderListEvent
import me.rerere.rikkihub.web.dto.toDto
import kotlin.time.Duration.Companion.seconds

/**
 * Multiplexed server-sent events stream.
 *
 * A single `/api/events` connection carries several event types, distinguished by the
 * SSE `event:` field, so new event kinds can be added without opening a new connection:
 *  - `settings`                     -> full Settings snapshot
 *  - `conversation_list_invalidate` -> the conversation list for an assistant changed
 *  - `folders`                      -> the folder list for an assistant changed
 *  - `workspace_update`             -> workspace rows changed (ids only), App/Web 修改互可见
 *
 * Per-conversation streaming (generation updates) keeps its own dedicated connection
 * at `/api/conversations/{id}/stream`.
 */
fun Route.eventsRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    workspaceRepository: WorkspaceRepository,
    subagentPresetRepository: SubagentPresetRepository? = null,
) {
    sse("/events") {
        heartbeat {
            period = 15.seconds
        }

        // Projected settings snapshot; StateFlow emits the current value
        // immediately on connect. NEVER serialize the raw Settings — it
        // contains live credentials (provider keys, MCP oauth, proxy/webdav
        // passwords). The projection masks secrets to "+Configured flags and
        // hides creator-domain resources (see WebSettingsProjection).
        val settingsEvents = settingsStore.settingsFlow.map { settings ->
            EventPayload(
                event = "settings",
                json = JsonInstant.encodeToString(projectSettingsForWeb(settings))
            )
        }

        // Conversation list invalidation, scoped to the currently selected assistant.
        val conversationListEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                combine(
                    conversationRepo.getConversationsOfAssistant(assistantId),
                    chatService.getConversationJobs()
                ) { conversations, generationJobs ->
                    // Key per conversation folds in generation state (so start/stop invalidates the
                    // sidebar even when content isn't persisted) and folderId (so moving a
                    // conversation between folders invalidates other clients' folder views).
                    conversations.map { conversation ->
                        buildString {
                            append(conversation.id)
                            append('|')
                            append(conversation.updateAt.toEpochMilli())
                            append('|')
                            append(generationJobs[conversation.id] != null)
                            append('|')
                            append(conversation.folderId?.toString().orEmpty())
                        }
                    }
                }
                    .distinctUntilChanged()
                    .map { assistantId }
            }
            .map { assistantId ->
                EventPayload(
                    event = "conversation_list_invalidate",
                    json = JsonInstant.encodeToString(
                        ConversationListInvalidateEvent(
                            assistantId = assistantId.toString(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                )
            }

        // Folder list for the currently selected assistant (Room flow emits on any change).
        val folderEvents = settingsStore.settingsFlow
            .map { it.assistantId }
            .distinctUntilChanged()
            .flatMapLatest { assistantId ->
                folderRepo.getFoldersOfAssistant(assistantId).map { folders ->
                    EventPayload(
                        event = "folders",
                        json = JsonInstant.encodeToString(
                            FolderListEvent(
                                assistantId = assistantId.toString(),
                                folders = folders.map { it.toDto() }
                            )
                        )
                    )
                }
            }

        // Workspace 失效事件: Room listFlow 任意行变化后 diff 相邻快照, 推出受影响
        // 的 workspaceIds (含新增/删除)。首个快照不算变更 —— 页面加载时已自行拉取。
        // diff 逻辑在 WorkspaceInvalidation.kt; previous 缓存每订阅独立, 不跨连接共享。
        val workspaceEvents = workspaceUpdateEvents(workspaceRepository.listFlow()).map { event ->
            EventPayload(event = "workspace_update", json = JsonInstant.encodeToString(event))
        }
        // Library-only edits (including unassigned roles) must invalidate every client too.
        val presetEvents = subagentPresetRepository?.libraryFlow()?.map {
            EventPayload(event = "subagent_presets_update", json = "{\"timestamp\":${System.currentTimeMillis()}}")
        } ?: emptyFlow()
        merge(settingsEvents, conversationListEvents, folderEvents, workspaceEvents, presetEvents).collect { payload ->
            send(data = payload.json, event = payload.event)
        }
    }
}

private data class EventPayload(
    val event: String,
    val json: String,
)
