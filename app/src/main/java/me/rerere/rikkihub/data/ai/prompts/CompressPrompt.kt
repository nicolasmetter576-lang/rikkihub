package me.rerere.rikkihub.data.ai.prompts

import me.rerere.rikkihub.data.ai.CompressionTemplates

/**
 * 压缩指令，作为一条 user 消息追加在对话末尾，模型直接总结上方的对话历史。
 * 摘要会替代之前的全部历史；[structure] 是模板给的段落结构（内置模板见 [builtInCompressStructure]，
 * 自定义模板是用户写的正文），其余要求所有模板共用。
 */
internal fun buildCompressPrompt(
    structure: String,
    focus: String,
    summaryTokens: Int,
    locale: String,
): String = buildString {
    appendLine(
        """
        Stop the current task and write a structured summary of the conversation so far. The summary will replace the conversation history as the context for continuing it, so anything not in the summary will be lost.

        Do not call any tools. Respond with the summary only, starting with the line "[Summary of previous conversation]".
        """.trimIndent()
    )
    appendLine()
    appendLine(structure.trim())
    appendLine()
    appendLine(
        """
        Requirements:
        - Keep the section headings as given above, but write the content in the same language as the conversation; if unclear, use $locale
        - Keep the summary under about $summaryTokens tokens
        - Be specific and concrete; prefer exact names and values over general descriptions
        - Do not add any explanation or commentary outside the summary
        """.trimIndent()
    )
    if (focus.isNotBlank()) {
        appendLine()
        appendLine("Additional instructions from user: $focus")
    }
}.trimEnd()

/**
 * 分段压缩的非末段提示词：不携带对话前缀，只把上一段的阶段摘要和这一段原对话合成为新的阶段摘要。
 */
internal fun buildChunkStagePrompt(
    previousSummary: String,
    segment: String,
    focus: String,
    summaryTokens: Int,
    locale: String,
): String = buildString {
    appendLine(
        """
        Write an updated running summary for staged context compression. Combine any earlier stage summary with the conversation segment below so the next stage has everything needed without re-reading earlier segments.

        Do not call any tools. Respond with the running summary only.
        """.trimIndent()
    )
    appendLine()
    appendLine(
        """
        Requirements:
        - Keep the content in the same language as the conversation; if unclear, use $locale
        - Keep the summary under about $summaryTokens tokens
        - Preserve user goals, decisions, rationales, file paths, exact names and values, errors, and open items
        - Do not add any explanation or commentary outside the summary
        """.trimIndent()
    )
    if (focus.isNotBlank()) {
        appendLine()
        appendLine("Additional instructions from user: $focus")
    }
    if (previousSummary.isNotBlank()) {
        appendLine()
        appendLine("<previous_summary>")
        appendLine(previousSummary.trim())
        appendLine("</previous_summary>")
    }
    appendLine()
    appendLine("<conversation_segment>")
    appendLine(segment.trim())
    appendLine("</conversation_segment>")
}.trimEnd()

/**
 * 分段压缩或从原文重压的末段提示词：按所选模板输出最终结构化摘要。
 */
internal fun buildChunkFinalPrompt(
    structure: String,
    previousSummary: String,
    segment: String,
    focus: String,
    summaryTokens: Int,
    locale: String,
): String = buildString {
    appendLine(
        """
        Write the final structured summary of the conversation from the material below. The summary will replace the conversation history as the context for continuing it, so anything not in the summary will be lost.

        Do not call any tools. Respond with the summary only, starting with the line "[Summary of previous conversation]".
        """.trimIndent()
    )
    appendLine()
    appendLine(structure.trim())
    appendLine()
    appendLine(
        """
        Requirements:
        - Keep the section headings as given above, but write the content in the same language as the conversation; if unclear, use $locale
        - Keep the summary under about $summaryTokens tokens
        - Be specific and concrete; prefer exact names and values over general descriptions
        - Incorporate everything important from <previous_summary> (if present) and <conversation_segment>
        - Do not add any explanation or commentary outside the summary
        """.trimIndent()
    )
    if (focus.isNotBlank()) {
        appendLine()
        appendLine("Additional instructions from user: $focus")
    }
    if (previousSummary.isNotBlank()) {
        appendLine()
        appendLine("<previous_summary>")
        appendLine(previousSummary.trim())
        appendLine("</previous_summary>")
    }
    appendLine()
    appendLine("<conversation_segment>")
    appendLine(segment.trim())
    appendLine("</conversation_segment>")
}.trimEnd()

