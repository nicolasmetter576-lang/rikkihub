package me.rerere.rikkihub.web.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.fts.MessageSearchSort
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.NodeFavoriteTarget
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FavoriteRepository
import me.rerere.rikkihub.data.repository.FolderRepository
import me.rerere.rikkihub.service.ChatService
import me.rerere.rikkihub.service.MessageQueueState
import me.rerere.rikkihub.web.BadRequestException
import me.rerere.rikkihub.web.NotFoundException
import me.rerere.rikkihub.web.toVisibleAssistantUuid
import me.rerere.rikkihub.web.dto.ConversationMetadataPatch
import me.rerere.rikkihub.web.dto.ConversationNodeUpdateEvent
import me.rerere.rikkihub.web.dto.ConversationQueueEvent
import me.rerere.rikkihub.web.dto.ConversationSnapshotEvent
import me.rerere.rikkihub.web.dto.EditMessageRequest
import me.rerere.rikkihub.web.dto.ErrorEvent
import me.rerere.rikkihub.web.dto.FinishEditQueuedMessageRequest
import me.rerere.rikkihub.web.dto.ForkConversationRequest
import me.rerere.rikkihub.web.dto.ForkConversationResponse
import me.rerere.rikkihub.web.dto.MoveConversationRequest
import me.rerere.rikkihub.web.dto.MoveConversationToFolderRequest
import me.rerere.rikkihub.web.dto.PagedResult
import me.rerere.rikkihub.web.dto.RegenerateRequest
import me.rerere.rikkihub.web.dto.SelectMessageNodeRequest
import me.rerere.rikkihub.web.dto.SendMessageRequest
import me.rerere.rikkihub.web.dto.ToolApprovalRequest
import me.rerere.rikkihub.web.dto.MessageSearchResultDto
import me.rerere.rikkihub.web.dto.TranslateMessageRequest
import me.rerere.rikkihub.web.dto.UpdateConversationInjectionsRequest
import me.rerere.rikkihub.web.dto.UpdateConversationTitleRequest
import me.rerere.rikkihub.web.dto.toDto
import me.rerere.rikkihub.web.dto.toListDto
import me.rerere.rikkihub.utils.JsonInstant
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

