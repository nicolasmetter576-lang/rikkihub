package me.rerere.tavern.macro.macros

import me.rerere.tavern.macro.MacroCategory
import me.rerere.tavern.macro.MacroDefinitionOptions
import me.rerere.tavern.macro.MacroRegistry
import me.rerere.tavern.macro.MacroValueType
import me.rerere.tavern.core.ChatLine

/**
 * 聊天检视宏。ST:public/scripts/macros/definitions/chat-macros.js。
 * `getLastMessageId` 默认排除 swipe 进行中的消息（swipeId >= swipeCount）。
 */
fun registerChatMacros(registry: MacroRegistry) {
    fun findLastId(chat: List<ChatLine>, excludeInProgressSwipe: Boolean = true, filter: (ChatLine) -> Boolean = { true }): Int? {
        if (chat.isEmpty()) return null
        for (i in chat.indices.reversed()) {
            val m = chat[i]
            if (excludeInProgressSwipe && m.swipeId >= m.swipeCount) continue
            if (filter(m)) return i
        }
        return null
    }

    fun register(name: String, desc: String, handler: (me.rerere.tavern.macro.MacroExecutionContext) -> String) {
        registry.registerMacro(name, MacroDefinitionOptions(
            category = MacroCategory.CHAT, description = desc, returns = desc,
            returnType = if (name.endsWith("Id")) listOf(MacroValueType.INTEGER) else listOf(MacroValueType.STRING),
            handler = handler,
        ))
    }

    register("lastMessage", "Last message in the chat.") { ctx ->
        findLastId(ctx.env.chat)?.let { ctx.env.chat[it].text } ?: ""
    }
    register("lastMessageId", "Index of the last message in the chat.") { ctx ->
        findLastId(ctx.env.chat)?.toString() ?: ""
    }
    register("lastUserMessage", "Last user message in the chat.") { ctx ->
        findLastId(ctx.env.chat) { it.isUser && !it.isSystem }?.let { ctx.env.chat[it].text } ?: ""
    }
    register("lastCharMessage", "Last character/bot message in the chat.") { ctx ->
        findLastId(ctx.env.chat) { !it.isUser && !it.isSystem }?.let { ctx.env.chat[it].text } ?: ""
    }
    register("firstIncludedMessageId", "Index of the first message included in the current context.") { ctx ->
        ctx.env.firstIncludedMessageId?.toString() ?: ""
    }
    register("firstDisplayedMessageId", "Index of the first displayed message in the chat.") { ctx ->
        ctx.env.firstDisplayedMessageId?.toString() ?: ""
    }
    register("lastSwipeId", "1-based index of the last swipe for the last message.") { ctx ->
        findLastId(ctx.env.chat, excludeInProgressSwipe = false)?.let { ctx.env.chat[it].swipeCount.toString() } ?: ""
    }
    register("currentSwipeId", "1-based index of the current swipe.") { ctx ->
        findLastId(ctx.env.chat, excludeInProgressSwipe = false)?.let { (ctx.env.chat[it].swipeId + 1).toString() } ?: ""
    }
    register("allChatRange", "Range of all message IDs in the chat.") { ctx ->
        if (ctx.env.chat.isEmpty()) "" else "0-${ctx.env.chat.size - 1}"
    }
}
