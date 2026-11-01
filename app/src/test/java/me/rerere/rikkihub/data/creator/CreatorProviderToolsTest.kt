package me.rerere.rikkihub.data.creator

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkihub.data.creator.definition.AssistantRegistry
import me.rerere.rikkihub.data.creator.definition.CreatorAssistantDefinition.ToolIds
import me.rerere.rikkihub.data.creator.runtime.CreatorManagementBridge
import me.rerere.rikkihub.data.creator.runtime.CreatorProviderTester
import me.rerere.rikkihub.data.creator.runtime.CreatorToolSurface
import me.rerere.rikkihub.data.creator.storage.CreatorReadDomain
import me.rerere.rikkihub.data.datastore.Settings
import me.rerere.rikkihub.data.management.ManagementFixture
import me.rerere.rikkihub.data.management.adapters.SettingsAction
import me.rerere.rikkihub.data.management.adapters.SettingsCollectionInterpreter
import me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand
import me.rerere.rikkihub.data.management.managementFixture
import me.rerere.rikkihub.data.management.ports.CollectionPatch
import me.rerere.rikkihub.data.management.ports.SettingsCollectionOp
import me.rerere.rikkihub.data.management.proposal.ProposalStatus
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The creator manages providers: it creates and patches them (keys are write-only), sends test
 * messages through saved providers, and can never delete one.
 */
class CreatorProviderToolsTest {

    private val conversationId = Uuid.random()
    private val key = "sk-test-0123456789abcdef"

    private class Tools(val f: ManagementFixture, val byName: Map<String, me.rerere.ai.core.Tool>)

    private class FakeClient : CreatorProviderTester.Client {
        var reply: (ProviderSetting, Model, String) -> String = { _, model, message -> "echo ${model.modelId}: $message" }
        var remote: List<String> = listOf("gpt-a", "gpt-b")
        var stall = false
        val sent = mutableListOf<Triple<ProviderSetting, Model, String>>()
        override suspend fun generate(provider: ProviderSetting, model: Model, message: String): String {
            sent += Triple(provider, model, message)
            if (stall) delay(10_000)
            return reply(provider, model, message)
        }
        override suspend fun listModels(provider: ProviderSetting) = remote.map { Model(modelId = it) }
    }

    private val client = FakeClient()

    private fun tools(f: ManagementFixture): Tools {
        val read = CreatorReadDomain(JsonInstant, { f.settings.settingsSnapshot() }, AssistantRegistry(), { f.runtime.readPort })
        val tester = CreatorProviderTester({ f.settings.settingsSnapshot() }, client, messageTimeoutMillis = 2_000)
        val surface = CreatorToolSurface(read, CreatorManagementBridge({ f.runtime }, JsonInstant), tester)
        return Tools(f, surface.tools(conversationId).associateBy { it.name })
    }

    private fun test(block: suspend Tools.() -> Unit) = managementFixture { f -> tools(f).block() }

    private suspend fun Tools.raw(tool: String, args: String): String =
        (byName.getValue(tool).execute(JsonInstant.parseToJsonElement(args)).single() as UIMessagePart.Text).text

    private suspend fun Tools.call(tool: String, args: String): JsonObject = JsonInstant.parseToJsonElement(raw(tool, args)).jsonObject

    private suspend fun Tools.preview(vararg operations: String) =
        call(ToolIds.PREVIEW_CHANGE, """{"operations":[${operations.joinToString(",")}]}""")

    private fun JsonObject.proposals() = getValue("proposals").jsonArray.map { it.jsonObject }
    private fun JsonObject.rejected() = getValue("rejected").jsonArray.map { it.jsonObject }
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun Tools.approveOnly(result: JsonObject) {
        assertTrue(result.toString(), result.rejected().isEmpty())
        result.proposals().forEach { assertEquals(ProposalStatus.APPLIED, f.approve(it.str("proposalId")).status) }
    }

    private suspend fun Tools.addProvider(provider: ProviderSetting) = f.settings.update { it.copy(providers = it.providers + provider) }

    // ------------------------------------------------------------------ create

