package me.rerere.rikkihub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.data.db.AppDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

@Serializable
data class BackupManifestIncludes(
    val settings: Boolean = true,
    val database: Boolean = false,
    val files: Boolean = false,
)

@Serializable
data class BackupManifestStats(
    val conversationCount: Int = 0,
    val messageNodeCount: Int = 0,
    val attachmentFileCount: Int = 0,
    val databaseBytes: Long = 0L,
)

@Serializable
data class BackupManifest(
    val format: String = FORMAT_ID,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val minimumReadableFormatVersion: Int = CURRENT_FORMAT_VERSION,
    val createdAtUtc: String,
    val appVersionName: String,
    val appVersionCode: Int,
    val databaseSchemaVersion: Int,
    val settingsSchemaVersion: Int = CURRENT_SETTINGS_SCHEMA_VERSION,
    val includes: BackupManifestIncludes = BackupManifestIncludes(),
    val stats: BackupManifestStats = BackupManifestStats(),
) {
    companion object {
        const val ENTRY_NAME = "manifest.json"
        const val FORMAT_ID = "rikkihub-backup"
        const val CURRENT_FORMAT_VERSION = 1
        const val CURRENT_SETTINGS_SCHEMA_VERSION = 7
    }
}

enum class CompatibilityLevel {
    COMPATIBLE,
    OLDER_DATABASE,
    NEWER_APP_SAME_DB,
    NEWER_DATABASE,
    UNSUPPORTED_FORMAT,
    INVALID_ARCHIVE,
}

data class BackupCompatibilityReport(
    val level: CompatibilityLevel,
    val manifest: BackupManifest?,
    val detectedDatabaseVersion: Int?,
    val currentAppVersionCode: Int,
    val currentDatabaseVersion: Int,
    val hasSettings: Boolean,
    val hasDatabase: Boolean,
    val attachmentFileCount: Int,
    val willRestoreSettings: Boolean,
    val willRestoreDatabase: Boolean,
    val willRestoreFiles: Boolean,
) {
    val isBlocked: Boolean
        get() = level == CompatibilityLevel.NEWER_DATABASE ||
            level == CompatibilityLevel.UNSUPPORTED_FORMAT ||
            level == CompatibilityLevel.INVALID_ARCHIVE

    val requiresExplicitConsent: Boolean
        get() = level == CompatibilityLevel.NEWER_APP_SAME_DB
}

object BackupCompatibilityChecker {
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val SQLITE_HEADER_MIN_BYTES = 64
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val manifestJson = Json { ignoreUnknownKeys = true }

    fun inspect(
        archive: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        currentAppVersionCode: Int,
        currentDatabaseVersion: Int = AppDatabase.VERSION,
    ): BackupCompatibilityReport {
        if (!archive.isFile || archive.length() == 0L) {
            return invalidReport(currentAppVersionCode, currentDatabaseVersion)
        }

        return runCatching {
            ZipFile(archive).use { zip ->
                var manifest: BackupManifest? = null
                var manifestMalformed = false
                var hasSettings = false
                var hasDatabase = false
                var attachmentCount = 0
                var headerDbVersion: Int? = null
                var invalidDbHeader = false

                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    when (entry.name) {
                        BackupManifest.ENTRY_NAME -> {
                            val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_MANIFEST_BYTES) }
                            if (bytes == null) {
                                manifestMalformed = true
                            } else {
                                manifest = runCatching {
                                    manifestJson.decodeFromString<BackupManifest>(bytes.toString(Charsets.UTF_8))
                                }.onFailure {
                                    manifestMalformed = true
                                }.getOrNull()
                            }
                        }
                        "settings.json" -> hasSettings = true
                        DatabaseBackup.ARCHIVE_DATABASE -> {
                            hasDatabase = true
                            headerDbVersion = zip.getInputStream(entry).use(::readSqliteUserVersion)
                            if (headerDbVersion == null) invalidDbHeader = true
                        }
                        else -> if (BackupManager.isManagedAttachmentPath(entry.name)) {
                            attachmentCount++
                        }
                    }
                }

                val willRestoreSettings = hasSettings
                val willRestoreDatabase = includeDatabase && hasDatabase
                val willRestoreFiles = includeFiles && attachmentCount > 0

