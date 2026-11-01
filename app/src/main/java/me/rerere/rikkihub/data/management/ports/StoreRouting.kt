package me.rerere.rikkihub.data.management.ports

import me.rerere.rikkihub.data.management.common.ManagementDomain
import me.rerere.rikkihub.data.management.common.ObjectRef

/** Never infer store ownership by probing arbitrary gateways or Map iteration order. */
object StoreRouting {
    fun forObject(ref: ObjectRef): StoreKind = when (ref.domain) {
        ManagementDomain.QUICK_MESSAGE, ManagementDomain.MODE_INJECTION,
        ManagementDomain.WORLD_BOOK, ManagementDomain.ASSISTANT,
        ManagementDomain.MODEL_AND_PROMPT_DEFAULTS -> StoreKind.SETTINGS_DATASTORE
        ManagementDomain.SUBAGENT_PRESET, ManagementDomain.CONVERSATION -> StoreKind.MAIN_ROOM
        ManagementDomain.USER_SKILL -> StoreKind.USER_FILES
    }

    fun forCollection(id: String): StoreKind = when {
        id.startsWith("settings.") -> StoreKind.SETTINGS_DATASTORE
        id.startsWith("room.") -> StoreKind.MAIN_ROOM
        id.startsWith("files.") -> StoreKind.USER_FILES
        else -> throw IllegalArgumentException("Unknown collection namespace")
    }
}
