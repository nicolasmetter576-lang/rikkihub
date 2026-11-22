package me.rerere.tavern.prompt

import me.rerere.tavern.core.PromptRole

/**
 * 提示词组装器：`ST:public/scripts/openai.js` 的 `preparePromptsForChatCompletion` +
 * `populateChatCompletion`/`populateChatHistory`/`populateDialogueExamples`/`populationInjectionPrompts`/
 * `parseExampleIntoIndividual`/`formatWorldInfo`/`setOpenAIMessages`/`setOpenAIMessageExamples`，
 * 以及 `ST:public/script.js` 里 `Generate()` 把世界书/作者注释/深度提示词变成 extension prompts 的胶水。
 *
 * 不做的分支：群聊（groupNudge、群成员名、sendas）、instruct/textgen、工具调用与推理链、
 * 图片/视频/音频内联、CFG、Horde、第三方插件（调用方可用 [AssemblyInput.extraExtensionPrompts]）。
 */
object PromptAssembler {

    /** ST:script.js `MAX_INJECTION_DEPTH`（getExtensionPromptMaxDepth 恒返回它）。 */
    private const val MAX_INJECTION_DEPTH = 10000

    fun assemble(input: AssemblyInput, host: PromptHost): AssemblyResult =
        Session(input, host).run()

    // ==================================================================

