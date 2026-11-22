package me.rerere.rikkihub.data.ai.qubagu

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.FakeContext
import me.rerere.rikkihub.data.ai.transformers.TransformerContext
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.tavern.regex.QuickJsRegexRuntime
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.uuid.Uuid

class QubaguRefineTest {
    companion object {
        private val runtime = QuickJsRegexRuntime()

        @AfterClass
        @JvmStatic
        fun close() = runtime.close()
    }

    private data class Call(val model: String, val messages: List<UIMessage>, val params: TextGenerationParams)

    /** 把 [reply] 当作模型的回复流出来；[failure] 非空时请求直接失败；[delayMillis] 模拟慢模型。 */
    private class ScriptProvider(
        private val reply: String,
        private val failure: Exception? = null,
        private val delayMillis: Long = 0,
    ) : Provider<ProviderSetting> {
        val calls = ConcurrentLinkedQueue<Call>()

        override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult =
            error("not used")

        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            calls += Call(params.model.modelId, messages, params)
            failure?.let { throw it }
            delay(delayMillis)
            emit(StreamChunk.TextStart(id = "t"))
            reply.chunked(7).forEach { emit(StreamChunk.TextDelta(id = "t", text = it)) }
            emit(StreamChunk.TextEnd(id = "t"))
            emit(StreamChunk.Finish(finishReason = "stop"))
        }

