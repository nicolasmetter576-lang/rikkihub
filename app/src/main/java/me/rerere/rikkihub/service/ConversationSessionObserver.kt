package me.rerere.rikkihub.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Follow registry changes without creating a placeholder session for a passive observer. */
internal fun observeSessionGenerationJob(
    registryVersion: StateFlow<Long>,
    sessionForId: () -> ConversationSession?,
): Flow<Job?> = registryVersion.flatMapLatest {
    sessionForId()?.generationJob ?: flowOf(null)
}
