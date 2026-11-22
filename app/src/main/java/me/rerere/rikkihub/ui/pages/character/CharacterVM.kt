package me.rerere.rikkihub.ui.pages.character

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkihub.R
import me.rerere.rikkihub.data.character.CharacterExportFormat
import me.rerere.rikkihub.data.character.CharacterService
import me.rerere.rikkihub.data.character.ImportedRecord
import me.rerere.rikkihub.data.character.observeListing
import me.rerere.rikkihub.data.character.setFavorite
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.CharacterSummary
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.tavern.card.CardFormatException
import me.rerere.tavern.core.PromptRole
import kotlin.uuid.Uuid

/** 角色库与角色详情共用的状态与操作。卡的改动都走 [CharacterRepository.update]（读最新、写前比版本，被抢先时重试）。 */
class CharacterVM(
    private val context: Application,
    private val repository: CharacterRepository,
    private val service: CharacterService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    val query = MutableStateFlow("")

    /** 列表：先按搜索词筛，再收藏的在前、其余按名字。`null` = 还没读出来。 */
    val characters: StateFlow<List<CharacterSummary>?> = repository.observeListing(query)
        .map<_, List<CharacterSummary>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 最近一次导入的结果（弹报告用）；关掉就清空。 */
    var lastImport by mutableStateOf<ImportedRecord?>(null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun dismissReport() { lastImport = null }

    /** 一个角色（含卡）；角色库有任何变化就重读，删了是 null。 */
    fun observe(id: Uuid): StateFlow<CharacterRecord?> = repository.observeSummaries()
        .map { repository.get(id) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun import(uri: Uri) {
        viewModelScope.launch {
            try {
                val (bytes, name) = withContext(Dispatchers.IO) { read(uri) }
                lastImport = service.import(bytes, name)
            } catch (e: CardFormatException) {
                _messages.tryEmit(context.getString(R.string.character_import_failed, e.message.orEmpty()))
            } catch (e: Exception) {
                _messages.tryEmit(context.getString(R.string.character_import_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun read(uri: Uri): Pair<ByteArray, String> {
        var name = ""
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0).orEmpty()
        }
        val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open the file")
        val bytes = stream.use { it.readLimited(MAX_IMPORT_BYTES) }
        return bytes to name
    }

    fun createBlank(name: String, onCreated: (Uuid) -> Unit) {
        viewModelScope.launch { onCreated(service.createBlank(name).id) }
    }

    fun update(id: Uuid, transform: (CharacterRecord) -> CharacterRecord) {
        viewModelScope.launch {
            runCatching { repository.update(id, transform = transform) }
                .onFailure { _messages.tryEmit(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun editCard(id: Uuid, key: String, value: String) = update(id) { it.copy(card = it.card.withData(key, JsonPrimitive(value))) }

    fun setTags(id: Uuid, text: String) = update(id) { r ->
        val tags = text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        r.copy(card = r.card.withData("tags", JsonArray(tags.map { JsonPrimitive(it) })))
    }

    fun setFavorite(id: Uuid, fav: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setFavorite(id, fav) }
                .onFailure { _messages.tryEmit(it.message ?: it.javaClass.simpleName) }
        }
    }

    fun setAlternateGreetings(id: Uuid, greetings: List<String>) = update(id) { r ->
        r.copy(card = r.card.withData("alternate_greetings", JsonArray(greetings.map { JsonPrimitive(it) })))
    }

    fun setDepthPrompt(id: Uuid, prompt: String? = null, depth: Int? = null, role: PromptRole? = null) = update(id) { r ->
        val current = r.card.depthPrompt
        val obj = kotlinx.serialization.json.JsonObject(mapOf(
            "prompt" to JsonPrimitive(prompt ?: current.prompt),
            "depth" to JsonPrimitive(depth ?: current.depth),
            "role" to JsonPrimitive((role ?: current.role).name.lowercase()),
        ))
        // 卡里 depth_prompt 里没动的未知键也要留着
        val old = r.card.extensions["depth_prompt"] as? kotlinx.serialization.json.JsonObject
        r.copy(card = r.card.withExtension("depth_prompt", kotlinx.serialization.json.JsonObject((old ?: kotlinx.serialization.json.JsonObject(emptyMap())) + obj)))
    }

    fun setAvatar(id: Uuid, uri: Uri) {
        viewModelScope.launch {
            try {
                val (bytes, _) = withContext(Dispatchers.IO) { read(uri) }
                val mime = context.contentResolver.getType(uri).orEmpty()
                val ext = when {
                    "png" in mime -> "png"
                    "webp" in mime -> "webp"
                    "gif" in mime -> "gif"
                    else -> "jpg"
                }
                repository.setAvatar(id, bytes, ext)
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun export(id: Uuid, format: CharacterExportFormat, uri: Uri) {
        viewModelScope.launch {
            try {
                val file = service.export(id, format)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(file.bytes) } ?: error("Cannot write the file")
                }
                _messages.tryEmit(context.getString(R.string.character_export_done))
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** 导出的建议文件名。 */
    suspend fun exportName(id: Uuid, format: CharacterExportFormat): String {
        val name = repository.get(id)?.name.orEmpty().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "character" }
        return "${name.take(80)}.${format.extension}"
    }

    suspend fun conversationCount(id: Uuid): Int = repository.conversationCount(id)

    fun delete(id: Uuid, deleteBook: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            service.delete(id, deleteBook)
            onDone()
        }
    }

    fun setRegexAllowed(id: Uuid, allowed: Boolean) {
        viewModelScope.launch { service.setRegexAllowed(id, allowed) }
    }

    fun attachBook(id: Uuid, bookId: Uuid) = update(id) { r ->
        if (bookId == r.local.embeddedBookId || bookId in r.local.extraBookIds) r
        else r.copy(local = r.local.copy(extraBookIds = r.local.extraBookIds + bookId))
    }

    fun detachBook(id: Uuid, bookId: Uuid) = update(id) { r ->
        r.copy(local = r.local.copy(
            embeddedBookId = r.local.embeddedBookId.takeIf { it != bookId },
            extraBookIds = r.local.extraBookIds - bookId,
        ))
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 128L * 1024 * 1024
    }
}

private fun java.io.InputStream.readLimited(limit: Long): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) throw CardFormatException("The file is too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
