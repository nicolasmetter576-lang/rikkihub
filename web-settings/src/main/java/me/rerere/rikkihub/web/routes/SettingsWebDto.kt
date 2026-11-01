package me.rerere.rikkihub.web.dto

import kotlinx.serialization.Serializable
import me.rerere.ai.provider.Model

@Serializable
data class CreateAssistantRequest(
    val name: String? = null,
)

@Serializable
data class AssistantTagRequest(
    val name: String? = null,
)

@Serializable
data class CreateProviderRequest(
    val type: String,
    val name: String? = null,
)

@Serializable
data class ReorderIdsRequest(
    val ids: List<String>,
)

@Serializable
data class RemoteModelsResponse(
    val models: List<Model> = emptyList(),
    val error: String? = null,
)
