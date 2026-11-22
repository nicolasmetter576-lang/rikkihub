package me.rerere.rikkihub.web.routes

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.management.ManagementFixture
import me.rerere.rikkihub.data.management.adapters.SettingsAction
import me.rerere.rikkihub.data.management.adapters.SettingsDomainCommand
import me.rerere.rikkihub.utils.JsonInstant
import me.rerere.rikkihub.web.installWebApiStatusPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The creator's proposals on the web: list, review by digest, approve, reject, approve all — against the real management runtime. */
class CreatorProposalRoutesTest {
    private fun withApi(block: suspend (HttpClient, ManagementFixture) -> Unit) {
        val f = ManagementFixture()
        try {
            testApplication {
                application {
                    install(ContentNegotiation) { json(JsonInstant) }
                    installWebApiStatusPages()
                    routing { route("/api") { creatorProposalRoutes(f.runtime) } }
                }
                block(client, f)
            }
        } finally { f.close() }
    }

    private fun quick(title: String) = SettingsDomainCommand("settings.quickMessages",
        SettingsAction.Create(mapOf("title" to JsonPrimitive(title), "content" to JsonPrimitive("text of $title"))))

    private suspend fun HttpResponse.obj(): JsonObject = JsonInstant.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpClient.postJson(url: String, body: JsonElement) =
        post(url) { contentType(ContentType.Application.Json); setBody(body.toString()) }