fun Route.conversationRoutes(
    chatService: ChatService,
    conversationRepo: ConversationRepository,
    folderRepo: FolderRepository,
    settingsStore: SettingsStore,
    favoriteRepo: FavoriteRepository,
) {
    route("/conversations") {
        // GET /api/conversations - List conversations of current assistant
        get {
            val settings = settingsStore.settingsFlow.first()
            val generationJobs = chatService.getConversationJobs().first()
            val conversations = conversationRepo
                .getConversationsOfAssistant(settings.assistantId)
                .first()
                .map { conversation ->
                    conversation.toListDto(isGenerating = generationJobs[conversation.id] != null)
                }
            call.respond(conversations)
        }

        // GET /api/conversations/paged?offset=0&limit=20&query=foo&folderId=none|<uuid>
        // folderId: absent = all conversations, "none" = unfiled only, <uuid> = that folder
        get("/paged") {
            val settings = settingsStore.settingsFlow.first()
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            val folderParam = call.request.queryParameters["folderId"]?.trim()

            if (offset < 0) {
                throw BadRequestException("offset must be >= 0")
            }
            if (limit !in 1..100) {
                throw BadRequestException("limit must be in 1..100")
            }

            val page = when {
                // Search ignores folder filter (searches across all conversations of the assistant)
                query.isNotBlank() -> conversationRepo.searchConversationsOfAssistantPage(
                    assistantId = settings.assistantId,
                    titleKeyword = query,
                    offset = offset,
                    limit = limit
                )

                folderParam == null -> conversationRepo.getConversationsOfAssistantPage(
                    assistantId = settings.assistantId,
                    offset = offset,
                    limit = limit
                )

                folderParam.isEmpty() || folderParam == "none" ->
                    conversationRepo.getUnfiledConversationsOfAssistantPage(
                        assistantId = settings.assistantId,
                        offset = offset,
                        limit = limit
                    )

                else -> conversationRepo.getConversationsOfFolderPage(
                    folderId = folderParam.toUuid("folderId"),
                    offset = offset,
                    limit = limit
                )
            }
            val generationJobs = chatService.getConversationJobs().first()

            call.respond(
                PagedResult(
                    items = page.items.map { conversation ->
                        conversation.toListDto(isGenerating = generationJobs[conversation.id] != null)
                    },
                    nextOffset = page.nextOffset
                )
            )
        }

        // GET /api/conversations/search?query=foo&assistantId=<uuid>&sort=RELEVANCE|NEWEST_FIRST|OLDEST_FIRST
        // assistantId 缺省 = 全部助手；sort 缺省 = RELEVANCE
        get("/search") {
            val query = call.request.queryParameters["query"]?.trim().orEmpty()
            if (query.isBlank()) {
                call.respond(emptyList<MessageSearchResultDto>())
                return@get
            }
            val assistantId = call.request.queryParameters["assistantId"]
                ?.takeIf { it.isNotBlank() }
                ?.toVisibleAssistantUuid("assistantId")
            val sort = call.request.queryParameters["sort"]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching { MessageSearchSort.valueOf(raw.uppercase()) }.getOrNull()
                        ?: throw BadRequestException("Invalid sort")
                }
                ?: MessageSearchSort.RELEVANCE
            val results = conversationRepo.searchMessages(query, sort = sort, assistantId = assistantId)
            call.respond(results.map { result ->
                MessageSearchResultDto(
                    nodeId = result.nodeId,
                    messageId = result.messageId,
                    conversationId = result.conversationId,
                    title = result.title,
                    updateAt = result.updateAt.toEpochMilli(),
                    snippet = result.snippet,
                )
            })
        }

        // GET /api/conversations/{id} - Get single conversation
        get("/{id}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
            call.respond(
                conversation.toDto(
                    isGenerating = isGenerating,
                    queueSize = queueState?.messages?.size ?: 0,
                    queuePaused = queueState?.paused ?: false,
                )
            )
        }

        // PUT /api/conversations/{id} - Merge-patch conversation metadata
        // allowlist: title / customSystemPrompt / workspaceCwd / workspaceCwdScoped; 未知键 400；显式 null = 清除
        put("/{id}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val body = call.receive<JsonObject>()

            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
                ?: throw NotFoundException("Assistant not found")

            val patch = parseConversationMetadataPatch(
                body = body,
                allowConversationSystemPrompt = assistant.allowConversationSystemPrompt,
            )
            chatService.updateConversationMetadata(uuid, patch)

            val updatedConversation = conversation.copy(
                title = patch.title ?: conversation.title,
                customSystemPrompt = if (patch.patchedCustomSystemPrompt) {
                    patch.customSystemPrompt
                } else {
                    conversation.customSystemPrompt
                },
                workspaceCwd = if (patch.patchedWorkspaceCwd) {
                    patch.workspaceCwd
                } else {
                    conversation.workspaceCwd
                },
                workspaceCwdScoped = patch.workspaceCwdScoped ?: conversation.workspaceCwdScoped,
            )
            val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
            call.respond(
                HttpStatusCode.OK,
                updatedConversation.toDto(
                    isGenerating = isGenerating,
                    queueSize = queueState?.messages?.size ?: 0,
                    queuePaused = queueState?.paused ?: false,
                )
            )
        }

        // DELETE /api/conversations/{id} - Delete conversation
        delete("/{id}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            conversationRepo.deleteConversation(conversation)
            call.respond(HttpStatusCode.NoContent)
        }

        // POST /api/conversations/{id}/pin - Toggle pinned status
        post("/{id}/pin") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            if (!conversationRepo.existsConversationById(uuid)) {
                throw NotFoundException("Conversation not found")
            }

            chatService.toggleConversationPin(uuid)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/regenerate-title - Regenerate conversation title
        post("/{id}/regenerate-title") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            chatService.generateTitle(uuid, conversation, force = true)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/title - Update conversation title
        post("/{id}/title") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<UpdateConversationTitleRequest>()
            val title = request.title.trim()

            if (title.isEmpty()) {
                throw BadRequestException("Title must not be blank")
            }

            if (!conversationRepo.existsConversationById(uuid)) {
                throw NotFoundException("Conversation not found")
            }

            chatService.updateConversationMetadata(
                uuid,
                ConversationMetadataPatch(patchedTitle = true, title = title),
            )
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/injections - Update conversation-scoped prompt injections
        post("/{id}/injections") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<UpdateConversationInjectionsRequest>()
            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            val settings = settingsStore.settingsFlow.first()
            val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
                ?: throw NotFoundException("Assistant not found")
            if (!assistant.allowConversationPromptInjection) {
                throw BadRequestException("Conversation prompt injection is not enabled for this assistant")
            }

            val (modeInjectionIds, lorebookIds) = validateConversationInjectionIds(
                settings = settings,
                modeInjectionIds = request.modeInjectionIds,
                lorebookIds = request.lorebookIds,
            )
            val updatedConversation = conversation.copy(
                modeInjectionIds = modeInjectionIds,
                lorebookIds = lorebookIds,
            )
            chatService.updateConversationInjections(uuid, modeInjectionIds, lorebookIds)

            val isGenerating = chatService.getGenerationJobStateFlow(uuid).first() != null
            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
            call.respond(
                HttpStatusCode.OK,
                updatedConversation.toDto(
                    isGenerating = isGenerating,
                    queueSize = queueState?.messages?.size ?: 0,
                    queuePaused = queueState?.paused ?: false,
                )
            )
        }

        // POST /api/conversations/{id}/move - Move conversation to another assistant
        post("/{id}/move") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<MoveConversationRequest>()
            val targetAssistantId = request.assistantId.toVisibleAssistantUuid("assistant id")

            val settings = settingsStore.settingsFlow.first()
            if (settings.assistants.none { it.id == targetAssistantId }) {
                throw BadRequestException("Assistant not found")
            }

            if (!conversationRepo.existsConversationById(uuid)) {
                throw NotFoundException("Conversation not found")
            }
            chatService.moveConversationToAssistant(uuid, targetAssistantId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/folder - Move conversation to a folder (null = unfiled)
        post("/{id}/folder") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<MoveConversationToFolderRequest>()

            val conversation = conversationRepo.getConversationById(uuid)
                ?: throw NotFoundException("Conversation not found")

            val targetFolderId = request.folderId?.takeIf { it.isNotBlank() }?.toUuid("folder id")
            if (targetFolderId != null) {
                val folder = folderRepo.getFolderById(targetFolderId)
                    ?: throw NotFoundException("Folder not found")
                if (folder.assistantId != conversation.assistantId) {
                    throw BadRequestException("Folder belongs to another assistant")
                }
            }

            chatService.moveConversationToFolder(uuid, targetFolderId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "updated"))
        }

        // POST /api/conversations/{id}/messages - Send a message
        post("/{id}/messages") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<SendMessageRequest>()
            val folderId = request.folderId?.takeIf { it.isNotBlank() }?.toUuid("folder id")

            chatService.initializeConversation(uuid)
            if (folderId != null) chatService.updateDraftConversationFolder(uuid, folderId)
            applyInitialConversationInjections(
                chatService = chatService,
                settingsStore = settingsStore,
                conversationId = uuid,
                modeInjectionIds = request.modeInjectionIds,
                lorebookIds = request.lorebookIds,
            )
            chatService.sendMessage(uuid, request.parts, answer = request.answer)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // GET /api/conversations/{id}/queue - Pending message queue state
        // 只读探针：不 initializeConversation；session 未加载时返回空队列。
        get("/{id}/queue") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            if (!conversationRepo.existsConversationById(uuid) &&
                chatService.getMessageQueueStateIfLoaded(uuid) == null
            ) {
                throw NotFoundException("Conversation not found")
            }

            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
                ?: MessageQueueState()
            call.respond(queueState.toDto())
        }

        // POST /api/conversations/{id}/queue/resume - Resume a paused queue (idempotent)
        post("/{id}/queue/resume") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            if (!conversationRepo.existsConversationById(uuid) &&
                chatService.getMessageQueueStateIfLoaded(uuid) == null
            ) {
                throw NotFoundException("Conversation not found")
            }

            chatService.resumeMessageQueue(uuid)
            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
                ?: MessageQueueState()
            call.respond(HttpStatusCode.OK, queueState.toDto())
        }

        // DELETE /api/conversations/{id}/queue/{messageId} - Withdraw a queued message
        delete("/{id}/queue/{messageId}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            if (!conversationRepo.existsConversationById(uuid) &&
                chatService.getMessageQueueStateIfLoaded(uuid) == null
            ) {
                throw NotFoundException("Conversation not found")
            }

            chatService.removeQueuedMessage(uuid, messageId)
            call.respond(HttpStatusCode.NoContent)
        }

        // POST /api/conversations/{id}/queue/{messageId}/begin-edit - Lock a queued message for editing
        // 幂等：已在编辑中直接返回当前态。
        post("/{id}/queue/{messageId}/begin-edit") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
            if (!conversationRepo.existsConversationById(uuid) && queueState == null) {
                throw NotFoundException("Conversation not found")
            }

            val target = queueState?.messages?.firstOrNull { it.id == messageId }
                ?: throw NotFoundException("Queued message not found")
            if (!target.isEditing) {
                chatService.beginEditQueuedMessage(uuid, messageId)
            }

            val updatedState = chatService.getMessageQueueStateIfLoaded(uuid)
                ?: MessageQueueState()
            call.respond(HttpStatusCode.OK, updatedState.toDto())
        }

        // POST /api/conversations/{id}/queue/{messageId}/finish-edit - Finish or cancel the edit
        // body {parts}: null = 取消编辑只解锁（不清附件）；非 null = 更新排队内容。
        post("/{id}/queue/{messageId}/finish-edit") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            val request = call.receive<FinishEditQueuedMessageRequest>()
            val queueState = chatService.getMessageQueueStateIfLoaded(uuid)
            if (!conversationRepo.existsConversationById(uuid) && queueState == null) {
                throw NotFoundException("Conversation not found")
            }
            if (queueState?.messages?.none { it.id == messageId } != false) {
                throw NotFoundException("Queued message not found")
            }

            chatService.finishEditQueuedMessage(uuid, messageId, request.parts)
            val updatedState = chatService.getMessageQueueStateIfLoaded(uuid)
                ?: MessageQueueState()
            call.respond(HttpStatusCode.OK, updatedState.toDto())
        }

        // POST /api/conversations/{id}/messages/{messageId}/edit - Edit a message as a new branch version
        post("/{id}/messages/{messageId}/edit") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            val request = call.receive<EditMessageRequest>()

            chatService.initializeConversation(uuid)
            chatService.editMessage(uuid, messageId, request.parts)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/fork - Create a forked conversation up to message
        post("/{id}/fork") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<ForkConversationRequest>()
            val messageId = request.messageId.toUuid("message id")

            chatService.initializeConversation(uuid)
            val fork = chatService.forkConversationAtMessage(uuid, messageId)

            call.respond(HttpStatusCode.Created, ForkConversationResponse(conversationId = fork.id.toString()))
        }

        // DELETE /api/conversations/{id}/messages/{messageId} - Delete a message
        delete("/{id}/messages/{messageId}") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")

            chatService.initializeConversation(uuid)
            chatService.deleteMessage(uuid, messageId)

            call.respond(HttpStatusCode.OK, mapOf("status" to "deleted"))
        }

        // POST /api/conversations/{id}/nodes/{nodeId}/select - Switch branch selection for a message node
        post("/{id}/nodes/{nodeId}/select") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val nodeId = call.parameters["nodeId"].toUuid("node id")
            val request = call.receive<SelectMessageNodeRequest>()

            chatService.initializeConversation(uuid)
            chatService.selectMessageNode(uuid, nodeId, request.selectIndex)

            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/nodes/{nodeId}/favorite - Toggle node favorite
        // 镜像 ChatVM.toggleMessageFavorite：查库决定方向，再翻转内存态。
        post("/{id}/nodes/{nodeId}/favorite") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val nodeId = call.parameters["nodeId"].toUuid("node id")

            chatService.initializeConversation(uuid)
            val conversation = chatService.getConversationFlow(uuid).first()
            val node = conversation.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")

            val currentlyFavorited = favoriteRepo.isNodeFavorited(uuid, nodeId)
            if (currentlyFavorited) {
                favoriteRepo.removeNodeFavorite(uuid, nodeId)
            } else {
                favoriteRepo.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = uuid,
                        conversationTitle = conversation.title,
                        nodeId = nodeId,
                        node = node,
                    )
                )
            }

            chatService.updateConversationState(uuid) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == nodeId) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }

            call.respond(HttpStatusCode.OK, mapOf("isFavorite" to !currentlyFavorited))
        }

        // POST /api/conversations/{id}/messages/{messageId}/translate - Translate a message
        // body {language} 为 BCP-47 tag；翻译在后台异步进行（同 App），流式更新走 SSE。
        post("/{id}/messages/{messageId}/translate") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")
            val request = call.receive<TranslateMessageRequest>()

            val language = request.language.trim()
            val locale = runCatching {
                Locale.Builder().setLanguageTag(language).build()
            }.getOrNull()?.takeIf { it.language.isNotBlank() }
                ?: throw BadRequestException("Invalid language tag")

            chatService.initializeConversation(uuid)
            val conversation = chatService.getConversationFlow(uuid).first()
            val node = conversation.getMessageNodeByMessageId(messageId)
            val message = node?.messages?.find { it.id == messageId }
                ?: throw NotFoundException("Message not found")

            chatService.translateMessage(uuid, message, locale)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // DELETE /api/conversations/{id}/messages/{messageId}/translation - Clear translation
        // 只清内存态不落库，与 App clearTranslationField 行为一致。
        delete("/{id}/messages/{messageId}/translation") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val messageId = call.parameters["messageId"].toUuid("message id")

            chatService.initializeConversation(uuid)
            chatService.clearTranslationField(uuid, messageId)

            call.respond(HttpStatusCode.NoContent)
        }

        // POST /api/conversations/{id}/regenerate - Regenerate message
        post("/{id}/regenerate") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<RegenerateRequest>()
            val messageId = request.messageId.toUuid("message id")

            chatService.initializeConversation(uuid)
            val conversation = chatService.getConversationFlow(uuid).first()
            val node = conversation.getMessageNodeByMessageId(messageId)
            val message = node?.messages?.find { it.id == messageId }
                ?: throw NotFoundException("Message not found")

            chatService.regenerateAtMessage(uuid, message)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // POST /api/conversations/{id}/stop - Stop generation
        post("/{id}/stop") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            chatService.stopGeneration(uuid)
            call.respond(HttpStatusCode.OK, mapOf("status" to "stopped"))
        }

        // POST /api/conversations/{id}/tool-approval - Handle tool approval
        post("/{id}/tool-approval") {
            val uuid = call.parameters["id"].toUuid("conversation id")
            val request = call.receive<ToolApprovalRequest>()
            chatService.handleToolApproval(uuid, request.toolCallId, request.approved, request.reason, request.answer)
            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
        }

        // SSE /api/conversations/{id}/stream - Stream conversation updates
        route("/{id}/stream") {
            // Validation must run before the sse handler: respond() in the SSE
            // handler commits the status line and headers first, so anything
            // thrown there can no longer produce a JSON 404. handle() on the same
            // route node runs before it and its exceptions still reach StatusPages.
            handle {
                val uuid = call.parameters["id"].toUuid("conversation id")
                if (!conversationRepo.existsConversationById(uuid)) {
                    throw NotFoundException("Conversation not found")
                }
            }

            sse {
                val id = call.parameters["id"] ?: return@sse
                val uuid = runCatching { Uuid.parse(id) }.getOrNull() ?: return@sse

                chatService.initializeConversation(uuid)
                chatService.addConversationReference(uuid)

                heartbeat {
                    period = 15.seconds
                }

                try {
                    var sequence = 0L
                    var previousConversation: Conversation? = null
                    var previousIsGenerating = false

                    val knownErrorIds = chatService.errors.value.map { it.id }.toMutableSet()

                    val conversationEvents = combine(
                        chatService.getConversationFlow(uuid),
                        chatService
                            .getGenerationJobStateFlow(uuid)
                            .map { it != null }
                            .distinctUntilChanged()
                    ) { conversation, isGenerating ->
                        ConversationStreamPayload.Conversation(conversation, isGenerating)
                    }

                    val errorEvents = chatService.errors.map { errors ->
                        errors
                            .asSequence()
                            .filter { it.conversationId == uuid && knownErrorIds.add(it.id) }
                            .map { chatError ->
                                chatError.error.message?.takeIf { it.isNotBlank() }
                                    ?: chatError.error.toString()
                            }
                            .toList()
                    }.map { events ->
                        ConversationStreamPayload.BatchErrors(events)
                    }

                    val queueEvents = chatService.getMessageQueueFlow(uuid).map { queueState ->
                        ConversationStreamPayload.Queue(queueState)
                    }

                    // claudelife：会话缓存保活状态 → `cache_warm` 事件（Web 输入框提示区）
                    val cacheWarmEvents = chatService.observeCacheWarmStatus(uuid).map { status ->
                        ConversationStreamPayload.CacheWarm(status)
                    }

                    // 后台压缩状态 → `compress_state`（连上时若有状态先推一次）
                    val compressEvents = chatService.compressStateFlow(uuid).filterNotNull().map { state ->
                        ConversationStreamPayload.Compress(state)
                    }

                    merge(conversationEvents, errorEvents, queueEvents, cacheWarmEvents, compressEvents).collect { payload ->
                        when (payload) {
                            is ConversationStreamPayload.Conversation -> {
                                val conversation = payload.conversation
                                val isGenerating = payload.isGenerating
                                val previous = previousConversation
                                previousConversation = conversation

                                // First emission always sends a full snapshot so the client
                                // has a baseline for subsequent node updates.
                                if (previous == null) {
                                    sequence += 1
                                    sendConversationSnapshot(
                                        sequence,
                                        conversation,
                                        isGenerating,
                                        chatService.getMessageQueueStateIfLoaded(uuid),
                                    )
                                } else {
                                    val contentIdentical = previous === conversation
                                    val metadataChanged =
                                        !contentIdentical && !previous.sameMetadataAs(conversation)
                                    val changedIndices =
                                        if (contentIdentical) emptyList()
                                        else previous.changedNodeIndices(conversation)
                                    val sendSnapshot = metadataChanged ||
                                            changedIndices == null ||
                                            changedIndices.size > MAX_NODE_UPDATES_PER_EVENT ||
                                            // Reordering/index churn is ambiguous for index-based
                                            // client updates; a snapshot is the safe fallback.
                                            (changedIndices.size > 1 &&
                                                    changedIndices.first() < previous.messageNodes.size)

                                    when {
                                        sendSnapshot -> {
                                            sequence += 1
                                            sendConversationSnapshot(
                                                sequence,
                                                conversation,
                                                isGenerating,
                                                chatService.getMessageQueueStateIfLoaded(uuid),
                                            )
                                        }

                                        changedIndices.isNotEmpty() -> {
                                            // Only serialize nodes that actually changed.
                                            for (index in changedIndices) {
                                                sequence += 1
                                                sendConversationNodeUpdate(
                                                    sequence,
                                                    conversation,
                                                    conversation.messageNodes[index],
                                                    index,
                                                    isGenerating
                                                )
                                            }
                                        }

                                        isGenerating != previousIsGenerating -> {
                                            // Generation flag flipped without any content change
                                            // (e.g. the job ends right after the final state was
                                            // already pushed). Re-send the last node so clients
                                            // see the flag flip without paying for a full snapshot.
                                            val lastIndex = conversation.messageNodes.lastIndex
                                            if (lastIndex >= 0) {
                                                sequence += 1
                                                sendConversationNodeUpdate(
                                                    sequence,
                                                    conversation,
                                                    conversation.messageNodes[lastIndex],
                                                    lastIndex,
                                                    isGenerating
                                                )
                                            }
                                        }
                                    }
                                    // No node or metadata change: emit nothing.
                                }

                                previousIsGenerating = isGenerating
                            }

                            is ConversationStreamPayload.BatchErrors -> {
                                payload.messages.forEach { message ->
                                    val json = JsonInstant.encodeToString(
                                        ErrorEvent(message = message)
                                    )
                                    send(data = json, event = "error")
                                }
                            }

                            is ConversationStreamPayload.CacheWarm -> {
                                val json = JsonInstant.encodeToString(
                                    payload.status.toEvent(
                                        conversationId = uuid.toString(),
                                        monotonicNowMs = chatService.cacheWarmNowMs(),
                                    )
                                )
                                send(data = json, event = "cache_warm")
                            }

                            is ConversationStreamPayload.Compress -> {
                                send(data = JsonInstant.encodeToString(payload.state.toDto(uuid)), event = "compress_state")
                            }

                            is ConversationStreamPayload.Queue -> {
                                val json = JsonInstant.encodeToString(
                                    ConversationQueueEvent(
                                        conversationId = uuid.toString(),
                                        queue = payload.queueState.toDto(),
                                    )
                                )
                                send(data = json, event = "queue_update")
                            }
                        }
                    }
                } finally {
                    chatService.removeConversationReference(uuid)
                }
            }
        }
    }
}

