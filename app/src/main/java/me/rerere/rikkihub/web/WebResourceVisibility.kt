package me.rerere.rikkihub.web

import kotlin.uuid.Uuid

/**
 * Creator-domain resources must never be visible through the
 * ordinary Web API (普通会话API/list/search/directID/SSE/message/
 * move/fork/toolapproval 均不识别创造资源).
 *
 * The creator package registers a
 * [CreatorResourceLookup] once its reserved-identity contract is frozen via
 * [WebCreatorResources.register]. Until then the lookup is empty and every id
 * is treated as an ordinary resource — the negative-admission tests install a
 * fake lookup so the rejection path is proven before real creator objects
 * exist. Web routes must route every assistant/conversation identifier through
 * these helpers instead of open-coding the check.
 */
fun interface CreatorResourceLookup {
    /** True when [id] belongs to the fixed creator domain (definition/vault-owned). */
    fun isCreatorAssistantId(id: Uuid): Boolean
}

object WebCreatorResources {
    @Volatile
    private var lookup: CreatorResourceLookup? = null

    /** Called by the creator infrastructure package after its ids are frozen. */
    fun register(creatorResourceLookup: CreatorResourceLookup) {
        lookup = creatorResourceLookup
    }

    /** Test/reset helper — production code must not call this. */
    fun unregisterForTests() {
        lookup = null
    }

    fun isCreatorAssistantId(id: Uuid): Boolean =
        lookup?.isCreatorAssistantId(id) == true
}

/**
 * Parse + visibility-check an assistant id from a request. A creator id is a
 * hard 404 (resource hidden — never distinguished from "does not exist"),
 * malformed input stays a 400.
 */
fun String?.toVisibleAssistantUuid(name: String = "assistantId"): Uuid {
    val id = when {
        this == null -> throw BadRequestException("Missing $name")
        else -> runCatching { Uuid.parse(this) }.getOrNull()
            ?: throw BadRequestException("Invalid $name")
    }
    if (WebCreatorResources.isCreatorAssistantId(id)) {
        throw NotFoundException("Assistant not found")
    }
    return id
}

/** Same check for an already-parsed id. */
fun Uuid.requireVisibleAssistant() {
    if (WebCreatorResources.isCreatorAssistantId(this)) {
        throw NotFoundException("Assistant not found")
    }
}
