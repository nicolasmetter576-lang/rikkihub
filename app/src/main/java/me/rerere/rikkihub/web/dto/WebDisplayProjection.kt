package me.rerere.rikkihub.web.dto

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tavern.ActiveCharacter
import me.rerere.rikkihub.data.ai.tavern.RegexScripts
import me.rerere.rikkihub.data.ai.tavern.TavernRegexes
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.CharacterRecord
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.tavern.regex.RegexScript
import kotlin.uuid.Uuid

/**
 * 网页端的「仅显示」正则：和 App 渲染时用同一个引擎、同一套宏环境，在服务端算好，随消息下发。
 * 存储里的文字不动，网页端编辑消息拿到的还是原文；`display` 里只有被正则改过的那几段（键是 part 的下标）。
 *
 * 深度 = 消息所在节点离会话末尾的距离（最新一条为 0），和 App 聊天列表提供的一样。
 * 没有「仅显示」脚本时什么都不做，不进正则引擎。
 */
class WebDisplayProjector(
    private val settingsStore: SettingsStore,
    runtime: TavernRuntime,
    private val characters: CharacterRepository? = null,
) {
    private val regexes = TavernRegexes(runtime)

    private data class CacheKey(val signature: Int, val role: MessageRole, val reasoning: Boolean, val depth: Int?, val text: String)

    private val cache = object : LinkedHashMap<CacheKey, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>?) = size > CACHE_SIZE
    }

    /** 有带深度范围的「仅显示」脚本时，会话多了或少了节点，所有节点的深度都变了，要整份重发。CLI 会话不跑显示正则。 */
    suspend fun dependsOnDepth(conversation: Conversation): Boolean =
        if (conversation.cliSession != null) false else dependsOnDepth(conversation.assistantId, conversation.characterId)

    suspend fun dependsOnDepth(assistantId: Uuid, characterId: Uuid? = null): Boolean = contextFor(assistantId, characterId)?.depthMatters == true

    /** 整个会话：每个节点按它离末尾的距离算。CLI 会话不跑酒馆显示正则。 */
    suspend fun project(dto: ConversationDto, conversation: Conversation): ConversationDto {
        if (conversation.cliSession != null) return dto
        val context = contextFor(conversation.assistantId, conversation.characterId) ?: return dto
        val last = dto.messages.lastIndex
        return dto.copy(messages = dto.messages.mapIndexed { index, node -> projectNode(context, node, last - index) })
    }

    /** 一个节点（流式更新用）：CLI 会话不跑酒馆显示正则。 */
    suspend fun projectNode(
        node: MessageNodeDto,
        conversation: Conversation,
        nodeIndex: Int,
        nodeCount: Int = conversation.messageNodes.size,
    ): MessageNodeDto {
        if (conversation.cliSession != null) return node
        return projectNode(node, nodeIndex, nodeCount, conversation.assistantId, conversation.characterId)
    }

    /** 一个节点（流式更新用）：[nodeIndex] 是它在会话里的下标，[nodeCount] 是节点总数。 */
    suspend fun projectNode(node: MessageNodeDto, nodeIndex: Int, nodeCount: Int, assistantId: Uuid, characterId: Uuid? = null): MessageNodeDto {
        val context = contextFor(assistantId, characterId) ?: return node
        return projectNode(context, node, nodeCount - 1 - nodeIndex)
    }

    private class Context(
        val settings: Settings,
        val assistant: Assistant?,
        val character: ActiveCharacter?,
        val signature: Int,
        val depthMatters: Boolean,
    )

    private class CachedCharacter(val loadedAtNanos: Long, val record: CharacterRecord?)

    private val characterCache = HashMap<Uuid, CachedCharacter>()

    /**
     * 会话绑的角色。流式更新每一帧都会来问，所以短时间内用缓存（角色改了最多晚一小会儿显示），
     * 不然每帧都要从库里读并解析整张卡。
     */
    private suspend fun activeCharacter(settings: Settings, characterId: Uuid?): ActiveCharacter? {
        if (characterId == null || characters == null) return null
        val now = System.nanoTime()
        val cached = synchronized(characterCache) { characterCache[characterId]?.takeIf { now - it.loadedAtNanos < CHARACTER_CACHE_NANOS } }
        val record = if (cached != null) cached.record else {
            characters.get(characterId).also { synchronized(characterCache) { characterCache[characterId] = CachedCharacter(now, it) } }
        }
        return record?.let { ActiveCharacter.of(it, settings) }
    }

    private suspend fun contextFor(assistantId: Uuid, characterId: Uuid?): Context? {
        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
        val character = activeCharacter(settings, characterId)
        if (!regexes.hasDisplayScripts(settings, assistant, character)) return null
        val scripts = displayScripts(settings, assistant, character)
        // 脚本的替换串里可以有宏，结果会随用户名、助手名、角色名和全局变量变，所以它们也进缓存键
        val signature = listOf(scripts, settings.tavern.globalVariables, settings.displaySetting.userNickname, assistant?.name, character?.name).hashCode()
        return Context(settings, assistant, character, signature, depthMatters = scripts.any { it.minDepth != null || it.maxDepth != null })
    }

    private fun displayScripts(settings: Settings, assistant: Assistant?, character: ActiveCharacter?): List<RegexScript> =
        RegexScripts.of(settings, assistant, character).filter { it.markdownOnly && !it.disabled }

    private suspend fun projectNode(context: Context, node: MessageNodeDto, depthFromEnd: Int): MessageNodeDto =
        node.copy(messages = node.messages.map { projectMessage(context, it, depthFromEnd) })

    private suspend fun projectMessage(context: Context, message: MessageDto, depthFromEnd: Int): MessageDto {
        val role = when (message.role) {
            MessageRole.USER.name -> MessageRole.USER
            MessageRole.ASSISTANT.name -> MessageRole.ASSISTANT
            else -> return message
        }
        val display = LinkedHashMap<Int, String>()
        message.parts.forEachIndexed { index, part ->
            val (text, reasoning) = when (part) {
                is UIMessagePart.Text -> part.text to false
                is UIMessagePart.Reasoning -> part.reasoning to true
                else -> return@forEachIndexed
            }
            if (text.isEmpty()) return@forEachIndexed
            val shown = shown(context, text, role, reasoning, depthFromEnd)
            if (shown != text) display[index] = shown
        }
        return if (display.isEmpty()) message else message.copy(display = display)
    }

    private suspend fun shown(context: Context, text: String, role: MessageRole, reasoning: Boolean, depthFromEnd: Int): String {
        val key = CacheKey(context.signature, role, reasoning, if (context.depthMatters) depthFromEnd else null, text)
        synchronized(cache) { cache[key] }?.let { return it }
        val result = try {
            regexes.display(text, role, depthFromEnd, context.settings, context.assistant, isReasoning = reasoning, character = context.character)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            text // 脚本坏了不该让整条消息发不出去，显示原文
        }
        synchronized(cache) { cache[key] = result }
        return result
    }

    private companion object {
        const val CACHE_SIZE = 256
        const val CHARACTER_CACHE_NANOS = 2_000_000_000L
    }
}