private const val MAX_NODE_UPDATES_PER_EVENT = 8

private sealed interface ConversationStreamPayload {
    data class Conversation(
        val conversation: me.rerere.rikkihub.data.model.Conversation,
        val isGenerating: Boolean,
    ) : ConversationStreamPayload

    data class BatchErrors(val messages: List<String>) : ConversationStreamPayload

    data class Queue(val queueState: MessageQueueState) : ConversationStreamPayload

    data class CacheWarm(val status: me.rerere.ai.claudelife.CacheWarmStatus) : ConversationStreamPayload

    data class Compress(val state: me.rerere.rikkihub.service.CompressState) : ConversationStreamPayload
}

private suspend fun io.ktor.server.sse.ServerSSESession.sendConversationSnapshot(
    sequence: Long,
    conversation: me.rerere.rikkihub.data.model.Conversation,
    isGenerating: Boolean,
    queueState: MessageQueueState?,
) {
    val json = JsonInstant.encodeToString(
        ConversationSnapshotEvent(
            seq = sequence,
            conversation = conversation.toDto(
                isGenerating = isGenerating,
                queueSize = queueState?.messages?.size ?: 0,
                queuePaused = queueState?.paused ?: false,
            )
        )
    )
    send(data = json, event = "snapshot")
}

private suspend fun io.ktor.server.sse.ServerSSESession.sendConversationNodeUpdate(
    sequence: Long,
    conversation: me.rerere.rikkihub.data.model.Conversation,
    node: me.rerere.rikkihub.data.model.MessageNode,
    nodeIndex: Int,
    isGenerating: Boolean,
) {
    val nodeDto = node.toDto()
    val json = JsonInstant.encodeToString(
        ConversationNodeUpdateEvent(
            seq = sequence,
            conversationId = conversation.id.toString(),
            nodeId = nodeDto.id,
            nodeIndex = nodeIndex,
            node = nodeDto,
            updateAt = conversation.updateAt.toEpochMilli(),
            isGenerating = isGenerating
        )
    )
    send(data = json, event = "node_update")
}

