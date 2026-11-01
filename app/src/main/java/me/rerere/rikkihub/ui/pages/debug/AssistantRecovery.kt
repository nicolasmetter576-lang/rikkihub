package me.rerere.rikkihub.ui.pages.debug

import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.db.dao.ConversationAssistantCount
import me.rerere.rikkihub.data.model.Assistant
import kotlin.uuid.Uuid

internal data class AssistantRecoveryScan(val counts: Map<Uuid, Long>, val invalidIdGroups: Int)

internal fun assistantRecoveryScan(rows: List<ConversationAssistantCount>): AssistantRecoveryScan {
    val counts = linkedMapOf<Uuid, Long>()
    var invalid = 0
    rows.forEach { row ->
        val id = runCatching { Uuid.parse(row.assistantId) }.getOrNull()
        if (id == null || row.count <= 0L) {
            invalid++
        } else {
            val prior = counts[id] ?: 0L
            counts[id] = if (row.count > Long.MAX_VALUE - prior) Long.MAX_VALUE else prior + row.count
        }
    }
    return AssistantRecoveryScan(counts, invalid)
}

internal fun missingConversationAssistants(scan: AssistantRecoveryScan, settings: Settings): List<Uuid> {
    val existing = settings.assistants.map { it.id }.toSet()
    return scan.counts.keys.filterNot { it in existing || it in settings.deletedAssistantIds ||
        me.rerere.rikkihub.data.creator.definition.AssistantRegistry.isReservedAssistantId(it) }
        .sortedWith(compareByDescending<Uuid> { scan.counts.getValue(it) }.thenBy { it.toString() })
}

/** Use the latest settings under SettingsStore's update mutex, after the asynchronous DB scan. */
internal suspend fun recoverConversationAssistants(
    scan: AssistantRecoveryScan,
    updateSettings: suspend ((Settings) -> Settings) -> Unit,
    nameForIndex: (Int) -> String,
): Int {
    var recovered = 0
    updateSettings { current ->
        check(!current.init) { "Settings not loaded" }
        val missing = missingConversationAssistants(scan, current)
        val added = missing.mapIndexed { index, id -> Assistant(id = id, name = nameForIndex(index + 1)) }
        recovered = added.size
        if (added.isEmpty()) current else current.copy(assistants = current.assistants + added)
    }
    // Not returned until persistence succeeded. No conversation/message/attachment writes.
    return recovered
}
