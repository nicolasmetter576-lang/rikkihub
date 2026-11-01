package me.rerere.rikkihub.web

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.rikkihub.web.tls.LocalCertificates
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.nio.file.Files
import javax.net.ssl.*

/** Real TLS socket, real server auth, cookie/CSRF/revocation. No Android/model API required. */
class TlsCookieIntegrationTest {
    @Test(timeout = 40_000) fun httpsLoginUsesHttpOnlyCookieNeverResponseBearerAndRequiresWriteOrigin() = runBlocking<Unit> {
        fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val caKeys = pair(); val keys = pair()
        val ca = LocalCertificates.ca(caKeys.public, caKeys.private)
        val cert = LocalCertificates.server(keys.public, caKeys.private, ca, setOf("localhost"), setOf(InetAddress.getByName("127.0.0.1")))
        val password = "fixture-only".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply { load(null); setKeyEntry("server", keys.private, password, arrayOf(cert, ca)) }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("ca", ca) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, factory.trustManagers, null) }
        val dir = Files.createTempDirectory("tls-auth-").toFile()
        val security = WebAuthSecurity(dir)
        val config = WebAuthConfig(true, "local-fixture-password", requireHttps = true)
        val observed = java.util.concurrent.atomic.AtomicReference<String>()
        val server = startWebServer(0, "127.0.0.1", WebTlsConfig(keyStore, "server", { password }, { password })) {
            intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                observed.set("scheme=${call.request.local.scheme}; host=${call.request.headers["Host"]}; origin=${call.request.headers["Origin"]}; port=${call.request.local.serverPort}")
            }
            install(ContentNegotiation) { json() }
            installWebApiStatusPages()
            installWebApiBoundary { config }
            install(Authentication) { installWebJwtAuth(security) { config } }
            routing {
                route("/api") {
                    publicWebApiRoutes(security, WebAuthRateLimiter()) { config }
                    authenticate(WEB_JWT_PROVIDER) {
                        authenticatedWebAuthRoutes(security) { config }
                        get("/protected") { call.respondText("ordinary-safe") }
                        post("/mutate") { call.respondText("changed") }
                    }
                }
            }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val origin = "https://localhost:$port"
            val client = java.net.http.HttpClient.newBuilder().sslContext(ssl)
                .connectTimeout(java.time.Duration.ofSeconds(5)).build()
            fun request(path: String, method: String = "GET", cookie: String? = null,
                requestOrigin: String? = null, body: String? = null): Triple<Int, String, List<String>> {
                // HttpURLConnection silently strips Origin on this JDK. Use a
                // real client that sends the actual header instead of weakening CSRF.
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI("$origin/api/$path"))
                    .timeout(java.time.Duration.ofSeconds(5))
                if (cookie != null) request.header("Cookie", cookie)
                if (requestOrigin != null) request.header("Origin", requestOrigin)
                if (body != null) request.header("Content-Type", "application/json")
                request.method(method, if (body == null) java.net.http.HttpRequest.BodyPublishers.noBody()
                    else java.net.http.HttpRequest.BodyPublishers.ofString(body))
                val response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
                return Triple(response.statusCode(), response.body(), response.headers().allValues("Set-Cookie"))
            }
            assertEquals(401, request("protected").first)
            val login = request("auth/token", "POST", requestOrigin = origin, body = "{\"password\":\"local-fixture-password\"}")
            assertEquals("login rejected: ${login.second}; ${observed.get()}", 200, login.first)
            assertEquals("", Json.parseToJsonElement(login.second).jsonObject.getValue("token").jsonPrimitive.content)
            val setCookie = login.third.single()
            assertTrue(setCookie.startsWith("$WEB_SESSION_COOKIE="))
            assertTrue(setCookie.contains("HttpOnly", true)); assertTrue(setCookie.contains("Secure", true))
            assertTrue(setCookie.contains("SameSite=Strict", true)); assertTrue(setCookie.contains("Path=/", true))
            assertFalse(setCookie.contains("Domain=", true))
            val cookie = setCookie.substringBefore(';')
            assertEquals(200, request("protected", cookie = cookie).first)
            assertEquals(403, request("mutate", "POST", cookie, body = "{}").first)
            assertEquals(403, request("mutate", "POST", cookie, "null", "{}").first)
            assertEquals(403, request("mutate", "POST", cookie, "https://untrusted.invalid", "{}").first)
            assertEquals(200, request("mutate", "POST", cookie, origin, "{}").first)
            assertEquals(200, request("auth/logout", "POST", cookie, origin, "{}").first)
            assertEquals(401, request("protected", cookie = cookie).first)
        } finally { server.stop(100, 1000); dir.deleteRecursively() }
    }
}
