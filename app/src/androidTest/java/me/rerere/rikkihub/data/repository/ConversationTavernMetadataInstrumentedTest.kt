package me.rerere.rikkihub.data.repository

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.fts.MessageFtsManager
import me.rerere.rikkihub.data.files.FilesManager
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** `Conversation.tavernMetadata` 经真实 Room 往返：插入、整行 CAS 更新、损坏值容错。 */
@RunWith(AndroidJUnit4::class)
class ConversationTavernMetadataInstrumentedTest {
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "conversation-tavern-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String): File =
                File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        database = AppDatabaseFactory.create(context)
        val files = FilesManager(context, FilesRepository(database.managedFileDao()), AppScope(),
            database.attachmentRetentionDao())
        repository = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
            database.favoriteDao(), database, files, MessageFtsManager(database))
    }

    @After fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    private fun conversation(metadata: JsonObject = JsonObject(emptyMap())) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        title = "tavern",
        messageNodes = listOf(MessageNode.of(UIMessage.user("hello"))),
        tavernMetadata = metadata,
    )

    private val sample = buildJsonObject {
        put("variables", buildJsonObject { put("mood", "calm"); put("count", 3) })
        put("timedWorldInfo", buildJsonObject { put("sticky", JsonArray(listOf(JsonPrimitive("a")))) })
        put("futureKey", buildJsonObject { put("kept", true) })
    }

    @Test fun metadataSurvivesInsertAndReload() = runBlocking {
        val c = conversation(sample)
        repository.insertConversation(c)
        assertEquals(sample, repository.getConversationById(c.id)!!.tavernMetadata)
    }

    @Test fun anEmptyConversationReadsBackAsAnEmptyObject() = runBlocking {
        val c = conversation()
        repository.insertConversation(c)
        assertEquals(JsonObject(emptyMap()), repository.getConversationById(c.id)!!.tavernMetadata)
    }

    @Test fun updatingTheConversationWritesTheNewMetadata() = runBlocking {
        val c = conversation(sample)
        repository.insertConversation(c)
        val loaded = repository.getConversationById(c.id)!!
        val changed = buildJsonObject { put("variables", buildJsonObject { put("mood", "angry") }) }
        repository.updateConversation(loaded.copy(tavernMetadata = changed))
        assertEquals(changed, repository.getConversationById(c.id)!!.tavernMetadata)
    }

    @Test fun aCorruptStoredValueReadsAsEmptyInsteadOfBreakingTheConversation() = runBlocking {
        val c = conversation(sample)
        repository.insertConversation(c)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE conversationentity SET tavern_metadata = 'not json {' WHERE id = ?", arrayOf(c.id.toString()),
        )
        assertEquals(JsonObject(emptyMap()), repository.getConversationById(c.id)!!.tavernMetadata)
    }
}
