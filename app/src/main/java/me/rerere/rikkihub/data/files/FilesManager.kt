package me.rerere.rikkihub.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkihub.AppScope
import me.rerere.rikkihub.data.db.entity.ManagedFileEntity
import me.rerere.rikkihub.data.repository.FilesRepository
import me.rerere.rikkihub.utils.exportImage
import me.rerere.rikkihub.utils.exportImageFile
import me.rerere.rikkihub.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FilesManager(
    private val context: Context,
    private val repository: FilesRepository,
    private val appScope: AppScope,
    /** Durable retention for released attachments; null only in detached tests. */
    private val retentionDao: me.rerere.rikkihub.data.db.dao.AttachmentRetentionDAO? = null,
) {
    companion object {
        private const val TAG = "FilesManager"
        /** Same bounded window as conversation-command releases. */
        const val RELEASE_RETENTION_MILLIS: Long = 24L * 60 * 60 * 1000
    }

    /**
     * Sweep step for one expired retention entry whose references were re-checked by
     * the caller: deletes the managed row and file (canonical-checked, under the same
     * mutex as [deleteManagedUpload]); true when nothing is left on disk.
     */
    suspend fun deleteRetainedUpload(relativePath: String): Boolean = deleteManagedMutex.withLock {
        withContext(Dispatchers.IO) {
            if (attachmentLeases.isLeased(relativePath)) return@withContext false
            val entity = repository.getByPath(relativePath)
            if (entity != null) {
                if (entity.folder != FileFolders.UPLOAD) return@withContext false
                val file = resolveUploadFile(entity) ?: return@withContext false
                if (file.exists() && !file.delete()) return@withContext false
                repository.deleteById(entity.id)
                true
            } else {
                val upload = uploadDir().canonicalFile
                val file = File(context.filesDir, relativePath)
                if (file.canonicalFile.parentFile != upload) return@withContext false
                !file.exists() || file.delete()
            }
        }
    }

    /**
     * UPLOAD 草稿附件租约表（进程内）。所有创建 UPLOAD 文件的入口在
     * 向调用方返回 URI 前登记租约，保证「已创建但尚未被任何持久引用」
     * 的文件在默认 24h 内不会被清理页/显式删除误删。
     */
    val attachmentLeases = AttachmentLeaseRegistry()

    /** filesDir 根（canonical 校验用，不暴露具体业务目录）。 */
    fun filesDir(): File = context.filesDir

    /** UPLOAD 目录（canonical 校验用）。 */
    fun uploadDir(): File = context.filesDir.resolve(FileFolders.UPLOAD)

    /**
     * 按 managed id 解析 UPLOAD 实体并做 canonical 归属校验。
     * 返回 null 表示 id 不存在、不属于 upload、或路径越出 upload 目录。
     */
    suspend fun resolveUploadEntity(id: Long): Pair<ManagedFileEntity, File>? =
        withContext(Dispatchers.IO) {
            val entity = repository.getById(id)
                ?.takeIf { it.folder == FileFolders.UPLOAD }
                ?: return@withContext null
            val file = resolveUploadFile(entity) ?: return@withContext null
            entity to file
        }

    /**
     * 实体 → 盘上文件的 canonical 校验：必须是 upload 目录的直接子文件
     * （单层文件名、无 ..、canonical 父目录就是 upload dir），防止 symlink/越界。
     */
    fun resolveUploadFile(entity: ManagedFileEntity): File? {
        if (entity.folder != FileFolders.UPLOAD) return null
        if (!entity.relativePath.startsWith("${entity.folder}/")) return null
        val name = entity.relativePath.removePrefix("${entity.folder}/")
        // canonical 直接子文件 + 拒绝 symlink 别名（alias 指向 upload 内也算拒绝）
        return FileUtils.resolveChildWithin(uploadDir(), name)
    }

    /**
     * 草稿附件续租：token 可为 managed id（纯数字）、file: URL 或 upload/<name>
     * 相对路径；只有解析到 managed_files 且 folder=upload 的目标才续租，
     * 其余记入 rejected，绝不接受任意 URL。登记与存在性校验在租约锁内完成。
     */
    suspend fun renewUploadLeases(tokens: List<String>): UploadLeaseRenewal = withContext(Dispatchers.IO) {
        val renewed = mutableListOf<Long>()
        val rejected = mutableListOf<String>()
        tokens.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { token ->
            val resolved = runCatching { resolveUploadRef(token) }.getOrNull()
            if (resolved != null) {
                val (entity, file) = resolved
                val ok = attachmentLeases.renew(entity.relativePath, stillValid = { file.exists() })
                if (ok) {
                    renewed += entity.id
                } else {
                    rejected += token
                }
            } else {
                rejected += token
            }
        }
        UploadLeaseRenewal(
            renewedIds = renewed,
            rejected = rejected,
            leaseTtlMillis = attachmentLeases.defaultTtlMillis,
        )
    }

    private suspend fun resolveUploadRef(token: String): Pair<ManagedFileEntity, File>? {
        val id = token.toLongOrNull()
        if (id != null) {
            return resolveUploadEntity(id)
        }
        val relativePath = extractUploadRelativePath(token) ?: return null
        val entity = repository.getByPath(relativePath)
            ?.takeIf { it.folder == FileFolders.UPLOAD }
            ?: return null
        val file = resolveUploadFile(entity) ?: return null
        return entity to file
    }

    /** 仅删 managed_files 行（盘上删除由调用方在租约锁内先行完成）。 */
    suspend fun deleteManagedRow(id: Long): Boolean = repository.deleteById(id) > 0

    /**
     * Upload-managed delete（I1 修复）：DB 行与盘上文件在同一 mutex 临界区内
     * 完成 canonical 归属复核后再删除——`resolveUploadEntity` 的 check-then-act
     * 竞态被消除：若 id 在复核后被改写为非 upload 行或指向越界路径，整次
     * 删除 fail-closed。leases 同步释放，杜绝已删文件被续租。
     */
    suspend fun deleteManagedUpload(id: Long): Boolean = deleteManagedMutex.withLock {
        withContext(Dispatchers.IO) {
            val entity = repository.getById(id)
                ?.takeIf { it.folder == FileFolders.UPLOAD }
                ?: return@withContext false
            // canonical 复核必须在锁内：拒绝 symlink/越界 alias（即使它指回 upload 内）
            val file = resolveUploadFile(entity) ?: return@withContext false
            runCatching { if (file.exists()) file.delete() }
            attachmentLeases.release(entity.relativePath)
            repository.deleteById(id) > 0
        }
    }

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
        val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
        val target = createTargetFile(folder, resolvedName, resolvedMime)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = resolvedName,
            mimeType = resolvedMime,
        ).also { trackUploadLeaseIfNeeded(folder, it.relativePath) }
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeBytes(bytes)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        ).also { trackUploadLeaseIfNeeded(folder, it.relativePath) }
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
    ): ManagedFileEntity = withContext(Dispatchers.IO) {
        val target = createTargetFile(folder, displayName, mimeType)
        target.writeText(text)
        createManagedFileEntity(
            folder = folder,
            file = target,
            displayName = displayName,
            mimeType = mimeType,
        ).also { trackUploadLeaseIfNeeded(folder, it.relativePath) }
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ManagedFileEntity>> =
        repository.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ManagedFileEntity> =
        repository.listByFolder(folder).first()

    suspend fun get(id: Long): ManagedFileEntity? = repository.getById(id)

    suspend fun getByRelativePath(relativePath: String): ManagedFileEntity? = repository.getByPath(relativePath)

    fun getFile(entity: ManagedFileEntity): File =
        File(context.filesDir, entity.relativePath)

    fun createChatFilesByContents(uris: List<Uri>): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                trackManagedFile(
                    folder = FileFolders.UPLOAD,
                    file = file,
                    displayName = sourceName,
                    mimeType = guessedMime
                )
                // 租约登记必须先于把 URI 交给调用方（草稿可能被挂很久）
                trackUploadLeaseIfNeeded(FileFolders.UPLOAD, "${FileFolders.UPLOAD}/${file.name}")
                newUris.add(file.toUri())
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
        return newUris
    }

    fun createChatFilesByByteArrays(
        byteArrays: List<ByteArray>,
        displayName: String = "image.png",
        mimeType: String = "image/png",
    ): List<Uri> {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            val fileName = buildUuidFileName(displayName = displayName, mimeType = mimeType)
            val file = dir.resolve(fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            val newUri = file.toUri()
            file.outputStream().use { outputStream ->
                outputStream.write(byteArray)
            }
            trackManagedFile(
                folder = FileFolders.UPLOAD,
                file = file,
                displayName = displayName,
                mimeType = mimeType
            )
            trackUploadLeaseIfNeeded(FileFolders.UPLOAD, "${FileFolders.UPLOAD}/${file.name}")
            newUris.add(newUri)
        }
        return newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is UIMessagePart.Image -> {
                            if (part.url.startsWith("data:image")) {
                                val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
                                val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                                val byteArray = FileUtils.compressBitmapToPng(bitmap)
                                val urls = createChatFilesByByteArrays(listOf(byteArray))
                                Log.i(
                                    TAG,
                                    "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
                                )
                                part.copy(
                                    url = urls.first().toString(),
                                )
                            } else {
                                part
                            }
                        }

                        else -> part
                    }
                }
            )
        }

    /**
     * Releases attachments that a caller stopped referencing. Managed upload files are
     * NOT deleted here: they enter the durable `attachment_retention` window and the
     * startup sweep deletes them only after re-checking every reference source. This
     * keeps shared/branch/queued references and Undo intact. Other files under filesDir
     * keep the historical immediate delete; files outside filesDir are never touched.
     */
    fun deleteChatFiles(uris: List<Uri>) {
        val relativePaths = mutableSetOf<String>()
        val retained = mutableMapOf<String, String>()
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            val relative = getRelativePathInFilesDir(file) ?: return@forEach
            if (retentionDao != null && relative.startsWith("${FileFolders.UPLOAD}/")) {
                retained[relative] = uri.toString()
                return@forEach
            }
            relativePaths.add(relative)
            if (file.exists()) {
                file.delete()
            }
        }
        if (retained.isNotEmpty()) {
            val now = System.currentTimeMillis()
            appScope.launch(Dispatchers.IO) {
                runCatching {
                    retentionDao?.upsertAll(retained.map { (rel, url) ->
                        me.rerere.rikkihub.data.db.entity.AttachmentRetentionEntity(
                            relativePath = rel,
                            conversationId = "",
                            sourceUrl = url,
                            reason = "released",
                            createdAt = now,
                            expiresAt = now + RELEASE_RETENTION_MILLIS,
                        )
                    })
                }.onFailure { Log.w(TAG, "retention record failed; files kept", it) }
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    repository.deleteByPath(path)
                }
            }
        }
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    fun createChatTextFile(text: String): UIMessagePart.Document {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        trackManagedFile(
            folder = FileFolders.UPLOAD,
            file = file,
            displayName = "pasted_text.txt",
            mimeType = "text/plain"
        )
        trackUploadLeaseIfNeeded(FileFolders.UPLOAD, "${FileFolders.UPLOAD}/${file.name}")
        return UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String) = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                activityContext.exportImage(activity, bitmap)
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                activityContext.exportImageFile(activity, file)
            }

            image.startsWith("/") -> {
                activityContext.exportImageFile(activity, File(image))
            }

            image.startsWith("http") -> {
                runCatching {
                    val url = URL(image)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        activityContext.exportImage(activity, bitmap)
                    } else {
                        Log.e(
                            TAG,
                            "saveMessageImage: Failed to download image from $image, response code: ${connection.responseCode}"
                        )
                    }
                }.getOrNull()
            }

            else -> error("Invalid image format")
        }
    }

    suspend fun syncFolder(folder: String = FileFolders.UPLOAD): SyncResult = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val diskFiles = if (dir.exists()) {
            dir.listFiles()?.filter { it.isFile }
                ?: return@withContext SyncResult(inserted = 0, removed = 0)
        } else {
            emptyList()
        }

        // 磁盘 -> 数据库：补录尚未登记的文件
        var inserted = 0
        val diskRelativePaths = HashSet<String>()
        diskFiles.forEach { file ->
            val relativePath = "${folder}/${file.name}"
            diskRelativePaths.add(relativePath)
            val existing = repository.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                val displayName = file.name
                val mimeType = guessMimeType(file, displayName)
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = file.lastModified().takeIf { it > 0 } ?: now,
                        updatedAt = now,
                    )
                )
                inserted += 1
            }
        }

        // 数据库 -> 磁盘：清理文件已不存在的孤儿记录
        var removed = 0
        repository.listByFolder(folder).first().forEach { entity ->
            if (entity.relativePath !in diskRelativePaths && !getFile(entity).isFile) {
                removed += repository.deleteByPath(entity.relativePath)
            }
        }

        SyncResult(inserted = inserted, removed = removed)
    }

    /**
     * Managed-file delete. For UPLOAD rows this goes through
     * [deleteManagedUpload]'s locked canonical re-check; other folders keep the
     * historical best-effort semantics (disk delete + row delete).
     */
    suspend fun delete(id: Long, deleteFromDisk: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val entity = repository.getById(id) ?: return@withContext false
        if (entity.folder == FileFolders.UPLOAD) {
            return@withContext if (deleteFromDisk) {
                deleteManagedUpload(id)
            } else {
                repository.deleteById(id) > 0
            }
        }
        if (deleteFromDisk) {
            // Re-resolve under canonical ownership — never delete a managed row
            // whose path escapes its folder or resolves through a symlink
            // (same invariant as resolveUploadFile, multi-segment aware).
            val file = resolveManagedFileWithinFolder(entity) ?: return@withContext false
            runCatching { file.delete() }
        }
        repository.deleteById(id) > 0
    }

    /**
     * Canonical re-check for a managed row before a destructive disk op
     * (delegates to FileUtils.resolveManagedWithin — JVM-testable core).
     */
    private fun resolveManagedFileWithinFolder(entity: ManagedFileEntity): File? =
        FileUtils.resolveManagedWithin(context.filesDir, entity.folder, entity.relativePath)

    suspend fun deleteAll(folder: String = FileFolders.UPLOAD): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, folder)
        val entries = dir.listFiles()
        if (dir.exists() && entries == null) {
            return@withContext false
        }

        var allDeletedFromDisk = true
        entries.orEmpty().forEach { entry ->
            if (!runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                allDeletedFromDisk = false
            }
        }

        if (allDeletedFromDisk) {
            repository.deleteByFolder(folder)
            return@withContext true
        }

        repository.listByFolder(folder).first().forEach { entity ->
            if (!getFile(entity).exists()) {
                repository.deleteById(entity.id)
            }
        }
        false
    }

    suspend fun deleteOlderThan(
        folder: String = FileFolders.UPLOAD,
        cutoffMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        var allDeleted = true
        repository.listByFolder(folder).first()
            .filter { it.createdAt < cutoffMillis }
            .forEach { entity ->
                val file = getFile(entity)
                val deletedFromDisk = !file.exists() || runCatching {
                    file.deleteRecursively()
                }.getOrDefault(false)

                if (deletedFromDisk) {
                    if (repository.deleteById(entity.id) == 0) {
                        allDeleted = false
                    }
                } else {
                    allDeleted = false
                }
            }
        allDeleted
    }

    /** Serializes managed-delete canonical re-checks against concurrent writes. */
    private val deleteManagedMutex = Mutex()

    private fun trackUploadLeaseIfNeeded(folder: String, relativePath: String) {
        if (folder != FileFolders.UPLOAD) return
        attachmentLeases.track(relativePath)
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createManagedFileEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
    ): ManagedFileEntity {
        val now = System.currentTimeMillis()
        return repository.insert(
            ManagedFileEntity(
                folder = folder,
                relativePath = buildRelativePath(folder, file),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = file.length(),
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    private fun trackManagedFile(folder: String, file: File, displayName: String, mimeType: String) {
        val relativePath = buildRelativePath(folder, file)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                val existing = repository.getByPath(relativePath)
                if (existing != null) {
                    return@runCatching
                }
                val now = System.currentTimeMillis()
                repository.insert(
                    ManagedFileEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
            }.onFailure {
                Log.e(TAG, "trackManagedFile: Failed to track file ${file.absolutePath}", it)
                Logging.log(
                    TAG,
                    "trackManagedFile: Failed to track file ${file.absolutePath} ${it.message} | ${it.stackTraceToString()}"
                )
            }
        }
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

data class SyncResult(
    val inserted: Int,
    val removed: Int,
)

/** 续租结果：解析成功并登记的 managed id 与无法解析/校验失败的原始 token。 */
data class UploadLeaseRenewal(
    val renewedIds: List<Long>,
    val rejected: List<String>,
    val leaseTtlMillis: Long,
)

private val UPLOAD_NAME_REGEX = Regex("[A-Za-z0-9._-]{1,160}")
private val API_FILE_PATH_PREFIX = "/api/files/path/"

/**
 * 从草稿附件引用（file: URL / 绝对路径 / upload 相对路径 / /api/files/path/…）
 * 提取 `upload/<name>` 相对路径；非 upload 或非法形态返回 null。
 * 纯函数，不触碰文件系统，可单测。
 */
internal fun extractUploadRelativePath(ref: String): String? {
    val trimmed = ref.trim()
    if (trimmed.isEmpty()) return null
    val path = when {
        trimmed.startsWith("file://") -> {
            // file:///data/.../files/upload/x 或 file://host/path
            var p = trimmed.removePrefix("file://")
            if (!p.startsWith("/")) p = p.substringAfter('/', "")
            p
        }
        trimmed.startsWith("file:") -> trimmed.removePrefix("file:")
        trimmed.startsWith(API_FILE_PATH_PREFIX) -> trimmed.removePrefix(API_FILE_PATH_PREFIX)
        // 带 scheme 的任意 URL（http/https 等）不接受
        else -> if (trimmed.contains("://")) return null else trimmed
    }
    val rel = when {
        path.startsWith("${FileFolders.UPLOAD}/") -> path
        else -> {
            val idx = path.lastIndexOf("/files/")
            if (idx >= 0) path.substring(idx + "/files/".length) else null
        }
    }?.takeIf { it.startsWith("${FileFolders.UPLOAD}/") } ?: return null
    val name = rel.removePrefix("${FileFolders.UPLOAD}/")
    if (name.contains("..") || !name.matches(UPLOAD_NAME_REGEX)) return null
    return rel
}

object FileFolders {
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
    const val BUILTIN_SKILLS = "builtin_skills"
    const val FONTS = "fonts"
    const val TOOL_OUTPUTS = "tool_outputs"
    const val MEDIA_CREATION = "media_creation"

    /** 子代理运行日志专用持久目录: 不在启动时清空, 重启后仍可读历史运行过程 */
    const val SUBAGENT_RUNS = "subagent_runs"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
): ManagedFileEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
): ManagedFileEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
): ManagedFileEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
)