    @Test(timeout = 60_000) fun oneCallCreatesAProviderItsModelAndTheDefaultSlot() = test {
        val result = preview(
            """{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"OpenAI","name":"Relay","baseUrl":"https://relay.example.com/v1","apiKey":"$key",
               "customHeaders":[{"name":"X-Team","value":"team-secret-value"}]}}}""",
            """{"kind":"MODEL_DEFINITION","command":"Create","params":{"providerId":"${'$'}0","fields":{"modelId":"gpt-relay","displayName":"Relay GPT"}}}""",
            """{"kind":"DEFAULT_SLOT","command":"SetSlot","targetId":"FAST","params":{"modelId":"${'$'}1"}}""",
        )
        val proposal = result.proposals().single()
        // The key never comes back to the model, not even in the diff.
        assertFalse(result.toString(), result.toString().contains(key))
        assertFalse(result.toString(), result.toString().contains("team-secret-value"))
        // Hidden lines are named, not counted as truncated.
        assertEquals("0", proposal.str("omittedDiffLines"))
        assertEquals(setOf("apiKey", "customHeaders"),
            proposal.getValue("hiddenValues").jsonArray.map { it.jsonPrimitive.content.substringAfterLast('.') }.toSet())
        val created = proposal.getValue("createdIds").jsonObject
        val providerId = created.str("${'$'}0")
        val modelId = created.str("${'$'}1")
        // The native review shows the secret lines as hidden.
        val stored = f.runtime.proposals.get(proposal.str("proposalId"))
        val secretPaths = stored.diffSummary.filter { it.secret }.map { it.path.substringAfterLast('.') }.toSet()
        assertEquals(setOf("apiKey", "customHeaders"), secretPaths)
        assertTrue(stored.diffSummary.filter { it.secret }.all { it.before == null && it.after == null })

        approveOnly(result)
        val after = f.settings.settingsSnapshot()
        val provider = after.providers.single { it.id.toString() == providerId } as ProviderSetting.OpenAI
        assertEquals("Relay", provider.name)
        assertEquals("https://relay.example.com/v1", provider.baseUrl)
        assertEquals(key, provider.apiKey)
        assertEquals(listOf(CustomHeader("X-Team", "team-secret-value")), provider.customHeaders)
        assertEquals("gpt-relay", provider.models.single().modelId)
        assertEquals(modelId, provider.models.single().id.toString())
        assertEquals(modelId, after.fastModelId.toString())
    }

    @Test(timeout = 60_000) fun createNeedsAKeyBasedTypeAndValidValues() = test {
        val noType = preview("""{"kind":"PROVIDER","command":"Create","params":{"fields":{"name":"X"}}}""")
        assertTrue(noType.toString(), noType.rejected().single().str("detail").contains("type is required"))
        val login = preview("""{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"codex","name":"X"}}}""")
        assertTrue(login.toString(), login.rejected().single().str("detail").contains("cannot be created"))
        val badUrl = preview("""{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"claude","baseUrl":"relay.example.com"}}}""")
        assertTrue(badUrl.toString(), badUrl.rejected().single().str("detail").contains("baseUrl"))
        val wrongField = preview("""{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"claude","vertexAI":true}}}""")
        assertTrue(wrongField.toString(), wrongField.rejected().single().str("detail").contains("cannot be set on a claude provider"))
        val newHeader = preview("""{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"openai","customHeaders":[{"name":"X-A"}]}}}""")
        assertTrue(newHeader.toString(), newHeader.rejected().single().str("detail").contains("needs a value"))
    }

    // ------------------------------------------------------------------ read and patch

    @Test(timeout = 60_000) fun readsHideSecretsAndPatchingKeepsWhatWasNotSent() = test {
        val existing = ProviderSetting.Google(name = "Gem", apiKey = "AIza-old-key-value", privateKey = "pem-secret-value",
            customHeaders = listOf(CustomHeader("X-Proxy", "proxy-secret")), models = listOf(Model(modelId = "gemini-x")))
        addProvider(existing)
        val id = existing.id.toString()

        val listed = call(ToolIds.LIST_OBJECTS, """{"kind":"PROVIDER","filter":"Gem"}""").toString()
        assertTrue(listed, listed.contains("key set"))
        val read = call(ToolIds.READ_OBJECT, """{"kind":"PROVIDER","id":"$id"}""")
        val text = read.toString()
        listOf("AIza-old-key-value", "pem-secret-value", "proxy-secret").forEach { assertFalse(text, text.contains(it)) }
        val content = read.getValue("contentJson").jsonObject
        assertFalse(content.containsKey("apiKey"))
        assertFalse(content.containsKey("type"))
        assertEquals("""[{"name":"X-Proxy"}]""", content.getValue("customHeaders").toString())
        val readOnly = read.getValue("readOnly").jsonObject
        assertEquals("google", readOnly.str("type"))
        assertEquals("true", readOnly.str("apiKeyConfigured"))
        assertEquals("true", readOnly.str("privateKeyConfigured"))

        // Echoing the read content unchanged is not a change.
        val echo = preview(buildJsonObject {
            put("kind", "PROVIDER"); put("command", "Patch"); put("targetId", id)
            put("params", buildJsonObject { put("fields", content) })
        }.toString())
        assertTrue(echo.toString(), echo.rejected().single().str("detail").contains("changes nothing"))

        // Only the key and the name change; the header keeps its value because it was sent without one.
        val patch = preview("""{"kind":"PROVIDER","command":"Patch","targetId":"$id","params":{"fields":{"type":"Gemini",
            "name":"Gem 2","apiKey":"AIza-new-key-value","customHeaders":[{"name":"X-Proxy"},{"name":"X-New","value":"v2-secret"}]}}}""")
        assertFalse(patch.toString(), patch.toString().contains("AIza-new-key-value"))
        approveOnly(patch)
        val after = f.settings.settingsSnapshot().providers.single { it.id == existing.id } as ProviderSetting.Google
        assertEquals("Gem 2", after.name)
        assertEquals("AIza-new-key-value", after.apiKey)
        assertEquals("pem-secret-value", after.privateKey)
        assertEquals(listOf(CustomHeader("X-Proxy", "proxy-secret"), CustomHeader("X-New", "v2-secret")), after.customHeaders)
        assertEquals(existing.models, after.models)
    }

