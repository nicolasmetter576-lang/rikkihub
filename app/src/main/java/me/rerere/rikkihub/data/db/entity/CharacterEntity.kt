package me.rerere.rikkihub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一张角色卡。卡本身（V3 形状的 JSON，未知字段原样保留）是唯一的事实来源；`name`、`tags`、`fav`、`search_text`
 * 是从卡里算出来的冗余列，只为让列表不用读整张卡（一张带世界书的卡可以上百 KB）。头像等文件在
 * `filesDir/characters/<id>/` 下，`avatar_path` 是相对那个目录的路径。
 */
@Entity(
    tableName = "characters",
    indices = [Index("updated_at"), Index("name")],
)
data class CharacterEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("avatar_path", defaultValue = "")
    val avatarPath: String = "",
    @ColumnInfo("card_json")
    val cardJson: String,
    /** 导入时的文件名（只用来显示来源），手动新建的为空。 */
    @ColumnInfo("source_name", defaultValue = "")
    val sourceName: String = "",
    @ColumnInfo("fav", defaultValue = "0")
    val fav: Boolean = false,
    @ColumnInfo("tags", defaultValue = "[]")
    val tags: String = "[]",
    /** 小写的 名字、作者、标签、描述开头，搜索用。 */
    @ColumnInfo("search_text", defaultValue = "")
    val searchText: String = "",
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    /** rikkihub 一侧的每角色设置（JSON）：卡内世界书转成的书、附加世界书。 */
    @ColumnInfo("local_settings", defaultValue = "{}")
    val localSettings: String = "{}",
    /** 乐观并发版本：每次写 +1，整行写用它做 CAS。 */
    @ColumnInfo("revision", defaultValue = "0")
    val revision: Long = 0,
)

/** 列表用的轻量行，不含卡内容。 */
data class CharacterSummaryRow(
    val id: String,
    val name: String,
    val avatarPath: String,
    val fav: Boolean,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long,
)