    @Test fun pendingProposalsAreListedWithTheirDiffAndDigest() = runBlocking<Unit> {
        withApi { client, f ->
            val proposal = f.runtime.preview(quick("Hello"), f.source)
            val other = f.runtime.preview(quick("Elsewhere"), me.rerere.rikkihub.data.management.proposal.Provenance("session-2", "run", "call"))
            val response = client.get("/api/creator/proposals?conversationId=session-1")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.obj()
            val listed = body.getValue("proposals").jsonArray.map { it.jsonObject }
            assertEquals(listOf(proposal.proposalId), listed.map { it.getValue("id").jsonPrimitive.content })
            val dto = listed.single()
            assertEquals("pending_approval", dto.getValue("status").jsonPrimitive.content)
            assertEquals(proposal.canonicalDigest, dto.getValue("digest").jsonPrimitive.content)
            assertTrue(dto.getValue("diff").jsonArray.any { it.jsonObject.getValue("path").jsonPrimitive.content.contains("title") })
            assertTrue(body.getValue("writeGateOpen").jsonPrimitive.content.toBoolean())
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/creator/proposals").status)
            assertEquals(HttpStatusCode.OK, client.get("/api/creator/proposals/${other.proposalId}").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/creator/proposals/nope").status)
        }
    }

    @Test fun approvingWithTheShownDigestAppliesTheChange() = runBlocking<Unit> {
        withApi { client, f ->
            val proposal = f.runtime.preview(quick("Applied"), f.source)
            val response = client.postJson("/api/creator/proposals/${proposal.proposalId}/approve", buildJsonObject { put("digest", proposal.canonicalDigest) })
            assertEquals(response.bodyAsText(), HttpStatusCode.OK, response.status)
            assertEquals("applied", response.obj().getValue("status").jsonPrimitive.content)
            assertEquals(listOf("Applied"), f.settings.settingsSnapshot().quickMessages.map { it.title })
            // a second approval of the same proposal is refused
            val again = client.postJson("/api/creator/proposals/${proposal.proposalId}/approve", buildJsonObject { put("digest", proposal.canonicalDigest) })
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals(1, f.settings.settingsSnapshot().quickMessages.size)
        }
    }

    @Test fun aWrongDigestAppliesNothing() = runBlocking<Unit> {
        withApi { client, f ->
            val proposal = f.runtime.preview(quick("Stale"), f.source)
            val response = client.postJson("/api/creator/proposals/${proposal.proposalId}/approve", buildJsonObject { put("digest", "0".repeat(64)) })
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("APPROVAL_DIGEST_MISMATCH", response.obj().toString().let { text -> Regex("APPROVAL_DIGEST_MISMATCH").find(text)?.value })
            assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
            // the proposal can still be approved with the right digest afterwards
            val ok = client.postJson("/api/creator/proposals/${proposal.proposalId}/approve", buildJsonObject { put("digest", proposal.canonicalDigest) })
            assertEquals(HttpStatusCode.OK, ok.status)
        }
    }

    @Test fun rejectedAndCancelledProposalsCannotBeApproved() = runBlocking<Unit> {
        withApi { client, f ->
            val rejected = f.runtime.preview(quick("No"), f.source)
            assertEquals("rejected", client.postJson("/api/creator/proposals/${rejected.proposalId}/reject", buildJsonObject {}).obj().getValue("status").jsonPrimitive.content)
            val cancelled = f.runtime.preview(quick("Later"), f.source)
            assertEquals("cancelled", client.postJson("/api/creator/proposals/${cancelled.proposalId}/cancel", buildJsonObject {}).obj().getValue("status").jsonPrimitive.content)
            listOf(rejected, cancelled).forEach {
                val response = client.postJson("/api/creator/proposals/${it.proposalId}/approve", buildJsonObject { put("digest", it.canonicalDigest) })
                assertEquals(HttpStatusCode.Conflict, response.status)
            }
            assertTrue(f.settings.settingsSnapshot().quickMessages.isEmpty())
        }
    }

    @Test fun approveAllAppliesEveryPendingProposalOfTheConversation() = runBlocking<Unit> {
        withApi { client, f ->
            f.runtime.preview(quick("One"), f.source)
            f.runtime.preview(quick("Two"), f.source)
            val untouched = f.runtime.preview(quick("Other chat"), me.rerere.rikkihub.data.management.proposal.Provenance("session-2", "run", "call"))
            val response = client.postJson("/api/creator/proposals/approve-all", buildJsonObject { put("conversationId", "session-1") })
            val results = response.obj().getValue("results").jsonArray.map { it.jsonObject }
            assertEquals(response.bodyAsText(), listOf("applied", "applied"), results.map { it.getValue("status").jsonPrimitive.content })
            assertEquals(setOf("One", "Two"), f.settings.settingsSnapshot().quickMessages.map { it.title }.toSet())
            assertEquals("pending_approval", client.get("/api/creator/proposals/${untouched.proposalId}").obj().getValue("status").jsonPrimitive.content)
        }
    }

    @Test fun secretValuesNeverLeaveTheDiff() = runBlocking<Unit> {
        withApi { client, f ->
            val create = SettingsDomainCommand("settings.providers", SettingsAction.Create(mapOf(
                "type" to JsonPrimitive("openai"), "name" to JsonPrimitive("Relay"),
                "apiKey" to JsonPrimitive("sk-live-secret"), "baseUrl" to JsonPrimitive("https://relay.example/v1"))))
            val proposal = f.runtime.preview(create, f.source)
            val text = client.get("/api/creator/proposals?conversationId=session-1").bodyAsText()
            assertFalse(text, text.contains("sk-live-secret"))
            val diff = client.get("/api/creator/proposals/${proposal.proposalId}").obj().getValue("diff").jsonArray.map { it.jsonObject }
            assertTrue(diff.any { it["secret"]?.jsonPrimitive?.content == "true" })
        }
    }

    @Test fun theRuntimeAnnouncesProposalChangesPerConversation() = runBlocking<Unit> {
        val f = ManagementFixture()
        try {
            val seen = async { withTimeout(10_000) { f.runtime.proposalChanges.first() } }
            kotlinx.coroutines.yield()
            kotlinx.coroutines.delay(100)
            f.runtime.preview(quick("Announced"), f.source)
            assertEquals("session-1", seen.await())
        } finally { f.close() }
    }
}