    private class Session(
        val input: AssemblyInput,
        val host: PromptHost,
    ) {
        val o: AssemblyOptions get() = input.options
        val genType: String get() = input.generationType.wire

        // ---- 计数：ST countAsync 的消息开销：每条 +3、有 name 再 +1、整批 +3、非 full −2
        // （ST:src/endpoints/tokenizers.js:998-1017 + tokenizers.js:846 countAsync(messages, full=false)）
        fun tokensOf(role: PromptRole, content: String, name: String? = null): Int {
            var t = 3 + host.countTokens(role.wire) + host.countTokens(content)
            if (name != null) t += host.countTokens(name) + 1
            return t + 3 - 2
        }

        fun makeMessage(role: PromptRole, content: String, identifier: String): PromptMessage =
            PromptMessage(
                role, content, identifier,
                if (content.isNotEmpty()) tokensOf(role, content) else 0,
            )

        fun substitute(text: String): String = host.substitute(text)

        /** `preparePrompt`：内容求值；original 非空时 `{{original}}` 换成它（上游传 options.original，一次性）。 */
        fun preparePrompt(p: P, original: String? = null): P {
            val copy = p.copy()
            // rikkihubContext 是调用方给的成品文本（记忆/工具说明可能原样含 `{{…}}`），不再求值（见 AssemblyInput.rikkihubContext）
            copy.content = if (p.identifier == PromptMarkers.RIKKIHUB_CONTEXT) p.content
            else if (original != null)
                host.substitute(p.content.replace("{{original}}", original))
            else host.substitute(p.content)
            return copy
        }

        fun isEnabled(identifier: String): Boolean =
            input.layout.entries.firstOrNull { it.identifier == identifier }?.enabled != false

        // ---------------------------------------------------------------- 入口

        fun run(): AssemblyResult {
            // extension_prompts 地图（Generate() 胶水）
            val extensionPrompts = buildExtensionPrompts()

            // prompt collection（getPromptCollection + systemPrompts 合并 + 覆盖）
            val prompts = buildPromptCollection(extensionPrompts)

            // oaiMessages：上游 setOpenAIMessages 产出 newest-first。
            // 上游 Generate() 对 chat[0] 先 substituteParams 一次（ST:script.js:4490），这里对 history[0] 同样求值。
            val messages = input.history.mapIndexed { i, h -> i to h }.asReversed().map { (i, h) ->
                HistoryMsg(
                    role = h.role,
                    content = applyNamesBehavior(
                        h.copy(text = if (i == 0) substitute(h.text) else h.text),
                        o.namesBehavior,
                    ).replace("\r", ""),
                    name = h.name,
                    injected = false,
                    originalIndex = i,
                    presetTokens = h.tokens,
                )
            }.toMutableList()

            val messageExamples = buildMessageExamples()

            val chatCompletion = ChatCompletion { r, c, n -> tokensOf(r, c, n) }
            chatCompletion.setTokenBudget(o.contextSize, o.responseTokens)

            try {
                populateChatCompletion(prompts, chatCompletion, messages, extensionPrompts, messageExamples)
            } catch (_: TokenBudgetExceededError) {
                // 上游 toastr + 返回已装好的部分
            }

            if (o.squashSystemMessages) {
                chatCompletion.squashSystemMessages()
            }

            val items = chatCompletion.getChat().map { m ->
                val oi = historyIndexByIdentifier(m.identifier)
                if (oi != null) AssembledItem.History(oi)
                else AssembledItem.Generated(m.role, m.content, m.name, m.identifier)
            }

            // ST:openai.js:2877 assistant_prefill（仅 claude 类来源；continue_prefill 时不单独产出）
            val assistantPrefill = if (o.assistantPrefillSupported
                && genType != "quiet"
                && !(genType == "continue" && o.continuePrefill)
            ) {
                substitute(if (genType == "impersonate") o.assistantImpersonation else o.assistantPrefill)
            } else null

            return AssemblyResult(items, assistantPrefill)
        }

        private fun historyIndexByIdentifier(identifier: String): Int? =
            identifier.removePrefix("chatHistory-").toIntOrNull()?.minus(1)

        // ---------------------------------------------------------------- 历史消息视图

        private data class HistoryMsg(
            var role: PromptRole,
            var content: String,
            var name: String?,
            var injected: Boolean,
            val originalIndex: Int,
            val presetTokens: Int? = null,
        )

        /** names_behavior 在 setOpenAIMessages 里的内容改写（单人聊天：DEFAULT/NONE/COMPLETION 不改内容）。 */
        private fun applyNamesBehavior(h: HistoryItem, behavior: NamesBehavior): String = when (behavior) {
            NamesBehavior.CONTENT -> if (h.name != null) "${h.name}: ${h.text}" else h.text
            else -> h.text
        }

        // ---------------------------------------------------------------- extension_prompts 地图

        /**
         * 上游 `extension_prompts` 的组装期快照（Generate() 胶水）：
         * `2_floating_prompt`（作者注释，含 chara/AN-WI 合并）、`DEPTH_PROMPT`（卡深度提示词）、
         * `customDepthWI_{depth}_{role}`（世界书深度条目）、`PERSONA_DESCRIPTION`（AT_DEPTH 时）、
         * 加 [AssemblyInput.extraExtensionPrompts]。
         */
        private fun buildExtensionPrompts(): Map<String, ExtensionPromptInput> {
            val map = LinkedHashMap<String, ExtensionPromptInput>()

            // 作者注释（resolve 返回 null → 不产 value，与上游 setExtensionPrompt('') 等价）
            val note = input.authorsNote?.resolve(input.history.count { it.role == PromptRole.USER })
            if (note != null && note.text.isNotEmpty()) {
                var text = note.text
                // persona TOP_AN/BOTTOM_AN：仅注释本回合存在时并入（ST:addPersonaDescriptionExtensionPrompt）
                if (input.persona.isNotEmpty()) {
                    text = when (input.personaPosition) {
                        PersonaDescriptionPosition.TOP_AN -> "${input.persona}\n$text"
                        PersonaDescriptionPosition.BOTTOM_AN -> "$text\n${input.persona}"
                        else -> text
                    }
                }
                // 世界书 ANTop/ANBottom：本回合注释存在才合并（ST:world-info.js:5268，条目前后各一段）
                if (input.worldInfo.anBefore.isNotEmpty() || input.worldInfo.anAfter.isNotEmpty()) {
                    val anB = input.worldInfo.anBefore.joinToString("\n")
                    val anA = input.worldInfo.anAfter.joinToString("\n")
                    text = "$anB\n$text\n$anA".stripOneLeadingAndTrailingNewline()
                }
                map["2_floating_prompt"] = ExtensionPromptInput(text, note.position, note.depth, note.role)
            }

            // 卡深度提示词
            if (input.charDepthPrompt.isNotEmpty()) {
                map["DEPTH_PROMPT"] = ExtensionPromptInput(
                    input.charDepthPrompt, ExtensionPromptType.IN_CHAT, input.depthPromptDepth, input.depthPromptRole)
            }

            // 世界书深度条目（customDepthWI_{depth}_{role}）
            for (e in input.worldInfo.worldInfoDepth) {
                map["customDepthWI_${e.depth}_${e.role.code}"] = ExtensionPromptInput(
                    e.entries.joinToString("\n"), ExtensionPromptType.IN_CHAT, e.depth, e.role)
            }

            // 用户设定 AT_DEPTH
            if (input.persona.isNotEmpty() && input.personaPosition == PersonaDescriptionPosition.AT_DEPTH) {
                map["PERSONA_DESCRIPTION"] = ExtensionPromptInput(
                    input.persona, ExtensionPromptType.IN_CHAT, input.personaDepth, input.personaRole)
            }

            map.putAll(input.extraExtensionPrompts)
            return map
        }

        // ---------------------------------------------------------------- prompt collection

        /** 一条参与组装的 Prompt（PromptManager 的 Prompt + systemPrompts 合成）。 */
        private data class P(
            val identifier: String,
            var role: PromptRole,
            var content: String,
            val systemPrompt: Boolean,
            var position: String? = null,          // 'start'/'end'：相对 main 的位置
            var injectionPosition: Int? = null,    // 0=RELATIVE 1=ABSOLUTE
            var injectionDepth: Int? = null,
            var injectionOrder: Int = DEFAULT_INJECTION_ORDER,
            var injectionTrigger: List<String> = emptyList(),
            var forbidOverrides: Boolean? = null,
            var extension: Boolean = false,
            var name: String? = null,
            var orderEnabled: Boolean = true,      // 布局 order 条目 enabled（保留，禁用判断走 isDisabled/布局）
        )

        private class PromptCollection(val collection: MutableList<P> = ArrayList()) {
            val overriddenPrompts = ArrayList<String>()
            fun get(id: String): P? = collection.firstOrNull { it.identifier == id }
            fun index(id: String): Int = collection.indexOfFirst { it.identifier == id }
            fun has(id: String): Boolean = index(id) != -1
            fun add(p: P) { collection.add(p) }
            fun override(p: P, position: Int) {
                collection[position] = p
                overriddenPrompts.add(p.identifier)
            }
        }

        private fun shouldTrigger(p: P, type: String): Boolean =
            p.injectionTrigger.isEmpty() || type in p.injectionTrigger

        private fun entryToP(entry: PromptEntry): P =
            P(
                identifier = entry.identifier,
                role = entry.role ?: PromptRole.SYSTEM,
                content = entry.content ?: "",
                systemPrompt = entry.systemPrompt ?: (entry.marker == true),
                injectionPosition = entry.injectionPosition,
                injectionDepth = entry.injectionDepth,
                injectionOrder = entry.injectionOrder ?: DEFAULT_INJECTION_ORDER,
                injectionTrigger = entry.injectionTrigger ?: emptyList(),
                forbidOverrides = entry.forbidOverrides,
                orderEnabled = entry.enabled,
            )

        /**
         * `getPromptCollection`（布局条目 → 已求值 Prompt）+ `preparePromptsForChatCompletion` 的
         * systemPrompts 构建、布局覆盖、main/jailbreak 覆盖。
         */
        private fun buildPromptCollection(
            extensionPrompts: Map<String, ExtensionPromptInput>,
        ): PromptCollection {
            val type = genType

            // getPromptCollection：enabled + trigger 才收；main 永远收（空内容占位）
            val prompts = PromptCollection()
            for (entry in input.layout.entries) {
                val p = entryToP(entry)
                val allowedTrigger = entry.enabled && shouldTrigger(p, type)
                if (allowedTrigger) {
                    prompts.add(preparePrompt(p))
                } else if (entry.identifier == PromptMarkers.MAIN) {
                    prompts.add(preparePrompt(p.copy(content = "")))
                }
            }

            // ---- systemPrompts（preparePromptsForChatCompletion:1374-1493）----
            val systemPrompts = ArrayList<P>()
            fun formatWorldInfo(value: String): String =
                if (value.isEmpty()) "" else
                    if (o.wiFormat.jsTrim().isEmpty()) value else stringFormat1(o.wiFormat, value)

            val scenarioText =
                if (input.scenario.isNotEmpty() && o.scenarioFormat.isNotEmpty()) substitute(o.scenarioFormat)
                else input.scenario
            val charPersonalityText =
                if (input.personality.isNotEmpty() && o.personalityFormat.isNotEmpty()) substitute(o.personalityFormat)
                else input.personality

            systemPrompts.add(P("worldInfoBefore", PromptRole.SYSTEM, formatWorldInfo(input.worldInfo.worldInfoBefore), true))
            systemPrompts.add(P("worldInfoAfter", PromptRole.SYSTEM, formatWorldInfo(input.worldInfo.worldInfoAfter), true))
            systemPrompts.add(P("charDescription", PromptRole.SYSTEM, input.description, true))
            systemPrompts.add(P("charPersonality", PromptRole.SYSTEM, charPersonalityText, true))
            systemPrompts.add(P("scenario", PromptRole.SYSTEM, scenarioText, true))
            systemPrompts.add(P("impersonate", PromptRole.SYSTEM,
                if (o.impersonationPrompt.isNotEmpty()) substitute(o.impersonationPrompt) else "", true))
            systemPrompts.add(P("quietPrompt", PromptRole.SYSTEM, input.quietPrompt, true))
            systemPrompts.add(P("groupNudge", PromptRole.SYSTEM, "", true))
            systemPrompts.add(P("bias", PromptRole.ASSISTANT, input.bias, true))
            // rikkihub 自有标记（上游没有）：调用方给的成品文本，放在布局里这个标记所在的位置，作为 system 消息
            systemPrompts.add(P(PromptMarkers.RIKKIHUB_CONTEXT, PromptRole.SYSTEM, input.rikkihubContext, true))

            // 已知 relative extension prompts（BEFORE_PROMPT→start / IN_PROMPT→end）
            fun relativePrompt(id: String, key: String) {
                val ep = extensionPrompts[key] ?: return
                if (ep.value.isEmpty()) return
                systemPrompts.add(P(
                    id, ep.role ?: PromptRole.SYSTEM, ep.value, true,
                    position = when (ep.position) {
                        ExtensionPromptType.BEFORE_PROMPT -> "start"
                        ExtensionPromptType.IN_PROMPT -> "end"
                        else -> null
                    },
                ))
            }
            relativePrompt("summary", "1_memory")
            relativePrompt("authorsNote", "2_floating_prompt")
            relativePrompt("vectorsMemory", "3_vectors")
            relativePrompt("vectorsDataBank", "4_vectors_data_bank")
            relativePrompt("smartContext", "chromadb")

            // 用户设定 IN_PROMPT
            if (input.persona.isNotEmpty() && input.personaPosition == PersonaDescriptionPosition.IN_PROMPT) {
                systemPrompts.add(P("personaDescription", PromptRole.SYSTEM, input.persona, true))
            }

            // 其余 BEFORE_PROMPT/IN_PROMPT extension prompts（extension=true，identifier 按 \W → _）
            val knownExt = setOf(
                "1_memory", "2_floating_prompt", "3_vectors", "4_vectors_data_bank", "chromadb",
                "PERSONA_DESCRIPTION", "QUIET_PROMPT", "DEPTH_PROMPT",
            )
            for ((key, ep) in extensionPrompts) {
                if (key in knownExt) continue
                if (ep.value.isEmpty()) continue
                if (ep.position != ExtensionPromptType.BEFORE_PROMPT && ep.position != ExtensionPromptType.IN_PROMPT) continue
                if (ep.filter?.invoke() == false) continue
                systemPrompts.add(P(
                    key.replace(Regex("\\W"), "_"), ep.role ?: PromptRole.SYSTEM, ep.value, true,
                    position = if (ep.position == ExtensionPromptType.BEFORE_PROMPT) "start" else "end",
                    extension = true,
                ))
            }

            // 合并：布局条目覆盖 systemPrompts 的 role/depth/order；已有标记位原位替换，否则追加
            for (sp in systemPrompts) {
                val cp = prompts.get(sp.identifier)
                if (cp != null) {
                    cp.injectionPosition?.let { sp.injectionPosition = it }
                    cp.injectionDepth?.let { sp.injectionDepth = it }
                    sp.injectionOrder = cp.injectionOrder
                    sp.role = cp.role
                }
                val newPrompt = preparePrompt(sp)
                val markerIndex = prompts.index(sp.identifier)
                if (markerIndex != -1) {
                    val markerEntry = prompts.collection[markerIndex]
                    newPrompt.orderEnabled = markerEntry.orderEnabled
                    newPrompt.injectionTrigger = markerEntry.injectionTrigger
                    newPrompt.forbidOverrides = markerEntry.forbidOverrides
                    prompts.collection[markerIndex] = newPrompt
                } else prompts.add(newPrompt)
            }

            // main/jailbreak 覆盖（上游 preparePromptsForChatCompletion:1496-1513，1B 用）
            input.systemPromptOverride?.let { override ->
                val main = prompts.get("main")
                if (override.isNotEmpty() && main != null && main.forbidOverrides != true && isEnabled("main")) {
                    val original = main.content
                    main.content = override
                    prompts.override(preparePrompt(main, original), prompts.index("main"))
                }
            }
            input.jailbreakPromptOverride?.let { override ->
                val jb = prompts.get("jailbreak")
                if (override.isNotEmpty() && jb != null && jb.forbidOverrides != true && isEnabled("jailbreak")) {
                    val original = jb.content
                    jb.content = override
                    prompts.override(preparePrompt(jb, original), prompts.index("jailbreak"))
                }
            }

            return prompts
        }

        // ---------------------------------------------------------------- 示例对话

        /**
         * `parseMesExamples`（script.js:3501）+ `setOpenAIMessageExamples`（openai.js:656）：
         * 原文 → `<START>` 分块（世界书示例插前/后）→ 每块 parseExampleIntoIndividual。
         */
        private fun buildMessageExamples(): List<List<P>> {
            fun parseMesExamples(examplesStr: String): List<String> {
                var s = examplesStr
                if (s.isEmpty() || s == "<START>") return emptyList()
                if (!s.startsWith("<START>")) s = "<START>\n" + s.jsTrim()
                return s.split(Regex("<START>", RegexOption.IGNORE_CASE)).drop(1)
                    .map { "<START>\n" + it.jsTrim() + "\n" }
            }

            val array = parseMesExamples(input.mesExamples).toMutableList()
            for (ex in input.worldInfo.worldInfoExamples) {
                if (ex.content.isEmpty()) continue
                val cleaned = parseMesExamples(ex.content)
                if (ex.before) cleaned.asReversed().forEach { array.add(0, it) }
                else array.addAll(cleaned)
            }
            if (o.stripExamples) array.clear()

            return array.map { item ->
                val replaced = item.replaceFirst(Regex("<START>", RegexOption.IGNORE_CASE), "{Example Dialogue:}").replace("\r", "")
                parseExampleIntoIndividual(replaced)
            }
        }

        /** `parseExampleIntoIndividual`（openai.js:729）单人版：appendNamesForGroup 恒 false。 */
        private fun parseExampleIntoIndividual(messageExampleString: String): List<P> {
            val result = ArrayList<P>()
            val tmp = messageExampleString.split("\n")
            val curLines = ArrayList<String>()
            var inUser = false
            var inBot = false
            val botName = input.charName

            fun addMsg(name: String, systemName: String) {
                // JS replace(name+':','') 只替换第一处
                val parsed = curLines.joinToString("\n").replaceFirst("$name:", "").jsTrim()
                result.add(P(
                    identifier = "example", role = PromptRole.SYSTEM, content = parsed,
                    systemPrompt = true, name = systemName))
                curLines.clear()
            }

            // 跳过第一行（恒为 "This is how ... should talk" / {Example Dialogue:}）
            for (i in 1 until tmp.size) {
                val cur = tmp[i]
                if (cur.startsWith("${input.userName}:")) {
                    inUser = true
                    if (inBot) addMsg(botName, "example_assistant")
                    inBot = false
                } else if (cur.startsWith("${input.charName}:")) {
                    inBot = true
                    if (inUser) addMsg(input.userName, "example_user")
                    inUser = false
                }
                curLines.add(cur)
            }
            if (inUser) addMsg(input.userName, "example_user")
            else if (inBot) addMsg(botName, "example_assistant")
            return result
        }

        // ---------------------------------------------------------------- populateChatCompletion

        private fun populateChatCompletion(
            prompts: PromptCollection,
            chatCompletion: ChatCompletion,
            messages: MutableList<HistoryMsg>,     // newest-first
            extensionPrompts: Map<String, ExtensionPromptInput>,
            messageExamples: List<List<P>>,
        ) {
            // isPromptDisabledForActiveCharacter：看布局 order 条目的 enabled，不看 collection 内对象
            fun isDisabled(identifier: String): Boolean =
                input.layout.entries.firstOrNull { it.identifier == identifier }?.enabled == false

            fun addToChatCompletion(source: String) {
                if (!prompts.has(source)) return
                if (isDisabled(source) && source != "main") return
                val prompt = prompts.get(source)!!
                if (prompt.injectionPosition == InjectionPosition.ABSOLUTE) return
                val coll = MessageCollection(source)
                coll.add(makeMessage(prompt.role, prompt.content, prompt.identifier))
                chatCompletion.add(coll, prompts.index(source))
            }

            chatCompletion.reserveBudget(3) // 每条回复的 assistant 开槽

            // 卡字段/世界书标记展开
            addToChatCompletion("worldInfoBefore")
            addToChatCompletion("main")
            addToChatCompletion("worldInfoAfter")
            addToChatCompletion("charDescription")
            addToChatCompletion("charPersonality")
            addToChatCompletion("scenario")
            addToChatCompletion("personaDescription")
            addToChatCompletion(PromptMarkers.RIKKIHUB_CONTEXT)

            // controlPrompts：impersonate / quietPrompt，恒排最后
            chatCompletion.setOverriddenPrompts(prompts.overriddenPrompts)
            val controlPrompts = MessageCollection("controlPrompts")

            prompts.get("impersonate")?.let { p ->
                if (genType == "impersonate") controlPrompts.add(makeMessage(p.role, p.content, p.identifier))
            }
            prompts.get("quietPrompt")?.let { p ->
                val m = makeMessage(p.role, p.content, p.identifier)
                if (m.content.isNotEmpty()) controlPrompts.add(m)
            }
            chatCompletion.reserveBudget(controlPrompts)

            // 系统条目（nsfw/jailbreak）+ 非 system_prompt 的相对条目
            val systemPromptIds = listOf("nsfw", "jailbreak")
            val userRelative = prompts.collection
                .filter { !it.systemPrompt && it.injectionPosition != InjectionPosition.ABSOLUTE }
                .map { it.identifier }
            for (id in systemPromptIds + userRelative) addToChatCompletion(id)
            if (prompts.has("enhanceDefinitions")) addToChatCompletion("enhanceDefinitions")
            if (input.bias.jsTrim().isNotEmpty()) addToChatCompletion("bias")

            // injectToMain：relative extension prompts（position='start'/'end'）插进 main 集合
            val absolutePrompts = prompts.collection
                .filter { it.injectionPosition == InjectionPosition.ABSOLUTE }
                .toMutableList()

            fun injectToMain(prompt: P, position: String) {
                if (chatCompletion.has("main")) {
                    val m = makeMessage(prompt.role, prompt.content, prompt.identifier)
                    chatCompletion.insert(m, "main", position)
                } else {
                    val idx = absolutePrompts.indexOfFirst { it.identifier == "main" }
                    if (idx >= 0) {
                        val main = absolutePrompts[idx]
                        val copy = prompt.copy(
                            role = main.role,
                            injectionPosition = main.injectionPosition,
                            injectionDepth = main.injectionDepth,
                            injectionOrder = main.injectionOrder,
                        )
                        absolutePrompts.add(if (position == "end") idx + 1 else idx, copy)
                    }
                }
            }

            for (key in listOf("summary", "authorsNote", "vectorsMemory", "vectorsDataBank", "smartContext")) {
                prompts.get(key)?.position?.let { pos -> injectToMain(prompts.get(key)!!, pos) }
            }
            for (p in prompts.collection.filter { it.extension && it.position != null }) {
                injectToMain(p, p.position!!)
            }

            // continue_prefill：被续消息挪出历史并预填（assistantPrefillSupported=claude）
            if (genType == "continue" && o.continuePrefill && messages.isNotEmpty()) {
                val chatMessage = messages.removeAt(0) // newest-first：shift() = 最新一条
                val isAssistant = chatMessage.role == PromptRole.ASSISTANT
                val namesInCompletion = o.namesBehavior == NamesBehavior.COMPLETION
                val prefill = if (isAssistant && o.assistantPrefillSupported) substitute(o.assistantPrefill) else ""
                val content = listOf(prefill, chatMessage.content).filter { it.isNotEmpty() }.joinToString("\n\n")
                val continueMessage = makeMessage(chatMessage.role, content, "continuePrefill")
                val continueName = chatMessage.name
                if (continueName != null && namesInCompletion) {
                    continueMessage.setName(sanitizeApiName(continueName)) { r, c, n -> tokensOf(r, c, n) }
                }
                controlPrompts.add(continueMessage)
                chatCompletion.reserveBudget(continueMessage)
            }

            // 历史内注入（absolute 条目 + extension prompts，depth/order/role 分组）
            val injected = populationInjectionPrompts(absolutePrompts, messages, extensionPrompts)

            // rikkihub 的插入位置修正（默认恒等）：对已就位的深度注入块整体调整
            val finalMessages = applyInsertIndexAdjustment(injected)

            // 示例对话与历史（pin_examples 决定先后）
            if (o.pinExamples) {
                populateDialogueExamples(prompts, chatCompletion, messageExamples)
                populateChatHistory(prompts, chatCompletion, finalMessages)
            } else {
                populateChatHistory(prompts, chatCompletion, finalMessages)
                populateDialogueExamples(prompts, chatCompletion, messageExamples)
            }

            chatCompletion.freeBudget(controlPrompts)
            if (controlPrompts.collection.isNotEmpty()) chatCompletion.add(controlPrompts)
        }

        // ---------------------------------------------------------------- 历史内注入

        private data class InjectedMessages(
            val messages: MutableList<HistoryMsg>,
            /** 每个深度插入的块（按插入顺序）：(newest-first 下标, 块内消息)。 */
            val blocks: List<Pair<Int, List<HistoryMsg>>>,
        )

        /**
         * `populationInjectionPrompts`（openai.js:810-875）：messages 为 newest-first；
         * depth i=0..10000：该深度 absolute 条目按 order 降序分组，组内按 role 序拼 '\n'；
         * order==100 的组并入 getExtensionPrompt(IN_CHAT, i, role)；splice 在 `i + totalInserted`。
         */
        private fun populationInjectionPrompts(
            prompts: List<P>,
            messages: MutableList<HistoryMsg>,
            extensionPrompts: Map<String, ExtensionPromptInput>,
        ): InjectedMessages {
            var totalInserted = 0
            val blocks = ArrayList<Pair<Int, List<HistoryMsg>>>()

            for (i in 0..MAX_INJECTION_DEPTH) {
                val depthPrompts = prompts.filter { it.injectionDepth == i && it.content.isNotEmpty() }
                val orderGroups = sortedMapOf<Int, MutableList<P>>(compareByDescending { it })
                orderGroups[100] = ArrayList()
                for (p in depthPrompts) orderGroups.getOrPut(p.injectionOrder) { ArrayList() }.add(p)

                val roleMessages = ArrayList<HistoryMsg>()
                for ((order, orderPrompts) in orderGroups) {
                    for (role in listOf(PromptRole.SYSTEM, PromptRole.USER, PromptRole.ASSISTANT)) {
                        val rolePrompts = orderPrompts.filter { it.role == role }
                            .joinToString("\n") { it.content }
                        val extensionPrompt = if (order == 100)
                            getExtensionPrompt(extensionPrompts, i, role)
                        else ""
                        val joint = listOf(rolePrompts, extensionPrompt).filter { it.isNotEmpty() }
                            .joinToString("\n") { it.jsTrim() }
                        if (joint.isNotEmpty()) {
                            roleMessages.add(HistoryMsg(role, joint, null, injected = true, originalIndex = -1))
                        }
                    }
                }

                if (roleMessages.isNotEmpty()) {
                    val injectIdx = i + totalInserted
                    messages.addAll(injectIdx.coerceAtMost(messages.size), roleMessages)
                    blocks.add(injectIdx to roleMessages)
                    totalInserted += roleMessages.size
                }
            }

            return InjectedMessages(messages, blocks)
        }

        /** `getExtensionPrompt`（script.js:3301）：IN_CHAT + depth + role 过滤，键排序拼接，wrap=false，substitute。 */
        private fun getExtensionPrompt(
            extensionPrompts: Map<String, ExtensionPromptInput>,
            depth: Int,
            role: PromptRole?,
        ): String {
            val values = extensionPrompts.keys.sorted()
                .map { k -> extensionPrompts.getValue(k) }
                .filter { it.position == ExtensionPromptType.IN_CHAT && it.value.isNotEmpty() }
                .filter { it.depth == null || it.depth == depth }
                .filter { role == null || it.role == null || it.role == role }
                .filter { it.filter?.invoke() != false }
                .joinToString("\n") { it.value.jsTrim() }
            return if (values.isNotEmpty()) substitute(values) else values
        }

        /**
         * rikkihub 提供商约束回调：对已插入的注入块按 final（最旧在前）下标整体调整；默认恒等。
         */
        private fun applyInsertIndexAdjustment(injected: InjectedMessages): MutableList<HistoryMsg> {
            val final = injected.messages.asReversed().toMutableList()
            for ((_, block) in injected.blocks) {
                if (block.isEmpty()) continue
                // 块在 final（最旧在前）里是连续的，块内顺序与 block 相反；取它实际占的区间，整体搬动时保持原样
                val start = block.minOf { b -> final.indexOfFirst { it === b } }
                if (start < 0) continue
                val asPlaced = final.subList(start, start + block.size).toList()
                // 回调看到的是拿掉这一块之后的列表：块原来在 start，拿掉后 start 指向块后面的第一条
                val without = final.filter { item -> block.none { it === item } }
                val probes = without.map { HistoryProbe(it.role, it.originalIndex) }
                val adjusted = host.adjustHistoryInsertIndex(start, probes).coerceIn(0, without.size)
                if (adjusted == start) continue
                final.clear()
                final.addAll(without)
                final.addAll(adjusted, asPlaced)
            }
            return final
        }

        // ---------------------------------------------------------------- 历史与示例

        /**
         * `populateChatHistory`（openai.js:885-1092 单人版，无媒体/工具/签名）：
         * newChatPrompt 预留 → send_if_empty → newest→oldest 填预算 → newChat 插入 + 释放 →
         * continue 的 nudge 集合（被续消息 + nudge）挂到末尾。
         */
        private fun populateChatHistory(
            prompts: PromptCollection,
            chatCompletion: ChatCompletion,
            messages: MutableList<HistoryMsg>,   // 最旧在前
        ) {
            if (!prompts.has("chatHistory")) return

            chatCompletion.add(MessageCollection("chatHistory"), prompts.index("chatHistory"))

            // newChat 预留
            val newChatMessage = makeMessage(PromptRole.SYSTEM, substitute(o.newChatPrompt), "newMainChat")
            chatCompletion.reserveBudget(newChatMessage)

            // continue + !continue_prefill：被续消息挪进 continueNudge 集合（最后一条非注入消息 + nudge）
            var continueCollection: MessageCollection? = null
            val cyclePrompt = input.history.lastOrNull()?.text.orEmpty()
            if (genType == "continue" && cyclePrompt.isNotEmpty() && !o.continuePrefill) {
                continueCollection = MessageCollection("continueNudge")
                val idx = messages.indexOfLast { !it.injected }
                if (idx >= 0) {
                    val cm = messages.removeAt(idx)
                    // 上游对被续消息同样过 preparePrompt（substituteParams）
                    continueCollection.add(makeMessage(cm.role, substitute(cm.content), "chatHistory-${cm.originalIndex + 1}"))
                }
                // substituteParamsExtended(…, {lastChatMessage})：动态宏先由 substitute 保留（未知宏原样），再替换；
                // 这样被续消息里的 {{…}} 不会被二次求值，与上游一致
                val nudgeContent = substitute(o.continueNudgePrompt).replace(
                    Regex("\\{\\{lastChatMessage\\}\\}", RegexOption.IGNORE_CASE), cyclePrompt.jsTrim())
                continueCollection.add(makeMessage(PromptRole.SYSTEM, substitute(nudgeContent), "continueNudge"))
                chatCompletion.reserveBudget(continueCollection)
            }

            // send_if_empty：最后一条是 assistant 且有 send_if_empty → 末尾补假 user
            val lastChat = messages.lastOrNull()
            val emptyReplacement = makeMessage(PromptRole.USER, o.sendIfEmpty, "emptyUserMessageReplacement")
            if (lastChat != null && lastChat.role == PromptRole.ASSISTANT && o.sendIfEmpty.isNotEmpty()
                && chatCompletion.canAfford(emptyReplacement)) {
                chatCompletion.insert(emptyReplacement, "chatHistory")
            }

            // newest→oldest 填预算（COMPLETION 时名字进 name 字段）
            val namesInCompletion = o.namesBehavior == NamesBehavior.COMPLETION
            val pool = messages.asReversed()
            for (chatPrompt in pool) {
                // 偏离：上游标识符用 `chatHistory-${messages.length - index}`（含注入条目的池下标），
                // 这里用原始历史下标，保证 AssembledItem.History(originalIndex) 指回输入
                // 注入条目（absolute / 扩展提示词）没有对应的输入历史，用独立标识符；借用 chatHistory-0 会被反查成 History(-1)
                val identifier = if (chatPrompt.injected) "injectedHistory" else "chatHistory-${chatPrompt.originalIndex + 1}"
                // 上游对每条历史消息过 preparePrompt（substituteParams），含注入消息（二次求值，幂等）
                val chatMessage = makeMessage(chatPrompt.role, substitute(chatPrompt.content), identifier)
                chatPrompt.presetTokens?.let { chatMessage.setTokens(it) }
                val promptName = chatPrompt.name
                if (namesInCompletion && promptName != null) {
                    val messageName = if (isValidApiName(promptName)) promptName else sanitizeApiName(promptName)
                    chatMessage.setName(messageName) { r, c, nm -> tokensOf(r, c, nm) }
                }
                if (chatCompletion.canAfford(chatMessage)) {
                    chatCompletion.insertAtStart(chatMessage, "chatHistory")
                } else break
            }

            // newChat 插入并释放
            chatCompletion.freeBudget(newChatMessage)
            chatCompletion.insertAtStart(newChatMessage, "chatHistory")

            // continueNudge 集合挂末尾
            if (genType == "continue" && continueCollection != null) {
                chatCompletion.freeBudget(continueCollection)
                chatCompletion.add(continueCollection, -1)
            }
        }

        /**
         * `populateDialogueExamples`（openai.js:1101-1134）：`dialogueExamples` 集合槽位，
         * `[Example Chat]` + 逐块示例；整块放得下才放（canAffordAll 否则 break）。
         */
        private fun populateDialogueExamples(
            prompts: PromptCollection,
            chatCompletion: ChatCompletion,
            messageExamples: List<List<P>>,
        ) {
            if (!prompts.has("dialogueExamples")) return
            chatCompletion.add(MessageCollection("dialogueExamples"), prompts.index("dialogueExamples"))

            if (messageExamples.isNotEmpty()) {
                val newExampleChat = makeMessage(PromptRole.SYSTEM, substitute(o.newExampleChatPrompt), "newChat")
                for ((dialogueIndex, dialogue) in messageExamples.withIndex()) {
                    val chatMessages = dialogue.mapIndexed { promptIndex, p ->
                        val m = makeMessage(PromptRole.SYSTEM, p.content, "dialogueExamples $dialogueIndex-$promptIndex")
                        // ST：setName 后按含 name 重算 token
                        m.setName(p.name) { r, c, n -> tokensOf(r, c, n) }
                        m
                    }
                    if (!chatCompletion.canAffordAll(listOf(newExampleChat) + chatMessages)) break
                    chatCompletion.insert(newExampleChat, "dialogueExamples")
                    for (m in chatMessages) chatCompletion.insert(m, "dialogueExamples")
                }
            }
        }
    }
}
