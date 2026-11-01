package me.rerere.rikkihub.data.files

import android.util.Log
import me.rerere.rikkihub.data.db.entity.AttachmentRetentionEntity
import me.rerere.rikkihub.web.routes.StorageProtectionEvaluator
import me.rerere.rikkihub.web.routes.StorageProtectionFacts
import me.rerere.rikkihub.web.routes.StorageProtectionReason

/**
 * Deletes attachments whose retention window has ended, re-checking the same reference
 * sources as the storage page (all conversation branches incl. tool-nested parts, live
 * sessions/queues, Settings avatars/backgrounds/preset messages, draft leases).
 *
 * - still referenced → the entry is dropped and the file stays (it is live again);
 * - leased / recently created / check failed → kept for the next sweep (fail closed);
 * - unreferenced → file + managed row deleted, then the entry is removed.
 */
class AttachmentRetentionSweeper(
    private val expired: suspend (now: Long) -> List<AttachmentRetentionEntity>,
    private val release: suspend (relativePath: String) -> Unit,
    private val evaluator: StorageProtectionEvaluator,
    private val leaseExpiry: (relativePath: String) -> Long?,
    private val canonicalUrl: (relativePath: String) -> String,
    private val createdAt: suspend (relativePath: String) -> Long?,
    private val deleteFile: suspend (relativePath: String) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class Result(val deleted: Int, val revived: Int, val kept: Int)

    suspend fun sweep(): Result {
        val now = nowMillis()
        var deleted = 0
        var revived = 0
        var kept = 0
        for (entry in expired(now)) {
            val facts = StorageProtectionFacts(
                relativePath = entry.relativePath,
                canonicalFileUrl = canonicalUrl(entry.relativePath),
                createdAt = createdAt(entry.relativePath) ?: 0L,
                leaseExpiresAt = leaseExpiry(entry.relativePath),
                nowMillis = now,
            )
            val reasons = evaluator.evaluateProtection(facts) - StorageProtectionReason.RETAINED
            when {
                reasons.any { it in REFERENCED } -> { release(entry.relativePath); revived++ }
                reasons.isNotEmpty() -> kept++
                deleteFile(entry.relativePath) -> { release(entry.relativePath); deleted++ }
                else -> kept++
            }
        }
        if (deleted + revived + kept > 0) Log.i(TAG, "retention sweep: deleted=$deleted revived=$revived kept=$kept")
        return Result(deleted, revived, kept)
    }

    private companion object {
        const val TAG = "RetentionSweeper"
        val REFERENCED = setOf(
            StorageProtectionReason.REFERENCED_DB,
            StorageProtectionReason.REFERENCED_SESSION,
            StorageProtectionReason.REFERENCED_SETTINGS,
        )
    }
}
