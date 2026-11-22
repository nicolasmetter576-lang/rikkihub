package me.rerere.rikkihub.web

import kotlin.uuid.Uuid

/** Parses an assistant id from a request: missing or malformed input is a 400. */
fun String?.toAssistantUuid(name: String = "assistantId"): Uuid = when {
    this == null -> throw BadRequestException("Missing $name")
    else -> runCatching { Uuid.parse(this) }.getOrNull() ?: throw BadRequestException("Invalid $name")
}
