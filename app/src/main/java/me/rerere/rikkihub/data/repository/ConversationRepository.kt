package me.rerere.rikkihub.data.repository

import android.database.sqlite.SQLiteBlobTooBigException
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.ConversationSortOrder
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.fts.MessageFtsManager
import me.rerere.rikkihub.data.db.fts.MessageSearchSort
import me.rerere.rikkihub.data.db.dao.ConversationDAO
import me.rerere.rikkihub.data.db.dao.FavoriteDAO
import me.rerere.rikkihub.data.db.dao.MessageNodeDAO
import me.rerere.rikkihub.data.db.entity.AttachmentRetentionEntity
import me.rerere.rikkihub.data.db.entity.ConversationEntity
import me.rerere.rikkihub.data.db.entity.MessageNodeEntity
import me.rerere.rikkihub.data.db.entity.OperationReceiptEntity
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.mutation.ConversationVersion
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.utils.JsonInstant
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

class ConversationRepository(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val favoriteDAO: FavoriteDAO,
    private val database: AppDatabase,
    private val filesManager: FilesManager,
    private val messageFtsManager: MessageFtsManager,
) {
    companion object {
        private const val TAG = "ConversationRepository"
        private const val PAGE_SIZE = 20
        private const val INITIAL_LOAD_SIZE = 40
        const val RETENTION_TTL_MILLIS: Long = 24L * 60 * 60 * 1000
        private const val SQL_IN_CHUNK = 500
    }

    private val _deletedConversationIds = kotlinx.coroutines.flow.MutableSharedFlow<Uuid>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 会话删除事件（全部删除入口都经 [deleteConversation]）；claudelife 据此停止该会话保活。 */
    val deletedConversationIds: kotlinx.coroutines.flow.SharedFlow<Uuid> = _deletedConversationIds

    // 加载节点时跳页（SQLiteBlobTooBig/IllegalState）的会话：
    // 其 message_nodes 已不完整，落库时禁止删节点重插，避免覆盖未读到的数据。
    private val dirtyConversationIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // dirty 会话已持久化节点的签名基线（nodeId -> encoded messages+selectIndex）：
    // 增量写回只 upsert 签名变化/新出现的节点，不重排、不删除。
    private val dirtyNodeSignatures = ConcurrentHashMap<String, MutableMap<String, String>>()

    private fun nodeSignature(node: MessageNode): String =
        JsonInstant.encodeToString(node.messages) + "#" + node.selectIndex

    /** dirty 会话判断（ChatService 命令层 BUSY/只读裁决用）。 */
    fun isDirtyConversation(conversationId: Uuid): Boolean =
        conversationId.toString() in dirtyConversationIds

    private fun nodeEntities(conversationId: String, nodes: List<MessageNode>): List<MessageNodeEntity> =
        nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex,
            )
        }

    /** 当前内存态对应的持久版本断言（调用方在事务外读取一次即可）。 */
    suspend fun currentVersion(conversationId: Uuid): ConversationVersion? =
        conversationDAO.getConversationByIdIncludingDeleted(conversationId.toString())?.let {
            ConversationVersion(epoch = 0, generation = it.generation, revision = it.revision)
        }

    /** 活跃（未软删除）会话的轻量行，按 id 批量查；分批避开 SQLite 999 个参数的上限。 */
    suspend fun getActiveConversationSummaries(ids: Collection<String>): Map<String, LightConversationEntity> =
        ids.distinct().chunked(SQL_IN_CHUNK).flatMap { conversationDAO.getLightConversationsByIds(it) }
            .associateBy { it.id }

    /** [nodeIds] 中仍持久存在于该会话的节点 id。 */
    suspend fun existingNodeIds(conversationId: String, nodeIds: Collection<String>): Set<String> =
        nodeIds.distinct().chunked(SQL_IN_CHUNK)
            .flatMapTo(HashSet()) { messageNodeDAO.getExistingNodeIds(conversationId, it) }

    private suspend fun writeReceipt(
        operationId: String,
        conversationId: String,
        phase: String,
        before: String = "",
        after: String = "",
        errorCode: String = "",
    ) {
        if (operationId.isBlank()) return
        val now = System.currentTimeMillis()
        database.operationReceiptDao().upsert(
            OperationReceiptEntity(
                operationId = operationId,
                domain = "ROOM_CONVERSATION",
                objectKey = "conversation:$conversationId",
                phase = phase,
                beforeToken = before,
                afterToken = after,
                errorCode = errorCode,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * 为「将被移除引用」的附件登记持久保留租约。
     * 由调用方在**同一个 Room 事务**里调用，保证与会话行/节点写原子提交；
     * 文件实际删除由 [retentionSweep] 在到期且复查无引用后进行。
     */
    fun retentionEntries(
        conversationId: String,
        relativePaths: Collection<String>,
        sourceUrls: Map<String, String> = emptyMap(),
        reason: String,
        ttlMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): List<AttachmentRetentionEntity> = relativePaths.distinct().map { rel ->
        AttachmentRetentionEntity(
            relativePath = rel,
            conversationId = conversationId,
            sourceUrl = sourceUrls[rel] ?: "",
            reason = reason,
            createdAt = now,
            expiresAt = now + ttlMillis,
        )
    }

    /** 事务内写保留租约行（供 Repository 内部事务复用）。 */
    private suspend fun writeRetentionSync(entries: List<AttachmentRetentionEntity>) {
        if (entries.isNotEmpty()) database.attachmentRetentionDao().upsertAll(entries)
    }

    /** 过期保留项：只返回复查后仍无引用的路径，由调用方删盘后再 [releaseRetention]。 */
    suspend fun retentionSweep(
        now: Long = System.currentTimeMillis(),
        isStillReferenced: suspend (AttachmentRetentionEntity) -> Boolean,
    ): List<AttachmentRetentionEntity> {
        val dao = database.attachmentRetentionDao()
        val expired = dao.expired(now)
        val releasable = mutableListOf<AttachmentRetentionEntity>()
        for (entry in expired) {
            val referenced = try {
                isStillReferenced(entry)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 引用复查失败按受保护处理（fail-closed，不删）。
                Log.w(TAG, "retentionSweep: reference check failed for ${entry.relativePath}", e)
                true
            }
            if (!referenced) releasable += entry
        }
        return releasable
    }

    suspend fun releaseRetention(relativePath: String) {
        database.attachmentRetentionDao().delete(relativePath)
    }

    suspend fun retainedForConversation(conversationId: Uuid): List<AttachmentRetentionEntity> =
        database.attachmentRetentionDao().listByConversation(conversationId.toString())

    /** 该相对路径当前是否仍有未过期租约（与其他保护判定的并集使用）。 */
    suspend fun isRetained(relativePath: String, now: Long = System.currentTimeMillis()): Boolean =
        database.attachmentRetentionDao().isRetained(relativePath, now)

    suspend fun openReceiptOperations(): List<String> =
        database.operationReceiptDao().openOperationIds()

    suspend fun hasFileReference(fileUrl: String): Boolean =
        messageNodeDAO.hasFileReference(JsonInstant.encodeToString(fileUrl))

    /**
     * 存储清理专用的多形态引用检查（比 hasFileReference 更保守）：
     * 除 `"file://…"` 精确形态外，还查裸 URL（命中 `Tool.input`/`ServerTool`/
     * `ToolResult` 等 JSON 串里 `\"file://…\"` 的转义嵌套）和 `upload/<name>` 相对路径。
     * 任一 needle 命中即视为有引用；只会误报保护，不会漏报导致误删。
     */
    suspend fun hasStorageFileReference(fileUrl: String, relativePath: String): Boolean {
        for (needle in storageFileReferenceNeedles(fileUrl, relativePath)) {
            if (messageNodeDAO.hasRawFileReference(needle)) return true
        }
        return false
    }

    /** Metadata-only rescue scan; does not load potentially large message trees. */
    suspend fun countConversationsByAssistant(): List<me.rerere.rikkihub.data.db.dao.ConversationAssistantCount> =
        conversationDAO.countByAssistant()

    suspend fun getRecentConversations(assistantId: Uuid, limit: Int = 10): List<Conversation> {
        return conversationDAO.getRecentConversationsOfAssistant(
            assistantId = assistantId.toString(),
            limit = limit
        ).map { entity ->
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        }
    }

    suspend fun getConversationTitlesOfAssistant(assistantId: Uuid): Set<String> =
        conversationDAO.getTitlesOfAssistant(assistantId.toString()).toSet()

    fun getConversationsOfAssistant(assistantId: Uuid): Flow<List<Conversation>> {
        return conversationDAO
            .getConversationsOfAssistant(assistantId.toString())
            .map { flow ->
                flow.map { entity ->
                    // 列表视图不需要完整的 nodes，使用空列表
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    fun getConversationsOfAssistantPaging(assistantId: Uuid): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = { conversationDAO.getConversationsOfAssistantPaging(assistantId.toString()) }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getUnfiledConversationsOfAssistantPaging(
        assistantId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getUnfiledConversationsOfAssistantPaging(
                assistantId = assistantId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    fun getConversationsOfFolderPaging(
        folderId: Uuid,
        sortOrder: ConversationSortOrder = ConversationSortOrder.UPDATE_TIME,
    ): Flow<PagingData<Conversation>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            initialLoadSize = INITIAL_LOAD_SIZE,
            enablePlaceholders = false
        ),
        pagingSourceFactory = {
            conversationDAO.getConversationsOfFolderPaging(
                folderId = folderId.toString(),
                sortByCreateTime = sortOrder == ConversationSortOrder.CREATE_TIME,
            )
        }
    ).flow.map { pagingData ->
        pagingData.map { entity ->
            conversationSummaryToConversation(entity)
        }
    }

    suspend fun getConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.getConversationsOfAssistantPaging(assistantId.toString())
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun searchConversationsOfAssistantPage(
        assistantId: Uuid,
        titleKeyword: String,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        val pagingSource = conversationDAO.searchConversationsOfAssistantPaging(
            assistantId = assistantId.toString(),
            searchText = titleKeyword
        )
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getUnfiledConversationsOfAssistantPage(
        assistantId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getUnfiledConversationsOfAssistantPaging(assistantId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    suspend fun getConversationsOfFolderPage(
        folderId: Uuid,
        offset: Int,
        limit: Int,
    ): ConversationPageResult = loadConversationPage(
        conversationDAO.getConversationsOfFolderPaging(folderId.toString(), sortByCreateTime = false),
        offset,
        limit,
    )

    private suspend fun loadConversationPage(
        pagingSource: PagingSource<Int, LightConversationEntity>,
        offset: Int,
        limit: Int,
    ): ConversationPageResult {
        return try {
            when (
                val result = pagingSource.load(
                    PagingSource.LoadParams.Refresh(
                        key = if (offset == 0) null else offset,
                        loadSize = limit,
                        placeholdersEnabled = false
                    )
                )
            ) {
                is PagingSource.LoadResult.Page -> ConversationPageResult(
                    items = result.data.map { entity ->
                        conversationSummaryToConversation(entity)
                    },
                    nextOffset = result.nextKey
                )

                is PagingSource.LoadResult.Error -> throw result.throwable
                is PagingSource.LoadResult.Invalid -> ConversationPageResult(emptyList(), null)
            }
        } finally {
            pagingSource.invalidate()
        }
    }

    suspend fun getConversationById(uuid: Uuid): Conversation? {
        val entity = conversationDAO.getConversationById(uuid.toString())
        return if (entity != null) {
            val nodes = loadMessageNodes(entity.id)
            conversationEntityToConversation(entity, nodes)
        } else null
    }

    suspend fun existsConversationById(uuid: Uuid): Boolean {
        return conversationDAO.existsById(uuid.toString())
    }

    suspend fun countConversations(): Int {
        return conversationDAO.countAll()
    }

    /** Returns the durable version after the write (callers holding memory state must adopt it). */
    suspend fun insertConversation(conversation: Conversation): ConversationVersion =
        ConversationVersion(0, conversation.generation, persistConversation(conversation))

    /** Returns the durable version after the write (callers holding memory state must adopt it). */
    suspend fun updateConversation(conversation: Conversation): ConversationVersion =
        ConversationVersion(0, conversation.generation, persistConversation(conversation))

    /**
     * Import of a conversation whose id may have been used before (backup importers derive stable
     * ids). A deleted conversation with that id is replaced by the imported copy; a live one is not.
     */
    suspend fun importConversation(conversation: Conversation) {
        database.withTransaction {
            purgeDeletedConversation(conversation.id)
            insertConversation(conversation)
        }
    }

    /** Ordinary row projection incl. tombstones (no message bodies). */
    suspend fun managedRow(conversationId: Uuid): Conversation? =
        conversationDAO.getConversationByIdIncludingDeleted(conversationId.toString())
            ?.let { conversationEntityToConversation(it, emptyList()) }

    /** Full tree including a tombstoned row (management read projection only). */
    suspend fun managedTree(conversationId: Uuid): Conversation? =
        conversationDAO.getConversationByIdIncludingDeleted(conversationId.toString())
            ?.let { conversationEntityToConversation(it, loadMessageNodes(it.id)) }

    /** Conversation page for the creator's read tools (all assistants except [excludedAssistantId]). */
    suspend fun managedConversationPage(excludedAssistantId: Uuid, filter: String, offset: Int, limit: Int) =
        conversationDAO.managedConversationPage(excludedAssistantId.toString(), filter, limit, offset)

    /** Reference projection for "no other reference" predicate baselines. */
    suspend fun referenceRows(): List<me.rerere.rikkihub.data.db.dao.ConversationReferenceRow> =
        conversationDAO.referenceRows()

    /** Non-blocking management receipt (outside a business transaction). */
    suspend fun writeManagementReceipt(operationId: String, objectKey: String, after: String) {
        val now = System.currentTimeMillis()
        database.operationReceiptDao().upsert(
            OperationReceiptEntity(operationId = operationId, domain = "MANAGEMENT", objectKey = objectKey,
                phase = "APPLIED", afterToken = after, createdAt = now, updatedAt = now)
        )
    }

    /** Durable operation receipt (written in the same Room transaction as the business row). */
    suspend fun operationReceipt(operationId: String): me.rerere.rikkihub.data.db.entity.OperationReceiptEntity? =
        database.operationReceiptDao().get(operationId)

    /** Retention rows for managed upload files a command stopped referencing (never immediate deletion). */
    fun retentionForReleasedFiles(conversationId: Uuid, urls: Collection<String>, reason: String): List<AttachmentRetentionEntity> {
        val byPath = urls.mapNotNull { url ->
            me.rerere.rikkihub.data.files.extractUploadRelativePath(url)?.let { it to url }
        }.toMap()
        return retentionEntries(conversationId.toString(), byPath.keys, byPath, reason, RETENTION_TTL_MILLIS)
    }

    suspend fun isSoftDeleted(conversationId: Uuid): Boolean =
        conversationDAO.getConversationByIdIncludingDeleted(conversationId.toString())?.deletedAt != null

    /**
     * 命令层持久化：CAS 整树写 + 附件保留租约 + operation receipt 同一 Room 事务。
     * conversation.revision/generation 即 CAS 期望值（由调用方从最新行/内存态取）。
     */
    suspend fun persistConversationCas(
        conversation: Conversation,
        operationId: String = "",
        retainedFiles: List<AttachmentRetentionEntity> = emptyList(),
    ) = persistConversation(conversation, operationId, retainedFiles)

    /**
     * 单事务 upsert：会话行 + 节点重建 + FTS 索引 + revision 乐观并发。
     *
     * - 已存在的活跃行：`updateCas(revision, generation)` ——并发写回退/越权写
     *   会命中 0 行并抛 [MutationConflictException]，不再静默覆盖。
     * - 新行：`insert`（行已存在但被软删除时按冲突抛错，禁止旧快照复活墓碑）。
     * - dirty 会话（跳页加载）：**增量节点 upsert**——只写签名变化的节点，
     *   不删不重排（修改/新增能落库；已损坏节点页仍被保护不丢失）。
     * - [operationId] 非空时在同一事务写 operation receipt（INTENT 相位由
     *   调用方在提交前写；这里只记 APPLIED 相位，失败由调用方记 FAILED）。
     */
    private suspend fun persistConversation(
        conversation: Conversation,
        operationId: String = "",
        retainedFiles: List<AttachmentRetentionEntity> = emptyList(),
    ): Long {
        val conversationId = conversation.id.toString()
        val dirty = conversationId in dirtyConversationIds
        var newRevision = conversation.revision
        database.withTransaction {
            val existing = conversationDAO.getConversationByIdIncludingDeleted(conversationId)
            if (existing == null) {
                conversationDAO.insert(conversationToConversationEntity(conversation).copy(revision = 1))
                newRevision = 1
            } else {
                if (existing.deletedAt != null) {
                    throw MutationConflictException(
                        "conversation $conversationId is soft-deleted; restore it before writing"
                    )
                }
                val entity = conversationToConversationEntity(conversation)
                val updated = conversationDAO.updateCas(
                    id = conversationId,
                    expectedRevision = conversation.revision,
                    expectedGeneration = conversation.generation,
                    assistantId = entity.assistantId,
                    title = entity.title,
                    nodes = entity.nodes,
                    createAt = entity.createAt,
                    updateAt = entity.updateAt,
                    chatSuggestions = entity.chatSuggestions,
                    isPinned = entity.isPinned,
                    customSystemPrompt = entity.customSystemPrompt,
                    modeInjectionIds = entity.modeInjectionIds,
                    lorebookIds = entity.lorebookIds,
                    workspaceCwd = entity.workspaceCwd,
                    workspaceCwdScoped = entity.workspaceCwdScoped,
                    folderId = entity.folderId,
                )
                if (updated == 0) {
                    throw MutationConflictException(
                        "conversation $conversationId CAS failed: expected revision=" +
                            "${conversation.revision}/generation=${conversation.generation}"
                    )
                }
                newRevision = conversation.revision + 1
            }
            if (dirty) {
                // 增量写回——签名基线内未变节点跳过；不删除库内已有节点。
                val signatures = dirtyNodeSignatures.getOrPut(conversationId) { mutableMapOf() }
                val changed = conversation.messageNodes.filter { node ->
                    signatures[node.id.toString()] != nodeSignature(node)
                }
                if (changed.isNotEmpty()) {
                    messageNodeDAO.upsertChangedNodes(nodeEntities(conversationId, changed))
                    changed.forEach { signatures[it.id.toString()] = nodeSignature(it) }
                    messageFtsManager.indexConversationSync(conversation)
                } else {
                    Log.d(TAG, "dirty conversation $conversationId: no node changes to persist")
                }
            } else {
                // 删除旧的节点，插入新的节点
                messageNodeDAO.deleteByConversation(conversationId)
                saveMessageNodes(conversationId, conversation.messageNodes)
                // FTS 索引并入同一事务，消除每行自动提交
                messageFtsManager.indexConversationSync(conversation)
            }
            writeRetentionSync(retainedFiles)
            if (operationId.isNotBlank()) {
                writeReceipt(
                    operationId = operationId,
                    conversationId = conversationId,
                    phase = "APPLIED",
                    after = "${conversation.generation}:$newRevision",
                )
            }
        }
        return newRevision
    }

    /**
     * 软删除（tombstone）：同事务置 deleted_at+generation+1、清 FTS、
     * 登记附件保留租约；文件由 sweeper 到期复查后删，Undo 窗口内可完整恢复。
     */
    suspend fun softDeleteConversation(
        conversationId: Uuid,
        expected: ConversationVersion? = null,
        operationId: String = "",
        retentionTtlMillis: Long = RETENTION_TTL_MILLIS,
        retainRelativePaths: List<String> = emptyList(),
        retainSourceUrls: Map<String, String> = emptyMap(),
    ): Conversation = run {
        val id = conversationId.toString()
        var restored: Conversation? = null
        database.withTransaction {
            val existing = conversationDAO.getConversationByIdIncludingDeleted(id)
                ?: throw MutationConflictException("conversation $id not found")
            if (existing.deletedAt != null) {
                throw MutationConflictException("conversation $id already deleted")
            }
            if (expected != null &&
                (existing.revision != expected.revision || existing.generation != expected.generation)
            ) {
                throw MutationConflictException(
                    "conversation $id soft-delete CAS failed (expected rev=${expected.revision}/gen=${expected.generation}, " +
                        "actual rev=${existing.revision}/gen=${existing.generation})"
                )
            }
            val now = System.currentTimeMillis()
            check(
                conversationDAO.markDeletedCas(
                    id = id,
                    expectedRevision = existing.revision,
                    expectedGeneration = existing.generation,
                    deletedAt = now,
                ) == 1
            ) { "soft-delete CAS lost the row for $id" }
            messageFtsManager.deleteConversationSync(id)
            writeRetentionSync(
                retentionEntries(
                    conversationId = id,
                    relativePaths = retainRelativePaths,
                    sourceUrls = retainSourceUrls,
                    reason = "conversation-soft-delete",
                    ttlMillis = retentionTtlMillis,
                )
            )
            val deleted = conversationDAO.getConversationByIdIncludingDeleted(id)!!
            if (operationId.isNotBlank()) {
                writeReceipt(operationId, id, "APPLIED", after = "${deleted.generation}:${deleted.revision}")
            }
            restored = conversationEntityToConversation(deleted, emptyList())
        }
        restored!!
    }

    /** 从 tombstone 恢复（同 ID 换代、FTS 重建；附件在保留期内仍在）。 */
    suspend fun restoreConversation(
        conversationId: Uuid,
        expectedGeneration: Long? = null,
        operationId: String = "",
    ): Conversation {
        val id = conversationId.toString()
        lateinit var restored: Conversation
        database.withTransaction {
            val existing = conversationDAO.getConversationByIdIncludingDeleted(id)
                ?: throw MutationConflictException("conversation $id not found")
            if (existing.deletedAt == null) {
                throw MutationConflictException("conversation $id is not deleted")
            }
            if (expectedGeneration != null && existing.generation != expectedGeneration) {
                throw MutationConflictException(
                    "conversation $id restore CAS failed (generation ${existing.generation} != $expectedGeneration)"
                )
            }
            check(conversationDAO.restoreDeletedCas(id = id, expectedGeneration = existing.generation) == 1) {
                "restore CAS lost the row for $id"
            }
            val nodes = loadMessageNodes(id)
            val entity = conversationDAO.getConversationByIdIncludingDeleted(id)!!
            restored = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversationSync(restored)
            if (operationId.isNotBlank()) {
                writeReceipt(operationId, id, "APPLIED", after = "${restored.generation}:${restored.revision}")
            }
        }
        return restored
    }

    /**
     * 删除的会话在附件保留期（[RETENTION_TTL_MILLIS]）内可撤销恢复；超过保留期的物理删除。
     * 启动维护时调用。
     */
    suspend fun purgeExpiredDeletedConversations(now: Long = System.currentTimeMillis()): Int =
        conversationDAO.purgeDeletedBefore(now - RETENTION_TTL_MILLIS)

    /** 物理删除一个已软删除的会话；活跃或不存在的 ID 不受影响。 */
    private suspend fun purgeDeletedConversation(conversationId: Uuid) {
        val id = conversationId.toString()
        database.withTransaction {
            val entity = conversationDAO.getConversationByIdIncludingDeleted(id) ?: return@withTransaction
            if (entity.deletedAt != null) {
                conversationDAO.delete(entity)
                messageFtsManager.deleteConversationSync(id)
            }
        }
        dirtyConversationIds.remove(id)
        dirtyNodeSignatures.remove(id)
    }

    /**
     * 会话删除：**不再即时删文件**——转 tombstone + 附件保留租约；
     * 到期由 [retentionSweep] 复查引用后统一删盘。删除事件仍即时广播（保活/UI）。
     */
    suspend fun deleteConversation(conversation: Conversation) {
        // 获取完整的 Conversation（包含 messageNodes）以收集保留附件清单
        val fullConversation = if (conversation.messageNodes.isEmpty()) {
            getConversationById(conversation.id) ?: conversation
        } else {
            conversation
        }
        val retained = fullConversation.files.mapNotNull { uri ->
            me.rerere.rikkihub.data.files.extractUploadRelativePath(uri.toString())
        }
        val sources = fullConversation.files.mapNotNull { uri ->
            me.rerere.rikkihub.data.files.extractUploadRelativePath(uri.toString())
                ?.let { it to uri.toString() }
        }.toMap()
        try {
            softDeleteConversation(
                conversationId = conversation.id,
                retainRelativePaths = retained,
                retainSourceUrls = sources,
            )
        } catch (e: MutationConflictException) {
            // 幂等：行不存在/已是 tombstone 均视为已删除，不重复报错。
            if (e.message?.contains("already deleted") == true ||
                e.message?.contains("not found") == true
            ) {
                dirtyConversationIds.remove(conversation.id.toString())
                dirtyNodeSignatures.remove(conversation.id.toString())
                return
            }
            throw e
        }
        dirtyConversationIds.remove(conversation.id.toString())
        dirtyNodeSignatures.remove(conversation.id.toString())
        _deletedConversationIds.tryEmit(conversation.id)
    }

    /** 删除并同时登记其附件的持久保留租约（同一事务）。 */
    suspend fun deleteConversationRetainingFiles(
        conversation: Conversation,
        retainRelativePaths: List<String>,
        retainSourceUrls: Map<String, String>,
        operationId: String = "",
    ) {
        softDeleteConversation(
            conversationId = conversation.id,
            operationId = operationId,
            retainRelativePaths = retainRelativePaths,
            retainSourceUrls = retainSourceUrls,
        )
        dirtyConversationIds.remove(conversation.id.toString())
        dirtyNodeSignatures.remove(conversation.id.toString())
        _deletedConversationIds.tryEmit(conversation.id)
    }

    suspend fun searchMessages(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: Uuid? = null,
    ) = messageFtsManager.search(keyword, sort, assistantId?.let { listOf(it.toString()) })

    /** 只在 [assistantIds] 这些助手的对话里搜索 */
    suspend fun searchMessagesOfAssistants(keyword: String, assistantIds: Set<Uuid>) =
        messageFtsManager.search(keyword, MessageSearchSort.RELEVANCE, assistantIds.map { it.toString() })

    suspend fun rebuildAllIndexes(onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }) {
        messageFtsManager.deleteAll()
        val allIds = conversationDAO.getAllIds()
        val total = allIds.size
        allIds.forEachIndexed { index, id ->
            val entity = conversationDAO.getConversationById(id) ?: return@forEachIndexed
            val nodes = loadMessageNodes(entity.id)
            val conversation = conversationEntityToConversation(entity, nodes)
            messageFtsManager.indexConversation(conversation)
            onProgress(index + 1, total)
        }
    }

    suspend fun deleteConversationOfAssistant(assistantId: Uuid) {
        getConversationsOfAssistant(assistantId).first().forEach { conversation ->
            deleteConversation(conversation)
        }
    }

    fun conversationToConversationEntity(conversation: Conversation): ConversationEntity {
        require(conversation.messageNodes.none { it.messages.any { message -> message.hasBase64Part() } })
        return ConversationEntity(
            id = conversation.id.toString(),
            title = conversation.title,
            nodes = "[]",  // nodes 现在存储在单独的表中
            createAt = conversation.createAt.toEpochMilli(),
            updateAt = conversation.updateAt.toEpochMilli(),
            assistantId = conversation.assistantId.toString(),
            chatSuggestions = JsonInstant.encodeToString(conversation.chatSuggestions),
            isPinned = conversation.isPinned,
            customSystemPrompt = conversation.customSystemPrompt ?: "",
            modeInjectionIds = JsonInstant.encodeToString(conversation.modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(conversation.lorebookIds),
            workspaceCwd = conversation.workspaceCwd ?: "",
            workspaceCwdScoped = conversation.workspaceCwdScoped,
            folderId = conversation.folderId?.toString() ?: "",
        )
    }

    fun conversationEntityToConversation(
        conversationEntity: ConversationEntity,
        messageNodes: List<MessageNode>
    ): Conversation {
        return Conversation(
            id = Uuid.parse(conversationEntity.id),
            title = conversationEntity.title,
            messageNodes = messageNodes.filter { it.messages.isNotEmpty() },
            createAt = Instant.ofEpochMilli(conversationEntity.createAt),
            updateAt = Instant.ofEpochMilli(conversationEntity.updateAt),
            assistantId = Uuid.parse(conversationEntity.assistantId),
            chatSuggestions = JsonInstant.decodeFromString(conversationEntity.chatSuggestions),
            isPinned = conversationEntity.isPinned,
            customSystemPrompt = conversationEntity.customSystemPrompt.ifEmpty { null },
            modeInjectionIds = JsonInstant.decodeFromString(conversationEntity.modeInjectionIds),
            lorebookIds = JsonInstant.decodeFromString(conversationEntity.lorebookIds),
            workspaceCwd = conversationEntity.workspaceCwd.ifEmpty { null },
            workspaceCwdScoped = conversationEntity.workspaceCwdScoped,
            folderId = conversationEntity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
            revision = conversationEntity.revision,
            generation = conversationEntity.generation,
            deletedAt = conversationEntity.deletedAt,
        )
    }

    fun getPinnedConversations(): Flow<List<Conversation>> {
        return conversationDAO
            .getPinnedConversations()
            .map { flow ->
                flow.map { entity ->
                    conversationEntityToConversation(entity, emptyList())
                }
            }
    }

    suspend fun updatePinStatus(conversationId: Uuid, isPinned: Boolean) {
        conversationDAO.updatePinStatus(conversationId.toString(), isPinned)
    }

    /** Moving between assistants atomically clears the old assistant's folder; nodes are untouched. */
    suspend fun updateConversationAssistant(conversationId: Uuid, assistantId: Uuid) {
        conversationDAO.updateAssistant(conversationId.toString(), assistantId.toString())
    }

    /**
     * 单列更新会话标题，避免整行 + 节点重建。
     */
    suspend fun updateTitle(conversationId: Uuid, title: String) {
        conversationDAO.updateTitle(conversationId.toString(), title)
    }

    /**
     * 单列更新会话的聊天建议，避免整行 + 节点重建。
     */
    suspend fun updateChatSuggestions(conversationId: Uuid, chatSuggestions: List<String>) {
        conversationDAO.updateChatSuggestions(
            id = conversationId.toString(),
            chatSuggestions = JsonInstant.encodeToString(chatSuggestions),
        )
    }

    /**
     * 单列更新会话的模式注入 / Lorebook 绑定。
     */
    suspend fun updateInjectionIds(
        conversationId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
    ) {
        conversationDAO.updateInjectionIds(
            id = conversationId.toString(),
            modeInjectionIds = JsonInstant.encodeToString(modeInjectionIds),
            lorebookIds = JsonInstant.encodeToString(lorebookIds),
        )
    }

    /**
     * 单列更新某个消息节点当前选中的消息分支下标。
     */
    suspend fun updateNodeSelectIndex(conversationId: Uuid, nodeId: Uuid, index: Int) {
        messageNodeDAO.updateSelectIndex(
            conversationId = conversationId.toString(),
            nodeId = nodeId.toString(),
            selectIndex = index,
        )
    }

    /**
     * 单列更新会话的文件夹归属，folderId 为 null 表示移出文件夹（未归类）。
     */
    suspend fun updateConversationFolderId(conversationId: Uuid, folderId: Uuid?) {
        conversationDAO.updateFolderId(
            id = conversationId.toString(),
            folderId = folderId?.toString() ?: ""
        )
    }

    /**
     * 单列更新会话级 system prompt 覆写，null 表示清除（列内以空串存储）。
     */
    suspend fun updateCustomSystemPrompt(conversationId: Uuid, customSystemPrompt: String?) {
        conversationDAO.updateCustomSystemPrompt(
            id = conversationId.toString(),
            customSystemPrompt = customSystemPrompt ?: ""
        )
    }

    /**
     * 单列更新会话的工作区 cwd，null 表示清除（列内以空串存储）。
     */
    suspend fun updateWorkspaceCwd(conversationId: Uuid, workspaceCwd: String?) {
        conversationDAO.updateWorkspaceCwd(
            id = conversationId.toString(),
            workspaceCwd = workspaceCwd ?: ""
        )
    }

    suspend fun updateWorkspaceCwdScoped(conversationId: Uuid, scoped: Boolean) {
        conversationDAO.updateWorkspaceCwdScoped(id = conversationId.toString(), scoped = scoped)
    }

    private fun conversationSummaryToConversation(entity: LightConversationEntity): Conversation {
        return Conversation(
            id = Uuid.parse(entity.id),
            assistantId = Uuid.parse(entity.assistantId),
            title = entity.title,
            isPinned = entity.isPinned,
            createAt = Instant.ofEpochMilli(entity.createAt),
            updateAt = Instant.ofEpochMilli(entity.updateAt),
            messageNodes = emptyList(),
            folderId = entity.folderId.ifEmpty { null }?.let { Uuid.parse(it) },
        )
    }

    private suspend fun loadMessageNodes(conversationId: String): List<MessageNode> {
        val favoriteNodeIds = favoriteDAO
            .getFavoriteNodeIdsOfConversation(conversationId)
            .mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            .toSet()

        return database.withTransaction {
            val nodes = mutableListOf<MessageNode>()
            var skippedPage = false
            var offset = 0
            val pageSize = 64
            while (true) {
                val page = readNodePageOrSkip(
                    read = { messageNodeDAO.getNodesOfConversationPaged(conversationId, pageSize, offset) },
                    onSkip = { e ->
                        val kind = if (e is SQLiteBlobTooBigException) "oversized" else "broken"
                        Log.w(TAG, "loadMessageNodes: skip $kind page for $conversationId", e)
                    },
                )
                if (page == null) {
                    skippedPage = true
                    offset += pageSize
                    continue
                }
                if (page.isEmpty()) break
                page.forEach { entity ->
                    val messages = JsonInstant.decodeFromString<List<UIMessage>>(entity.messages)
                    val nodeId = Uuid.parse(entity.id)
                    nodes.add(
                        MessageNode(
                            id = nodeId,
                            messages = messages,
                            selectIndex = entity.selectIndex.coerceIn(0, (messages.size - 1).coerceAtLeast(0)),
                            isFavorite = favoriteNodeIds.contains(nodeId)
                        )
                    )
                }
                offset += page.size
            }
            if (skippedPage) {
                // 节点不完整：记入 dirty + 建立增量写回签名基线。
                // 之后持久化只 upsert 变化节点，不删未读到的节点。
                dirtyConversationIds.add(conversationId)
                dirtyNodeSignatures[conversationId] = nodes.associate {
                    it.id.toString() to nodeSignature(it)
                }.toMutableMap()
            } else {
                dirtyConversationIds.remove(conversationId)
                dirtyNodeSignatures.remove(conversationId)
            }
            nodes
        }
    }

    private suspend fun saveMessageNodes(conversationId: String, nodes: List<MessageNode>) {
        val entities = nodes.mapIndexed { index, node ->
            MessageNodeEntity(
                id = node.id.toString(),
                conversationId = conversationId,
                nodeIndex = index,
                messages = JsonInstant.encodeToString(node.messages),
                selectIndex = node.selectIndex
            )
        }
        messageNodeDAO.insertAll(entities)
    }
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String = "",
)

data class ConversationPageResult(
    val items: List<Conversation>,
    val nextOffset: Int?,
)

/**
 * 存储清理引用检查的 needle 集合（纯函数，可单测）。
 * 覆盖三种持久化形态：
 * 1. `"file://<abs>"` —— part.url 的 JSON 字符串精确形态（含引号）；
 * 2. `file://<abs>` —— 裸 URL，命中 Tool.input/ServerTool 等 JSON 串内
 *    `\"file://…\"` 转义嵌套（转义符 `\` 出现在 `file` 之前，不影响 instr 匹配）；
 * 3. `upload/<name>` —— 裸相对路径，兜底非标准 URL 形态的引用。
 * 不加引号、不做 URL decode；过短 needle 直接剔除避免误命中。
 */
internal fun storageFileReferenceNeedles(fileUrl: String, relativePath: String): List<String> =
    listOf(
        JsonInstant.encodeToString(fileUrl), // 形如 "file:///…"（带引号）
        fileUrl,
        relativePath,
    ).map { it.trim() }
        .filter { it.length >= 6 }
        .distinct()

/**
 * Reads one page of message nodes; null means the page is unreadable (oversized or broken row) and
 * is skipped. CancellationException is an IllegalStateException, so it has to be rethrown first:
 * swallowed, a cancelled load keeps "skipping" pages forever inside the Room transaction.
 */
internal suspend fun <T> readNodePageOrSkip(
    read: suspend () -> List<T>,
    onSkip: (Throwable) -> Unit,
): List<T>? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: SQLiteBlobTooBigException) {
    onSkip(e)
    null
} catch (e: IllegalStateException) {
    onSkip(e)
    null
}
