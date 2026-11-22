package me.rerere.rikkihub.data.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.tavern.card.CharacterCard
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * rikkihub 一侧的每角色设置（不属于卡本身，导出卡时不带）：
 * [embeddedBookId] 是卡内世界书转成的那本世界书，[extraBookIds] 是用户另外挂在这个角色上的世界书。
 * 两者合起来就是这个角色在聊天里生效的“角色书”（卡内的在前）。
 */
data class CharacterLocalSettings(
    val embeddedBookId: Uuid? = null,
    val extraBookIds: List<Uuid> = emptyList(),
) {
    val bookIds: List<Uuid> get() = listOfNotNull(embeddedBookId) + extraBookIds.filter { it != embeddedBookId }

    fun toJson(): JsonObject = JsonObject(buildMap {
        embeddedBookId?.let { put("embeddedBookId", JsonPrimitive(it.toString())) }
        if (extraBookIds.isNotEmpty()) put("extraBookIds", JsonArray(extraBookIds.map { JsonPrimitive(it.toString()) }))
    })

    companion object {
        fun fromJson(obj: JsonObject): CharacterLocalSettings {
            fun id(el: kotlinx.serialization.json.JsonElement?): Uuid? =
                (el as? JsonPrimitive)?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            return CharacterLocalSettings(
                embeddedBookId = id(obj["embeddedBookId"]),
                extraBookIds = (obj["extraBookIds"] as? JsonArray)?.mapNotNull { id(it) } ?: emptyList(),
            )
        }
    }
}

/** 一个角色：卡加上 rikkihub 自己的记录。`tags`、`fav` 就在卡里（[CharacterCard.tags]、[CharacterCard.fav]）。 */
data class CharacterRecord(
    val id: Uuid,
    val card: CharacterCard,
    /** 头像文件，相对 `filesDir/characters/<id>/`；没有头像为空串。 */
    val avatarPath: String = "",
    val sourceName: String = "",
    val createdAt: Instant,
    val updatedAt: Instant,
    val local: CharacterLocalSettings = CharacterLocalSettings(),
    val revision: Long = 0,
) {
    val name: String get() = card.name
}

/** 列表里的一行（不读整张卡）。 */
data class CharacterSummary(
    val id: Uuid,
    val name: String,
    val avatarPath: String,
    val fav: Boolean,
    val tags: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revision: Long,
)
