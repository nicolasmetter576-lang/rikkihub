package me.rerere.rikkihub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import me.rerere.rikkihub.data.db.entity.MessageNodeEntity

@Dao
interface MessageNodeDAO {
    // 使用与 messages 相同的 JSON 编码，保守保留所有分支中出现的 URL。
    // deleted_at IS NULL：软删除会话的附件由 attachment_retention 承担，
    // 引用检查只覆盖活跃会话（否则墓碑里的旧 URL 会永远阻止文件回收）。
    @Query(
        "SELECT EXISTS(SELECT 1 FROM message_node mn " +
            "JOIN conversationentity c ON c.id = mn.conversation_id " +
            "WHERE c.deleted_at IS NULL AND instr(mn.messages, :encodedFileUrl) > 0)"
    )
    suspend fun hasFileReference(encodedFileUrl: String): Boolean

    /**
     * 裸片段 instr 查询（调用方负责构造 needle，不要加引号/转义）。
     * 用于存储清理的多形态引用检查：能覆盖 JSON 串内 `\"file://…\"`
     * 转义嵌套等带引号 needle 命不中的场景。同样只覆盖活跃会话。
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM message_node mn " +
            "JOIN conversationentity c ON c.id = mn.conversation_id " +
            "WHERE c.deleted_at IS NULL AND instr(mn.messages, :needle) > 0)"
    )
    suspend fun hasRawFileReference(needle: String): Boolean

    @Query("SELECT id FROM message_node WHERE conversation_id = :conversationId AND id IN (:nodeIds)")
    suspend fun getExistingNodeIds(conversationId: String, nodeIds: List<String>): List<String>

    // 注意：不加 deleted_at 过滤——tombstone 恢复需要能读回已删会话的节点；
    // 父行是否活跃由 ConversationDAO 侧控制，节点查询保持按 conversation_id 中立。
    @Query(
        "SELECT * FROM message_node WHERE conversation_id = :conversationId " +
            "ORDER BY node_index ASC LIMIT :limit OFFSET :offset"
    )
    suspend fun getNodesOfConversationPaged(
        conversationId: String,
        limit: Int,
        offset: Int
    ): List<MessageNodeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<MessageNodeEntity>)

    /**
     * dirty（跳页加载）会话的增量节点写回：只插/改调用方给出的节点，
     * 绝不删除调用方未给出的行——不完整内存态不能把未读到的节点抹掉。
     */
    suspend fun upsertChangedNodes(nodes: List<MessageNodeEntity>) = insertAll(nodes)

    @Query("DELETE FROM message_node WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("UPDATE message_node SET select_index = :selectIndex WHERE id = :nodeId AND conversation_id = :conversationId")
    suspend fun updateSelectIndex(conversationId: String, nodeId: String, selectIndex: Int)

    // 使用 @RawQuery 绕过 Room 编译期校验，以便使用 json_each() 虚拟表
    @RawQuery
    suspend fun getTokenStatsRaw(query: SupportSQLiteQuery): MessageTokenStats

    @RawQuery
    suspend fun getMessageCountPerDayRaw(query: SupportSQLiteQuery): List<MessageDayCount>
}

data class MessageTokenStats(
    val totalMessages: Int = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
)

data class MessageDayCount(val day: String, val count: Int)

// 在 json_each() 的参数内校验 JSON，避免损坏行导致整个统计查询失败。
// 使用 CASE 而非依赖 WHERE 条件的求值顺序，无效 JSON 按空数组处理。
private const val VALID_MESSAGES_JSON = "CASE WHEN json_valid(mn.messages) THEN mn.messages ELSE '[]' END"

// SQLite json_each() 展开 messages JSON 数组，json_extract() 提取 Token 字段并聚合
private val TOKEN_STATS_SQL = SimpleSQLiteQuery(
    "SELECT COUNT(*) AS totalMessages, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.promptTokens') AS INTEGER)), 0) AS promptTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.completionTokens') AS INTEGER)), 0) AS completionTokens, " +
        "COALESCE(SUM(CAST(json_extract(j.value, '$.usage.cachedTokens') AS INTEGER)), 0) AS cachedTokens " +
        "FROM message_node mn, json_each($VALID_MESSAGES_JSON) j"
)

suspend fun MessageNodeDAO.getTokenStats(): MessageTokenStats = getTokenStatsRaw(TOKEN_STATS_SQL)

// 按用户消息的 createdAt 字段（LocalDateTime ISO 字符串前10位即日期）统计每日消息数
suspend fun MessageNodeDAO.getMessageCountPerDay(startDate: String): List<MessageDayCount> =
    getMessageCountPerDayRaw(
        SimpleSQLiteQuery(
            "SELECT substr(json_extract(j.value, '$.createdAt'), 1, 10) AS day, " +
                "COUNT(*) AS count " +
                "FROM message_node mn, json_each($VALID_MESSAGES_JSON) j " +
                "WHERE json_extract(j.value, '$.role') = 'user' " +
                "AND json_extract(j.value, '$.createdAt') >= ? " +
                "GROUP BY day",
            arrayOf(startDate)
        )
    )
