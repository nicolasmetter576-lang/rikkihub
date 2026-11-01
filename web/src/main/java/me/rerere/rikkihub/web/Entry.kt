package me.rerere.rikkihub.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import kotlin.time.Duration.Companion.seconds

/**
 * CORS for the embedded web server: same-origin only.
 *
 * With zero allowed hosts the plugin grants no cross-origin access —
 * cross-origin simple requests and preflights get a 403 from the plugin, and
 * same-origin requests are skipped by CORS entirely (allowSameOrigin default).
 * `Origin: null` is not same-origin and is denied. This is defense-in-depth:
 * `installWebApiBoundary` additionally enforces Origin admission on /api.
 * The vite dev proxy rewrites Origin to the target origin (changeOrigin) and
 * therefore keeps working.
 *
 * Extracted (not inline) so app-module route tests can install the exact
 * production CORS policy.
 */
fun Application.installWebSameOriginCors() {
    install(CORS) {
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Upgrade)
        allowNonSimpleContentTypes = true
        // Deliberately no `anyHost()`/`allowHost(...)`: no origin is allowed
        // cross-origin; same-origin requests skip CORS entirely.
    }
}

fun startWebServer(
    port: Int = 8080,
    host: String = "0.0.0.0",
    tls: WebTlsConfig? = null,
    module: suspend Application.() -> Unit
): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    require(port in 0..65535)
    return embeddedServer(Netty, configure = {
        connectionGroupSize = 1
        workerGroupSize = 2
        callGroupSize = 2
        // Preserve the prior HTTP/1.1 Host/Origin contract. Netty otherwise
        // enables HTTP/2 implicitly and strips Host into :authority; admitting
        // that new protocol before its authority policy is audited is unsafe.
        enableHttp2 = false
        enableH2c = false
        if (tls != null) {
            sslConnector(tls.keyStore, tls.keyAlias, tls.keyStorePassword, tls.privateKeyPassword) {
                this.port = port; this.host = host
            }
        } else {
            connector { this.port = port; this.host = host }
        }
    }, module = {
        install(Compression) {
            // SSE streams must not be buffered by the compression encoder.
            excludeContentType(ContentType.Text.EventStream)
        }
        install(ConditionalHeaders)
        install(CachingHeaders)
        installWebSameOriginCors()
        install(SSE)
        install(WebSockets) {
            pingPeriod = 15.seconds
        }
        install(DefaultHeaders)
        routing {
            staticResources("/", "static") {
                default("index.html")
                enableAutoHeadResponse()
                singlePageApplication()
            }
        }
        module()
    })
}
