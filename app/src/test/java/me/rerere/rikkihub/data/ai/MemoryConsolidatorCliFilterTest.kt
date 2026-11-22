package me.rerere.rikkihub.data.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tools.BuiltinToolIds
import me.rerere.rikkihub.data.ai.tools.CONVERSATION_READ_TOOL
import me.rerere.rikkihub.data.ai.tools.createMemoryConversationTools
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.dao.AttachmentRetentionDAO
import me.rerere.rikkihub.data.db.dao.CharacterDAO
import me.rerere.rikkihub.data.db.dao.ConversationDAO
import me.rerere.rikkihub.data.db.dao.FavoriteDAO
import me.rerere.rikkihub.data.db.dao.FolderDAO
import me.rerere.rikkihub.data.db.dao.GenMediaDAO
import me.rerere.rikkihub.data.db.dao.LegacyMemoryConsolidationRow
import me.rerere.rikkihub.data.db.dao.ManagedFileDAO
import me.rerere.rikkihub.data.db.dao.MediaCreationDAO
import me.rerere.rikkihub.data.db.dao.MemoryConsolidationDAO
import me.rerere.rikkihub.data.db.dao.MemoryDAO
import me.rerere.rikkihub.data.db.dao.MessageNodeDAO
import me.rerere.rikkihub.data.db.dao.OperationReceiptDAO
import me.rerere.rikkihub.data.db.dao.SubagentPresetLibraryDAO
import me.rerere.rikkihub.data.db.dao.TranslationHistoryDAO
import me.rerere.rikkihub.data.db.dao.UsageRecordDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDAO
import me.rerere.rikkihub.data.db.dao.WorkspaceDeviceMountDAO
import me.rerere.rikkihub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkihub.data.db.entity.MessageNodeEntity
import me.rerere.rikkihub.data.db.fts.MessageFtsManager
import me.rerere.rikkihub.data.db.fts.MessageSearchResult
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.data.repository.FakeMemoryDAO
import me.rerere.rikkihub.data.repository.MemoryRepository
import me.rerere.rikkihub.data.repository.memoryId
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.uuid.Uuid

class MemoryConsolidatorCliFilterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val txExecutor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        scope.cancel()
        txExecutor.shutdownNow()
    }

    private class FakeMemoryConsolidationDao(
        private val conversationsProvider: () -> Map<Uuid, Conversation> = { emptyMap() },
    ) : MemoryConsolidationDAO {
        val marks = mutableMapOf<Pair<String, String>, Long>()
        var candidateIds: List<String>? = null

        override suspend fun getConsolidatedAt(conversationId: String, memoryId: String): Long? =
            marks[conversationId to memoryId]

        override suspend fun upsert(entity: MemoryConsolidationEntity) {
            marks[entity.conversationId to entity.memoryId] = entity.consolidatedAt
        }

        override suspend fun upsertMax(conversationId: String, memoryId: String, consolidatedAt: Long) {
            val key = conversationId to memoryId
            marks[key] = maxOf(marks[key] ?: Long.MIN_VALUE, consolidatedAt)
        }

        override suspend fun getUpdatedConversationIds(
            assistantIds: List<String>,
            memoryId: String,
            since: Long,
            limit: Int,
        ): List<String> {
            candidateIds?.let { return it.take(limit) }
            val allowed = assistantIds.toSet()
            return conversationsProvider().values
                .filter { c ->
                    c.assistantId.toString() in allowed &&
                        c.cliSession == null &&
                        c.updateAt.toEpochMilli() >= since &&
                        c.updateAt.toEpochMilli() > (marks[c.id.toString() to memoryId] ?: 0L)
                }
                .sortedByDescending { it.updateAt }
                .take(limit)
                .map { it.id.toString() }
        }

        override suspend fun countUpdatedConversations(
            assistantIds: List<String>,
            memoryId: String,
            since: Long,
        ): Int = getUpdatedConversationIds(assistantIds, memoryId, since, Int.MAX_VALUE).size

        override suspend fun getPrivateAssistantIdsWithUpdatedConversations(
            assistantIds: List<String>,
            since: Long,
        ): List<String> {
            val allowed = assistantIds.toSet()
            return conversationsProvider().values
                .filter { c ->
                    val aId = c.assistantId.toString()
                    aId in allowed &&
                        c.cliSession == null &&
                        c.updateAt.toEpochMilli() >= since &&
                        c.updateAt.toEpochMilli() > (marks[c.id.toString() to aId] ?: 0L)
                }
                .map { it.assistantId.toString() }
                .distinct()
        }

        override suspend fun getAssistantIdsWithUpdatedConversations(
            assistantIds: List<String>,
            memoryId: String,
            since: Long,
        ): List<String> {
            val allowed = assistantIds.toSet()
            return conversationsProvider().values
                .filter { c ->
                    c.assistantId.toString() in allowed &&
                        c.cliSession == null &&
                        c.updateAt.toEpochMilli() >= since &&
                        c.updateAt.toEpochMilli() > (marks[c.id.toString() to memoryId] ?: 0L)
                }
                .map { it.assistantId.toString() }
                .distinct()
        }

        override suspend fun copyMarks(assistantId: String, targetMemoryId: String) {
            val convs = conversationsProvider().values.filter { it.assistantId.toString() == assistantId }
            convs.forEach { c ->
                val src = marks[c.id.toString() to assistantId] ?: return@forEach
                upsertMax(c.id.toString(), targetMemoryId, src)
            }
        }

        override suspend fun getLegacyRows(legacyMemoryId: String): List<LegacyMemoryConsolidationRow> {
            val convs = conversationsProvider()
            return marks.entries
                .filter { (key, _) -> key.second == legacyMemoryId }
                .mapNotNull { (key, ts) ->
                    val conv = convs[Uuid.parse(key.first)] ?: return@mapNotNull null
                    LegacyMemoryConsolidationRow(key.first, conv.assistantId.toString(), ts)
                }
        }

        override suspend fun deleteLegacyRows(legacyMemoryId: String) {
            marks.keys.removeAll { it.second == legacyMemoryId }
        }
    }

    private inline fun <reified T> unusedProxy(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unexpected call to ${T::class.java.simpleName}.${method.name}")
        } as T

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateShell(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private inner class FakeAppDatabase(
        private val conversationDao: ConversationDAO,
        private val messageNodeDao: MessageNodeDAO,
        private val favoriteDao: FavoriteDAO,
        private val consolidationDao: MemoryConsolidationDAO,
    ) : AppDatabase() {
        override fun clearAllTables() = Unit
        override fun createInvalidationTracker(): InvalidationTracker =
            InvalidationTracker(this, emptyMap(), emptyMap(), "conversationentity")

        override val transactionExecutor: Executor get() = txExecutor
        override fun beginTransaction() = Unit
        override fun setTransactionSuccessful() = Unit
        override fun endTransaction() = Unit
        override fun inTransaction(): Boolean = false
        override val openHelper: SupportSQLiteOpenHelper get() = unusedProxy()

        override fun conversationDao(): ConversationDAO = conversationDao
        override fun memoryDao(): MemoryDAO = unusedProxy()
        override fun memoryConsolidationDao(): MemoryConsolidationDAO = consolidationDao
        override fun genMediaDao(): GenMediaDAO = unusedProxy()
        override fun messageNodeDao(): MessageNodeDAO = messageNodeDao
        override fun managedFileDao(): ManagedFileDAO = unusedProxy()
        override fun favoriteDao(): FavoriteDAO = favoriteDao
        override fun workspaceDao(): WorkspaceDAO = unusedProxy()
        override fun workspaceDeviceMountDao(): WorkspaceDeviceMountDAO = unusedProxy()
        override fun subagentPresetLibraryDao(): SubagentPresetLibraryDAO = unusedProxy()
        override fun folderDao(): FolderDAO = unusedProxy()
        override fun operationReceiptDao(): OperationReceiptDAO = unusedProxy()
        override fun attachmentRetentionDao(): AttachmentRetentionDAO = unusedProxy()
        override fun translationHistoryDao(): TranslationHistoryDAO = unusedProxy()
        override fun mediaCreationDao(): MediaCreationDAO = unusedProxy()
        override fun characterDao(): CharacterDAO = unusedProxy()
        override fun usageRecordDao(): UsageRecordDAO = unusedProxy()
    }

    private inner class Harness(
        val assistant: Assistant,
        val otherAssistant: Assistant,
        val settingsStore: SettingsStore,
        val memoryRepository: MemoryRepository,
        val consolidationDao: FakeMemoryConsolidationDao,
        val repo: ConversationRepository,
        val stored: MutableMap<Uuid, Conversation>,
        val runnerCalls: MutableList<String>,
        val consolidator: MemoryConsolidator,
    )

    private suspend fun newHarness(): Harness {
        val root = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(root, "settings.preferences_pb") },
        )
        val settingsStore = SettingsStore.forTesting(
            dataStore,
            MutationRuntime(File(root, "runtime"), File(root, "restore.marker")),
            scope,
        )
        val model = Model(modelId = "dream-model", abilities = listOf(ModelAbility.TOOL))
        val assistant = Assistant(name = "mem", enableMemory = true, autoConsolidateMemory = true)
        val otherAssistant = Assistant(name = "other", enableMemory = true)
        settingsStore.update {
            it.copy(
                providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
                chatModelId = model.id,
                fastModelId = model.id,
                assistants = listOf(assistant, otherAssistant),
            )
        }
        withTimeout(10_000) {
            settingsStore.settingsFlow.first { s -> !s.init && s.assistants.any { it.id == assistant.id } }
        }

        val stored = mutableMapOf<Uuid, Conversation>()
        lateinit var repo: ConversationRepository

        val conversationDao = Proxy.newProxyInstance(
            ConversationDAO::class.java.classLoader,
            arrayOf(ConversationDAO::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getConversationById" -> {
                    val id = Uuid.parse(args!![0] as String)
                    stored[id]?.let { repo.conversationToConversationEntity(it) }
                }
                else -> error("Unexpected ConversationDAO.${method.name}")
            }
        } as ConversationDAO

        val messageNodeDao = Proxy.newProxyInstance(
            MessageNodeDAO::class.java.classLoader,
            arrayOf(MessageNodeDAO::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getNodesOfConversationPaged" -> {
                    val convId = Uuid.parse(args!![0] as String)
                    val limit = args[1] as Int
                    val offset = args[2] as Int
                    val nodes = stored[convId]?.messageNodes.orEmpty()
                    nodes.mapIndexed { index, node ->
                        MessageNodeEntity(
                            id = node.id.toString(),
                            conversationId = convId.toString(),
                            nodeIndex = index,
                            messages = JsonInstant.encodeToString(node.messages),
                            selectIndex = node.selectIndex,
                        )
                    }.drop(offset).take(limit)
                }
                else -> error("Unexpected MessageNodeDAO.${method.name}")
            }
        } as MessageNodeDAO

        val favoriteDao = Proxy.newProxyInstance(
            FavoriteDAO::class.java.classLoader,
            arrayOf(FavoriteDAO::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getFavoriteNodeIdsOfConversation" -> emptyList<String>()
                else -> error("Unexpected FavoriteDAO.${method.name}")
            }
        } as FavoriteDAO

        val consolidationDao = FakeMemoryConsolidationDao { stored }
        val db = FakeAppDatabase(conversationDao, messageNodeDao, favoriteDao, consolidationDao)
        repo = ConversationRepository(
            conversationDAO = conversationDao,
            messageNodeDAO = messageNodeDao,
            favoriteDAO = favoriteDao,
            database = db,
            filesManager = allocateShell(FilesManager::class.java),
            messageFtsManager = allocateShell(MessageFtsManager::class.java),
        )

        val runnerCalls = mutableListOf<String>()
        val runner = MemoryDreamRunner { _, _, _, input, _, _ ->
            runnerCalls += input
            emptyList()
        }
        val memoryRepository = MemoryRepository(FakeMemoryDAO())
        val consolidator = MemoryConsolidator(
            runner = runner,
            memoryRepository = memoryRepository,
            conversationRepo = repo,
            consolidationDao = consolidationDao,
            settingsStore = settingsStore,
        )
        return Harness(
            assistant,
            otherAssistant,
            settingsStore,
            memoryRepository,
            consolidationDao,
            repo,
            stored,
            runnerCalls,
            consolidator,
        )
    }

    private fun finishedConversation(
        assistant: Assistant,
        title: String,
        text: String,
        cliSession: ConversationCliSession? = null,
    ): Conversation {
        val t1 = LocalDateTime(2026, 2, 1, 10, 1)
        val t2 = LocalDateTime(2026, 2, 1, 10, 2)
        val nodes = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), createdAt = t1).toMessageNode(),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("reply: $text")), createdAt = t2).toMessageNode(),
        )
        return Conversation(
            assistantId = assistant.id,
            title = title,
            messageNodes = nodes,
            updateAt = Instant.parse("2026-02-01T10:02:00Z"),
            cliSession = cliSession,
        )
    }

    @Test
    fun `pendingOf and findPending exclude CLI conversations`() = runBlocking {
        val h = newHarness()
        val normal = finishedConversation(h.assistant, "Normal Chat", "I like green tea")
        val cli = finishedConversation(
            h.assistant,
            "CLI Workspace Chat",
            "refactor build.gradle.kts",
            cliSession = ConversationCliSession(agentId = "builtin:agy"),
        )
        h.stored[normal.id] = normal
        h.stored[cli.id] = cli

        assertNotNull(h.consolidator.pendingOf(normal))
        assertNull(h.consolidator.pendingOf(cli))

        // Even if a candidate ID slipped past the DAO query, findPending still drops CLI chats.
        h.consolidationDao.candidateIds = listOf(cli.id.toString(), normal.id.toString())
        val pending = h.consolidator.findPending(h.assistant.memoryId)
        assertEquals(listOf(normal.id), pending.map { it.conversationId })
    }

    @Test
    fun `consolidate filters out CLI conversations and skips dream run when only CLI chats are passed`() = runBlocking {
        val h = newHarness()
        val normal = finishedConversation(h.assistant, "Normal Chat", "Normal memory fact")
        val cli = finishedConversation(
            h.assistant,
            "CLI Chat",
            "CLI secret command output",
            cliSession = ConversationCliSession(agentId = "builtin:droid"),
        )
        h.stored[normal.id] = normal
        h.stored[cli.id] = cli

        val cliOnlyRun = h.consolidator.consolidate(h.assistant.memoryId, setOf(cli.id), fullReview = false)
        assertNull(cliOnlyRun)
        assertTrue(h.runnerCalls.isEmpty())
        assertFalse(h.consolidationDao.marks.keys.any { it.first == cli.id.toString() })

        val mixedRun = h.consolidator.consolidate(h.assistant.memoryId, setOf(cli.id, normal.id), fullReview = false)
        assertNotNull(mixedRun)
        assertEquals(setOf(normal.id), mixedRun!!.advancedConversations)
        assertEquals(1, h.runnerCalls.size)
        val input = h.runnerCalls.single()
        assertTrue(input.contains(normal.id.toString()))
        assertFalse(input.contains(cli.id.toString()))
        assertFalse(input.contains("CLI secret command output"))
        assertFalse(h.consolidationDao.marks.keys.any { it.first == cli.id.toString() })
    }

    @Test
    fun `memory conversation tools reject CLI conversations on read and filter them from search with lookup cache`() = runBlocking {
        val h = newHarness()
        val normal = finishedConversation(h.assistant, "Normal", "likes espresso")
        val cli = finishedConversation(
            h.assistant,
            "CLI",
            "debug espresso build",
            cliSession = ConversationCliSession(agentId = "builtin:agy"),
        )
        val foreign = finishedConversation(h.otherAssistant, "Foreign", "foreign espresso")
        h.stored[normal.id] = normal
        h.stored[cli.id] = cli
        h.stored[foreign.id] = foreign

        val lookupCounts = mutableMapOf<Uuid, Int>()
        val searchHits = listOf(
            MessageSearchResult("n1", "m1", cli.id.toString(), cli.title, Instant.now(), "cli hit 1"),
            MessageSearchResult("n2", "m2", cli.id.toString(), cli.title, Instant.now(), "cli hit 2"),
            MessageSearchResult("n3", "m3", cli.id.toString(), cli.title, Instant.now(), "cli hit 3"),
            MessageSearchResult("n4", "m4", foreign.id.toString(), foreign.title, Instant.now(), "foreign hit"),
            MessageSearchResult("n5", "m5", normal.id.toString(), normal.title, Instant.now(), "normal hit 1"),
            MessageSearchResult("n6", "m6", normal.id.toString(), normal.title, Instant.now(), "normal hit 2"),
        )
        val tools = createMemoryConversationTools(
            searchMessages = { _, _ -> searchHits },
            getConversationById = { id ->
                lookupCounts[id] = (lookupCounts[id] ?: 0) + 1
                h.repo.getConversationById(id)
            },
            assistantIds = setOf(h.assistant.id),
        ).associateBy { it.name }

        // conversation_search excludes CLI & foreign hits, honors limit after filtering, and caches per conversationId.
        val searchOutput = tools.getValue(BuiltinToolIds.CONVERSATION_SEARCH).execute(
            buildJsonObject {
                put("query", "espresso")
                put("limit", 2)
            }
        )
        val items = JsonInstant.parseToJsonElement((searchOutput.single() as UIMessagePart.Text).text).jsonArray
        assertEquals(2, items.size)
        assertTrue(items.all { it.jsonObject["conversation_id"]?.jsonPrimitive?.content == normal.id.toString() })
        assertEquals(1, lookupCounts[cli.id])
        assertEquals(1, lookupCounts[foreign.id])
        assertEquals(1, lookupCounts[normal.id])

        // conversation_read allows normal conversation of the same assistant, rejects CLI & foreign ones.
        val readTool = tools.getValue(CONVERSATION_READ_TOOL)
        val readNormal = readTool.execute(buildJsonObject { put("conversation_id", normal.id.toString()) })
        assertTrue((readNormal.single() as UIMessagePart.Text).text.contains("likes espresso"))

        val readCliError = runCatching {
            readTool.execute(buildJsonObject { put("conversation_id", cli.id.toString()) })
        }.exceptionOrNull()
        assertNotNull(readCliError)
        assertTrue(readCliError!!.message!!.contains("conversation not found"))

        val readForeignError = runCatching {
            readTool.execute(buildJsonObject { put("conversation_id", foreign.id.toString()) })
        }.exceptionOrNull()
        assertNotNull(readForeignError)
        assertTrue(readForeignError!!.message!!.contains("conversation not found"))
    }

    @Test
    fun `consolidation marks are isolated per memoryId when switching between private and global memory`() = runBlocking {
        val h = newHarness()
        val now = Instant.now()
        val recent = finishedConversation(h.assistant, "Recent", "I prefer pour-over coffee").copy(updateAt = now)
        h.stored[recent.id] = recent

        // Consolidate into assistant's private memory store
        val privateRun = h.consolidator.consolidate(h.assistant.id.toString(), setOf(recent.id), fullReview = false)
        assertNotNull(privateRun)
        assertEquals(setOf(recent.id), privateRun!!.advancedConversations)
        assertNull(h.consolidator.pendingOf(recent))

        // Switch assistant to global memory -> same conversation is now pending for __global__!
        h.settingsStore.update { s ->
            s.copy(assistants = s.assistants.map { if (it.id == h.assistant.id) it.copy(useGlobalMemory = true) else it })
        }
        withTimeout(5_000) {
            h.settingsStore.settingsFlow.first { s -> s.assistants.first { it.id == h.assistant.id }.useGlobalMemory }
        }

        val pendingGlobal = h.consolidator.pendingOf(recent)
        assertNotNull(pendingGlobal)
        assertEquals(MemoryRepository.GLOBAL_MEMORY_ID, pendingGlobal!!.memoryId)
        assertEquals(listOf(MemoryRepository.GLOBAL_MEMORY_ID), h.consolidator.memoryIdsWithUpdates())

        // Consolidating into __global__ succeeds independently without disturbing private mark
        val globalRun = h.consolidator.consolidate(MemoryRepository.GLOBAL_MEMORY_ID, setOf(recent.id), fullReview = false)
        assertNotNull(globalRun)
        assertEquals(setOf(recent.id), globalRun!!.advancedConversations)
        assertNull(h.consolidator.pendingOf(recent))

        // Switching back to private memory does NOT re-trigger consolidation
        h.settingsStore.update { s ->
            s.copy(assistants = s.assistants.map { if (it.id == h.assistant.id) it.copy(useGlobalMemory = false) else it })
        }
        withTimeout(5_000) {
            h.settingsStore.settingsFlow.first { s -> !s.assistants.first { it.id == h.assistant.id }.useGlobalMemory }
        }
        assertNull(h.consolidator.pendingOf(recent))
    }

    @Test
    fun `legacy DB46 sentinel marks reconcile to each assistant's actual memoryId without false re-dreaming`() = runBlocking {
        val h = newHarness()
        val now = Instant.now()
        // otherAssistant ALREADY had useGlobalMemory = true before upgrade
        h.settingsStore.update { s ->
            s.copy(assistants = s.assistants.map { if (it.id == h.otherAssistant.id) it.copy(useGlobalMemory = true) else it })
        }
        withTimeout(5_000) {
            h.settingsStore.settingsFlow.first { s -> s.assistants.first { it.id == h.otherAssistant.id }.useGlobalMemory }
        }

        val privateChat = finishedConversation(h.assistant, "Private", "private fact").copy(updateAt = now)
        val globalChat = finishedConversation(h.otherAssistant, "Global", "global fact").copy(updateAt = now)
        h.stored[privateChat.id] = privateChat
        h.stored[globalChat.id] = globalChat

        // Simulate Migration_46_47 writing LEGACY_MEMORY_ID sentinel rows
        val ts = now.toEpochMilli() + 1_000
        h.consolidationDao.upsert(MemoryConsolidationEntity(privateChat.id.toString(), MemoryConsolidationEntity.LEGACY_MEMORY_ID, ts))
        h.consolidationDao.upsert(MemoryConsolidationEntity(globalChat.id.toString(), MemoryConsolidationEntity.LEGACY_MEMORY_ID, ts))

        h.consolidator.reconcileLegacyMarks()

        // Sentinel rows deleted; each resolved to its true memoryId
        assertTrue(h.consolidationDao.getLegacyRows().isEmpty())
        assertEquals(ts, h.consolidationDao.getConsolidatedAt(privateChat.id.toString(), h.assistant.id.toString()))
        assertNull(h.consolidationDao.getConsolidatedAt(privateChat.id.toString(), MemoryRepository.GLOBAL_MEMORY_ID))
        assertEquals(ts, h.consolidationDao.getConsolidatedAt(globalChat.id.toString(), MemoryRepository.GLOBAL_MEMORY_ID))
        assertNull(h.consolidationDao.getConsolidatedAt(globalChat.id.toString(), h.otherAssistant.id.toString()))
        // Neither assistant falsely re-triggers dreaming after upgrade
        assertTrue(h.consolidator.memoryIdsWithUpdates().isEmpty())
    }

    @Test
    fun `copyMarksToGlobal prevents re-dreaming already-consolidated conversations after file migration`() = runBlocking {
        val h = newHarness()
        val now = Instant.now()
        val chat = finishedConversation(h.assistant, "Chat", "I like jasmine tea").copy(updateAt = now)
        h.stored[chat.id] = chat

        h.consolidationDao.upsert(
            MemoryConsolidationEntity(chat.id.toString(), h.assistant.id.toString(), now.toEpochMilli() + 5_000)
        )
        h.consolidator.copyMarksToGlobal(h.assistant.id)

        assertEquals(
            now.toEpochMilli() + 5_000,
            h.consolidationDao.getConsolidatedAt(chat.id.toString(), MemoryRepository.GLOBAL_MEMORY_ID),
        )
    }

    @Test
    fun `standalone global memory fullReview runs even when no assistant has global memory enabled`() = runBlocking {
        val h = newHarness()
        h.memoryRepository.writeFile(
            MemoryRepository.GLOBAL_MEMORY_ID,
            "/profile.md",
            "- [stated] Name is Lin\n",
            MemoryRepository.NEW_FILE_VERSION,
        )

        val run = h.consolidator.consolidate(MemoryRepository.GLOBAL_MEMORY_ID, emptySet(), fullReview = true)
        assertNotNull(run)
        assertEquals(1, h.runnerCalls.size)
        assertTrue(h.runnerCalls.single().contains("Name is Lin"))
    }
}