    @Test(timeout = 60_000) fun aPatchDoesNotRevalidateWhatTheUserTypedInTheApp() = test {
        val odd = ProviderSetting.OpenAI(name = "Odd", baseUrl = "relay.local/v1", apiKey = "")
        addProvider(odd)
        approveOnly(preview("""{"kind":"PROVIDER","command":"Patch","targetId":"${odd.id}","params":{"fields":{"apiKey":"$key"}}}"""))
        assertEquals(key, (f.settings.settingsSnapshot().providers.single { it.id == odd.id } as ProviderSetting.OpenAI).apiKey)
        val bad = preview("""{"kind":"PROVIDER","command":"Patch","targetId":"${odd.id}","params":{"fields":{"baseUrl":"ftp://x"}}}""")
        assertTrue(bad.toString(), bad.rejected().single().str("detail").contains("baseUrl"))
    }

    @Test(timeout = 60_000) fun loginProvidersOnlyChangeNameAndEnabledAndTypesStay() = test {
        val codex = f.settings.settingsSnapshot().providers.first { it is ProviderSetting.Codex }
        val key = preview("""{"kind":"PROVIDER","command":"Patch","targetId":"${codex.id}","params":{"fields":{"apiKey":"x"}}}""")
        assertEquals("FORBIDDEN_FIELD", key.rejected().single().str("code"))
        approveOnly(preview("""{"kind":"PROVIDER","command":"Patch","targetId":"${codex.id}","params":{"fields":{"enabled":true,"name":"My Codex"}}}"""))
        val after = f.settings.settingsSnapshot().providers.single { it.id == codex.id }
        assertEquals("My Codex", after.name)
        assertTrue(after.enabled)

        val claude = ProviderSetting.Claude(name = "C")
        addProvider(claude)
        val retype = preview("""{"kind":"PROVIDER","command":"Patch","targetId":"${claude.id}","params":{"fields":{"type":"openai"}}}""")
        assertTrue(retype.toString(), retype.rejected().single().str("detail").contains("cannot be changed"))
    }

    // ------------------------------------------------------------------ never delete

    @Test(timeout = 60_000) fun providersCannotBeDeletedAtAnyLayer() = test {
        val provider = ProviderSetting.OpenAI(name = "Keep")
        addProvider(provider)
        listOf("Delete", "remove", "Clone", "Reorder").forEach { command ->
            val result = preview("""{"kind":"PROVIDER","command":"$command","targetId":"${provider.id}","params":{}}""")
            assertEquals(command, "NOT_ALLOWED", result.rejected().single().str("code"))
            assertTrue(result.rejected().single().str("hint").contains("Settings › Providers"))
        }
        // The planner refuses a hand-made command, and the commit-time interpreter refuses the store operation.
        val planned = runCatching {
            f.runtime.plan(SettingsDomainCommand(SettingsCollectionInterpreter.PROVIDERS, SettingsAction.Delete(provider.id.toString())))
        }
        assertTrue(planned.exceptionOrNull()?.message.orEmpty(), planned.isFailure)
        val settings = f.settings.settingsSnapshot()
        val removed = runCatching {
            SettingsCollectionInterpreter.apply(settings, SettingsCollectionOp(SettingsCollectionInterpreter.PROVIDERS,
                CollectionPatch.Remove(listOf(provider.id.toString())), emptyList(), "delete-object"))
        }
        assertTrue(removed.exceptionOrNull()?.message.orEmpty().contains("cannot be deleted"))
        assertTrue(f.settings.settingsSnapshot().providers.any { it.id == provider.id })
    }

