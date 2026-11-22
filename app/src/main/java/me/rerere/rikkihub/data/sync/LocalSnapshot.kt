package me.rerere.rikkihub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkihub.utils.JsonInstant
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import kotlin.math.max

private const val TAG = "LocalSnapshot"

@Serializable
enum class LocalSnapshotOrigin {
    AUTOMATIC,
    MANUAL,
    BEFORE_RESTORE,
}

@Serializable
data class LocalSnapshotMeta(
    val version: Int = 1,
    val createdAtUtc: String,
    val origin: LocalSnapshotOrigin,
    val pinned: Boolean = false,
    val bytes: Long,
    val conversationCount: Int = 0,
    val messageNodeCount: Int = 0,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val databaseSchemaVersion: Int? = null,
)

data class LocalSnapshotEntry(
    val id: String,
    val file: File,
    val createdAtMillis: Long,
    val bytes: Long,
    val origin: LocalSnapshotOrigin,
    val pinned: Boolean,
    val conversationCount: Int,
    val messageNodeCount: Int,
    val appVersionName: String?,
    val databaseSchemaVersion: Int?,
) {
    val hasContent: Boolean get() = messageNodeCount > 0
}

/**
 * 廉价文件指纹（3 次 stat，不读库）：
 * rikkihub 数据库本体、WAL 旁挂与 DataStore 设置文件的大小与修改时间。
 */
data class SnapshotChangeFingerprint(
    val dbBytes: Long,
    val dbModified: Long,
    val walBytes: Long,
    val walModified: Long,
    val settingsBytes: Long,
    val settingsModified: Long,
) {
    fun encode(): String =
        "$dbBytes:$dbModified:$walBytes:$walModified:$settingsBytes:$settingsModified"

    fun matches(other: SnapshotChangeFingerprint?): Boolean {
        if (other == null) return false
        if (dbBytes < 0 || dbModified < 0 ||
            walBytes < 0 || walModified < 0 ||
            settingsBytes < 0 || settingsModified < 0
        ) {
            return false
        }
        return dbBytes == other.dbBytes &&
            dbModified == other.dbModified &&
            walBytes == other.walBytes &&
            walModified == other.walModified &&
            settingsBytes == other.settingsBytes &&
            settingsModified == other.settingsModified
    }

    companion object {
        fun read(databaseFile: File, settingsFile: File): SnapshotChangeFingerprint {
            val (dbBytes, dbModified) = statOf(databaseFile)
            val (walBytes, walModified) = statOf(File(databaseFile.path + "-wal"))
            val (settingsBytes, settingsModified) = statOf(settingsFile)
            return SnapshotChangeFingerprint(
                dbBytes = dbBytes,
                dbModified = dbModified,
                walBytes = walBytes,
                walModified = walModified,
                settingsBytes = settingsBytes,
                settingsModified = settingsModified,
            )
        }

        fun decode(raw: String?): SnapshotChangeFingerprint? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(':')
            if (parts.size != 6) return null
            val nums = parts.map { it.toLongOrNull() ?: return null }
            return SnapshotChangeFingerprint(
                dbBytes = nums[0],
                dbModified = nums[1],
                walBytes = nums[2],
                walModified = nums[3],
                settingsBytes = nums[4],
                settingsModified = nums[5],
            )
        }

        private fun statOf(file: File): Pair<Long, Long> = runCatching {
            if (!file.exists()) return 0L to 0L
            if (!file.isFile) return -1L to -1L
            file.length() to file.lastModified()
        }.getOrElse { -1L to -1L }
    }
}

/**
 * GFS-lite 槽位与防误删保护规则（只做加法保护，绝不用空库或骤减库顶掉完整副本）。
 */
object LocalSnapshotRetentionPolicy {
    private const val DAY_MS = 24L * 60 * 60 * 1000L
    private const val WEEKLY_AGE_MS = 7L * DAY_MS
    private const val MONTHLY_AGE_MS = 30L * DAY_MS
    private const val DROP_GUARD_WINDOW_MS = 90L * DAY_MS
    private const val DROP_GUARD_RATIO = 0.5

