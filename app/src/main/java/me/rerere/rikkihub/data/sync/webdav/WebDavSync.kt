package me.rerere.rikkihub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.datastore.WebDavConfig
import me.rerere.rikkihub.data.sync.BackupDeviceStateStore
import me.rerere.rikkihub.data.sync.BackupEncryption
import me.rerere.rikkihub.data.sync.BackupManager
import me.rerere.rikkihub.data.sync.BackupPartials
import me.rerere.rikkihub.data.sync.RestoreMetadata
import me.rerere.rikkihub.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "WebDavSync"

class WebDavSync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
    private val deviceStateStore: BackupDeviceStateStore,
) {
    private val partials = BackupPartials(deviceStateStore)

    private fun getClient(config: WebDavConfig): WebDavClient {
        return WebDavClient(config, httpClient)
    }

    private fun scopeFor(config: WebDavConfig): String =
        BackupPartials.scopeOf("webdav", config.url, config.username, config.path)

    private fun contentTypeFor(file: File): String =
        if (file.name.endsWith(".${BackupEncryption.EXTENSION}", ignoreCase = true)) {
            "application/octet-stream"
        } else {
            "application/zip"
        }

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        // Test by listing the root directory
        client.propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig, encryptPassword: String? = null) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, encryptPassword)
        val client = getClient(config)
        val scope = scopeFor(config)
        val partialName = "${file.name}.partial"
        val contentType = contentTypeFor(file)

        try {
            // Ensure the backup directory exists
            client.ensureCollectionExists().getOrThrow()

            // Best-effort 清理上次可能遗留的本机登记残片
            runCatching {
                partials.sweep(
                    scope = scope,
                    listRemote = { emptyMap() },
                    removeRemote = { name -> deletePartialIfPresent(client, name) },
                )
            }

            var moved = false
            partials.publish(
                scope = scope,
                partialName = partialName,
                uploadAndCommit = {
                    client.put(
                        path = partialName,
                        file = file,
                        contentType = contentType,
                    ).getOrThrow()

                    val moveResult = client.move(partialName, file.name)
                    if (moveResult.isSuccess) {
                        moved = true
                    } else {
                        val err = moveResult.exceptionOrNull()
                        if (err is WebDavException && err.statusCode in FALLBACK_STATUS_CODES) {
                            Log.w(TAG, "WebDAV MOVE unsupported (${err.statusCode}), falling back to direct PUT")
                            client.put(
                                path = file.name,
                                file = file,
                                contentType = contentType,
                            ).getOrThrow()
                        } else {
                            moveResult.getOrThrow()
                        }
                    }
                },
                removePartial = {
                    if (!moved) {
                        deletePartialIfPresent(client, partialName)
                    }
                },
            )

            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> = withContext(Dispatchers.IO) {
        val client = getClient(config)

        // Ensure the backup directory exists
        client.ensureCollectionExists().getOrThrow()

        val resources = client.list().getOrThrow()

        // 顺手清理超过 6 小时的孤儿 .partial 及本机登记残片（不影响列表返回）
        val scope = scopeFor(config)
        val remotePartials = resources
            .filter { !it.isCollection && BackupPartials.isOwnedPartialName(it.displayName) }
            .associate { it.displayName to it.lastModified }
        runCatching {
            partials.sweep(
                scope = scope,
                listRemote = { remotePartials },
                removeRemote = { name -> deletePartialIfPresent(client, name) },
            )
        }

        resources
            .filter { !it.isCollection && BackupPartials.isBackupArchiveName(it.displayName) }
            .map { resource ->
                WebDavBackupItem(
                    href = resource.href,
                    displayName = resource.displayName,
                    size = resource.contentLength,
                    lastModified = resource.lastModified ?: Instant.EPOCH,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun downloadBackupFile(config: WebDavConfig, item: WebDavBackupItem): File =
        withContext(Dispatchers.IO) {
            val client = getClient(config)
            val suffix = if (item.isEncrypted) ".${BackupEncryption.EXTENSION}" else ".zip"
            val backupFile = File.createTempFile("restore-", suffix, BackupManager.backupTempDir(context))
            try {
                Log.i(TAG, "downloadBackupFile: Downloading ${item.displayName}")
                client.downloadToFile(item.displayName, backupFile).getOrThrow()
                Log.i(TAG, "downloadBackupFile: Downloaded ${backupFile.length().fileSizeToString()}")
                backupFile
            } catch (e: Throwable) {
                backupFile.delete()
                throw e
            }
        }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val backupFile = downloadBackupFile(config, item)
        try {
            restoreFromBackupFile(backupFile, config)
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restore: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) = withContext(Dispatchers.IO) {
        val client = getClient(config)
        client.delete(item.displayName).getOrThrow()
        Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
    }

    suspend fun restoreFromLocalFile(
        file: File,
        config: WebDavConfig,
        metadata: RestoreMetadata? = null,
    ) {
        restoreFromBackupFile(file, config, metadata)
    }

    suspend fun prepareBackupFile(config: WebDavConfig, encryptPassword: String? = null): File =
        backupManager.createBackup(
            includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
            includeFiles = WebDavConfig.BackupItem.FILES in config.items,
            encryptPassword = encryptPassword,
        )

    private suspend fun restoreFromBackupFile(
        backupFile: File,
        config: WebDavConfig,
        metadata: RestoreMetadata? = null,
    ) = backupManager.stageRestore(
        archive = backupFile,
        includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
        includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        metadata = metadata,
    )

    private suspend fun deletePartialIfPresent(client: WebDavClient, name: String) {
        val result = client.delete(name)
        if (result.isFailure) {
            val err = result.exceptionOrNull()
            if (err is WebDavException && err.statusCode == 404) return
            result.getOrThrow()
        }
    }

    private companion object {
        val FALLBACK_STATUS_CODES = setOf(400, 403, 405, 501)
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
) {
    val isEncrypted: Boolean
        get() = displayName.endsWith(".${BackupEncryption.EXTENSION}", ignoreCase = true)
}
