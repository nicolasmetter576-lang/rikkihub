package me.rerere.common.cache

import kotlinx.serialization.Serializable

@Serializable
data class CacheEntry<V>(val value: V, val expiresAt: Long? = null) {
    fun isExpired(nowMillis: Long): Boolean = expiresAt?.let { nowMillis >= it } ?: false
}