    fun selectForDeletion(
        entries: List<LocalSnapshotEntry>,
        config: LocalSnapshotConfig,
        nowMillis: Long,
    ): List<LocalSnapshotEntry> {
        val ordered = entries.sortedByDescending { it.createdAtMillis }
        if (ordered.size <= 1) return emptyList()

        val protectedIds = protectedIds(ordered, nowMillis)
        val slotIds = slotIds(ordered, config, nowMillis)
        val retainedIds = protectedIds + slotIds

        val deletions = ordered.asReversed().filter { it.id !in retainedIds }.toMutableList()
        if (config.maxTotalBytes <= 0L) return deletions

        val deletedIds = deletions.mapTo(mutableSetOf()) { it.id }
        var totalBytes = ordered.filter { it.id !in deletedIds }.sumOf { it.bytes }
        for (entry in ordered.asReversed()) {
            if (totalBytes <= config.maxTotalBytes) break
            if (entry.id in deletedIds || entry.id in protectedIds) continue
            deletions.add(entry)
            deletedIds.add(entry.id)
            totalBytes -= entry.bytes
        }
        return deletions
    }

    private fun protectedIds(ordered: List<LocalSnapshotEntry>, nowMillis: Long): Set<String> {
        val protected = mutableSetOf(ordered.first().id)
        for (entry in ordered) {
            if (entry.pinned) protected.add(entry.id)
        }
        ordered.firstOrNull { it.hasContent }?.let { protected.add(it.id) }
        highWaterGuard(ordered, nowMillis)?.let { protected.add(it.id) }
        return protected
    }

    private fun highWaterGuard(ordered: List<LocalSnapshotEntry>, nowMillis: Long): LocalSnapshotEntry? {
        var highWater: LocalSnapshotEntry? = null
        for (entry in ordered) {
            if (highWater == null || entry.messageNodeCount > highWater.messageNodeCount) {
                highWater = entry
            }
        }
        if (highWater == null || !highWater.hasContent) return null
        if (highWater.id == ordered.first().id) return null
        if (nowMillis - highWater.createdAtMillis > DROP_GUARD_WINDOW_MS) return null

        val floor = highWater.messageNodeCount * DROP_GUARD_RATIO
        for (entry in ordered) {
            if (entry.createdAtMillis <= highWater.createdAtMillis) break
            if (entry.messageNodeCount < floor) return highWater
        }
        return null
    }

    private fun slotIds(
        ordered: List<LocalSnapshotEntry>,
        config: LocalSnapshotConfig,
        nowMillis: Long,
    ): Set<String> {
        val slots = ordered.take(max(1, config.keepRecent)).mapTo(mutableSetOf()) { it.id }
        if (config.keepWeekly) {
            ordered.firstOrNull { nowMillis - it.createdAtMillis >= WEEKLY_AGE_MS }?.let { slots.add(it.id) }
        }
        if (config.keepMonthly) {
            ordered.firstOrNull { nowMillis - it.createdAtMillis >= MONTHLY_AGE_MS }?.let { slots.add(it.id) }
        }
        return slots
    }
}

object LocalSnapshotSchedule {
    const val INITIAL_GRACE_MS = 10L * 60L * 1000L
    const val FREE_SPACE_FLOOR_BYTES = 512L * 1024L * 1024L
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L

    fun intervalMillis(config: LocalSnapshotConfig, databaseBytes: Long): Long {
        val days = if (config.intervalDays > 0) {
            config.intervalDays
        } else {
            when {
                databaseBytes >= 1024L * 1024L * 1024L -> 7
                databaseBytes >= 200L * 1024L * 1024L -> 3
                else -> 1
            }
        }
        return days.toLong() * DAY_MS
    }

    fun failureBackoffMillis(failureStreak: Int): Long {
        if (failureStreak <= 0) return 0L
        val shift = (failureStreak - 1).coerceAtMost(4)
        return (1L shl shift) * HOUR_MS
    }

    fun estimatedRequiredBytes(databaseBytes: Long): Long =
        (databaseBytes * 1.4).toLong() + 4L * 1024L * 1024L

