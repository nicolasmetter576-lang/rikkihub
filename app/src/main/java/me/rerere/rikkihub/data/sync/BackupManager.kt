package me.rerere.rikkihub.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.BuildConfig
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkihub.data.datastore.migration.TAVERN_CONVERSATION_ASSISTANTS_SQL
import me.rerere.rikkihub.data.db.AppDatabase
import me.rerere.rikkihub.data.db.AppDatabaseFactory
import me.rerere.rikkihub.data.db.SQLiteConfiguration
import me.rerere.rikkihub.data.files.FileFolders
import me.rerere.rikkihub.data.files.MediaCreationFiles
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class PreparedSnapshotArchive(
    val file: File,
    val conversationCount: Int,
    val messageNodeCount: Int,
    val databaseBytes: Long,
    val appVersionName: String,
    val appVersionCode: Int,
    val databaseSchemaVersion: Int,
)

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()
    private val activeOperations = AtomicInteger(0)

    val isBusy: Boolean get() = activeOperations.get() > 0

    suspend fun hasLiveData(): Boolean = withContext(Dispatchers.IO) {
        database.conversationDao().countAll() > 0 || database.messageNodeDao().countAll() > 0
    }

    fun hasPendingRestore(): Boolean = pendingRestore(context).hasPending()

    suspend fun createBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        encryptPassword: String? = null,
    ): File = withContext(Dispatchers.IO) {
        activeOperations.incrementAndGet()
        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val tempDir = backupTempDir(context)
            val archive = File.createTempFile("backup_${timestamp}_", ".zip", tempDir)
            val staging = Files.createTempDirectory(tempDir.toPath(), "backup-").toFile()
            var finishedArchive: File? = null
            try {
                val stats = writeArchiveContents(
                    archive = archive,
                    staging = staging,
                    includeDatabase = includeDatabase,
                    includeFiles = includeFiles,
                )
                if (!encryptPassword.isNullOrEmpty()) {
                    val encrypted = File.createTempFile(
                        "backup_${timestamp}_",
                        ".${BackupEncryption.EXTENSION}",
                        tempDir,
                    )
                    try {
                        BackupEncryption.encryptFile(archive, encrypted, encryptPassword)
                        finishedArchive = encrypted
                        encrypted
                    } finally {
                        archive.delete()
                        if (finishedArchive == null) encrypted.delete()
                    }
                } else {
                    finishedArchive = archive
                    archive
                }
            } catch (e: Throwable) {
                archive.delete()
                finishedArchive?.delete()
                throw e
            } finally {
                staging.deleteRecursively()
            }
        } finally {
            activeOperations.decrementAndGet()
        }
    }

    suspend fun createSnapshotArchive(): PreparedSnapshotArchive = withContext(Dispatchers.IO) {
        activeOperations.incrementAndGet()
        try {
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val tempDir = backupTempDir(context)
            val archive = File.createTempFile("snapshot_${timestamp}_", ".zip", tempDir)
            val staging = Files.createTempDirectory(tempDir.toPath(), "snapshot-").toFile()
            try {
                val stats = writeArchiveContents(
                    archive = archive,
                    staging = staging,
                    includeDatabase = true,
                    includeFiles = false,
                )
                PreparedSnapshotArchive(
                    file = archive,
                    conversationCount = stats.conversationCount,
                    messageNodeCount = stats.messageNodeCount,
                    databaseBytes = stats.databaseBytes,
                    appVersionName = BuildConfig.VERSION_NAME,
                    appVersionCode = BuildConfig.VERSION_CODE.toIntOrNull() ?: 0,
                    databaseSchemaVersion = AppDatabase.VERSION,
                )
            } catch (e: Throwable) {
                archive.delete()
                throw e
            } finally {
                staging.deleteRecursively()
            }
        } finally {
            activeOperations.decrementAndGet()
        }
    }

    private suspend fun writeArchiveContents(
        archive: File,
        staging: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
    ): BackupManifestStats {
        val settings = settingsStore.settingsFlowRaw.first()
        var conversationCount = 0
        var messageNodeCount = 0
        var databaseBytes = 0L
        var attachmentFileCount = 0

        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(json.encodeToString(settings).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (includeDatabase) {
                val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                databaseBytes = snapshot.length()
                conversationCount = database.conversationDao().countAll()
                messageNodeCount = database.messageNodeDao().countAll()
                addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
            }

            if (includeFiles) {
                for (folder in ATTACHMENT_FOLDERS) {
                    val directory = File(context.filesDir, folder)
                    val files = if (folder in NESTED_ATTACHMENT_FOLDERS) directory.walkTopDown().asSequence()
                    else directory.listFiles().orEmpty().asSequence()
                    for (file in files.filter { it.isFile }) {
                        currentCoroutineContext().ensureActive()
                        // 还在下载的媒体结果不完整，下完会改名
                        if (folder == FileFolders.MEDIA_CREATION &&
                            file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                        ) continue
                        val relative = file.relativeTo(directory).invariantSeparatorsPath
                        PendingRestore.resolveInside(directory, relative)
                        addFile(zip, file, "$folder/$relative")
                        attachmentFileCount++
                    }
                }
            }

            val stats = BackupManifestStats(
                conversationCount = conversationCount,
                messageNodeCount = messageNodeCount,
                attachmentFileCount = attachmentFileCount,
                databaseBytes = databaseBytes,
            )
            val manifest = BackupManifest(
                createdAtUtc = Instant.now().toString(),
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE.toIntOrNull() ?: 0,
                databaseSchemaVersion = AppDatabase.VERSION,
                includes = BackupManifestIncludes(
                    settings = true,
                    database = includeDatabase,
                    files = includeFiles,
                ),
                stats = stats,
            )
            zip.putNextEntry(ZipEntry(BackupManifest.ENTRY_NAME))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            return stats
        }
    }

    suspend fun stageRestore(
        archive: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        metadata: RestoreMetadata? = null,
    ) = withContext(Dispatchers.IO) {
        activeOperations.incrementAndGet()
        try {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    // fsync is reserved for the files the pending installer replays verbatim:
                    // the database, its WAL and settings.json. Attachment payloads are staged
                    // copies only; fsyncing hundreds of them would make restore needlessly slow.
                    val durableTargets = mutableSetOf(stagedDatabase, stagedWal)
                    val settingsFile = File(staging, "settings.json")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> settingsFile
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(target).use { output ->
                                    input.copyTo(output)
                                    if (target in durableTargets || target == settingsFile) {
                                        output.fd.sync()
                                    }
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    // 旧备份的助手没有酒馆模式开关：和角色聊过、写过作者注释的会话所属的助手要开（见 SettingsJsonMigrator V7）
                    var tavernAssistantIds = emptySet<String>()
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                            tavernAssistantIds = runCatching {
                                room.openHelper.readableDatabase.query(TAVERN_CONVERSATION_ASSISTANTS_SQL).use { cursor ->
                                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                                }
                            }.getOrDefault(emptySet())
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                        // The checkpoint above rewrote the file after the extraction-time fsync.
                        RandomAccessFile(stagedDatabase, "rw").use { it.fd.sync() }
                    }

                    if (settingsFile.exists()) {
                        val settings = json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsFile.readText(), tavernAssistantIds))
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeToString(settings))
                    }
                    if (metadata != null) {
                        PendingRestore.writeDurably(
                            File(staging, PendingRestore.RESTORE_META_FILE_NAME),
                            BackupDeviceStateStore.stateJson.encodeToString(metadata),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        } finally {
            activeOperations.decrementAndGet()
        }
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    companion object {
        private const val TEMP_DIR_NAME = "backup-tmp"

        private val ATTACHMENT_FOLDERS = listOf(
            FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION,
            FileFolders.CHARACTERS, FileFolders.LIBRARY,
        )

        /** 连同子目录一起备份；其余目录只有顶层文件。 */
        private val NESTED_ATTACHMENT_FOLDERS =
            setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION, FileFolders.CHARACTERS, FileFolders.LIBRARY)

        internal fun isManagedAttachmentPath(name: String): Boolean {
            val folder = name.substringBefore('/')
            if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
            val relative = name.substringAfter('/')
            if (relative.isBlank() || relative.startsWith('/') || '\\' in relative) return false
            if (relative.split('/').any { it == ".." || it == "." || it.isBlank() }) return false
            return folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative
        }

        fun backupTempDir(context: Context): File =
            File(context.cacheDir, TEMP_DIR_NAME).apply { mkdirs() }

        fun cleanupTempDir(context: Context) {
            File(context.cacheDir, TEMP_DIR_NAME).deleteRecursively()
        }

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
            receiptFile = BackupDeviceStateStore.receiptFile(context),
            beforeDatabaseInstall = {
                // Restoring on the same installation must not reuse noBackup activations
                // that happen to have the same mount IDs as the restored database.
                val activationFile = File(context.noBackupFilesDir, "workspace_device_mount_activations.enc")
                check(!activationFile.exists() || activationFile.delete()) {
                    "Cannot invalidate device directory activations before database restore"
                }
            },
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context,
                    json.decodeFromString<Settings>(SettingsJsonMigrator.migrate(settingsJson)),
                )
            }
        }
    }
}
