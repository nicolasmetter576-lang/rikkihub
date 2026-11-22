package me.rerere.rikkihub.web

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.ai.tavern.TavernRuntime
import me.rerere.rikkihub.data.cli.ConversationCliSession
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.model.Assistant
import me.rerere.rikkihub.data.model.AssistantPresets
import me.rerere.rikkihub.data.model.Conversation
import me.rerere.rikkihub.data.model.MessageNode
import me.rerere.rikkihub.data.model.TavernSettings
import me.rerere.rikkihub.data.model.withRegexScripts
import me.rerere.rikkihub.data.mutation.MutationRuntime
import me.rerere.rikkihub.web.dto.WebDisplayProjector
import me.rerere.rikkihub.web.dto.toDto
import me.rerere.rikkihub.web.dto.toListDto
import me.rerere.rikkihub.data.repository.CharacterRepository
import me.rerere.rikkihub.data.repository.FakeCharacterDAO
import me.rerere.tavern.card.CharacterCard
import kotlinx.serialization.json.JsonArray
import me.rerere.tavern.regex.RegexPlacement
import me.rerere.tavern.regex.RegexScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 网页端「仅显示」正则：服务端用 App 同一个引擎算好随消息下发，存储里的文字不动。 */
class WebDisplayProjectionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime = TavernRuntime()

    @After
    fun tearDown() {
        runtime.close()
    }

    private fun store(): SettingsStore {
        val root = tmp.newFolder()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
        return SettingsStore.forTesting(dataStore, MutationRuntime(File(root, "runtime"), File(root, "restore.marker")), scope)
    }

    private fun displayScript(find: String, replace: String, minDepth: Int? = null, maxDepth: Int? = null) = RegexScript(
        id = "s-$find", scriptName = "s", findRegex = "/$find/g", replaceString = replace,
        placement = listOf(RegexPlacement.USER_INPUT, RegexPlacement.AI_OUTPUT, RegexPlacement.REASONING),
        markdownOnly = true, minDepth = minDepth, maxDepth = maxDepth,
    )

    private fun message(role: MessageRole, vararg parts: UIMessagePart) = UIMessage(role = role, parts = parts.toList())

    private fun conversation(assistant: Assistant, vararg messages: UIMessage) =
        Conversation(assistantId = assistant.id, messageNodes = messages.map { MessageNode.of(it) })

    private suspend fun SettingsStore.projector(assistant: Assistant, tavern: TavernSettings): WebDisplayProjector {
        update { it.copy(assistants = listOf(assistant), tavern = tavern) }
        withTimeout(10_000) { settingsFlow.first { s -> s.assistants.any { it.id == assistant.id } && s.tavern == tavern } }
        return WebDisplayProjector(this, runtime)
    }

    @Test
    fun `display only scripts change the shown text and leave the stored text alone`() = runBlocking {
        val assistant = Assistant(name = "a")
        val conversation = conversation(
            assistant,
            message(MessageRole.USER, UIMessagePart.Text("cat for me")),
            message(MessageRole.ASSISTANT, UIMessagePart.Reasoning("thinking about a cat"), UIMessagePart.Text("a cat sat"), UIMessagePart.Text("nothing here")),
        )
        val projector = store().projector(assistant, TavernSettings(regexScripts = listOf(displayScript("cat", "dog"))))

        val projected = projector.project(conversation.toDto(), conversation)

        val user = projected.messages[0].messages.single()
        assertEquals(mapOf(0 to "dog for me"), user.display)
        val reply = projected.messages[1].messages.single()
        assertEquals(mapOf(0 to "thinking about a dog", 1 to "a dog sat"), reply.display)
        // 下发的 parts 还是存储里的原文
        assertEquals("a cat sat", (reply.parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `a characters own display scripts count only after the user allowed them`() = runBlocking {
        val assistant = Assistant(name = "a")
        val characters = CharacterRepository(FakeCharacterDAO(), tmp.newFolder())
        val card = CharacterCard.blank("Ines").withExtension("regex_scripts", JsonArray(listOf(displayScript("cat", "dog").toJson())))
        val record = characters.create(card)
        val settings = store()
        settings.projector(assistant, TavernSettings()) // puts the assistant into the store
        val projector = WebDisplayProjector(settings, runtime, characters)
        val withCharacter = conversation(assistant, message(MessageRole.ASSISTANT, UIMessagePart.Text("a cat sat"))).copy(characterId = record.id)
        val without = conversation(assistant, message(MessageRole.ASSISTANT, UIMessagePart.Text("a cat sat")))

        // somebody else's script: nothing runs until the user says so
        assertNull(projector.project(withCharacter.toDto(), withCharacter).messages.single().messages.single().display)
        assertFalse(projector.dependsOnDepth(assistant.id, record.id))

        val key = "character:${record.id}"
        settings.update { it.copy(tavern = it.tavern.copy(regexAllowed = it.tavern.regexAllowed + key)) }
        withTimeout(10_000) { settings.settingsFlow.first { s -> key in s.tavern.regexAllowed } }
        assertEquals(mapOf(0 to "a dog sat"), projector.project(withCharacter.toDto(), withCharacter).messages.single().messages.single().display)
        // a conversation with another character, or none, is not affected
        assertNull(projector.project(without.toDto(), without).messages.single().messages.single().display)
        val node = withCharacter.messageNodes.single().toDto()
        assertEquals(
            mapOf(0 to "a dog sat"),
            projector.projectNode(node, 0, 1, assistant.id, record.id).messages.single().display,
        )
        assertNull(projector.projectNode(node, 0, 1, assistant.id, null).messages.single().display)
    }

    @Test
    fun `the conversation dto carries the character id`() {
        val characterId = kotlin.uuid.Uuid.random()
        val assistant = Assistant(name = "a")
        val c = conversation(assistant, message(MessageRole.USER, UIMessagePart.Text("hi"))).copy(characterId = characterId)
        assertEquals(characterId.toString(), c.toDto().characterId)
        assertEquals(characterId.toString(), c.toListDto().characterId)
        assertNull(conversation(assistant).toDto().characterId)
    }

    @Test
    fun `without display scripts nothing is added and nothing runs`() = runBlocking {
        val assistant = Assistant(name = "a")
        val conversation = conversation(assistant, message(MessageRole.ASSISTANT, UIMessagePart.Text("a cat sat")))
        // 普通脚本（会改存储）不是显示脚本
        val plain = displayScript("cat", "dog").copy(markdownOnly = false)
        val projector = store().projector(assistant, TavernSettings(regexScripts = listOf(plain)))
        val dto = conversation.toDto()
        assertTrue(projector.project(dto, conversation) === dto)
        assertNull(dto.messages.single().messages.single().display)
    }

    @Test
    fun `a disabled script and a script of another placement do nothing`() = runBlocking {
        val assistant = Assistant(name = "a")
        val conversation = conversation(assistant, message(MessageRole.ASSISTANT, UIMessagePart.Text("a cat sat")))
        val off = displayScript("cat", "dog").copy(disabled = true)
        val userOnly = displayScript("cat", "dog").copy(placement = listOf(RegexPlacement.USER_INPUT))
        val projector = store().projector(assistant, TavernSettings(regexScripts = listOf(off, userOnly)))
        val projected = projector.project(conversation.toDto(), conversation)
        assertNull(projected.messages.single().messages.single().display)
    }

    @Test
    fun `the assistant's own scripts count for that assistant`() = runBlocking {
        val script = displayScript("cat", "dog")
        val assistant = Assistant(name = "a", promptPreset = AssistantPresets.withSystemPrompt("x").withRegexScripts(listOf(script)))
        val other = Assistant(name = "b")
        val settings = store()
        val projector = settings.projector(assistant, TavernSettings())
        val own = conversation(assistant, message(MessageRole.ASSISTANT, UIMessagePart.Text("cat")))
        assertEquals(mapOf(0 to "dog"), projector.project(own.toDto(), own).messages.single().messages.single().display)
        val foreign = conversation(other, message(MessageRole.ASSISTANT, UIMessagePart.Text("cat")))
        assertNull(projector.project(foreign.toDto(), foreign).messages.single().messages.single().display)
    }

    @Test
    fun `depth ranges follow the distance from the end of the conversation`() = runBlocking {
        val assistant = Assistant(name = "a")
        val conversation = conversation(
            assistant,
            message(MessageRole.ASSISTANT, UIMessagePart.Text("cat 0")),
            message(MessageRole.USER, UIMessagePart.Text("cat 1")),
            message(MessageRole.ASSISTANT, UIMessagePart.Text("cat 2")),
        )
        // 只处理离末尾 0..1 条的消息
        val projector = store().projector(assistant, TavernSettings(regexScripts = listOf(displayScript("cat", "dog", minDepth = 0, maxDepth = 1))))
        assertTrue(projector.dependsOnDepth(assistant.id))
        val projected = projector.project(conversation.toDto(), conversation)
        assertEquals(listOf(null, mapOf(0 to "dog 1"), mapOf(0 to "dog 2")), projected.messages.map { it.messages.single().display })

        // 流式更新单个节点时按它在会话里的下标算同一个深度
        val node = conversation.toDto().messages[1]
        assertEquals(mapOf(0 to "dog 1"), projector.projectNode(node, nodeIndex = 1, nodeCount = 3, assistantId = assistant.id).messages.single().display)
        assertNull(projector.projectNode(conversation.toDto().messages[0], 0, 3, assistant.id).messages.single().display)
    }

    @Test
    fun `a script that depends on nothing deep does not ask for snapshots`() = runBlocking {
        val assistant = Assistant(name = "a")
        val projector = store().projector(assistant, TavernSettings(regexScripts = listOf(displayScript("cat", "dog"))))
        assertFalse(projector.dependsOnDepth(assistant.id))
    }

    @Test
    fun `cli conversations bypass markdownOnly display scripts for full projection, single nodes and depth checks`() = runBlocking {
        val assistant = Assistant(name = "a")
        val cli = ConversationCliSession(agentId = "builtin:agy")
        val cliChat = conversation(
            assistant,
            message(MessageRole.USER, UIMessagePart.Text("cat in user prompt")),
            message(MessageRole.ASSISTANT, UIMessagePart.Reasoning("cat thought"), UIMessagePart.Text("cat in reply")),
        ).copy(cliSession = cli)
        val normalChat = conversation(
            assistant,
            message(MessageRole.ASSISTANT, UIMessagePart.Text("cat in reply")),
        )
        val projector = store().projector(
            assistant,
            TavernSettings(regexScripts = listOf(displayScript("cat", "dog", minDepth = 0, maxDepth = 2))),
        )

        val cliDto = cliChat.toDto()
        val projectedCli = projector.project(cliDto, cliChat)
        assertTrue(projectedCli === cliDto)
        assertTrue(projectedCli.messages.all { node -> node.messages.all { it.display == null } })

        val cliNode = cliDto.messages[1]
        val projectedNode = projector.projectNode(cliNode, cliChat, nodeIndex = 1)
        assertTrue(projectedNode === cliNode)
        assertNull(projectedNode.messages.single().display)
        assertFalse(projector.dependsOnDepth(cliChat))

        // Same assistant & script still applies to non-CLI conversations.
        assertTrue(projector.dependsOnDepth(normalChat))
        assertEquals(mapOf(0 to "dog in reply"), projector.project(normalChat.toDto(), normalChat).messages.single().messages.single().display)
        assertEquals(mapOf(0 to "dog in reply"), projector.projectNode(normalChat.toDto().messages[0], normalChat, nodeIndex = 0).messages.single().display)
    }
}