/** 内置模板的段落结构；未知 id 用通用模板。 */
internal fun builtInCompressStructure(templateId: String): String = when (templateId) {
    CompressionTemplates.CODE -> """
        Use exactly the following Markdown structure. Omit a section if there is nothing to put in it.

        ## User Goals
        What the user wants built or fixed, including how the requests evolved.

        ## Project and Environment
        Repository, languages, frameworks, versions, relevant files and directories, and the commands used to build, run, and test.

        ## Changes Made
        Each file that was created or changed and what changed in it. Quote exact signatures, config keys, and values.

        ## Decisions and Rationale
        Design choices, rejected alternatives, and why.

        ## Errors and Fixes
        Exact error messages, their causes, and how they were fixed, or that they remain unresolved.

        ## Open Items
        Unfinished tasks, failing tests, and questions waiting on the user.

        ## Current State
        What was being worked on right before this summary, and the next concrete step.
    """.trimIndent()

    CompressionTemplates.WRITING -> """
        Use exactly the following Markdown structure. Omit a section if there is nothing to put in it.

        ## Project Brief
        What is being written: form, genre, audience, length, tone, point of view, and style rules the user set.

        ## Characters and Setting
        Names, roles, traits, relationships, and the world or setting details established so far.

        ## Structure and Progress
        Outline, chapters or sections, and what has been drafted. Quote short key passages verbatim.

        ## Feedback and Revisions
        What the user liked, disliked, asked to change, or asked to avoid.

        ## Open Items
        Unwritten parts, unresolved plot or argument points, and open questions.

        ## Current State
        What was being written right before this summary, and the next step.
    """.trimIndent()

    CompressionTemplates.RESEARCH -> """
        Use exactly the following Markdown structure. Omit a section if there is nothing to put in it.

        ## Research Question
        What the user wants to find out, its scope, and how it evolved.

        ## Sources and Findings
        Each source consulted (title, author, link) and what it says. Quote exact figures, dates, and claims.

        ## Methods and Assumptions
        How the question was approached and the assumptions made.

        ## Conclusions
        What was concluded, with the supporting evidence and how confident it is.

        ## Conflicts and Gaps
        Contradicting sources, weak evidence, and what is still unknown.

        ## Open Items
        Follow-up searches, questions for the user, and unfinished analysis.

        ## Current State
        What was being investigated right before this summary, and the next step.
    """.trimIndent()

    CompressionTemplates.ROLEPLAY -> """
        Use exactly the following Markdown structure. Omit a section if there is nothing to put in it.

        ## Characters
        Each character's name, personality, appearance, relationships, and current condition, including the user's character.

        ## World and Setting
        Places, factions, rules of the world, and the time period established so far.

        ## Story So Far
        The key events in order, with their consequences.

        ## Current Scene
        Where and when the story is now, who is present, what is happening, and the last few lines of dialogue verbatim.

        ## Established Facts
        Promises, secrets, items, injuries, and other details that must stay consistent.

        ## User Preferences
        Pacing, writing style, and boundaries the user asked for.

        ## Open Threads
        Unresolved plot lines and what the characters intend to do next.
    """.trimIndent()

    else -> """
        Use exactly the following Markdown structure. Omit a section if there is nothing to put in it.

        ## User Goals
        What the user wants to achieve, including how their requests evolved over the conversation.

        ## Key Context
        Facts, background, constraints, and preferences the user stated or that were established, including any earlier summary at the start of the conversation.

        ## Decisions and Conclusions
        Choices that were made, answers that were reached, and the reasons behind them.

        ## Tool Results and Artifacts
        Important details from tool calls and their results that are needed to continue: file paths, commands, code, data, links, and search findings. Quote exact values instead of describing them.

        ## Errors and Resolutions
        Problems that came up and how they were resolved, or that they remain unresolved.

        ## Open Items
        Unfinished tasks, open questions, and anything the user is waiting for.

        ## Current State
        What was being worked on right before this summary, and the next step if one is clear.
    """.trimIndent()
}