    @Test(timeout = 60_000) fun undoingAProviderCreationRestoresTheProviderList() = test {
        val before = f.settings.settingsSnapshot().providers.map { it.id }
        val result = preview(
            """{"kind":"PROVIDER","command":"Create","params":{"fields":{"type":"openai","name":"Temp","apiKey":"$key"}}}""",
            """{"kind":"MODEL_DEFINITION","command":"Create","params":{"providerId":"${'$'}0","fields":{"modelId":"m-1"}}}""",
        )
        approveOnly(result)
        assertEquals(before.size + 1, f.settings.settingsSnapshot().providers.size)
        val undo = call(ToolIds.PROPOSE_UNDO, """{"proposalId":"${result.proposals().single().str("proposalId")}"}""")
        approveOnly(undo)
        assertEquals(before, f.settings.settingsSnapshot().providers.map { it.id })
    }

    // ------------------------------------------------------------------ test messages

    @Test(timeout = 60_000) fun testMessagesGoThroughTheSavedProvider() = test {
        val model = Model(modelId = "gpt-saved", displayName = "Saved")
        val provider = ProviderSetting.OpenAI(name = "T", apiKey = "$key,sk-second-key-000", models = listOf(model))
        addProvider(provider)

        val ok = call(ToolIds.TEST_PROVIDER, """{"providerId":"${provider.id}","message":"ping"}""")
        assertEquals("true", ok.str("ok"))
        assertEquals("gpt-saved", ok.str("model"))
        assertEquals("echo gpt-saved: ping", ok.str("reply"))
        assertEquals(provider.id, client.sent.single().first.id)

        // By model name not yet in the list, plus the remote model list.
        val named = call(ToolIds.TEST_PROVIDER, """{"providerId":"${provider.id}","model":"gpt-new","listModels":true}""")
        assertEquals("gpt-new", named.str("model"))
        assertEquals("hello", client.sent.last().third)
        assertEquals("""["gpt-a","gpt-b"]""", named.getValue("remoteModels").toString())
        assertTrue(named.str("note").contains("not in this provider's model list"))

        // Errors are reported with every stored key masked.
        client.reply = { p, _, _ -> throw IllegalStateException("401 bad key ${(p as ProviderSetting.OpenAI).apiKey}") }
        val failed = call(ToolIds.TEST_PROVIDER, """{"providerId":"${provider.id}","model":"${model.id}"}""")
        assertEquals("false", failed.str("ok"))
        assertFalse(failed.toString(), failed.toString().contains(key))
        assertFalse(failed.toString(), failed.toString().contains("sk-second-key-000"))
        assertTrue(failed.str("error").contains("401 bad key ***"))

        client.stall = true
        val slow = call(ToolIds.TEST_PROVIDER, """{"providerId":"${provider.id}"}""")
        assertTrue(slow.str("error").contains("Timed out"))
    }

    @Test(timeout = 60_000) fun testMessagesNeedASavedProviderAndAChatModel() = test {
        val pending = call(ToolIds.TEST_PROVIDER, """{"providerId":"${Uuid.random()}"}""")
        assertEquals("NOT_FOUND", pending.str("reason"))
        assertTrue(pending.str("error").contains("after the user approves"))
        val empty = ProviderSetting.Claude(name = "Empty", models = listOf(Model(modelId = "img", type = ModelType.IMAGE)))
        addProvider(empty)
        assertEquals("NO_MODEL", call(ToolIds.TEST_PROVIDER, """{"providerId":"${empty.id}"}""").str("reason"))
        val image = call(ToolIds.TEST_PROVIDER, """{"providerId":"${empty.id}","model":"img"}""")
        assertTrue(image.toString(), image.str("error").contains("only chat models"))
        val listOnly = call(ToolIds.TEST_PROVIDER, """{"providerId":"${empty.id}","listModels":true}""")
        assertNull(listOnly["ok"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        assertEquals(2, listOnly.str("remoteModelCount").toInt())
        assertTrue(client.sent.isEmpty())
    }

    @Test fun secretsAreSplitLikeTheKeyRotation() {
        val provider = ProviderSetting.OpenAI(apiKey = "sk-aaaaaaaa, sk-bbbbbbbb\nsk-cccccccc", customHeaders = listOf(CustomHeader("A", "hdr-value-1")))
        val masked = CreatorProviderTester.mask("x sk-aaaaaaaa y sk-cccccccc z hdr-value-1", CreatorProviderTester.secretsOf(provider))
        assertEquals("x *** y *** z ***", masked)
    }

    @Test fun describeDocumentsEveryProviderType() = runBlocking {
        val doc = me.rerere.rikkihub.data.creator.runtime.CreatorSchemaCatalog.describe(
            me.rerere.rikkihub.data.creator.session.CreatorObjectKind.PROVIDER).toString()
        listOf("[all] name", "[openai] apiKey", "[google] privateKey", "[claude] promptCacheTtl", "\\\"5m\\\"", "cannot be deleted")
            .forEach { assertTrue(it, doc.contains(it)) }
        assertNotNull(Settings())
    }
}
