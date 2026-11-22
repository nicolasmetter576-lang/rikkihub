package me.rerere.rikkihub.data.sync

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkihub.data.sync.s3.S3Client
import me.rerere.rikkihub.data.sync.s3.S3Config
import me.rerere.rikkihub.data.sync.s3.S3Exception
import me.rerere.rikkihub.data.sync.s3.S3Object
import me.rerere.rikkihub.utils.fileSizeToString
import java.io.File
import java.time.Instant

private const val TAG = "S3Sync"

class S3Sync(
    private val backupManager: BackupManager,
    private val context: Context,
    private val httpClient: HttpClient,
    private val deviceStateStore: BackupDeviceStateStore,
) {
    private val partials = BackupPartials(deviceStateStore)

    private fun getS3Client(config: S3Config): S3Client {
        return S3Client(config, httpClient)
    }

    private fun scopeFor(config: S3Config): String =
        BackupPartials.scopeOf("s3", config.endpoint, config.bucket, config.region, config.pathStyle.toString())

    private fun contentTypeFor(file: File): String =
        if (file.name.endsWith(".${BackupEncryption.EXTENSION}", ignoreCase = true)) {
            "application/octet-stream"
        } else {
            "application/zip"
        }

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Test by listing objects with max 1 result
        client.listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config, encryptPassword: String? = null) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config, encryptPassword)
        val client = getS3Client(config)
        val scope = scopeFor(config)
        val partialName = "${file.name}.partial"
        val partialKey = "$KEY_PREFIX$partialName"
        val finalKey = "$KEY_PREFIX${file.name}"
        val contentType = contentTypeFor(file)

        try {
            runCatching {
                partials.sweep(
                    scope = scope,
                    listRemote = { emptyMap() },
                    removeRemote = { name -> deletePartialIfPresent(client, "$KEY_PREFIX$name") },
                )
            }

            partials.publish(
                scope = scope,
                partialName = partialName,
                uploadAndCommit = {
                    client.putObject(
                        key = partialKey,
                        file = file,
                        contentType = contentType,
                    ).getOrThrow()

                    val copyResult = client.copyObject(partialKey, finalKey)
                    if (copyResult.isFailure) {
                        val err = copyResult.exceptionOrNull()
                        if (err is S3Exception && err.statusCode in FALLBACK_STATUS_CODES) {
                            Log.w(TAG, "S3 CopyObject unsupported (${err.statusCode}), falling back to direct PUT")
                            client.putObject(
                                key = finalKey,
                                file = file,
                                contentType = contentType,
                            ).getOrThrow()
                        } else {
                            copyResult.getOrThrow()
                        }
                    }
                },
                removePartial = { deletePartialIfPresent(client, partialKey) },
            )

            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        // Follow ListObjectsV2 continuation so buckets with >1000 backups are listed fully.
        val objects = mutableListOf<S3Object>()
        var continuationToken: String? = null
        do {
            val result = client.listObjects(
                prefix = KEY_PREFIX,
                maxKeys = 1000,
                continuationToken = continuationToken,
            ).getOrThrow()
            objects += result.objects
            continuationToken =
                if (result.isTruncated) result.nextContinuationToken else null
        } while (continuationToken != null)

        val scope = scopeFor(config)
        val remotePartials = objects
            .filter { it.key.startsWith(KEY_PREFIX) }
            .map { it.key.removePrefix(KEY_PREFIX) to it.lastModified }
            .filter { (name, _) -> '/' !in name && BackupPartials.isOwnedPartialName(name) }
            .toMap()
        runCatching {
            partials.sweep(
                scope = scope,
                listRemote = { remotePartials },
                removeRemote = { name -> deletePartialIfPresent(client, "$KEY_PREFIX$name") },
            )
        }

        objects
            .filter { obj ->
                obj.key.startsWith(KEY_PREFIX) &&
                    BackupPartials.isBackupArchiveName(obj.key.substringAfterLast("/"))
            }
            .map { obj ->
                S3BackupItem(
                    key = obj.key,
                    displayName = obj.key.substringAfterLast("/"),
                    size = obj.size,
                    lastModified = obj.lastModified ?: Instant.EPOCH,
                )
            }
            .sortedByDescending { it.lastModified }
    }

    suspend fun downloadBackupFile(config: S3Config, item: S3BackupItem): File =
        withContext(Dispatchers.IO) {
            val client = getS3Client(config)
            val suffix = if (item.isEncrypted) ".${BackupEncryption.EXTENSION}" else ".zip"
            val backupFile = File.createTempFile("restore-", suffix, BackupManager.backupTempDir(context))
            try {
                Log.i(TAG, "downloadBackupFile: Downloading ${item.displayName}")
                client.downloadObjectToFile(item.key, backupFile).getOrThrow()
                Log.i(TAG, "downloadBackupFile: Downloaded ${backupFile.length().fileSizeToString()}")
                backupFile
            } catch (e: Throwable) {
                backupFile.delete()
                throw e
            }
        }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val backupFile = downloadBackupFile(config, item)
        try {
            restoreFromBackupFile(backupFile, config)
        } finally {
            if (backupFile.exists()) {
                backupFile.delete()
                Log.i(TAG, "restoreFromS3: Cleaned up temporary backup file")
            }
        }
    }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) = withContext(Dispatchers.IO) {
        val client = getS3Client(config)
        client.deleteObject(item.key).getOrThrow()
        Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
    }

    suspend fun prepareBackupFile(config: S3Config, encryptPassword: String? = null): File =
        backupManager.createBackup(
            includeDatabase = S3Config.BackupItem.DATABASE in config.items,
            includeFiles = S3Config.BackupItem.FILES in config.items,
            encryptPassword = encryptPassword,
        )

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) = backupManager.stageRestore(
        archive = backupFile,
        includeDatabase = S3Config.BackupItem.DATABASE in config.items,
        includeFiles = S3Config.BackupItem.FILES in config.items,
    )

    private suspend fun deletePartialIfPresent(client: S3Client, key: String) {
        val result = client.deleteObject(key)
        if (result.isFailure) {
            val err = result.exceptionOrNull()
            if (err is S3Exception && err.statusCode == 404) return
            result.getOrThrow()
        }
    }

    private companion object {
        const val KEY_PREFIX = "rikkihub_backups/"
        val FALLBACK_STATUS_CODES = setOf(403, 405, 501)
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
) {
    val isEncrypted: Boolean
        get() = displayName.endsWith(".${BackupEncryption.EXTENSION}", ignoreCase = true)
}
