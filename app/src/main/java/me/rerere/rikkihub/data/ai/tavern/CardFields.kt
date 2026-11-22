package me.rerere.rikkihub.data.ai.tavern

import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.tavern.macro.MacroEnvCharacter

/**
 * 本回合用到的卡字段与用户设定：先去首尾空白，再只代名字类宏，结果当作成品用（酒馆的 `baseChatReplace`，
 * `{{description}}` 之类不套娃）。没有角色、没有用户设定时全是空串，和没有角色卡时的请求完全相同。
 */
internal class CardFields(
    val description: String,
    val personality: String,
    val scenario: String,
    val mesExamples: String,
    val creatorNotes: String,
    val depthPrompt: String,
    val system: String,
    val jailbreak: String,
    val persona: String,
    private val version: String,
    private val firstMessage: String,
    private val alternateGreetings: List<String>,
) {
    /** 宏里 `{{description}}`、`{{persona}}`、`{{charPrompt}}` 等读到的值。 */
    fun macroEnv() = MacroEnvCharacter(
        charPrompt = system,
        charInstruction = jailbreak,
        description = description,
        personality = personality,
        scenario = scenario,
        persona = persona,
        mesExamplesRaw = mesExamples,
        charDepthPrompt = depthPrompt,
        creatorNotes = creatorNotes,
        version = version,
        firstMessage = firstMessage,
        alternateGreetings = alternateGreetings,
    )

    companion object {
        /** [plain] 是不带卡字段的宏会话（代名字用）。 */
        fun compute(plain: MacroSession, character: ActiveCharacter?, settings: Settings): CardFields {
            fun base(text: String?): String =
                text?.trim().orEmpty().let { if (it.isEmpty()) it else plain.evaluate(it).replace("\r", "") }
            val card = character?.record?.card
            return CardFields(
                description = base(card?.description),
                personality = base(card?.personality),
                scenario = base(card?.scenario),
                mesExamples = base(card?.mesExample),
                creatorNotes = base(card?.creatorNotes),
                depthPrompt = base(card?.depthPrompt?.prompt),
                system = base(card?.systemPrompt),
                jailbreak = base(card?.postHistoryInstructions),
                persona = base(settings.tavern.personaDescription),
                version = card?.characterVersion.orEmpty(),
                firstMessage = card?.firstMes.orEmpty(),
                alternateGreetings = card?.alternateGreetings ?: emptyList(),
            )
        }
    }
}
