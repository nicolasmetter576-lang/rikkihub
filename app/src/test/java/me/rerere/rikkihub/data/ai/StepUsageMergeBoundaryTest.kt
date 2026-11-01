package me.rerere.rikkihub.data.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * [mergeStepUsage] 边界矩阵 (修正后固化):
 * provider 在同一 request 内补发「修正版」usage 快照时, 后续的有效 total
 * 是**最新快照值**, 不是历史 max——先报 105 再修正为 20, 结果必须是 20。
 * 同时固化必须保留的语义:
 * - total-only 分片 (p=c=0) 不被 p+c 重算归零
 * - 空/无新 total 的分片不覆盖、不归零在案 total
 * - partial p/c 分片累计: p=50(t=50) → c=15(t=15) 合并是 65, 不是 15
 * - `total > p+c` 的独立上报口径保留
 * - 失败 attempt 的 usage 在重试后丢弃, 只计成功 attempt
 */
class StepUsageMergeBoundaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private inner class StreamHarness(attempts: List<ScriptedAttempt>) {
        val provider = ScriptedProvider(attempts = ArrayDeque(attempts))
        val model = testChatModel()
        val settings: Settings = testSettings(model)
        val loop = testGenerationLoop(provider, FakeContext(tempFolder.newFolder()))
    }

    private fun List<GenerationChunk>.steps() = filterIsInstance<GenerationChunk.StepFinished>()

    // ---------------------------------------------------------------
    // helper 级: total-only 保留 + 后续非零 total 是最新快照, 不是历史 max
    // ---------------------------------------------------------------
    @Test
    fun `step usage keeps total only shard and takes latest non-zero snapshot not historical max`() {
        // 保留项 1: total-only 分片 (p=c=0) 不能被 p+c 重算归零
        val totalOnly = mergeStepUsage(null, TokenUsage(0, 0, totalTokens = 55))
        assertEquals(55, totalOnly.totalTokens)

        // 保留项 2: 后续 0 不覆盖已得值 (sharded 上报形态)
        val accumulated = mergeStepUsage(totalOnly, TokenUsage(40, 0, totalTokens = 0))
        assertEquals(55, accumulated.totalTokens)

        // 修正项: 同一 request 内后续非零 total 是最新快照。
        // provider 先报 105, 之后修正为 20 → 步统计必须是 20, 不能抬回历史 max 105。
        val corrected = mergeStepUsage(
            TokenUsage(100, 5, totalTokens = 105),
            TokenUsage(0, 0, totalTokens = 20),
        )
        assertEquals(20, corrected.totalTokens)

        // 同向固化: p/c 正值覆盖语义不受影响
        assertEquals(100, corrected.promptTokens)
        assertEquals(5, corrected.completionTokens)
    }

    // ---------------------------------------------------------------
    // 真实流链路: step 内 usage 快照 105 → 修正 20, StepFinished 取 20
    // ---------------------------------------------------------------
    @Test
    fun `corrected usage snapshot in same step wins over inflated earlier shard`() = runBlocking {
        val harness = StreamHarness(
            listOf(
                ScriptedAttempt(
                    listOf(
                        StreamChunk.ToolCallStart(id = "u-fix", toolName = "echo_tool"),
                        StreamChunk.ToolCallDelta(id = "u-fix", inputDelta = "{}"),
                        StreamChunk.ToolCallEnd(id = "u-fix"),
                        // 先报一个被高估的完整快照
                        StreamChunk.Usage(TokenUsage(100, 5, totalTokens = 105)),
                        // provider 补发修正快照 (真实网关存在该形态)
                        StreamChunk.Usage(TokenUsage(0, 0, totalTokens = 20)),
                        StreamChunk.Finish(finishReason = "tool_calls"),
                    )
                ),
                textStepScript("done"),
            )
        )
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            tools = listOf(echoTool()),
            maxSteps = 10,
        ).toList()

        // 步 usage 取修正后的最新快照 20, 不是历史 max 105
        // (第一步含修正快照; 第二步纯文本无 usage, 断言取第一步)
        val steps = chunks.steps()
        assertEquals(2, steps.size)
        assertEquals(20, steps[0].usage?.totalTokens)
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(20L, stats.totalTokens)
    }

    // ---------------------------------------------------------------
    // helper 级矩阵: 完整 p/c 快照允许向下修正 (含默认 total 形态)
    // ---------------------------------------------------------------
    @Test
    fun `complete prompt completion snapshot may correct downwards`() {
        // p=100 c=5 total=105 → p=10 c=10 total=20: 完整快照向下修正为 20
        val corrected = mergeStepUsage(
            TokenUsage(100, 5, totalTokens = 105),
            TokenUsage(10, 10, totalTokens = 20),
        )
        assertEquals(20, corrected.totalTokens)
        assertEquals(10, corrected.promptTokens)
        assertEquals(10, corrected.completionTokens)

        // total 缺省 (TokenUsage 默认 0) 的完整 p/c 快照同样是完整快照,
        // total 取新快照 p+c = 20 (与显式上报 20 同逻辑, 不可区分)
        val derivedCorrected = mergeStepUsage(
            TokenUsage(100, 5, totalTokens = 105),
            TokenUsage(10, 10),
        )
        assertEquals(20, derivedCorrected.totalTokens)
    }

    // ---------------------------------------------------------------
    // helper 级: total-only 快照也允许向下修正
    // ---------------------------------------------------------------
    @Test
    fun `total only snapshot may correct downwards`() {
        val corrected = mergeStepUsage(
            TokenUsage(0, 0, totalTokens = 105),
            TokenUsage(0, 0, totalTokens = 20),
        )
        assertEquals(20, corrected.totalTokens)
    }

    // ---------------------------------------------------------------
    // helper 级: partial p/c 分片累计, total 只作自身分片的派生值时
    // 不能抹去另一轴的累计 (反例: 机械 other.total>0 ?: old 会得 15)
    // ---------------------------------------------------------------
    @Test
    fun `partial prompt completion shards accumulate across axes`() {
        val afterPrompt = mergeStepUsage(null, TokenUsage(50, 0, totalTokens = 50))
        val accumulated = mergeStepUsage(afterPrompt, TokenUsage(0, 15, totalTokens = 15))
        assertEquals(50, accumulated.promptTokens)
        assertEquals(15, accumulated.completionTokens)
        assertEquals(65, accumulated.totalTokens)
    }

    // ---------------------------------------------------------------
    // helper 级: 连续完整 p/c 快照覆盖不累加
    // ---------------------------------------------------------------
    @Test
    fun `successive full snapshots override rather than accumulate`() {
        var usage = mergeStepUsage(null, TokenUsage(100, 50, totalTokens = 150))
        usage = mergeStepUsage(usage, TokenUsage(80, 40, totalTokens = 120))
        usage = mergeStepUsage(usage, TokenUsage(60, 30, totalTokens = 90))
        assertEquals(60, usage.promptTokens)
        assertEquals(30, usage.completionTokens)
        assertEquals(90, usage.totalTokens)
    }

    // ---------------------------------------------------------------
    // helper 级: 空 usage 不覆盖; 全零 usage chunk 不抬出虚假 total
    // ---------------------------------------------------------------
    @Test
    fun `empty usage shards do not overwrite standing values`() {
        val base = mergeStepUsage(null, TokenUsage(10, 5, totalTokens = 15))
        val afterZero = mergeStepUsage(base, TokenUsage())
        assertEquals(10, afterZero.promptTokens)
        assertEquals(5, afterZero.completionTokens)
        assertEquals(15, afterZero.totalTokens)

        // 独立 total 也不被全零分片归零
        val reported = mergeStepUsage(null, TokenUsage(0, 0, totalTokens = 105))
        assertEquals(105, mergeStepUsage(reported, TokenUsage()).totalTokens)

        // 空 current + 全零 other 不产生正 total
        assertEquals(0, mergeStepUsage(null, TokenUsage()).totalTokens)
    }

    // ---------------------------------------------------------------
    // helper 级: total>p+c 独立口径, 后续分片不改变在案上报
    // ---------------------------------------------------------------
    @Test
    fun `independent total survives later partial shards`() {
        val base = mergeStepUsage(null, TokenUsage(40, 15, totalTokens = 200))
        assertEquals(200, base.totalTokens)

        // total 缺失的分片: 只补 p/c, 不重置在案独立 total
        val filled = mergeStepUsage(base, TokenUsage(50, 0, totalTokens = 0))
        assertEquals(50, filled.promptTokens)
        assertEquals(200, filled.totalTokens)

        // total 等于自身分片派生值的分片同样不能改写独立口径
        val ambiguous = mergeStepUsage(base, TokenUsage(60, 0, totalTokens = 60))
        assertEquals(60, ambiguous.promptTokens)
        assertEquals(200, ambiguous.totalTokens)

        // 新的独立上报 (或修正) 则取最新值, 允许向下
        val corrected = mergeStepUsage(base, TokenUsage(0, 0, totalTokens = 80))
        assertEquals(80, corrected.totalTokens)
        assertEquals(40, corrected.promptTokens)
        assertEquals(15, corrected.completionTokens)
    }

    // ---------------------------------------------------------------
    // 真实流链路: 失败 attempt 上报的 usage 在重试后被丢弃, 只计成功 attempt
    // ---------------------------------------------------------------
    @Test
    fun `failed attempt usage is discarded after retry`() = runBlocking {
        val harness = StreamHarness(
            listOf(
                ScriptedAttempt(
                    listOf(
                        StreamChunk.TextStart(id = "t"),
                        StreamChunk.TextDelta(id = "t", text = "partial"),
                        StreamChunk.Usage(TokenUsage(999, 999, totalTokens = 1998)),
                    ),
                    errorAfter = IOException("connection reset"),
                ),
                textStepScript("full reply", usage = TokenUsage(40, 20, totalTokens = 60)),
            )
        )
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            tools = emptyList(),
            maxSteps = 10,
        ).toList()

        // 两次 attempt, 但只成功一次: 恰好一个 StepFinished 且取成功的 60
        assertEquals(2, harness.provider.streamAttempts.size)
        val steps = chunks.steps()
        assertEquals(1, steps.size)
        assertEquals(60, steps.single().usage?.totalTokens)
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertEquals(60L, stats.totalTokens)
    }

    // ---------------------------------------------------------------
    // 真实流链路: 全部步无 usage 时 StepFinished.usage=null, 统计 total=null
    // ---------------------------------------------------------------
    @Test
    fun `no usage anywhere yields null step usage and null total`() = runBlocking {
        val harness = StreamHarness(
            listOf(
                toolStepScript("no-usage", "echo_tool", usage = null),
                textStepScript("done", usage = null),
            )
        )
        val chunks = harness.loop.generateText(
            settings = harness.settings,
            model = harness.model,
            messages = listOf(UIMessage.user("hi")),
            assistant = testAssistant(),
            tools = listOf(echoTool()),
            maxSteps = 10,
        ).toList()

        val steps = chunks.steps()
        assertEquals(2, steps.size)
        steps.forEach { assertNull(it.usage) }
        val stats = GenerationRunStats().also { s -> chunks.forEach(s::onChunk) }
        assertNull(stats.totalTokens)
    }
}
