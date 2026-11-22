package me.rerere.tavern.prompt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tavern.core.GenerationType
import me.rerere.tavern.core.PromptRole
import me.rerere.tavern.core.WorldInfoDepthEntry
import me.rerere.tavern.core.WorldInfoExampleEntry
import me.rerere.tavern.core.WorldInfoPromptResult
import me.rerere.tavern.token.TokenCounter

/** 从录到的 golden case 还原 [AssemblyInput] + host（subs 查表 + cl100k 计数）。 */
internal object PromptGoldenSupport {
    private val json = Json { ignoreUnknownKeys = true }

    class Replay(
        val input: AssemblyInput,
        val host: PromptHost,
        val expectedMessages: List<ExpectedMessage>,
        val expectedAssistantPrefill: String?,
    )

    class ExpectedMessage(val role: PromptRole, val content: String, val name: String?)

    fun load(path: String): JsonObject =
        json.parseToJsonElement(Thread.currentThread().contextClassLoader.getResource(path)!!.readText()).jsonObject

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.i(k: String) = this[k]?.jsonPrimitive?.intOrNull
    private fun JsonObject.b(k: String) = this[k]?.jsonPrimitive?.booleanOrNull

    fun replay(case: JsonObject): Replay {
        val input = case["input"]!!.jsonObject
        val subs = mutableMapOf<String, String>()
        case["subs"]?.jsonObject?.forEach { (k, v) -> subs[k] = v.jsonPrimitive.content }

        val host = object : PromptHost {
            override fun substitute(text: String): String = subs[text] ?: text
            override fun countTokens(text: String): Int = TokenCounter.count(text)
        }

        val presetObj = input["preset"]!!.jsonObject
        val preset = PromptPreset.fromJson(presetObj)
        val layout = preset.layout()

        val opt = input["options"]!!.jsonObject
        val pu = input["powerUser"]!!.jsonObject
        val options = AssemblyOptions(
            contextSize = opt.i("openai_max_context") ?: 4095,
            responseTokens = opt.i("openai_max_tokens") ?: 300,
            namesBehavior = NamesBehavior.fromCode(opt.i("names_behavior")) ?: NamesBehavior.DEFAULT,
            sendIfEmpty = opt.s("send_if_empty") ?: "",
            impersonationPrompt = opt.s("impersonation_prompt") ?: "",
            newChatPrompt = opt.s("new_chat_prompt") ?: "",
            newExampleChatPrompt = opt.s("new_example_chat_prompt") ?: "",
            continueNudgePrompt = opt.s("continue_nudge_prompt") ?: "",
            wiFormat = opt.s("wi_format") ?: "{0}",
            scenarioFormat = opt.s("scenario_format") ?: "",
            personalityFormat = opt.s("personality_format") ?: "",
            assistantPrefill = opt.s("assistant_prefill") ?: "",
            assistantImpersonation = opt.s("assistant_impersonation") ?: "",
            squashSystemMessages = opt.b("squash_system_messages") ?: false,
            continuePrefill = opt.b("continue_prefill") ?: false,
            continuePostfix = opt.s("continue_postfix") ?: " ",
            pinExamples = pu.b("pin_examples") ?: false,
            stripExamples = pu.b("strip_examples") ?: false,
            // 录制来源一律 openai（cl100k）；assistant_prefill 只在 claude 下产出，golden 不覆盖，走单测
            assistantPrefillSupported = false,
        )

        val names = input["names"]!!.jsonObject
        val card = input["card"]!!.jsonObject
        val wiJson = input["worldInfo"]!!.jsonObject
        val worldInfo = WorldInfoPromptResult(
            worldInfoBefore = wiJson.s("worldInfoBefore") ?: "",
            worldInfoAfter = wiJson.s("worldInfoAfter") ?: "",
            worldInfoExamples = wiJson["worldInfoExamples"]?.jsonArray?.map {
                val o = it.jsonObject
                WorldInfoExampleEntry(before = (o.i("position") ?: 0) == 0, content = o.s("content") ?: "")
            } ?: emptyList(),
            worldInfoDepth = wiJson["worldInfoDepth"]?.jsonArray?.map {
                val o = it.jsonObject
                WorldInfoDepthEntry(
                    depth = o.i("depth") ?: 0,
                    role = PromptRole.fromCode(o.i("role")),
                    entries = o["entries"]!!.jsonArray.map { e -> e.jsonPrimitive.content },
                )
            } ?: emptyList(),
            anBefore = wiJson["anBefore"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            anAfter = wiJson["anAfter"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        )

        val an = input["authorsNote"]?.takeIf { it is JsonObject }?.jsonObject?.let { a ->
            val chara = a["chara"]?.takeIf { it is JsonObject }?.jsonObject?.let { cn ->
                AuthorsNote.CharaNote(
                    prompt = cn.s("prompt") ?: "",
                    useChara = cn.b("useChara") ?: true,
                    position = cn.i("position") ?: 0,
                )
            }
            AuthorsNote(
                prompt = a.s("prompt") ?: "",
                interval = a.i("interval") ?: 1,
                depth = a.i("depth") ?: 4,
                position = a.i("position") ?: 1,
                role = PromptRole.fromCode(a.i("role")),
                charaNote = chara,
            )
        }

        val extraEP = input["extraEP"]?.jsonObject?.mapValues { (_, v) ->
            val e = v.jsonObject
            ExtensionPromptInput(
                value = e.s("value") ?: "",
                position = e.i("position") ?: -1,
                depth = e.i("depth"),
                role = e.i("role")?.let { PromptRole.fromCode(it) },
            )
        } ?: emptyMap()

        val history = input["history"]!!.jsonArray.map {
            val o = it.jsonObject
            HistoryItem(
                role = PromptRole.fromWire(o.s("role")),
                name = o.s("name"),
                text = o.s("text") ?: "",
            )
        }

        val cardObj = input["card"]!!.jsonObject
        val assemblyInput = AssemblyInput(
            layout = layout,
            options = options,
            generationType = GenerationType.fromWire(input.s("type")),
            charName = names.s("char") ?: "",
            userName = names.s("user") ?: "",
            description = cardObj.s("description") ?: "",
            personality = cardObj.s("personality") ?: "",
            scenario = cardObj.s("scenario") ?: "",
            persona = pu.s("persona_description") ?: "",
            personaPosition = pu.i("persona_description_position") ?: 0,
            personaDepth = pu.i("persona_description_depth") ?: 0,
            personaRole = PromptRole.fromCode(pu.i("persona_description_role")),
            mesExamples = cardObj.s("mesExamples") ?: "",
            charDepthPrompt = cardObj.s("charDepthPrompt") ?: "",
            depthPromptDepth = cardObj.i("depthPromptDepth") ?: 4,
            depthPromptRole = PromptRole.fromWire(cardObj.s("depthPromptRole")),
            history = history,
            worldInfo = worldInfo,
            authorsNote = an,
            extraExtensionPrompts = extraEP,
            quietPrompt = input.s("quietPrompt") ?: "",
            bias = input.s("bias") ?: "",
            systemPromptOverride = cardObj.s("systemPrompt")?.takeIf { it.isNotEmpty() },
            jailbreakPromptOverride = cardObj.s("jailbreak")?.takeIf { it.isNotEmpty() },
        )

        val expected = case["expected"]!!.jsonObject["messages"]!!.jsonArray.map {
            val o = it.jsonObject
            ExpectedMessage(
                role = PromptRole.fromWire(o.s("role")),
                content = o.s("content") ?: "",
                name = o.s("name"),
            )
        }
        val prefill = case["expected"]!!.jsonObject["assistantPrefill"]?.jsonPrimitive?.contentOrNull

        return Replay(assemblyInput, host, expected, prefill)
    }

    /** 把组装结果映射成上游 wire 形态对比。index 0 的历史按上游 chat[0] 规则先求值。 */
    fun toWire(result: AssemblyResult, input: AssemblyInput, host: PromptHost): List<ExpectedMessage> = result.items.map { item ->
        when (item) {
            is AssembledItem.Generated -> ExpectedMessage(item.role, item.content, item.name)
            is AssembledItem.History -> {
                val h = input.history[item.originalIndex]
                // 上游 populateChatHistory 对每条消息过 preparePrompt（substituteParams）
                val rawText = host.substitute(h.text)
                // 回放时按上游 setOpenAIMessages 的内容变换还原 wire 形态
                val text = when (input.options.namesBehavior) {
                    NamesBehavior.CONTENT -> if (h.name != null) "${h.name}: ${rawText}" else rawText
                    else -> rawText
                }.replace("\r", "")
                ExpectedMessage(h.role, text, if (input.options.namesBehavior == NamesBehavior.COMPLETION) sanitizedName(h.name) else null)
            }
        }
    }

    private fun sanitizedName(name: String?): String? = name?.let {
        if (isValidApiName(it)) it else sanitizeApiName(it)
    }
}
