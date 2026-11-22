package me.rerere.rikkihub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.DEFAULT_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ContextCheckpointKind
import me.rerere.ai.ui.ContextCheckpointMetadata
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.AutoCompressMode
import me.rerere.rikkihub.data.ai.CarryOriginalsMode
import me.rerere.rikkihub.data.ai.CompressionConfig
import me.rerere.rikkihub.data.ai.CompressionTemplate
import me.rerere.rikkihub.data.ai.CompressionTemplates
import me.rerere.rikkihub.data.ai.ConversationCompressionOverride
import me.rerere.rikkihub.data.ai.prompts.buildChunkFinalPrompt
import me.rerere.rikkihub.data.ai.prompts.buildChunkStagePrompt
import me.rerere.rikkihub.data.ai.prompts.buildCompressPrompt
import me.rerere.rikkihub.data.ai.prompts.builtInCompressStructure
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.appendClearContextCheckpoint
import me.rerere.rikkihub.data.model.toMessageNode
import me.rerere.tavern.token.TokenCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompressionPlanningTest {
    private fun user(text: String) = UIMessage.user(text)
    private fun reply(text: String, prompt: Int? = null, completion: Int = 0) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
        lastUsage = prompt?.let { TokenUsage(promptTokens = it, completionTokens = completion) },
        usageComplete = prompt != null,
    )
    private fun checkpoint(text: String) = UIMessage.user(text).copy(isContextCheckpoint = true)

    private fun conversation(vararg messages: UIMessage) = Conversation(
        id = Uuid.random(),
        assistantId = Uuid.random(),
        messageNodes = messages.map { it.toMessageNode() },
    )

    @Test
    fun `input budget subtracts the output reserve and a 5 percent margin`() {
        val model = Model(modelId = "my-private-model", contextLength = 100_000)
        assertEquals(100_000 - 8192 - 5_000, compressionInputBudget(model, null))
        assertEquals(100_000 - 4_000 - 5_000, compressionInputBudget(model, 4_000))
        // 输出预留最多占一半窗口，过大的 maxTokens 不会把预算压成负数
        assertEquals(100_000 - 50_000 - 5_000, compressionInputBudget(model, 900_000))
    }

    @Test
    fun `an unrecognised model on the default window has no budget, a recognised 1M model has one`() {
        assertNull(compressionInputBudget(Model(modelId = "totally-unknown-xyz", contextLength = DEFAULT_MODEL_CONTEXT_LENGTH), null))
        assertNotNull(compressionInputBudget(Model(modelId = "claude-opus-5", contextLength = DEFAULT_MODEL_CONTEXT_LENGTH), null))
        assertNull(compressionInputBudget(Model(modelId = "x", contextLength = 0), null))
    }

    @Test
    fun `usage is the last reported request plus the pending message, else a local estimate`() {
        val measured = conversation(user("hi"), reply("hello", prompt = 5_000, completion = 200))
        val usage = measured.contextUsage("next question")
        assertTrue(usage.exact)
        assertEquals(5_200 + TokenCounter.count("next question") + 4, usage.tokens)

        // 检查点之后的旧回复用量已过时：只估检查点及之后的消息
        val afterCheckpoint = conversation(user("a"), reply("b", prompt = 9_000), checkpoint("summary text"), user("new"))
        val estimated = afterCheckpoint.contextUsage()
        assertFalse(estimated.exact)
        assertEquals(TokenCounter.count("summary text") + TokenCounter.count("new") + 8, estimated.tokens)
    }

    @Test
    fun `fixed part is reported usage minus the estimated history, never negative`() {
        val c = conversation(user("hello there"), reply("general kenobi", prompt = 2_000, completion = 10))
        val history = TokenCounter.count("hello there") + TokenCounter.count("general kenobi") + 8
        assertEquals(2_010 - history, c.fixedContextTokens())
        assertEquals(0, conversation(user("x".repeat(400)), reply("y", prompt = 1)).fixedContextTokens())
        assertEquals(0, conversation(user("no usage")).fixedContextTokens())
    }

    @Test
    fun `auto keep shrinks until fixed plus summary plus kept fits the target, down to the minimum`() {
        val tail = listOf(100, 100, 100, 100, 100, 100)
        // 都放得下：保留偏好的条数
        assertEquals(6, autoKeepRecent(tail, preferred = 6, minKeep = 0, fixedTokens = 100, summaryTokens = 100, targetTokens = 1_000))
        // 只放得下 3 条
        assertEquals(3, autoKeepRecent(tail, preferred = 6, minKeep = 0, fixedTokens = 400, summaryTokens = 300, targetTokens = 1_000))
        // 怎么都放不下：退到最少
        assertEquals(1, autoKeepRecent(tail, preferred = 6, minKeep = 1, fixedTokens = 5_000, summaryTokens = 300, targetTokens = 1_000))
        // 偏好比现有节点多：按现有的算
        assertEquals(2, autoKeepRecent(listOf(10, 10), preferred = 6, minKeep = 0, fixedTokens = 0, summaryTokens = 0, targetTokens = 1_000))
        assertEquals(0, autoKeepRecent(emptyList(), preferred = 6, minKeep = 0, fixedTokens = 0, summaryTokens = 0, targetTokens = 1_000))
    }

    @Test
    fun `estimate counts the previous summary as summarized and bounds the new summary`() {
        val longText = "word ".repeat(2_000)
        val c = conversation(
            checkpoint("old summary"),
            user(longText),
            reply(longText, prompt = 6_000, completion = 100),
            user("keep me"),
        )
        val estimate = c.estimateCompression(keepRecent = 1, summaryTokens = 500, budget = 10_000)!!
        assertEquals(3, estimate.summarizedMessages)
        assertEquals(1, estimate.keptMessages)
        assertTrue(estimate.beforeExact)
        // 摘要按被总结部分的 10%–30% 估，但不超过上限 500
        assertTrue(estimate.afterMaxTokens - estimate.afterMinTokens <= 500)
        assertTrue(estimate.afterMaxTokens < estimate.beforeTokens)
        assertEquals(10_000, estimate.budgetTokens)
        assertNull(c.estimateCompression(keepRecent = 3, summaryTokens = 500, budget = null))
    }

    @Test
    fun `config is clamped and the conversation override wins only with a known template`() {
        val wild = CompressionConfig(triggerPercent = 200, targetPercent = 99, keepRecent = -3, summaryTokens = 1).normalized()
        assertEquals(CompressionConfig.MAX_TRIGGER_PERCENT, wild.triggerPercent)
        assertTrue(wild.targetPercent < wild.triggerPercent)
        assertEquals(0, wild.keepRecent)
        assertEquals(CompressionConfig.MIN_SUMMARY_TOKENS, wild.summaryTokens)

        val config = CompressionConfig(
            mode = AutoCompressMode.NOTIFY,
            templateId = CompressionTemplates.CODE,
            customTemplates = listOf(CompressionTemplate("mine", "Mine", "## A")),
        )
        assertEquals(config.mode, config.resolvedFor(null).mode)
        val resolved = config.resolvedFor(ConversationCompressionOverride(mode = AutoCompressMode.AUTO, templateId = "mine"))
        assertEquals(AutoCompressMode.AUTO, resolved.mode)
        assertEquals("mine", resolved.templateId)
        // 会话指向已删除的自定义模板：回到全局默认
        assertEquals(CompressionTemplates.CODE, config.resolvedFor(ConversationCompressionOverride(templateId = "gone")).templateId)
        assertTrue(ConversationCompressionOverride().isEmpty)
    }

    @Test
    fun `every template keeps the opening the mock and the summary parser rely on`() {
        val failures = (CompressionTemplates.BUILT_IN + "unknown").mapNotNull { id ->
            val prompt = buildCompressPrompt(builtInCompressStructure(id), focus = "", summaryTokens = 2048, locale = "English")
            when {
                !prompt.startsWith("Stop the current task and write a structured summary") -> "$id: opening"
                "[Summary of previous conversation]" !in prompt -> "$id: header"
                "about 2048 tokens" !in prompt -> "$id: length"
                "Additional instructions" in prompt -> "$id: empty focus leaked"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), failures)
        val custom = buildCompressPrompt("## Only This", focus = "keep the API keys out", summaryTokens = 512, locale = "中文")
        assertTrue("## Only This" in custom && "## User Goals" !in custom)
        assertTrue(custom.endsWith("Additional instructions from user: keep the API keys out"))
        assertEquals(builtInCompressStructure(CompressionTemplates.GENERAL), builtInCompressStructure("unknown"))
    }

    @Test
    fun `usage percent is relative to the budget`() {
        assertEquals(80, usagePercent(8_000, 10_000))
        assertNull(usagePercent(8_000, null))
    }

    @Test
    fun `carry originals plans recent items, keeps excluded slots, and edits copies only`() {
        val conv = conversation(
            user("u1"),
            reply("a1"),
            user("u2"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "hidden thought"),
                    UIMessagePart.Tool(toolCallId = "t1", toolName = "read", input = "{}"),
                    UIMessagePart.Text("visible reply 2"),
                ),
            ),
            user("u3"),
            reply("visible reply 3"),
            user("kept user"),
            reply("kept reply"),
        )
        val n1 = conv.messageNodes[0].id
        val n2 = conv.messageNodes[2].id
        val n3 = conv.messageNodes[4].id

        val off = planCarriedOriginals(conv, keepRecent = 2, fromOriginal = false, CarryOriginalsMode.OFF, count = 3, includeReplies = true)
        assertEquals(0, off.items.size)

        // RECENT 2 takes u2 & u3; excluding u3 keeps its slot so u1 is NOT pulled in
        val recentExcluded = planCarriedOriginals(
            conversation = conv,
            keepRecent = 2,
            fromOriginal = false,
            mode = CarryOriginalsMode.RECENT,
            count = 2,
            includeReplies = true,
            excludedNodeIds = setOf(n3),
            edits = mapOf(n2 to CarriedOriginalEdit(userText = "edited u2")),
        )
        assertEquals(1, recentExcluded.excludedCount)
        assertEquals(listOf(n2), recentExcluded.items.map { it.nodeId })
        assertEquals("edited u2", recentExcluded.items.single().userText)
        assertEquals("visible reply 2", recentExcluded.items.single().replyText)
        assertTrue(recentExcluded.items.single().edited)
        // 原消息内容未被修改
        assertEquals("u2", conv.messageNodes[2].currentMessage.toText())

        // 严格越过切点后即使后面有用户消息，也不越过切点带入
        assertFalse(recentExcluded.items.any { it.userText == "kept user" })

        val body = appendCarriedOriginals("## Summary\ndone", recentExcluded.items)
        assertTrue(CARRIED_ORIGINALS_HEADING in body)
        assertTrue("### 1. User\nedited u2" in body)
        assertTrue("### 1. Assistant\nvisible reply 2" in body)
        assertFalse("hidden thought" in body)
        assertFalse(appendCarriedOriginals("## Summary\ndone", emptyList()).contains(CARRIED_ORIGINALS_HEADING))

        // RECENT overBudget flag triggers when carried exceeds target remaining budget
        val tightRecent = planCarriedOriginals(
            conversation = conversation(user("long ".repeat(200))),
            keepRecent = 0,
            fromOriginal = false,
            mode = CarryOriginalsMode.RECENT,
            count = 1,
            includeReplies = false,
            summaryTokens = 200,
            targetPercent = 40,
            chatBudget = 500,
        )
        assertTrue(tightRecent.overBudget)
        assertFalse(n1 == n2)
    }

    @Test
    fun `auto carry mode fills from newest to oldest and stops at first unfit message`() {
        val conv = conversation(
            user("short old"),
            user("huge ".repeat(300)),
            user("newest fit"),
        )
        // targetTotal = 400 (80% of 500), summaryReserve ~ ceiling(summarized*0.3) capped at summaryTokens=80 -> remaining ~304t
        val plan = planCarriedOriginals(
            conversation = conv,
            keepRecent = 0,
            fromOriginal = false,
            mode = CarryOriginalsMode.AUTO,
            count = 10,
            includeReplies = false,
            summaryTokens = 80,
            targetPercent = 80,
            chatBudget = 500,
        )
        // Only "newest fit" fits; hits "huge ..." and stops (so "short old" is also skippedForBudget!)
        assertEquals(listOf("newest fit"), plan.items.map { it.userText })
        assertEquals(2, plan.skippedForBudget)
        assertFalse(plan.overBudget)
    }

    @Test
    fun `chunk planner splits along message boundaries, handles surrogate pairs, and ends with final template`() {
        val emoji = "\uD83D\uDE00" // 4-byte 😀 surrogate pair (length 2 in UTF-16)
        assertEquals(0, utf16SafeEnd(emoji, 0))
        assertEquals(0, utf16SafeEnd(emoji, 1))
        assertEquals(2, utf16SafeEnd(emoji, 2))
        assertEquals(1, utf16SafeEnd("a$emoji", 2))

        val msgs = (1..5).map { "[Message #$it | User]\n" + "token ".repeat(60) + "m$it" }
        val planner = ChunkCompressionPlanner(
            materials = msgs,
            summaryTokens = 100,
            renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
            renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Custom Final", prev, seg, "", t, "English") },
        )
        val first = planner.nextSegment(0, 0, "", budget = 350)
        assertFalse(first.isFinal)
        assertTrue(first.nextIndex in 1 until msgs.size)
        assertEquals(0, first.nextOffset)
        assertTrue(first.prompt.startsWith("Write an updated running summary"))

        val est = planner.estimateChunkCount("", budget = 350)
        assertTrue("expected multi-chunk plan, got $est", est >= 2)

        // Single huge message forces offset splitting and completes with final segment
        val bigSingle = listOf("[Message #1 | User]\n" + "line one two three four five.\n".repeat(40))
        val singlePlanner = ChunkCompressionPlanner(
            materials = bigSingle,
            summaryTokens = 80,
            renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
            renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Final", prev, seg, "", t, "English") },
        )
        val seg1 = singlePlanner.nextSegment(0, 0, "", budget = 350)
        assertFalse(seg1.isFinal)
        assertTrue(seg1.nextOffset > 0 || seg1.nextIndex == 1)

        // Oversized previousSummary gets folded into materials
        val giantPrev = "history ".repeat(400)
        val (foldedMaterials, foldedPrev) = prepareChunkingInputs(
            materials = listOf("[Message #1 | User]\nhi"),
            previousSummary = giantPrev,
            summaryTokens = 80,
            budget = 350,
            renderStage = { prev, seg, t -> buildChunkStagePrompt(prev, seg, "", t, "English") },
            renderFinal = { prev, seg, t -> buildChunkFinalPrompt("## Final", prev, seg, "", t, "English") },
        )
        assertEquals("", foldedPrev)
        assertTrue(foldedMaterials.first().startsWith("[Previous summary]"))
    }

    @Test
    fun `estimate reports willChunk and willBreakCache and config clamps batch 2 bounds`() {
        val conv = conversation(
            checkpoint("prev"),
            user("a ".repeat(150)),
            reply("b ".repeat(150)),
        )
        val normalSmall = conv.estimateCompression(
            keepRecent = 0,
            summaryTokens = 200,
            budget = 20_000,
            compressionBudget = 200,
            maxChunkRequests = 8,
        )!!
        assertTrue(normalSmall.willChunk)
        assertTrue(normalSmall.estimatedChunks > 1)
        assertTrue(normalSmall.willBreakCache)
        assertTrue(normalSmall.hasPreviousCheckpoint)

        val fromOriginalEst = conv.estimateCompression(
            keepRecent = 0,
            summaryTokens = 200,
            budget = 20_000,
            fromOriginal = true,
        )!!
        assertTrue(fromOriginalEst.fromOriginal)
        assertTrue(fromOriginalEst.willBreakCache)
        assertEquals(2, fromOriginalEst.summarizedMessages) // skips previous checkpoint

        val clamped = CompressionConfig(
            carryOriginalsCount = 999,
            maxChunkRequests = 1,
            chunkTimeoutSeconds = 5,
        ).normalized()
        assertEquals(CompressionConfig.MAX_CARRY_COUNT, clamped.carryOriginalsCount)
        assertEquals(CompressionConfig.MIN_MAX_CHUNK_REQUESTS, clamped.maxChunkRequests)
        assertEquals(CompressionConfig.MIN_CHUNK_TIMEOUT_SECONDS, clamped.chunkTimeoutSeconds)
    }

    @Test
    fun `clear context, context records, cascade revoke, stale message detection, and new conv copy work`() {
        val m1 = UIMessage.user("u1").toMessageNode()
        val m2 = UIMessage.assistant("a1").toMessageNode()
        val cp1 = UIMessage.user("summary 1").copy(
            isContextCheckpoint = true,
            checkpointMeta = ContextCheckpointMetadata(
                kind = ContextCheckpointKind.MANUAL,
                templateId = "general",
                rangeStartNodeId = m1.id,
                rangeEndNodeId = m2.id,
                summarizedCount = 2,
            ),
        ).toMessageNode()
        val m3 = UIMessage.user("u2").toMessageNode()
        val conv = Conversation(
            assistantId = Uuid.random(),
            title = "Story",
            messageNodes = listOf(m1, m2, cp1, m3),
            customSystemPrompt = "Keep brief",
        )
        assertTrue(canClearContext(conv.messageNodes))

        val cleared = conv.appendClearContextCheckpoint(
            ContextCheckpointMetadata(
                kind = ContextCheckpointKind.CLEAR,
                rangeStartNodeId = m3.id,
                rangeEndNodeId = m3.id,
                summarizedCount = 1,
                supersededCheckpointNodeId = cp1.id,
            )
        )
        assertFalse(canClearContext(cleared.messageNodes))
        assertTrue(cleared.messageNodes.last().currentMessage.isClearContextCheckpoint)

        val records = listContextRecords(cleared.messageNodes)
        assertEquals(2, records.size)
        assertFalse(records[0].isActive)
        assertEquals(listOf(cleared.messageNodes.last().id), records[0].laterCheckpointNodeIds)
        assertEquals(2, records[0].coveredPreviews.size)
        assertTrue(records[1].isActive)
        assertTrue(records[1].isClear)
        assertEquals(1, records[1].coveredMessageCount)

        // Stale message checks skip checkpoints themselves and list all later checkpoints
        assertEquals(listOf(cp1, cleared.messageNodes.last()), affectedCheckpointsForMessage(cleared.messageNodes, m1.currentMessage.id))
        assertEquals(listOf(cleared.messageNodes.last()), affectedCheckpointsForMessage(cleared.messageNodes, m3.currentMessage.id))
        assertTrue(affectedCheckpointsForMessage(cleared.messageNodes, cp1.currentMessage.id).isEmpty())

        // Single revoke keeps later checkpoint; cascade revoke removes both
        val (afterSingle, removedSingle) = revokeCheckpointNodes(cleared.messageNodes, cp1.id, cascadeLater = false)!!
        assertEquals(listOf(cp1.id), removedSingle)
        assertEquals(4, afterSingle.size)

        val (afterCascade, removedCascade) = revokeCheckpointNodes(cleared.messageNodes, cp1.id, cascadeLater = true)!!
        assertEquals(listOf(cp1.id, cleared.messageNodes.last().id), removedCascade)
        assertEquals(3, afterCascade.size)

        // New compressed conversation copies tavern/compression metadata cleanly
        val copied = createCompressedConversation(conv, listOf(cp1, m3), existingTitles = setOf("Story", "Story(1)"))
        assertEquals("Story(2)", copied.title)
        assertEquals("Keep brief", copied.customSystemPrompt)
        assertNull(copied.cliSession)

        // Draft estimation includes text + attachment overhead
        val draftTokens = estimateDraftPartsTokens(
            listOf(UIMessagePart.Text("hello world"), UIMessagePart.Image("file:///a.png")),
            readDocument = { "" },
        )
        assertTrue(draftTokens > 1000)
        assertEquals(0, estimateDraftPartsTokens(emptyList()) { "" })
    }
}
