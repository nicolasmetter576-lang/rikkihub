package me.rerere.rikkihub.web

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith

/**
 * Tests for the mandatory CLI remote-control security gate ([requireCliControlAllowed],
 * [WebCliControlForbiddenException], and `WEB_CLI_CONTROL_FORBIDDEN` status-page mapping)
 * across LAN-unauthenticated, authenticated-JWT, and localhost-only configurations.
 */
class WebCliSecurityAndRoutesTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun testCliBoundary(
        initial: WebAuthConfig,
        block: suspend HttpClient.(AtomicReference<WebAuthConfig>, WebAuthSecurity) -> Unit,
    ) = testApplication {
        val config = AtomicReference(initial)
        val security = WebAuthSecurity(tmp.newFolder("web-auth"))
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            install(Authentication) { installWebJwtAuth(security) { config.get() } }
            installWebApiBoundary { config.get() }
            routing {
                route("/api") {
                    publicWebApiRoutes(
                        security = security,
                        rateLimiter = WebAuthRateLimiter(globalAttemptLimit = 1000, hostFailureLimit = 1000),
                    ) { config.get() }
                    authenticate(WEB_OPEN_PROVIDER, WEB_JWT_PROVIDER) {
                        post("/conversations/{id}/cli") {
                            requireCliControlAllowed(config.get())
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                        }
                        post("/conversations/{id}/cli/restart") {
                            requireCliControlAllowed(config.get())
                            call.respond(HttpStatusCode.OK, mapOf("status" to "restarted"))
                        }
                        post("/conversations/{id}/cli/shell") {
                            requireCliControlAllowed(config.get())
                            call.respond(HttpStatusCode.Accepted, mapOf("status" to "accepted"))
                        }
                    }
                }
            }
        }
        createClient {}.block(config, security)
    }

    @Test
    fun `requireCliControlAllowed rejects LAN without active password auth`() {
        val ex = assertFailsWith<WebCliControlForbiddenException> {
            requireCliControlAllowed(
                WebAuthConfig(jwtEnabled = false, accessPassword = "", localhostOnly = false)
            )
        }
        assertEquals(HttpStatusCode.Forbidden, ex.status)
        assertEquals(WEB_ERROR_CLI_CONTROL_FORBIDDEN, ex.errorCode)

        assertFailsWith<WebCliControlForbiddenException> {
            requireCliControlAllowed(
                WebAuthConfig(jwtEnabled = true, accessPassword = "   ", localhostOnly = false)
            )
        }
    }

    @Test
    fun `requireCliControlAllowed permits localhostOnly or active JWT auth`() {
        requireCliControlAllowed(
            WebAuthConfig(jwtEnabled = false, accessPassword = "", localhostOnly = true)
        )
        requireCliControlAllowed(
            WebAuthConfig(jwtEnabled = true, accessPassword = "secret", localhostOnly = false)
        )
    }

    @Test
    fun `LAN without password returns 403 WEB_CLI_CONTROL_FORBIDDEN on all CLI control routes`() =
        testCliBoundary(WebAuthConfig(jwtEnabled = false, accessPassword = "", localhostOnly = false)) { _, _ ->
            val convId = "00000000-0000-0000-0000-000000000001"
            val endpoints = listOf(
                "/api/conversations/$convId/cli" to """{"agentId":"claude-code"}""",
                "/api/conversations/$convId/cli/restart" to "{}",
                "/api/conversations/$convId/cli/shell" to """{"command":"ls"}""",
            )
            for ((path, body) in endpoints) {
                val res = post(path) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                assertEquals("Expected 403 for $path", HttpStatusCode.Forbidden, res.status)
                val json = JsonInstant.parseToJsonElement(res.bodyAsText()).jsonObject
                assertEquals(
                    WEB_ERROR_CLI_CONTROL_FORBIDDEN,
                    json["errorCode"]?.jsonPrimitive?.content,
                )
            }
        }

    @Test
    fun `authenticated JWT allows CLI control routes through gate`() =
        testCliBoundary(WebAuthConfig(jwtEnabled = true, accessPassword = "secret", localhostOnly = false)) { _, _ ->
            val tokenRes = post("/api/auth/token") {
                contentType(ContentType.Application.Json)
                setBody("""{"password":"secret"}""")
            }
            assertEquals(HttpStatusCode.OK, tokenRes.status)
            val token = JsonInstant.parseToJsonElement(tokenRes.bodyAsText())
                .jsonObject["token"]!!.jsonPrimitive.content

            val convId = "00000000-0000-0000-0000-000000000001"
            val cliRes = post("/api/conversations/$convId/cli") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"agentId":"claude-code"}""")
            }
            assertEquals(HttpStatusCode.OK, cliRes.status)

            val restartRes = post("/api/conversations/$convId/cli/restart") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, restartRes.status)

            val shellRes = post("/api/conversations/$convId/cli/shell") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"command":"ls"}""")
            }
            assertEquals(HttpStatusCode.Accepted, shellRes.status)
        }

    @Test
    fun `localhostOnly allows CLI control routes without password`() =
        testCliBoundary(WebAuthConfig(jwtEnabled = false, accessPassword = "", localhostOnly = true)) { _, _ ->
            val convId = "00000000-0000-0000-0000-000000000001"
            val cliRes = post("/api/conversations/$convId/cli") {
                contentType(ContentType.Application.Json)
                setBody("""{"agentId":"claude-code"}""")
            }
            assertEquals(HttpStatusCode.OK, cliRes.status)

            val restartRes = post("/api/conversations/$convId/cli/restart") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.OK, restartRes.status)

            val shellRes = post("/api/conversations/$convId/cli/shell") {
                contentType(ContentType.Application.Json)
                setBody("""{"command":"ls"}""")
            }
            assertEquals(HttpStatusCode.Accepted, shellRes.status)
        }
}
