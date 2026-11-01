package me.rerere.rikkihub.data.ai

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.utils.JsonInstant
import okhttp3.OkHttpClient
import java.io.File
import kotlin.uuid.Uuid

/**
 * 单元测试用 Context。mockable android.jar 里 ContextWrapper 构造器可用,
 * 其余方法全部抛 "not mocked"; 这里只补齐测试路径实际触碰的方法:
 * - filesDir: 子代理 run 日志目录 / 工具输出截断目录
 * - cacheDir: KeyRoulette 懒加载兜底
 *
 * 注意 `Context.getString` 是 final, 不能覆写; 生成循环的文案经
 * `GenerationLoop.statusStrings` 注入, 不走 Context。
 */
internal class FakeContext(
    private val filesDirOverride: File,
) : ContextWrapper(null) {
    override fun getFilesDir(): File = filesDirOverride
    override fun getCacheDir(): File = filesDirOverride
}

/** 一次 provider 生成尝试的脚本: 正常发出 chunks 后以 [errorAfter] 异常收场(可空)。 */
internal data class ScriptedAttempt(
    val chunks: List<StreamChunk>,
    val errorAfter: Throwable? = null,
)

/** 顺序消费脚本的重放型 Provider, 模拟真实流式/非流式响应与网络故障。 */
internal class ScriptedProvider(
    private val attempts: ArrayDeque<ScriptedAttempt>,
    private val results: ArrayDeque<TextGenerationResult> = ArrayDeque(),
) : Provider<ProviderSetting> {
    val streamAttempts = mutableListOf<List<StreamChunk>>()
    val streamParams = mutableListOf<TextGenerationParams>()
    val streamMessages = mutableListOf<List<UIMessage>>()
    val generateParams = mutableListOf<TextGenerationParams>()
    val generateMessages = mutableListOf<List<UIMessage>>()
    var generateCalls = 0
        private set

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        generateCalls += 1
        generateParams += params
        generateMessages += messages
        return results.removeFirstOrNull()
            ?: error("ScriptedProvider: no scripted generateText result left")
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        val attempt = attempts.removeFirstOrNull()
            ?: error("ScriptedProvider: no scripted stream attempt left")
        streamAttempts += attempt.chunks
        streamParams += params
        streamMessages += messages
        attempt.chunks.forEach { emit(it) }
        attempt.errorAfter?.let { throw it }
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult = error("not used in tests")
}

internal fun testChatModel(): Model = Model(
    modelId = "scripted-chat",
    abilities = listOf(ModelAbility.TOOL),
)

internal fun testProviderSetting(model: Model): ProviderSetting.OpenAI = ProviderSetting.OpenAI(
    apiKey = "test",
    baseUrl = "http://localhost",
    models = listOf(model),
)

internal fun testSettings(model: Model): Settings = Settings(
    providers = listOf(testProviderSetting(model)),
)

internal fun testGenerationLoop(
    provider: ScriptedProvider,
    context: FakeContext,
): GenerationLoop = GenerationLoop(
    context = context,
    providerManager = ProviderManager(OkHttpClient(), context).apply {
        registerProvider("openai", provider)
    },
    json = JsonInstant,
).apply {
    // Android Resources 在 JVM 单测不可用; 用确定性桩返回可断言文案
    statusStrings = StatusStringResolver { resId, args ->
        "status:$resId" + if (args.isNotEmpty()) ":" + args.joinToString(",") else ""
    }
}

/** 一轮工具调用步的脚本: toolCallStart -> 完整 input delta -> end -> usage -> finish */
internal fun toolStepScript(
    toolCallId: String,
    toolName: String,
    inputJson: String = "{}",
    usage: me.rerere.ai.core.TokenUsage? = null,
    finishReason: String = "tool_calls",
): ScriptedAttempt = ScriptedAttempt(
    buildList {
        add(StreamChunk.ToolCallStart(id = toolCallId, toolName = toolName))
        add(StreamChunk.ToolCallDelta(id = toolCallId, inputDelta = inputJson))
        add(StreamChunk.ToolCallEnd(id = toolCallId))
        if (usage != null) add(StreamChunk.Usage(usage))
        add(StreamChunk.Finish(finishReason = finishReason))
    }
)

/** 一轮纯文本步的脚本 */
internal fun textStepScript(
    text: String,
    id: String = "txt",
    usage: me.rerere.ai.core.TokenUsage? = null,
    finishReason: String = "stop",
): ScriptedAttempt = ScriptedAttempt(
    buildList {
        add(StreamChunk.TextStart(id = id))
        add(StreamChunk.TextDelta(id = id, text = text))
        add(StreamChunk.TextEnd(id = id))
        if (usage != null) add(StreamChunk.Usage(usage))
        add(StreamChunk.Finish(finishReason = finishReason))
    }
)

/** 回显参数的最小工具 (非写路径, 不产 edited_paths) */
internal fun echoTool(name: String = "echo_tool"): Tool = Tool(
    name = name,
    description = "echo input",
    parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
    execute = { args: JsonElement ->
        listOf(UIMessagePart.Text("""{"ok":true,"input":${args}}"""))
    },
)

/**
 * 写文件工具: 参数 {"path": "<abs path>", "content": "<text>"}, 实际落盘并回显大小。
 * 用于断言真实副作用链 (decoder/handler → loop dispatch → 文件内容)。
 */
internal fun fileWritingTool(name: String = "workspace_write_file"): Tool = Tool(
    name = name,
    description = "write a text file",
    parameters = { InputSchema.Obj(properties = JsonObject(emptyMap()), required = null) },
    execute = { args: JsonElement ->
        val obj = args.jsonObject
        val path = obj["path"]!!.jsonPrimitive.content
        val content = obj["content"]!!.jsonPrimitive.content
        val file = File(path).apply { parentFile?.mkdirs() }
        file.writeText(content)
        listOf(UIMessagePart.Text("""{"ok":true,"written":${file.length()}}"""))
    },
)

internal fun testAssistant(streamOutput: Boolean = true): Assistant = Assistant(streamOutput = streamOutput)
