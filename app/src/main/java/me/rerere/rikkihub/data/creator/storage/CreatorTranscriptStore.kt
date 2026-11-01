package me.rerere.rikkihub.data.creator.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import java.io.File
import kotlin.uuid.Uuid

/**
 * 2.7.x 及以前创造会话 transcript（Vault 目录内 JSON 文件）。
 *
 * 2.8.0 起创造对话是普通会话，本类只供 [CreatorTranscriptMigration] 读取旧文件、
 * 迁移后删除。未执行的 `UIMessagePart.Tool` 读出时标记为已中断占位输出。
 */
class CreatorTranscriptStore(
    private val dir: File,
    private val json: Json,
) {
    companion object {
        const val MAX_TRANSCRIPT_BYTES = 4L * 1024 * 1024
        private const val EXT = ".json"
    }

    private val mutex = Mutex()

    private fun fileFor(sessionId: Uuid): File =
        File(dir, "transcript_$sessionId$EXT")

    fun exists(sessionId: Uuid): Boolean = fileFor(sessionId).exists()

    suspend fun load(sessionId: Uuid): List<UIMessage> =
        runCatching { loadStrict(sessionId) }.getOrDefault(emptyList())

    /** 文件缺失 → 空列表；文件损坏 → 抛出（迁移据此保留原文件）。 */
    suspend fun loadStrict(sessionId: Uuid): List<UIMessage> = mutex.withLock {
        val file = fileFor(sessionId)
        if (!file.exists()) return emptyList()
        val messages = json.decodeFromString<List<UIMessage>>(file.readText())
        // 未执行 tool part 固化 INTERRUPTED 占位输出，不留给下一段会话"待执行"。
        messages.map { message ->
            message.copy(
                parts = message.parts.map { part ->
                    when (part) {
                        is me.rerere.ai.ui.UIMessagePart.Tool -> {
                            if (!part.isExecuted) {
                                part.copy(
                                    output = listOf(
                                        me.rerere.ai.ui.UIMessagePart.Text(
                                            """{"status":"interrupted","error":"tool call was interrupted before execution"}"""
                                        )
                                    )
                                )
                            } else part
                        }
                        else -> part
                    }
                }
            )
        }
    }

    suspend fun save(sessionId: Uuid, messages: List<UIMessage>): Unit = mutex.withLock {
        dir.mkdirs()
        val file = fileFor(sessionId)
        val tmp = File(dir, "${file.name}.tmp")
        val text = json.encodeToString(messages)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TRANSCRIPT_BYTES) {
            throw IllegalStateException("creator transcript exceeds $MAX_TRANSCRIPT_BYTES bytes")
        }
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("creator transcript rename failed for $sessionId")
        }
    }

    suspend fun delete(sessionId: Uuid) = mutex.withLock {
        fileFor(sessionId).delete()
    }
}
