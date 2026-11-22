package me.rerere.tavern.prompt

import me.rerere.tavern.core.PromptRole

/**
 * `ST:public/scripts/openai.js:3511-4268` 的 `Message`/`MessageCollection`/`ChatCompletion` 移植。
 * 只保留组装需要的路径：内容/name 的 token 记账、预算（reserve/free/canAfford）、
 * 按序集合（相对位置插槽）、squash。图片/视频/音频/工具调用/签名在 1A 不做。
 */

class TokenBudgetExceededError(val identifier: String) :
    RuntimeException("Token budget exceeded while adding $identifier")

class IdentifierNotFoundError(val identifier: String) :
    RuntimeException("Identifier not found: $identifier")

/**
 * 组装期消息（ST `Message`）。
 * [tokens] = ST 的消息计数：`countAsync({role, content[, name]})`（每条 +3 + name +1、整批 +3、非 full −2，
 * 即 tok(role) + tok(content) [+ tok(name) + 1] + 4，见 `ST:src/endpoints/tokenizers.js:998-1017` 与
 * `ST:public/scripts/tokenizers.js:846` 的 countAsync）。
 */
internal class PromptMessage(
    val role: PromptRole,
    var content: String,
    val identifier: String,
    tokens: Int,
    var name: String? = null,
) {
    private var tokens: Int = tokens

    fun getTokens(): Int = tokens

    /** rikkihub 的 `HistoryItem.tokens` 覆盖路径：接入层自带计数（含媒体等）时直接设置。 */
    fun setTokens(v: Int) { tokens = v }

    /** setName 后重新计数（ST:openai.js:3599）。 */
    fun setName(n: String?, count: (PromptRole, String, String?) -> Int) {
        name = n
        tokens = count(role, content, name)
    }

    fun setContent(newContent: String, count: (PromptRole, String, String?) -> Int) {
        content = newContent
        tokens = count(role, content, name)
    }
}

/** ST `MessageCollection`：按标识符聚合的消息集合。 */
internal class MessageCollection(val identifier: String) {
    val collection = ArrayList<PromptMessage>()

    fun add(vararg items: PromptMessage) {
        collection.addAll(items)
    }

    fun getTokens(): Int = collection.sumOf { it.getTokens() }

    fun flatten(): List<PromptMessage> = ArrayList(collection)
}

/**
 * ST `ChatCompletion`：token 预算 + 集合插槽。
 * 集合用稀疏列表实现——`add(collection, position)` 在上游是按下标赋值，未覆盖的位置保持占位，
 * getChat 遍历时跳过；这里用 MutableMap<Int, MessageCollection> + append 列表保序。
 */
internal class ChatCompletion(
    private val countMessageTokens: (PromptRole, String, String?) -> Int,
) {
    var tokenBudget: Int = 0
        private set

    private val root = ArrayList<MessageCollection?>()
    var overriddenPrompts: List<String> = emptyList()
        private set

    fun setTokenBudget(context: Int, response: Int) {
        tokenBudget = context - response
    }

    /** `add(collection, position)`：position 非 null 且非 -1 → 按插槽覆盖，否则追加。 */
    fun add(collection: MessageCollection, position: Int? = null) {
        checkTokenBudget(collection, collection.identifier)
        if (position != null && position != -1) {
            while (root.size <= position) root.add(null)
            root[position] = collection
        } else {
            root.add(collection)
        }
        decreaseTokenBudgetBy(collection.getTokens())
    }

    fun insertAtStart(message: PromptMessage, identifier: String) = insert(message, identifier, "start")
    fun insertAtEnd(message: PromptMessage, identifier: String) = insert(message, identifier, "end")

    fun insert(message: PromptMessage, identifier: String, position: Any = "end") {
        checkTokenBudget(message, message.identifier)
        val index = findMessageIndex(identifier)
        if (message.content.isNotEmpty()) {
            val target = root[index]!!.collection
            when {
                position == "start" -> target.add(0, message)
                position == "end" -> target.add(message)
                position is Int -> target.add(position, message)
            }
            decreaseTokenBudgetBy(message.getTokens())
        }
    }

    fun canAfford(message: PromptMessage): Boolean = 0 <= tokenBudget - message.getTokens()
    fun canAffordAll(messages: List<PromptMessage>): Boolean =
        0 <= tokenBudget - messages.sumOf { it.getTokens() }

    fun has(identifier: String): Boolean = root.any { it?.identifier == identifier }

    fun checkTokenBudget(messageTokens: Int, identifier: String) {
        if (tokenBudget - messageTokens < 0) throw TokenBudgetExceededError(identifier)
    }

    private fun checkTokenBudget(message: PromptMessage, identifier: String) {
        checkTokenBudget(message.getTokens(), identifier)
    }

    private fun checkTokenBudget(collection: MessageCollection, identifier: String) {
        checkTokenBudget(collection.getTokens(), identifier)
    }

    fun reserveBudget(message: PromptMessage) = decreaseTokenBudgetBy(message.getTokens())
    fun reserveBudget(tokens: Int) = decreaseTokenBudgetBy(tokens)
    fun reserveBudget(collection: MessageCollection) = decreaseTokenBudgetBy(collection.getTokens())
    fun freeBudget(message: PromptMessage) = increaseTokenBudgetBy(message.getTokens())
    fun freeBudget(collection: MessageCollection) = increaseTokenBudgetBy(collection.getTokens())

    fun increaseTokenBudgetBy(tokens: Int) { tokenBudget += tokens }
    fun decreaseTokenBudgetBy(tokens: Int) { tokenBudget -= tokens }

    fun findMessageIndex(identifier: String): Int {
        val index = root.indexOfFirst { it?.identifier == identifier }
        if (index < 0) throw IdentifierNotFoundError(identifier)
        return index
    }

    fun setOverriddenPrompts(list: List<String>) { overriddenPrompts = list }

    /** ST:openai.js:3922 squashSystemMessages（仅非 dry-run，由调用方决定）。 */
    fun squashSystemMessages() {
        val excludeList = setOf("newMainChat", "newChat", "groupNudge")
        val flat = ArrayList<PromptMessage>()
        for (c in root) c?.let { flat.addAll(it.flatten()) }

        val squashed = ArrayList<PromptMessage>()
        var lastMessage: PromptMessage? = null
        fun shouldSquash(m: PromptMessage) =
            m.identifier !in excludeList && m.role == PromptRole.SYSTEM && m.name == null

        for (message in flat) {
            // 强制排除空的 system
            if (message.role == PromptRole.SYSTEM && message.content.isEmpty()) continue
            if (shouldSquash(message)) {
                if (lastMessage != null && shouldSquash(lastMessage!!)) {
                    lastMessage!!.setContent(lastMessage!!.content + "\n" + message.content, countMessageTokens)
                } else {
                    squashed.add(message)
                    lastMessage = message
                }
            } else {
                squashed.add(message)
                lastMessage = message
            }
        }

        root.clear()
        root.addAll(squashed.map { c -> MessageCollection(c.identifier).also { it.add(c) } })
    }

    /** ST `getChat()`：跳过空内容消息（没有 tool_calls 的；1A 不做工具调用）。 */
    fun getChat(): List<PromptMessage> {
        val out = ArrayList<PromptMessage>()
        for (item in root) {
            item ?: continue
            for (m in item.collection) {
                if (m.content.isNotEmpty()) out.add(m)
            }
        }
        return out
    }

    fun collections(): List<MessageCollection> = root.filterNotNull()
}
