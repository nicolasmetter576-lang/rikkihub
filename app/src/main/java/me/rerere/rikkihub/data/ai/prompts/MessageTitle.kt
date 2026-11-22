package me.rerere.rikkihub.data.ai.prompts

// 每轮标题的约定：开了的助手固定追加在系统提示词末尾（不含占位符，每轮不变，不影响提示词缓存）
internal val DEFAULT_MESSAGE_TITLE_PROMPT = """
    End every reply with one final line in exactly this form:
    <rk-title>title</rk-title>
    The title names what this turn is about: a short phrase in the language of the conversation, at most 8 characters
    (about 4 words in languages written with spaces). Not a list of keywords, no punctuation or quotes.
    Write nothing after this line. The app hides it from the reader and shows the title next to the reply.
""".trimIndent()