private data class ConversationInjectionIds(
    val modeInjectionIds: Set<Uuid>,
    val lorebookIds: Set<Uuid>,
)

private fun validateConversationInjectionIds(
    settings: me.rerere.rikkihub.data.datastore.Settings,
    modeInjectionIds: List<String>,
    lorebookIds: List<String>,
): ConversationInjectionIds {
    val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
    val requestedModeInjectionIds = modeInjectionIds.map { it.toUuid("modeInjectionIds") }.toSet()
    if (!validModeInjectionIds.containsAll(requestedModeInjectionIds)) {
        throw BadRequestException("modeInjectionIds contains unknown injection id")
    }

    val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
    val requestedLorebookIds = lorebookIds.map { it.toUuid("lorebookIds") }.toSet()
    if (!validLorebookIds.containsAll(requestedLorebookIds)) {
        throw BadRequestException("lorebookIds contains unknown lorebook id")
    }

    return ConversationInjectionIds(
        modeInjectionIds = requestedModeInjectionIds,
        lorebookIds = requestedLorebookIds,
    )
}

private suspend fun applyInitialConversationInjections(
    chatService: ChatService,
    settingsStore: SettingsStore,
    conversationId: Uuid,
    modeInjectionIds: List<String>?,
    lorebookIds: List<String>?,
) {
    if (modeInjectionIds == null && lorebookIds == null) {
        return
    }

    val conversation = chatService.getConversationFlow(conversationId).first()
    val settings = settingsStore.settingsFlow.first()
    val assistant = settings.assistants.firstOrNull { it.id == conversation.assistantId }
        ?: throw NotFoundException("Assistant not found")
    if (!assistant.allowConversationPromptInjection) {
        if (modeInjectionIds.orEmpty().isNotEmpty() || lorebookIds.orEmpty().isNotEmpty()) {
            throw BadRequestException("Conversation prompt injection is not enabled for this assistant")
        }
        return
    }

    val (requestedModeInjectionIds, requestedLorebookIds) = validateConversationInjectionIds(
        settings = settings,
        modeInjectionIds = modeInjectionIds ?: conversation.modeInjectionIds.map { it.toString() },
        lorebookIds = lorebookIds ?: conversation.lorebookIds.map { it.toString() },
    )

    chatService.updateConversationState(conversationId) {
        it.copy(
            modeInjectionIds = requestedModeInjectionIds,
            lorebookIds = requestedLorebookIds,
        )
    }
}
