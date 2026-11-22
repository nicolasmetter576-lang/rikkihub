package me.rerere.rikkihub.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.getCurrentAssistant
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.mutation.MutationConflictException
import me.rerere.rikkihub.data.repository.ConversationRepository
import me.rerere.rikkihub.service.ChatService
import kotlin.uuid.Uuid

private const val TAG = "HistoryVM"

class HistoryVM(
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {
    val assistant = settingsStore.settingsFlow
        .map { it.getCurrentAssistant() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations = assistant.flatMapLatest { assistant ->
        conversationRepo.getConversationsOfAssistant(assistant?.id ?: Uuid.random())
    }.catch {
        Log.e(TAG, "Error: ${it.message}")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Serializes delete and its undo: an undo tapped right away still runs after the delete.
    private val deletions = Mutex()

    fun deleteConversations(conversations: List<Conversation>) {
        viewModelScope.launch {
            deletions.withLock { conversations.forEach { conversationRepo.deleteConversation(it) } }
        }
    }

    /** Undo of [deleteConversations]: the conversations come back unchanged, in their old places. */
    fun undoDelete(conversationIds: List<Uuid>) {
        viewModelScope.launch {
            deletions.withLock {
                conversationIds.forEach { conversationId ->
                    try {
                        conversationRepo.restoreConversation(conversationId)
                    } catch (e: MutationConflictException) {
                        Log.w(TAG, "undo delete: conversation $conversationId is no longer restorable", e)
                    }
                }
            }
        }
    }

    fun deleteAllConversations() {
        val assistant = assistant.value ?: return
        viewModelScope.launch {
            conversationRepo.deleteConversationOfAssistant(assistant.id)
        }
    }

    fun togglePinStatus(conversationId: Uuid) {
        viewModelScope.launch {
            chatService.toggleConversationPin(conversationId)
        }
    }

    fun getPinnedConversations(): Flow<List<Conversation>> =
        conversationRepo.getPinnedConversations()
}
