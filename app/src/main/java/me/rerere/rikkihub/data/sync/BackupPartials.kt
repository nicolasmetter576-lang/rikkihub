package me.rerere.rikkihub.data.sync

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BackupPartials"

/**
 * 远端临时 `.partial` 原子上传登记与孤儿残片清扫。
 * 登记只用于防止中断后远端残留半截坏包并支持后续清扫，不作断点续传使用。
 */
class BackupPartials(
    private val stateStore: BackupDeviceStateStore,
) {
    private val activeTransfers = ConcurrentHashMap.newKeySet<String>()

    suspend fun publish(
        scope: String,
        partialName: String,
        uploadAndCommit: suspend () -> Unit,
        removePartial: suspend () -> Unit,
    ) {
        val token = "$scope/$partialName"
        check(activeTransfers.add(token)) { "Backup transfer already in progress for $partialName" }
        try {
            mark(scope, partialName, add = true)
            try {
                uploadAndCommit()
            } finally {
                withContext(NonCancellable) {
                    val removed = runCatching { removePartial() }.isSuccess
                    if (removed) {
                        runCatching { mark(scope, partialName, add = false) }
                    }
                }
            }
        } finally {
            activeTransfers.remove(token)
        }
    }

    suspend fun sweep(
        scope: String,
        listRemote: suspend () -> Map<String, Instant?>,
        removeRemote: suspend (String) -> Unit,
        now: Instant = Instant.now(),
    ) {
        val tracked = stateStore.state.value.outstandingPartials[scope].orEmpty()
            .filter(::isOwnedPartialName)
            .toSet()

        for (partial in tracked) {
            val token = "$scope/$partial"
            if (!activeTransfers.add(token)) continue
            try {
                runCatching { removeRemote(partial) }
                    .onSuccess { mark(scope, partial, add = false) }
                    .onFailure { Log.d(TAG, "Failed to sweep tracked partial $partial", it) }
            } finally {
                activeTransfers.remove(token)
            }
        }

        val remoteItems = runCatching { listRemote() }.getOrElse { return }
        for ((name, lastModified) in remoteItems) {
            if (!isOwnedPartialName(name) || name in tracked) continue
            if (!isOrphanOldEnough(lastModified, now)) continue
            val token = "$scope/$name"
            if (!activeTransfers.add(token)) continue
            try {
                runCatching { removeRemote(name) }
                    .onFailure { Log.d(TAG, "Failed to sweep orphan partial $name", it) }
            } finally {
                activeTransfers.remove(token)
            }
        }
    }

    private suspend fun mark(scope: String, partialName: String, add: Boolean) {
        stateStore.update { current ->
            val existing = current.outstandingPartials[scope].orEmpty()
            val updatedScope = if (add) existing + partialName else existing - partialName
            val nextMap = current.outstandingPartials.toMutableMap()
            if (updatedScope.isEmpty()) {
                nextMap.remove(scope)
            } else {
                nextMap[scope] = updatedScope
            }
            current.copy(outstandingPartials = nextMap)
        }
    }

    companion object {
        private val ARCHIVE_REGEX = Regex("^backup_[\\w.-]+\\.(zip|rkbackup)$", RegexOption.IGNORE_CASE)
        private val PARTIAL_REGEX = Regex("^backup_[\\w.-]+\\.(zip|rkbackup)\\.partial$", RegexOption.IGNORE_CASE)
        private val ORPHAN_AGE: Duration = Duration.ofHours(6)

        fun isBackupArchiveName(fileName: String): Boolean = ARCHIVE_REGEX.matches(fileName)

        fun isOwnedPartialName(fileName: String): Boolean = PARTIAL_REGEX.matches(fileName)

        fun isOrphanOldEnough(lastModified: Instant?, now: Instant): Boolean {
            if (lastModified == null) return false
            return !Duration.between(lastModified, now).minus(ORPHAN_AGE).isNegative
        }

        fun scopeOf(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in parts) {
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