                if (manifestMalformed || (willRestoreDatabase && invalidDbHeader)) {
                    return@use BackupCompatibilityReport(
                        level = CompatibilityLevel.INVALID_ARCHIVE,
                        manifest = manifest,
                        detectedDatabaseVersion = headerDbVersion,
                        currentAppVersionCode = currentAppVersionCode,
                        currentDatabaseVersion = currentDatabaseVersion,
                        hasSettings = hasSettings,
                        hasDatabase = hasDatabase,
                        attachmentFileCount = attachmentCount,
                        willRestoreSettings = willRestoreSettings,
                        willRestoreDatabase = willRestoreDatabase,
                        willRestoreFiles = willRestoreFiles,
                    )
                }

                if (manifest != null &&
                    (manifest.format != BackupManifest.FORMAT_ID ||
                        manifest.minimumReadableFormatVersion > BackupManifest.CURRENT_FORMAT_VERSION)
                ) {
                    return@use BackupCompatibilityReport(
                        level = CompatibilityLevel.UNSUPPORTED_FORMAT,
                        manifest = manifest,
                        detectedDatabaseVersion = headerDbVersion ?: manifest.databaseSchemaVersion,
                        currentAppVersionCode = currentAppVersionCode,
                        currentDatabaseVersion = currentDatabaseVersion,
                        hasSettings = hasSettings,
                        hasDatabase = hasDatabase,
                        attachmentFileCount = attachmentCount,
                        willRestoreSettings = willRestoreSettings,
                        willRestoreDatabase = willRestoreDatabase,
                        willRestoreFiles = willRestoreFiles,
                    )
                }

                if (!willRestoreSettings && !willRestoreDatabase && !willRestoreFiles) {
                    return@use BackupCompatibilityReport(
                        level = CompatibilityLevel.INVALID_ARCHIVE,
                        manifest = manifest,
                        detectedDatabaseVersion = headerDbVersion ?: manifest?.databaseSchemaVersion,
                        currentAppVersionCode = currentAppVersionCode,
                        currentDatabaseVersion = currentDatabaseVersion,
                        hasSettings = hasSettings,
                        hasDatabase = hasDatabase,
                        attachmentFileCount = attachmentCount,
                        willRestoreSettings = false,
                        willRestoreDatabase = false,
                        willRestoreFiles = false,
                    )
                }

                val effectiveDbVersion = if (hasDatabase) {
                    listOfNotNull(
                        manifest?.databaseSchemaVersion?.takeIf { it > 0 },
                        headerDbVersion,
                    ).maxOrNull()
                } else {
                    null
                }

                val level = when {
                    willRestoreDatabase && effectiveDbVersion != null && effectiveDbVersion > currentDatabaseVersion ->
                        CompatibilityLevel.NEWER_DATABASE
                    manifest != null && manifest.appVersionCode > currentAppVersionCode ->
                        CompatibilityLevel.NEWER_APP_SAME_DB
                    willRestoreDatabase && effectiveDbVersion != null && effectiveDbVersion < currentDatabaseVersion ->
                        CompatibilityLevel.OLDER_DATABASE
                    else -> CompatibilityLevel.COMPATIBLE
                }

                BackupCompatibilityReport(
                    level = level,
                    manifest = manifest,
                    detectedDatabaseVersion = effectiveDbVersion,
                    currentAppVersionCode = currentAppVersionCode,
                    currentDatabaseVersion = currentDatabaseVersion,
                    hasSettings = hasSettings,
                    hasDatabase = hasDatabase,
                    attachmentFileCount = attachmentCount,
                    willRestoreSettings = willRestoreSettings,
                    willRestoreDatabase = willRestoreDatabase,
                    willRestoreFiles = willRestoreFiles,
                )
            }
        }.getOrElse {
            invalidReport(currentAppVersionCode, currentDatabaseVersion)
        }
    }

    internal fun readSqliteUserVersion(input: InputStream): Int? {
        val header = ByteArray(100)
        var offset = 0
        while (offset < header.size) {
            val read = input.read(header, offset, header.size - offset)
            if (read <= 0) break
            offset += read
        }
        if (offset < SQLITE_HEADER_MIN_BYTES) return null
        for (i in SQLITE_MAGIC.indices) {
            if (header[i] != SQLITE_MAGIC[i]) return null
        }
        return ByteBuffer.wrap(header, 60, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    private fun invalidReport(currentAppVersionCode: Int, currentDatabaseVersion: Int) =
        BackupCompatibilityReport(
            level = CompatibilityLevel.INVALID_ARCHIVE,
            manifest = null,
            detectedDatabaseVersion = null,
            currentAppVersionCode = currentAppVersionCode,
            currentDatabaseVersion = currentDatabaseVersion,
            hasSettings = false,
            hasDatabase = false,
            attachmentFileCount = 0,
            willRestoreSettings = false,
            willRestoreDatabase = false,
            willRestoreFiles = false,
        )
}
