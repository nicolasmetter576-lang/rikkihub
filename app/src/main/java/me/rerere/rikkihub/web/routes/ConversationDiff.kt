package me.rerere.rikkihub.web.routes

import me.rerere.rikkihub.data.model.Conversation

/**
 * Conversation fields rendered above/around the message list. When any of these
 * change, clients need a full snapshot; node diffs alone cannot express them.
 */
internal fun Conversation.sameMetadataAs(other: Conversation): Boolean {
    return id == other.id &&
            assistantId == other.assistantId &&
            title == other.title &&
            chatSuggestions == other.chatSuggestions &&
            isPinned == other.isPinned &&
            customSystemPrompt == other.customSystemPrompt &&
            modeInjectionIds == other.modeInjectionIds &&
            lorebookIds == other.lorebookIds &&
            workspaceCwd == other.workspaceCwd &&
            workspaceCwdScoped == other.workspaceCwdScoped &&
            folderId == other.folderId &&
            createAt == other.createAt &&
            updateAt == other.updateAt
}

/**
 * Indices of message nodes that changed between this (previous) conversation and
 * [current], compared by reference — generation updates only replace the nodes
 * they touch, so unchanged nodes keep their identity and can be skipped without
 * re-serializing them.
 *
 * Returns null when nodes were removed (the new list is shorter), which index-based
 * incremental updates cannot express and callers must fall back to a snapshot.
 */
internal fun Conversation.changedNodeIndices(current: Conversation): List<Int>? {
    if (messageNodes === current.messageNodes) return emptyList()
    if (current.messageNodes.size < messageNodes.size) return null

    var changed: MutableList<Int>? = null
    for (index in current.messageNodes.indices) {
        val previousNode = messageNodes.getOrNull(index)
        if (previousNode === current.messageNodes[index]) continue
        if (changed == null) changed = mutableListOf()
        changed.add(index)
    }
    return changed.orEmpty()
}
