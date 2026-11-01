package me.rerere.rikkihub.data.favorite

import me.rerere.rikkihub.data.db.entity.FavoriteEntity
import me.rerere.rikkihub.data.model.FavoriteType

interface FavoriteAdapter<T> {
    val type: FavoriteType

    fun buildRefKey(target: T): String

    fun buildFavoriteEntity(
        target: T,
        existing: FavoriteEntity? = null,
        now: Long = System.currentTimeMillis()
    ): FavoriteEntity
}
