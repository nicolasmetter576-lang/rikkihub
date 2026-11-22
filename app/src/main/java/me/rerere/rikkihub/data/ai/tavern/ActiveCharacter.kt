package me.rerere.rikkihub.data.ai.tavern

import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.Lorebook
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.tavern.regex.RegexScript
import kotlin.uuid.Uuid

/**
 * 一个会话里正在聊的角色，连同这一刻生效的东西：角色书（卡内世界书转成的书和用户另外挂上的，已去掉停用的）、
 * 用户有没有放行卡内正则。回合准备、发送、渲染都用它；它是只读快照，设置或角色变了就重新解析。
 */
class ActiveCharacter(
    val record: CharacterRecord,
    val books: List<Lorebook>,
    val regexAllowed: Boolean,
) {
    val id: Uuid get() = record.id

    /** `{{char}}` 的值。 */
    val name: String get() = record.card.name.ifBlank { "character" }

    /** 卡自带的正则脚本；没放行就是空（它们是别人写的脚本，第一次用之前要用户点头）。 */
    val regexScripts: List<RegexScript> get() = if (regexAllowed) record.card.regexScripts else emptyList()

    companion object {
        /** `Settings.tavern.regexAllowed` 里这个角色的键。 */
        fun regexSource(id: Uuid): String = "character:$id"

        /** 按会话上存的角色 id 解析；没有角色、角色已被删除都返回 null（会话按普通助手对话处理）。 */
        suspend fun resolve(repository: CharacterRepository, settings: Settings, characterId: Uuid?): ActiveCharacter? {
            if (characterId == null) return null
            val record = repository.get(characterId) ?: return null
            return of(record, settings)
        }

        fun of(record: CharacterRecord, settings: Settings): ActiveCharacter = ActiveCharacter(
            record = record,
            books = record.local.bookIds.mapNotNull { id -> settings.lorebooks.firstOrNull { it.id == id && it.enabled } },
            regexAllowed = regexSource(record.id) in settings.tavern.regexAllowed,
        )
    }
}
