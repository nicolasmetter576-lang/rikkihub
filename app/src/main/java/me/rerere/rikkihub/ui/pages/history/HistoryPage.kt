package me.rerere.rikkihub.ui.pages.history

import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import me.rerere.rikkihub.ui.components.ui.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.ui.components.nav.BackButton
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.utils.navigateToChatPage
import me.rerere.rikkihub.utils.plus
import me.rerere.rikkihub.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid
import me.rerere.rikkihub.ui.theme.AppSpacing
import me.rerere.rikkihub.ui.components.ui.CompactListItem
import me.rerere.rikkihub.ui.components.ui.CardGroupDivider
import me.rerere.rikkihub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkihub.ui.theme.CustomColors

@Composable
fun HistoryPage(vm: HistoryVM = koinViewModel()) {
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteAllDialog by remember { mutableStateOf(false) }

    val conversations by vm.conversations.collectAsStateWithLifecycle()

    // null 表示不在多选状态
    var selectedIds by remember { mutableStateOf<Set<Uuid>?>(null) }
    // 列表变了（删除、切换助手）就去掉已经不在列表里的选中项
    LaunchedEffect(conversations) {
        val existing = conversations.mapTo(HashSet()) { it.id }
        selectedIds = selectedIds?.filterTo(HashSet()) { it in existing }
    }
    BackHandler(enabled = selectedIds != null) {
        selectedIds = null
    }

    val snackMessageDeleted = stringResource(R.string.history_page_conversation_deleted)
    val snackMessageDeletedMany = stringResource(R.string.history_page_conversations_deleted)
    val snackMessageUndo = stringResource(R.string.history_page_undo)
    fun delete(targets: List<Conversation>) {
        if (targets.isEmpty()) return
        vm.deleteConversations(targets)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = if (targets.size == 1) snackMessageDeleted else snackMessageDeletedMany.format(targets.size),
                actionLabel = snackMessageUndo,
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) {
                vm.undoDelete(targets.map { it.id })
            }
        }
    }

    Scaffold(
        topBar = {
            val selected = selectedIds
            TopAppBar(
                title = {
                    if (selected != null) {
                        Text(stringResource(R.string.history_page_selected_count, selected.size))
                    } else {
                        Text(stringResource(R.string.history_page_title))
                    }
                },
                navigationIcon = {
                    if (selected != null) {
                        IconButton(onClick = { selectedIds = null }) {
                            Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.history_page_exit_selection))
                        }
                    } else {
                        BackButton()
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(
                            onClick = {
                                selectedIds = if (selected.size == conversations.size) emptySet()
                                else conversations.mapTo(HashSet()) { it.id }
                            }
                        ) {
                            Icon(HugeIcons.CheckList, contentDescription = stringResource(R.string.history_page_select_all))
                        }
                        IconButton(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                delete(conversations.filter { it.id in selected })
                                selectedIds = null
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_selected))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                navController.navigate(Screen.MessageSearch)
                            }
                        ) {
                            Icon(
                                HugeIcons.GlobalSearch,
                                contentDescription = stringResource(R.string.history_page_search_messages)
                            )
                        }
                        if (conversations.isNotEmpty()) {
                            IconButton(onClick = { selectedIds = emptySet() }) {
                                Icon(HugeIcons.CheckList, contentDescription = stringResource(R.string.history_page_select))
                            }
                        }
                        IconButton(
                            onClick = {
                                showDeleteAllDialog = true
                            }
                        ) {
                            Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.history_page_delete_all))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding + PaddingValues(AppSpacing.sm),
        ) {
            itemsIndexed(conversations, key = { _, conversation -> conversation.id }) { index, conversation ->
                val selected = selectedIds
                SwipeableConversationItem(
                    conversation = conversation,
                    selected = selected?.let { conversation.id in it },
                    shape = cardGroupItemShape(index, conversations.size),
                    divider = index > 0,
                    onClick = {
                        if (selected != null) {
                            selectedIds = if (conversation.id in selected) selected - conversation.id else selected + conversation.id
                        } else {
                            navigateToChatPage(navController, conversation.id)
                        }
                    },
                    onLongClick = {
                        if (selected == null) selectedIds = setOf(conversation.id)
                    },
                    onDelete = { delete(listOf(conversation)) },
                    onTogglePin = { vm.togglePinStatus(conversation.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateItem()
                )
            }
        }
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.history_page_delete_all_conversations)) },
            text = { Text(stringResource(R.string.history_page_delete_all_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteAllConversations()
                        showDeleteAllDialog = false
                    }
                ) {
                    Text(stringResource(R.string.history_page_delete))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteAllDialog = false }
                ) {
                    Text(stringResource(R.string.history_page_cancel))
                }
            }
        )
    }
}

/** [selected] 为 null 表示不在多选状态；多选时不能侧滑删除。 */
@Composable
private fun SwipeableConversationItem(
    conversation: Conversation,
    selected: Boolean?,
    shape: Shape,
    divider: Boolean,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val positionThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    val dismissState = remember {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            positionalThreshold = positionThreshold,
        )
    }

    LaunchedEffect(dismissState.currentValue) {
        when (dismissState.currentValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        shape
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.history_page_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = selected == null,
        modifier = modifier
    ) {
        ConversationItem(
            conversation = conversation,
            selected = selected,
            shape = shape,
            divider = divider,
            onTogglePin = onTogglePin,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    selected: Boolean?,
    shape: Shape,
    divider: Boolean,
    modifier: Modifier = Modifier,
    onTogglePin: () -> Unit = {},
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current
    Surface(
        shape = shape,
        color = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else CustomColors.listItemColors.containerColor,
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
    ) {
        Column {
            if (divider) CardGroupDivider()
            CompactListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = selected?.let { checked ->
                    { Checkbox(checked = checked, onCheckedChange = null) }
                },
                headlineContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (conversation.isPinned) {
                            Icon(
                                imageVector = HugeIcons.Pin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = conversation.title.ifBlank { stringResource(R.string.history_page_new_conversation) }
                                .trim(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                supportingContent = {
                    Text(conversation.createAt.toLocalDateTime())
                },
                trailingContent = if (selected == null) {
                    {
                        IconButton(
                            onClick = onTogglePin
                        ) {
                            Icon(
                                if (conversation.isPinned) HugeIcons.PinOff else HugeIcons.Pin,
                                contentDescription = if (conversation.isPinned) stringResource(R.string.history_page_unpin) else stringResource(
                                    R.string.history_page_pin
                                )
                            )
                        }
                    }
                } else null,
            )
        }
    }
}