        override suspend fun generateEmbedding(providerSetting: ProviderSetting, params: EmbeddingGenerationParams): EmbeddingGenerationResult =
            error("not used")
    }

    private val engine = Qubagu(runtime)
    private val replyModel = Model(modelId = "reply-model")
    private val ownModel = Model(modelId = "refine-model")
    private val assistant = Assistant(chatModelId = replyModel.id)

    private val base = QubaguConfig(
        enabled = true,
        packs = listOf(
            QubaguRulePack(
                id = "mine",
                rules = listOf(
                    QubaguRule.Replace(id = "throat", name = "喉结", find = "喉结滚动", replacements = listOf("顿了顿")),
                    QubaguRule.Replace(id = "shock", name = "心头", find = "心头一震", replacements = listOf("愣了一下")),
                ),
            ),
        ),
    )

    /** 原来内置、现在作为规则文件提供的规则。 */
    private val file by lazy { QubaguRulesJson.decode(checkNotNull(javaClass.getResource("/qubagu/rules-v1.json")).readText()) }

    private fun filePack(id: String, prefix: String = "") =
        file.single { it.id == id }.let { p -> p.copy(enabled = true, rules = p.rules.filter { it.id.startsWith(prefix) }) }

    private fun QubaguConfig.with(vararg rules: QubaguRule) = copy(packs = packs + QubaguRulePack(id = "extra", rules = rules.toList()))
    private fun QubaguConfig.with(pack: QubaguRulePack) = copy(packs = packs + pack)

    private fun settings(config: QubaguConfig = base, assistant: Assistant = this.assistant) = Settings(
        providers = listOf(ProviderSetting.OpenAI(apiKey = "k", baseUrl = "http://localhost", models = listOf(replyModel, ownModel))),
        assistants = listOf(assistant),
        assistantId = assistant.id,
        qubagu = config,
    )

    private fun refiner(provider: ScriptProvider): QubaguRefiner {
        val context = FakeContext(Files.createTempDirectory("qubagu").toFile())
        return QubaguRefiner(engine, ProviderManager(OkHttpClient(), context).apply { registerProvider("openai", provider) })
    }

    private fun targets(text: String, config: QubaguConfig = base) = runBlocking {
        engine.refineTargets(text, engine.clean(text, config), config)
    }

    // ---- 要重写哪些句子 ----

    @Test
    fun targetsGrowToTheWholeSentence() {
        val text = "门开了。他喉结滚动，没有说话。她走了进来。"
        val target = targets(text).single()
        assertEquals("他喉结滚动，没有说话。", target.sentence)
        assertEquals("他喉结滚动，没有说话。", text.substring(target.start, target.end))
        assertEquals(listOf("喉结滚动"), target.phrases)
    }

    @Test
    fun targetsStopAtDialogueAndLineBreaks() {
        assertEquals(listOf("他喉结滚动，说："), targets("他喉结滚动，说：“走吧。”").map { it.sentence })
        assertEquals(listOf("他喉结滚动"), targets("第一段\n他喉结滚动\n第三段").map { it.sentence })
    }

    @Test
    fun hitsInOneSentenceShareATarget() {
        val target = targets("他喉结滚动，心头一震。").single()
        assertEquals("他喉结滚动，心头一震。", target.sentence)
        assertEquals(listOf("喉结滚动", "心头一震"), target.phrases)
        assertEquals(2, targets("他喉结滚动。她心头一震。").size)
    }

    @Test
    fun targetsShowQuotaAndPunctuationEditsAlreadyApplied() {
        val config = base.with(QubaguRule.Quota(id = "q", words = listOf("缓缓"), max = 0)).with(filePack("punctuation"))
        val target = targets("他缓缓抬头，喉结滚动…………", config).single()
        assertEquals("他抬头，喉结滚动……", target.sentence)
        assertEquals(listOf("喉结滚动"), target.phrases)
    }

    @Test
    fun onlyWordAndNegationHitsAreRefined() {
        val config = base.with(QubaguRule.Quota(id = "q", words = listOf("缓缓"), max = 0))
            .with(filePack("sentence", "finale_")).with(filePack("punctuation")).copy(merge = true)
        assertTrue(targets("他缓缓抬头…………\n\n沉默。\n寂静。\n\n门开了，夜还很长。", config).isEmpty())
        assertEquals(listOf("那不是愤怒，而是执念。"), targets("那不是愤怒，而是执念。", base.with(filePack("sentence", "negation_"))).map { it.sentence })
    }

    @Test
    fun targetsSkipOverlongSentencesAndHitsInsideMergedRuns() {
        assertTrue(targets("他喉结滚动，" + "走".repeat(Qubagu.MAX_SENTENCE_CHARS)).isEmpty())
        val config = base.copy(merge = true).with(QubaguRule.Replace(find = "寂静", replacements = listOf("安静")))
        assertTrue(targets("沉默。\n寂静。", config).isEmpty())
    }

    @Test
    fun hitsInsideDialogueAreNotRefined() {
        val config = QubaguConfig(packs = listOf(QubaguRulePack(id = "p", rules = listOf(QubaguRule.Replace(find = "喉结滚动", replacements = listOf("顿了顿"), dialogue = true)))))
        assertTrue(targets("“喉结滚动。”", config).isEmpty())
    }

    @Test
    fun targetsCarryTheRulesThatFlaggedThem() {
        val target = targets("他喉结滚动，心头一震。").single()
        assertEquals(listOf("mine/throat", "mine/shock"), target.rules)
    }

    // ---- 每条规则的精修提示词 ----

    private val prompted = base.copy(
        packs = listOf(
            QubaguRulePack(
                id = "mine",
                rules = listOf(
                    QubaguRule.Replace(id = "throat", name = "喉结", find = "喉结滚动", replacements = listOf("顿了顿"), refinePrompt = "  写成具体的动作  "),
                    QubaguRule.Replace(id = "shock", name = "心头", find = "心头一震", replacements = listOf("愣了一下"), refinePrompt = "   "),
                    QubaguRule.Replace(id = "eyes", find = "瞳孔地震", replacements = listOf("一愣"), refinePrompt = "没命中，不该出现"),
                ),
            ),
        ),
    )

    @Test
    fun refineAppendsThePromptsOfTheRulesThatHitOnly() = runBlocking {
        val provider = ScriptProvider("""{"1":"他张了张嘴，愣在那里。"}""")
        val text = "他喉结滚动，心头一震。"
        refiner(provider).refine(text, engine.clean(text, prompted), settings(prompted), fallback = replyModel)
        val system = provider.calls.single().messages[0].toText()
        assertTrue(system, "\n\n【这些套话的改写要求】\n- 喉结：写成具体的动作\n\n" in system)
        assertFalse(system, "心头：" in system)
        assertFalse(system, "不该出现" in system)
        assertTrue(system, system.indexOf("【这些套话的改写要求】") < system.indexOf("只输出一个 JSON 对象"))
    }

    @Test
    fun systemPromptSkipsDeletedRulesAndRepeatsNothing() {
        val system = QubaguRefine.systemPrompt(prompted, listOf("mine/throat", "gone/x", "mine/throat", "eyes"), "FORMAT")
        assertEquals(QubaguRefine.DEFAULT_PROMPT + "\n\n【这些套话的改写要求】\n- 喉结：写成具体的动作\n\nFORMAT", system)
        val unnamed = prompted.copy(packs = prompted.packs.map { p -> p.copy(rules = p.rules.map { (it as QubaguRule.Replace).copy(name = "") }) })
        assertTrue(QubaguRefine.systemPrompt(unnamed, listOf("mine/throat"), "F").contains("- throat：写成具体的动作"))
    }

    // ---- 工作区文件里一句的 AI 建议 ----

    private val fileText = "门开了。他喉结滚动，没有说话。她走了进来。"
    private val fileMark by lazy { runBlocking { engine.annotate(fileText, prompted) }.single() }

    @Test
    fun suggestSendsTheSentenceWithContextAndKeepsUsableRewrites() = runBlocking {
        val provider = ScriptProvider("""{"2":"他张了张嘴，没有说话。","1":"他抿着唇，没有说话。","3":"他喉结滚动着，没有说话。","4":"多余的"}""")
        val suggestions = refiner(provider).suggest(fileText, fileMark, settings(prompted), fallback = replyModel)
        // 按编号排序；还带着套话的、多出来的不要
        assertEquals(listOf("他抿着唇，没有说话。", "他张了张嘴，没有说话。"), suggestions)
        val call = provider.calls.single()
        assertEquals("reply-model", call.model)
        val system = call.messages[0].toText()
        assertTrue(system, "给出三种不同的重写" in system && "- 喉结：写成具体的动作" in system)
        val request = call.messages[1].toText()
        assertTrue(request, "【上下文】\n$fileText" in request)
        assertTrue(request, "【需要重写的句子】\n他喉结滚动，没有说话。\n套话：「喉结滚动」" in request)
    }

    @Test
    fun suggestCutsTheContextAroundLongFiles() = runBlocking {
        val before = "前".repeat(QubaguRefine.ALTERNATIVE_CONTEXT_CHARS + 500)
        val after = "后".repeat(QubaguRefine.ALTERNATIVE_CONTEXT_CHARS + 500)
        val text = "$before。他喉结滚动。$after"
        val mark = engine.annotate(text, prompted).single()
        val provider = ScriptProvider("""{"1":"他抿着唇。"}""")
        refiner(provider).suggest(text, mark, settings(prompted), fallback = replyModel)
        val context = provider.calls.single().messages[1].toText().substringAfter("【上下文】\n").substringBefore("\n\n【需要重写的句子】")
        assertEquals(QubaguRefine.ALTERNATIVE_CONTEXT_CHARS * 2 + mark.original.length, context.length)
        assertTrue(context.startsWith("前") && context.endsWith("后"))
    }

    @Test
    fun suggestReportsWhyThereIsNothing() {
        fun reason(provider: ScriptProvider, fallback: Model? = replyModel) = try {
            runBlocking { refiner(provider).suggest(fileText, fileMark, settings(prompted), fallback) }
            null
        } catch (e: QubaguRefineException) {
            e.reason
        }
        assertEquals(QubaguRefineException.Reason.NO_REWRITES, reason(ScriptProvider("""{"1":"他喉结滚动，没有说话。"}""")))
        assertEquals(QubaguRefineException.Reason.NO_REWRITES, reason(ScriptProvider("看不懂")))
        assertEquals(QubaguRefineException.Reason.NO_MODEL, reason(ScriptProvider("{}"), fallback = null))
    }

    // ---- 解析与校验 ----

    @Test
    fun parseReadsTheJsonObjectOutOfTheReply() {
        assertEquals(mapOf(1 to "甲", 2 to "乙"), QubaguRefine.parse("""{"1":"甲","2":"乙"}"""))
        assertEquals(mapOf(1 to "甲"), QubaguRefine.parse("好的：\n```json\n{\"1\": \"甲\"}\n```\n以上。"))
        assertEquals(mapOf(1 to "甲"), QubaguRefine.parse("<think>先想 {\"9\":\"错\"}</think>{\"1\":\"甲\"}"))
        assertEquals(mapOf(2 to "乙"), QubaguRefine.parse("""{"x":"甲","2":"乙","3":4,"4":null,"5":["丙"]}"""))
    }

    @Test
    fun parseGivesUpQuietlyOnGarbage() {
        for (output in listOf("", "抱歉，我做不到。", "{", "}{", "{\"1\":\"甲\"", "[\"甲\"]")) {
            assertTrue(output, QubaguRefine.parse(output).isEmpty())
        }
    }

    private val target = QubaguRefineTarget(0, 11, "他喉结滚动，没有说话。", listOf("喉结滚动"), listOf("mine/throat"))

    @Test
    fun acceptsOnlyUsableRewrites() {
        assertTrue(QubaguRefine.accepts(target, "他张了张嘴，没有说话。"))
        assertFalse("还留着套话", QubaguRefine.accepts(target, "他喉结滚动了一下，没说话。"))
        assertFalse("换行", QubaguRefine.accepts(target, "他张了张嘴，\n没有说话。"))
        assertFalse("没改", QubaguRefine.accepts(target, "他喉结滚动，没有说话。"))
        assertFalse("空", QubaguRefine.accepts(target, "  "))
        assertFalse("太长", QubaguRefine.accepts(target, "他" + "张了张嘴".repeat(12)))
    }

    @Test
    fun applyReplacesTheSentenceEditsWithOneAiEdit() {
        val text = "门开了。他喉结滚动，心头一震。她走了进来，喉结滚动。"
        val local = runBlocking { engine.clean(text, base) }
        val found = runBlocking { engine.refineTargets(text, local, base) }
        val refined = QubaguRefine.apply(text, local, found, mapOf(1 to " 他张了张嘴，半天没出声。 ", 2 to "她走了进来，喉结滚动。"))
        assertEquals(
            listOf(QubaguRules.AI to "他张了张嘴，半天没出声。", local.edits.last().rule to "顿了顿"),
            refined.edits.map { it.rule to it.after },
        )
        val rendered = refined.render(text)
        assertEquals("门开了。他张了张嘴，半天没出声。她走了进来，顿了顿。", rendered)
        assertEquals(text, refined.original(rendered))
        assertEquals("门开了。他喉结滚动，心头一震。她走了进来，顿了顿。", refined.withApplied(0, false).render(text))
    }

    // ---- 请求 ----

    @Test
    fun refineSendsContextAndFlaggedSentencesWithoutAssistantPromptOrTools() = runBlocking {
        val provider = ScriptProvider("""{"1":"他张了张嘴，没有说话。"}""")
        val text = "门开了。他喉结滚动，没有说话。"
        val refined = refiner(provider).refine(text, engine.clean(text, base), settings(), fallback = replyModel)
        assertEquals("门开了。他张了张嘴，没有说话。", refined.render(text))
        val call = provider.calls.single()
        assertEquals("reply-model", call.model)
        assertEquals(ReasoningLevel.OFF, call.params.reasoningLevel)
        assertTrue(call.params.tools.isEmpty())
        assertEquals(listOf(MessageRole.SYSTEM, MessageRole.USER), call.messages.map { it.role })
        val system = call.messages[0].toText()
        assertTrue(system, system.startsWith(QubaguRefine.DEFAULT_PROMPT + "\n\n"))
        assertTrue(system, "只输出一个 JSON 对象" in system)
        assertFalse("没有规则提示词时不加这一节", "【这些套话的改写要求】" in system)
        val request = call.messages[1].toText()
        assertTrue(request, "【正文】\n$text" in request)
        assertTrue(request, "1. 原句：他喉结滚动，没有说话。\n   套话：「喉结滚动」" in request)
    }

    @Test
    fun refineUsesItsOwnModelAndPromptWhenConfigured() = runBlocking {
        val provider = ScriptProvider("""{"1":"他张了张嘴。"}""")
        val config = base.copy(refineModelId = ownModel.id, refinePrompt = "自定义提示词")
        refiner(provider).refine("他喉结滚动。", engine.clean("他喉结滚动。", config), settings(config), fallback = replyModel)
        assertEquals("refine-model", provider.calls.single().model)
        val system = provider.calls.single().messages[0].toText()
        assertTrue(system, system.startsWith("自定义提示词\n\n") && "只输出一个 JSON 对象" in system)
    }

    @Test
    fun refineFallsBackWhenItsModelWasDeleted() = runBlocking {
        val provider = ScriptProvider("""{"1":"他张了张嘴。"}""")
        val config = base.copy(refineModelId = Uuid.random())
        refiner(provider).refine("他喉结滚动。", engine.clean("他喉结滚动。", config), settings(config), fallback = replyModel)
        assertEquals("reply-model", provider.calls.single().model)
    }

    @Test
    fun refineLeavesLongRepliesOutOfTheRequest() = runBlocking {
        val provider = ScriptProvider("""{"1":"他张了张嘴。"}""")
        val text = "他喉结滚动。" + "\n这一行不是套话。".repeat(QubaguRefine.MAX_CONTEXT_CHARS / 8)
        refiner(provider).refine(text, engine.clean(text, base), settings(), fallback = replyModel)
        val request = provider.calls.single().messages[1].toText()
        assertFalse("【正文】" in request)
        assertTrue("1. 原句：他喉结滚动。" in request)
    }

    private fun refineFailure(provider: ScriptProvider, text: String, fallback: Model? = replyModel): QubaguRefineException.Reason? = runBlocking {
        try {
            refiner(provider).refine(text, engine.clean(text, base), settings(), fallback)
            fail("expected a QubaguRefineException")
            null
        } catch (e: QubaguRefineException) {
            e.reason
        }
    }

    @Test
    fun refineReportsWhyNothingChanged() {
        val provider = ScriptProvider("""{"1":"他喉结滚动。"}""")
        assertEquals(QubaguRefineException.Reason.NO_REWRITES, refineFailure(provider, "他喉结滚动。"))
        assertEquals(QubaguRefineException.Reason.NO_REWRITES, refineFailure(ScriptProvider("做不到"), "他喉结滚动。"))
        assertEquals(QubaguRefineException.Reason.NO_MODEL, refineFailure(provider, "他喉结滚动。", fallback = null))
        assertEquals(QubaguRefineException.Reason.NO_MODEL, refineFailure(provider, "他喉结滚动。", fallback = Model(modelId = "orphan")))
        val untouched = ScriptProvider("{}")
        assertEquals(QubaguRefineException.Reason.NOTHING_TO_REFINE, refineFailure(untouched, "门开了。"))
        assertTrue(untouched.calls.isEmpty())
    }

    // ---- 生成结束时 ----

    private fun finish(
        provider: ScriptProvider,
        config: QubaguConfig,
        text: String,
        assistant: Assistant = this.assistant,
        history: List<UIMessage> = emptyList(),
    ): UIMessagePart.Text = runBlocking {
        val ctx = TransformerContext(
            context = FakeContext(Files.createTempDirectory("qubagu").toFile()),
            model = replyModel,
            assistant = assistant,
            settings = settings(config, assistant),
            history = history,
        )
        val out = QubaguOutputTransformer(engine, refiner(provider)).onGenerationFinish(ctx, listOf(UIMessage.assistant(text)))
        out.single().parts.single() as UIMessagePart.Text
    }

    @Test
    fun finishCleansOnlyWhenTheSwitchIsOn() {
        val provider = ScriptProvider("{}")
        val off = finish(provider, base.copy(enabled = false), "他喉结滚动。")
        assertEquals("他喉结滚动。", off.text)
        assertNull(off.metadata)

        val on = finish(provider, base, "他喉结滚动。")
        assertEquals("他顿了顿。", on.text)
        assertEquals("他喉结滚动。", QubaguRecord.of(on)!!.original(on.text))
        assertTrue("本地模式不调模型", provider.calls.isEmpty())
    }

    @Test
    fun finishLeavesCleanRepliesWithoutARecord() {
        val part = finish(ScriptProvider("{}"), base, "门开了。")
        assertEquals("门开了。", part.text)
        assertNull(part.metadata)
    }

    @Test
    fun finishRefinesInTheSameStepWhenAutoRefineIsOn() {
        val provider = ScriptProvider("""{"1":"他张了张嘴。"}""")
        val part = finish(provider, base.copy(refine = true), "他喉结滚动。")
        assertEquals("他张了张嘴。", part.text)
        assertEquals(listOf(QubaguRules.AI), QubaguRecord.of(part)!!.edits.map { it.rule })
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun finishKeepsTheLocalResultWhenRefineFailsOrIsUnusable() {
        for (provider in listOf(ScriptProvider("", failure = IllegalStateException("HTTP 500")), ScriptProvider("做不到"))) {
            val part = finish(provider, base.copy(refine = true), "他喉结滚动。")
            assertEquals("他顿了顿。", part.text)
            assertEquals(1, provider.calls.size)
        }
    }

    @Test
    fun finishDoesNotRefineInReviewMode() {
        val provider = ScriptProvider("""{"1":"他张了张嘴。"}""")
        val part = finish(provider, base.copy(refine = true, mode = QubaguMode.REVIEW), "他喉结滚动。")
        assertEquals("他喉结滚动。", part.text)
        assertEquals(0, QubaguRecord.of(part)!!.appliedCount)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun finishAvoidsReplacementsFromRecentReplies() {
        val config = QubaguConfig(enabled = true, packs = listOf(QubaguRulePack(id = "p", rules = listOf(QubaguRule.Replace(find = "心头一震", replacements = listOf("愣了一下", "怔了怔", "顿了顿"))))))
        val history = listOf(UIMessage.assistant("她愣了一下。"), UIMessage.user("怔了怔"), UIMessage.assistant("他顿了顿。"))
        repeat(10) {
            assertEquals("他怔了怔。", finish(ScriptProvider("{}"), config, "他心头一震。", history = history).text)
        }
    }
}