    fun dueSkipReason(
        nowMillis: Long,
        config: LocalSnapshotConfig,
        state: LocalSnapshotRunState,
        databaseBytes: Long,
    ): LocalSnapshotSkipReason? {
        if (!config.enabled) return LocalSnapshotSkipReason.DISABLED
        val lastFailure = state.lastFailureAtMillis
        val backoff = failureBackoffMillis(state.failureStreak)
        if (lastFailure != null && backoff > 0L && lastFailure <= nowMillis && nowMillis - lastFailure < backoff) {
            return LocalSnapshotSkipReason.BACKOFF
        }
        val lastSuccess = state.lastSuccessAtMillis ?: return null
        if (lastSuccess > nowMillis) return null
        val interval = intervalMillis(config, databaseBytes)
        if (nowMillis - lastSuccess < interval) {
            return LocalSnapshotSkipReason.NOT_DUE
        }
        return null
    }
}

class LocalSnapshotStore(
    private val rootDir: File,
    private val json: Json = JsonInstant,
) {
    fun list(): List<LocalSnapshotEntry> {
        if (!rootDir.isDirectory) return emptyList()
        val files = rootDir.listFiles().orEmpty()
        val entries = mutableListOf<LocalSnapshotEntry>()
        for (file in files) {
            if (!file.isFile) continue
            val createdAtMillis = parseCreatedAtMillis(file.name) ?: continue
            val bytes = file.length()
            if (bytes <= 0L) continue
            val meta = readMeta(file.name)
            entries.add(
                LocalSnapshotEntry(
                    id = file.name,
                    file = file,
                    createdAtMillis = createdAtMillis,
                    bytes = bytes,
                    origin = meta?.origin ?: LocalSnapshotOrigin.AUTOMATIC,
                    pinned = meta?.pinned == true,
                    // sidecar 丢失或损坏时视为有内容，防止被当作空库淘汰
                    conversationCount = meta?.conversationCount?.coerceAtLeast(0) ?: 1,
                    messageNodeCount = meta?.messageNodeCount?.coerceAtLeast(0) ?: 1,
                    appVersionName = meta?.appVersionName,
                    databaseSchemaVersion = meta?.databaseSchemaVersion,
                )
            )
        }
        entries.sortByDescending { it.createdAtMillis }
        return entries
    }

    fun findById(id: String): LocalSnapshotEntry? {
        if (parseCreatedAtMillis(id) == null) return null
        return list().firstOrNull { it.id == id }
    }

    fun publish(
        preparedZip: File,
        createdAtMillis: Long,
        origin: LocalSnapshotOrigin,
        pinned: Boolean,
        conversationCount: Int,
        messageNodeCount: Int,
        appVersionName: String?,
        appVersionCode: Int?,
        databaseSchemaVersion: Int?,
    ): LocalSnapshotEntry {
        check(rootDir.isDirectory || rootDir.mkdirs()) {
            "Cannot create snapshot directory: ${rootDir.path}"
        }
        var stamp = createdAtMillis.coerceAtLeast(0L)
        var fileName = fileNameFor(stamp)
        var published = File(rootDir, fileName)
        var attempts = 0
        while (published.exists() && attempts < 100) {
            stamp += 1L
            fileName = fileNameFor(stamp)
            published = File(rootDir, fileName)
            attempts++
        }
        check(!published.exists()) { "Snapshot file already exists: $fileName" }

        val staged = File(rootDir, "$TEMP_PREFIX$fileName")
        val metaFile = metaFileFor(fileName)
        staged.delete()

        moveOrCopy(preparedZip, staged)
        try {
            FileOutputStream(staged, true).use { it.fd.sync() }
            val bytes = staged.length()
            check(bytes > 0L) { "Snapshot archive is empty" }

            val meta = LocalSnapshotMeta(
                createdAtUtc = Instant.ofEpochMilli(stamp).toString(),
                origin = origin,
                pinned = pinned,
                bytes = bytes,
                conversationCount = conversationCount,
                messageNodeCount = messageNodeCount,
                appVersionName = appVersionName,
                appVersionCode = appVersionCode,
                databaseSchemaVersion = databaseSchemaVersion,
            )
            PendingRestore.writeDurably(metaFile, BackupDeviceStateStore.stateJson.encodeToString(meta))
            Files.move(staged.toPath(), published.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)

            return LocalSnapshotEntry(
                id = fileName,
                file = published,
                createdAtMillis = stamp,
                bytes = bytes,
                origin = origin,
                pinned = pinned,
                conversationCount = conversationCount,
                messageNodeCount = messageNodeCount,
                appVersionName = appVersionName,
                databaseSchemaVersion = databaseSchemaVersion,
            )
        } catch (e: Throwable) {
            staged.delete()
            metaFile.delete()
            throw e
        }
    }

    fun setPinned(id: String, pinned: Boolean) {
        val entry = findById(id) ?: return
        val existingMeta = readMeta(entry.id)
        val updated = LocalSnapshotMeta(
            createdAtUtc = existingMeta?.createdAtUtc ?: Instant.ofEpochMilli(entry.createdAtMillis).toString(),
            origin = entry.origin,
            pinned = pinned,
            bytes = entry.bytes,
            conversationCount = entry.conversationCount,
            messageNodeCount = entry.messageNodeCount,
            appVersionName = entry.appVersionName,
            appVersionCode = existingMeta?.appVersionCode,
            databaseSchemaVersion = entry.databaseSchemaVersion,
        )
        PendingRestore.writeDurably(
            metaFileFor(entry.id),
            BackupDeviceStateStore.stateJson.encodeToString(updated),
        )
    }

    fun delete(id: String) {
        require(parseCreatedAtMillis(id) != null) { "Invalid snapshot id: $id" }
        val archive = File(rootDir, id)
        val meta = metaFileFor(id)
        meta.delete()
        archive.delete()
        check(!archive.exists()) { "Failed to delete snapshot: $id" }
    }

    fun prune(config: LocalSnapshotConfig, nowMillis: Long = System.currentTimeMillis()): List<LocalSnapshotEntry> {
        val entries = list()
        if (entries.size <= 1) return emptyList()
        val victims = LocalSnapshotRetentionPolicy.selectForDeletion(entries, config, nowMillis)
        val removed = mutableListOf<LocalSnapshotEntry>()
        for (victim in victims) {
            runCatching { delete(victim.id) }
                .onSuccess { removed.add(victim) }
                .onFailure { Log.w(TAG, "Failed to prune snapshot ${victim.id}", it) }
        }
        return removed
    }

    fun sweepIncomplete() {
        if (!rootDir.isDirectory) return
        for (file in rootDir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            val name = file.name
            if (name.startsWith(TEMP_PREFIX)) {
                file.delete()
                continue
            }
            if (name.endsWith(META_SUFFIX)) {
                val archiveName = name.removeSuffix(META_SUFFIX)
                if (parseCreatedAtMillis(archiveName) != null && !File(rootDir, archiveName).isFile) {
                    file.delete()
                }
            }
        }
    }

    private fun readMeta(fileName: String): LocalSnapshotMeta? {
        val metaFile = metaFileFor(fileName)
        if (!metaFile.isFile) return null
        return runCatching {
            BackupDeviceStateStore.stateJson.decodeFromString<LocalSnapshotMeta>(metaFile.readText())
        }.onFailure {
            Log.w(TAG, "Corrupt snapshot sidecar for $fileName", it)
        }.getOrNull()
    }

    private fun metaFileFor(fileName: String): File = File(rootDir, "$fileName$META_SUFFIX")

    private fun moveOrCopy(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return
        } catch (_: Exception) {
            // 跨文件系统或目录不支持 ATOMIC_MOVE 时复制后删源
        }
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        source.delete()
    }

    companion object {
        const val DIR_NAME = "snapshots"
        private const val PREFIX = "snapshot_"
        private const val SUFFIX = ".zip"
        private const val META_SUFFIX = ".meta.json"
        private const val TEMP_PREFIX = ".incomplete-"

        fun directory(context: Context): File = File(context.noBackupFilesDir, DIR_NAME)

        fun fileNameFor(createdAtMillis: Long): String =
            "$PREFIX${createdAtMillis.coerceAtLeast(0L).toString().padStart(16, '0')}$SUFFIX"

        fun parseCreatedAtMillis(fileName: String): Long? {
            if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null
            val raw = fileName.substring(PREFIX.length, fileName.length - SUFFIX.length)
            if (raw.isEmpty() || raw.any { !it.isDigit() }) return null
            return raw.toLongOrNull()?.takeIf { it >= 0L }
        }
    }
}
