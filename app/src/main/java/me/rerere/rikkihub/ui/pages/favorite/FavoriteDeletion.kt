package me.rerere.rikkihub.ui.pages.favorite

import androidx.compose.material3.SwipeToDismissBoxValue
import me.rerere.rikkihub.data.db.entity.FavoriteEntity

/**
 * The page owns this operation, not the disappearing row. Delete must commit before
 * offering Undo; restoring is awaited too. Errors and cancellation belong to the caller.
 */
internal suspend fun deleteFavoriteWithUndo(
    load: suspend () -> FavoriteEntity?,
    remove: suspend () -> Unit,
    offerUndo: suspend () -> Boolean,
    restore: suspend (FavoriteEntity) -> Unit,
) {
    val entity = load() ?: return
    remove()
    if (offerUndo()) restore(entity)
}

/** Pass settledValue, never the closest anchor crossed during an unfinished drag. */
internal fun shouldDeleteSettledFavorite(value: SwipeToDismissBoxValue): Boolean =
    value == SwipeToDismissBoxValue.EndToStart
