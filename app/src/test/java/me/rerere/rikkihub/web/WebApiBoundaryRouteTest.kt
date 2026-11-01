package me.rerere.rikkihub.web

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.close
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkihub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Route-level tests for the production web boundary:
 * [installWebApiBoundary] + [installWebJwtAuth] + [publicWebApiRoutes] +
 * [resolveManagedWebFile]/[respondManagedWebFile] — the same functions
 * `configureWebApi` installs. Only the dependency wiring (SettingsStore →
 * AtomicReference, FilesManager → temp dir) is stubbed; every policy check,
 * error code, JWT verify and file admission under test is production code.
 */
class WebApiBoundaryRouteTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var filesDir: File

    private fun prepareFiles(): File {
        filesDir = tmp.newFolder("files")
        File(filesDir, "upload").mkdirs()
        File(filesDir, "datastore").mkdirs()
        File(filesDir, "subagent_runs").mkdirs()
        File(filesDir, "upload/pic.png").writeBytes(byteArrayOf(1, 2, 3))
        File(filesDir, "upload/evil.html").writeText("<script>alert(1)</script>")
        File(filesDir, "subagent_runs/run-1.json").writeText("{\"status\":\"ok\"}")
        File(filesDir, "datastore/settings.preferences_pb").writeBytes(byteArrayOf(9, 9))
        return filesDir
    }

    private fun testBoundary(
        initial: WebAuthConfig,
        block: suspend HttpClient.(AtomicReference<WebAuthConfig>, WebAuthSecurity) -> Unit,
    ) = testApplication {
        val config = AtomicReference(initial)
        val dir = prepareFiles()
        val security = WebAuthSecurity(tmp.newFolder("web-auth"))
        application {
            install(ContentNegotiation) { json(JsonInstant) }
            installWebApiStatusPages()
            install(Authentication) { installWebJwtAuth(security) { config.get() } }
            installWebApiBoundary { config.get() }
            install(WebSockets)
            routing {
                route("/api") {
                    publicWebApiRoutes(
                        security = security,
                        rateLimiter = WebAuthRateLimiter(globalAttemptLimit = 1000, hostFailureLimit = 1000),
                    ) { config.get() }
                    authenticate(WEB_OPEN_PROVIDER, WEB_JWT_PROVIDER) {
                        authenticatedWebAuthRoutes(security) { config.get() }
                        get("/settings") { call.respondText("ok-settings") }
                        get("/boom") {
                            throw RuntimeException(
                                "db at /data/data/me.rerere.rikkihub/databases/app.db failed; pw=hunter2"
                            )
                        }
                        webSocket("/terminal") { /* handshake covered by gate */ }
                        get("/files/path/{path...}") {
                            val rel = call.pathParameters.getAll("path")!!.joinToString("/")
                            val (_, file) = resolveManagedWebFile(dir, rel)
                            call.respondManagedWebFile(file, managedMime = null)
                        }
                    }
                }
                get("/page") { call.respondText("spa") }
            }
        }
        createClient { install(io.ktor.client.plugins.websocket.WebSockets) }.block(config, security)
    }

    /**
     * Installs the production same-origin CORS helper
     * ([installWebSameOriginCors] from the :web module — the same function
     * `startWebServer` calls) over a minimal read/write surface.
     */
    private fun testCors(
        block: suspend HttpClient.() -> Unit,
    ) = testApplication {
        application {
            installWebSameOriginCors()
            routing {
                get("/ping") { call.respondText("pong") }
                post("/ping") { call.respondText("pong") }
            }
        }
        createClient { }.block()
    }

    private val ACTIVE = WebAuthConfig(jwtEnabled = true, accessPassword = "s3cret")
    private val DISABLED = WebAuthConfig(jwtEnabled = false, accessPassword = "")
    private val MISSING = WebAuthConfig(jwtEnabled = true, accessPassword = " ")

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String? =
        runCatching {
            JsonInstant.parseToJsonElement(bodyAsText()).jsonObject["errorCode"]?.jsonPrimitive?.content
        }.getOrNull()

    // ---------- fail-closed states ----------

    @Test
    fun `auth disabled opens the api for intranet use but keeps origin checks`() = testBoundary(DISABLED) { _, security ->
        val open = get("/api/settings")
        assertEquals(HttpStatusCode.OK, open.status)
        assertEquals("ok-settings", open.bodyAsText())
        assertEquals("nosniff", open.headers["X-Content-Type-Options"])

        val status = get("/api/auth/status")
        assertEquals(HttpStatusCode.OK, status.status)
        assertEquals("disabled", JsonInstant.parseToJsonElement(status.bodyAsText())
            .jsonObject["authState"]!!.jsonPrimitive.content)

        // Token endpoint reports the stable disabled code (handler-level).
        val tokenResp = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"s3cret"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, tokenResp.status)
        assertEquals(WEB_ERROR_AUTH_DISABLED, tokenResp.errorCode())

        // A leftover token is irrelevant while auth is off.
        val (staleToken, _) = security.issueSession(ACTIVE)
        assertEquals(HttpStatusCode.OK, get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $staleToken")
        }.status)

        // Sockets need no credential: the ticket endpoint hands out an empty ticket.
        val ticket = post("/api/auth/ticket") {
            contentType(ContentType.Application.Json)
            setBody("""{"scope":"ws","target":"/api/terminal"}""")
        }
        assertEquals(HttpStatusCode.OK, ticket.status)
        assertEquals("", JsonInstant.parseToJsonElement(ticket.bodyAsText())
            .jsonObject["ticket"]!!.jsonPrimitive.content)

        // Cross-site browser requests stay denied even without auth.
        val crossSite = post("/api/auth/ticket") {
            header(HttpHeaders.Origin, "https://evil.example")
            contentType(ContentType.Application.Json)
            setBody("""{"scope":"ws","target":"/api/terminal"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, crossSite.status)
    }

    @Test
    fun `blank password closes admin api with password-missing code`() = testBoundary(MISSING) { _, security ->
        val denied = get("/api/settings")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(WEB_ERROR_AUTH_NOT_CONFIGURED, denied.errorCode())

        val status = get("/api/auth/status")
        assertEquals("password_missing", JsonInstant.parseToJsonElement(status.bodyAsText())
            .jsonObject["authState"]!!.jsonPrimitive.content)

        val tokenResp = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"x"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, tokenResp.status)
        assertEquals(WEB_ERROR_AUTH_NOT_CONFIGURED, tokenResp.errorCode())
    }

    // ---------- authenticated flow ----------

    @Test
    fun `active auth requires token and accepts valid one`() = testBoundary(ACTIVE) { _, security ->
        val anon = get("/api/settings")
        assertEquals(HttpStatusCode.Unauthorized, anon.status)
        assertEquals(WEB_ERROR_UNAUTHORIZED, anon.errorCode())

        // Wrong password → stable invalid-credentials code.
        val bad = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals(WEB_ERROR_INVALID_CREDENTIALS, bad.errorCode())

        // Right password → token via the production endpoint.
        val ok = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"s3cret"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val token = JsonInstant.parseToJsonElement(ok.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        assertNotNull(token)

        val authed = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, authed.status)
        assertEquals("ok-settings", authed.bodyAsText())

        // Browsers keep the session in an HttpOnly cookie on plain HTTP too (no Secure flag).
        val setCookie = ok.headers.getAll(HttpHeaders.SetCookie)!!.single()
        assertTrue(setCookie.startsWith("$WEB_SESSION_COOKIE="))
        assertTrue(setCookie.contains("HttpOnly", true))
        assertTrue(setCookie.contains("SameSite=Strict", true))
        assertFalse(setCookie.contains("Secure", true))
        assertEquals(HttpStatusCode.OK, get("/api/settings") {
            header(HttpHeaders.Cookie, setCookie.substringBefore(';'))
        }.status)

        // The legacy ?access_token= URL credential is gone — URL-carried long
        // JWTs are no longer a transport at all (401, not silently ignored).
        val viaQuery = get("/api/settings?access_token=$token")
        assertEquals(HttpStatusCode.Unauthorized, viaQuery.status)
    }

    @Test
    fun `single use ticket admits one ws upgrade then dies`() = testBoundary(ACTIVE) { _, security ->
        val (token, _) = security.issueSession(ACTIVE)
        suspend fun mint(target: String): String {
            val response = post("/api/auth/ticket") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""{"scope":"ws","target":"$target"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            return JsonInstant.parseToJsonElement(response.bodyAsText())
                .jsonObject["ticket"]!!.jsonPrimitive.content
        }
        suspend fun denied(ticket: String) {
            try {
                webSocketSession("ws://localhost/api/terminal?ticket=$ticket").close()
                org.junit.Assert.fail("unauthorized WebSocket handshake succeeded")
            } catch (failure: IllegalStateException) {
                // Ktor's in-memory engine rejects failed upgrades before the client
                // plugin can turn the HTTP response into WebSocketException.
                assertTrue("expected rejected WebSocket handshake: ${failure.message}",
                    failure.message.orEmpty().contains("WebSocket") ||
                        failure.message.orEmpty().contains("401"))
            }
        }
        val ticket = mint("/api/terminal")
        val first = webSocketSession("ws://localhost/api/terminal?ticket=$ticket")
        assertEquals(HttpStatusCode.SwitchingProtocols, first.call.response.status)
        first.close()
        denied(ticket)
        val wrongTarget = mint("/api/settings")
        denied(wrongTarget)
        assertEquals(HttpStatusCode.Unauthorized, get("/api/settings?ticket=$wrongTarget").status)
    }

    @Test
    fun `logout revokes the session jti immediately`() = testBoundary(ACTIVE) { _, security ->
        val (token, _) = security.issueSession(ACTIVE)
        suspend fun authed() = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, authed().status)

        val out = post("/api/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, out.status)
        assertEquals(HttpStatusCode.Unauthorized, authed().status)
    }

    @Test
    fun `password change and disable revoke live tokens without restart`() = testBoundary(ACTIVE) { cfg, security ->
        val (token, _) = security.issueSession(ACTIVE)
        suspend fun request() = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        assertEquals(HttpStatusCode.OK, request().status)

        // Password rotated → old token must not verify (no restart needed).
        cfg.set(WebAuthConfig(jwtEnabled = true, accessPassword = "rotated"))
        assertEquals(HttpStatusCode.Unauthorized, request().status)

        // Password cleared → fail-closed, not anonymous-open.
        cfg.set(WebAuthConfig(jwtEnabled = true, accessPassword = ""))
        val cleared = request()
        assertEquals(HttpStatusCode.Forbidden, cleared.status)
        assertEquals(WEB_ERROR_AUTH_NOT_CONFIGURED, cleared.errorCode())

        // JWT disabled entirely → open (intranet mode).
        cfg.set(WebAuthConfig(jwtEnabled = false, accessPassword = "rotated"))
        assertEquals(HttpStatusCode.OK, request().status)
    }

    // ---------- origin enforcement ----------

    @Test
    fun `null and cross origin denied, same origin passes`() = testBoundary(ACTIVE) { _, security ->
        val (token, _) = security.issueSession(ACTIVE)

        val nullOrigin = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Origin, "null")
        }
        assertEquals(HttpStatusCode.Forbidden, nullOrigin.status)
        assertEquals(WEB_ERROR_ORIGIN_DENIED, nullOrigin.errorCode())

        val cross = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Origin, "http://evil.example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, cross.status)
        assertEquals(WEB_ERROR_ORIGIN_DENIED, cross.errorCode())

        // Same origin (explicit host+port match) passes.
        val same = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
        }
        assertEquals(HttpStatusCode.OK, same.status)

        // Same origin with explicit non-default port passes too.
        val samePort = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Host, "localhost:8080")
            header(HttpHeaders.Origin, "http://localhost:8080")
        }
        assertEquals(HttpStatusCode.OK, samePort.status)

        // Origin port mismatch is denied.
        val badPort = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Host, "localhost:8080")
            header(HttpHeaders.Origin, "http://localhost:9090")
        }
        assertEquals(HttpStatusCode.Forbidden, badPort.status)

        // No Origin at all: not authorized by origin check — auth applies.
        val cli = get("/api/settings") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, cli.status)

        // Origin is NOT an authorization substitute: same-origin but no token → 401.
        val noToken = get("/api/settings") {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
        }
        assertEquals(HttpStatusCode.Unauthorized, noToken.status)

        // WS handshake path is covered by the same gate: null origin → 403
        // before the upgrade is even evaluated.
        val wsNull = get("/api/terminal") {
            header(HttpHeaders.Origin, "null")
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Forbidden, wsNull.status)
        assertEquals(WEB_ERROR_ORIGIN_DENIED, wsNull.errorCode())
    }

    // ---------- file admission via the production helpers ----------

    @Test
    fun `files path serves managed upload and forces download for html`() = testBoundary(ACTIVE) { _, security ->
        val (token, _) = security.issueSession(ACTIVE)
        suspend fun file(path: String) = get("/api/files/path/$path") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }

        // Normal managed upload → inline with real mime.
        val png = file("upload/pic.png")
        assertEquals(HttpStatusCode.OK, png.status)
        assertEquals("image/png", png.headers[HttpHeaders.ContentType])
        assertNull(png.headers[HttpHeaders.ContentDisposition])
        assertEquals("nosniff", png.headers["X-Content-Type-Options"])

        // Executable HTML never renders inline on the admin origin.
        val html = file("upload/evil.html")
        assertEquals(HttpStatusCode.OK, html.status)
        assertEquals("attachment", html.headers[HttpHeaders.ContentDisposition])
        assertTrue(html.headers[HttpHeaders.ContentType]!!.startsWith("application/octet-stream"))

        // Subagent run log stays a legal flat resource.
        val log = file("subagent_runs/run-1.json")
        assertEquals(HttpStatusCode.OK, log.status)

        // datastore / private subtrees are denied.
        val ds = file("datastore/settings.preferences_pb")
        assertEquals(HttpStatusCode.Forbidden, ds.status)
        assertEquals(WEB_ERROR_FILE_FORBIDDEN, ds.errorCode())

        // Traversal & nested paths denied.
        assertEquals(HttpStatusCode.Forbidden, file("upload/../datastore/x").status)
        assertEquals(HttpStatusCode.Forbidden, file("upload/sub/deep.png").status)

        // Unknown-but-allowed-shape missing file → 404.
        val missing = file("upload/missing.png")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(WEB_ERROR_FILE_NOT_FOUND, missing.errorCode())

        // File endpoints require auth like everything else under /api.
        val anon = get("/api/files/path/upload/pic.png")
        assertEquals(HttpStatusCode.Unauthorized, anon.status)
    }

    // ---------- error sanitization ----------

    @Test
    fun `unhandled failure returns generic 500 and leaks nothing`() = testBoundary(ACTIVE) { _, security ->
        val (token, _) = security.issueSession(ACTIVE)
        val resp = get("/api/boom") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.InternalServerError, resp.status)
        assertEquals(WEB_ERROR_INTERNAL, resp.errorCode())
        val body = resp.bodyAsText()
        // The real exception text must never reach the client.
        assertFalse(body.contains("hunter2"))
        assertFalse(body.contains("app.db"))
        assertFalse(body.contains("/data/data/"))
        assertFalse(body.contains("RuntimeException"))
        assertTrue(body.contains("Internal server error"))
    }

    @Test
    fun `malformed json and content type errors map to stable 400`() = testBoundary(ACTIVE) { _, _ ->
        val malformed = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"hunter2"""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals(WEB_ERROR_BAD_REQUEST, malformed.errorCode())
        // The submitted secret / raw body fragment must not be echoed back.
        assertFalse(malformed.bodyAsText().contains("hunter2"))

        // Wrong shape (array instead of object) also → 400, no echo.
        val shape = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""["hunter2"]""")
        }
        assertEquals(HttpStatusCode.BadRequest, shape.status)
        assertEquals(WEB_ERROR_BAD_REQUEST, shape.errorCode())
        assertFalse(shape.bodyAsText().contains("hunter2"))

        // Invalid credentials stay a stable 401 and never echo the password.
        val bad = post("/api/auth/token") {
            contentType(ContentType.Application.Json)
            setBody("""{"password":"hunter2"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)
        assertEquals(WEB_ERROR_INVALID_CREDENTIALS, bad.errorCode())
        assertFalse(bad.bodyAsText().contains("hunter2"))
    }

    // ---------- production CORS policy ----------

    @Test
    fun `same origin cors allows read and write, cross and null and preflight denied`() = testCors {
        // Same-origin read + write pass through (CORS skipped for same origin).
        val sameGet = get("/ping") {
            header(HttpHeaders.Origin, "http://localhost")
        }
        assertEquals(HttpStatusCode.OK, sameGet.status)
        assertEquals("pong", sameGet.bodyAsText())

        val samePost = post("/ping") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Origin, "http://localhost")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, samePost.status)

        // No Origin header: not a CORS request — allowed.
        assertEquals(HttpStatusCode.OK, get("/ping").status)

        // Cross-origin simple request → plugin denies with 403.
        val cross = get("/ping") {
            header(HttpHeaders.Origin, "http://evil.example.com")
        }
        assertEquals(HttpStatusCode.Forbidden, cross.status)

        // Opaque `null` origin → denied.
        val nullOrigin = get("/ping") {
            header(HttpHeaders.Origin, "null")
        }
        assertEquals(HttpStatusCode.Forbidden, nullOrigin.status)

        // Cross-origin preflight → denied.
        val preflight = options("/ping") {
            header(HttpHeaders.Origin, "http://evil.example.com")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "Content-Type")
        }
        assertEquals(HttpStatusCode.Forbidden, preflight.status)
        assertNull(preflight.headers[HttpHeaders.AccessControlAllowOrigin])

        // Same-origin preflight must not be denied by CORS.
        val samePreflight = options("/ping") {
            header(HttpHeaders.Origin, "http://localhost")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
        }
        assertNotEquals(HttpStatusCode.Forbidden, samePreflight.status)
    }

    // ---------- non-api surface untouched ----------

    @Test
    fun `non api paths are unaffected by the boundary`() = testBoundary(DISABLED) { _, _ ->
        val page = get("/page")
        assertEquals(HttpStatusCode.OK, page.status)
        assertNull(page.headers["X-Content-Type-Options"])

        // With auth off, unknown /api paths simply fall through to a JSON 404.
        assertEquals(HttpStatusCode.NotFound, get("/api/nonexistent").status)
    }

    @Test
    fun `unknown api path returns json 404 under valid auth`() = testBoundary(ACTIVE) { _, security ->
        val (token, _) = security.issueSession(ACTIVE)
        val unknown = get("/api/nonexistent") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        // No production catch-all is installed in this test module, so the
        // engine's 404 is expected; the key point is the request passed the
        // gate + auth (not 401/403).
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }
}
