package me.rerere.rikkihub.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.web.tls.AndroidTlsMaterial
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Local emulator test; never uses the production aliases or connected phone. */
@RunWith(AndroidJUnit4::class)
class LocalTlsInstrumentedTest {
    @Test(timeout = 90_000)
    fun androidKeystoreKeysAreNonExportableAndNettyTlsActuallyHandshakes() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "rikkihub.test.${java.util.UUID.randomUUID()}"
        val provider = AndroidTlsMaterial(context, prefix)
        var server: io.ktor.server.engine.EmbeddedServer<io.ktor.server.netty.NettyApplicationEngine,
            io.ktor.server.netty.NettyApplicationEngine.Configuration>? = null
        var certificateFile: java.io.File? = null
        try {
            val material = provider.load("rikkihub-test")
            certificateFile = material.certificateFile
            val ca = CertificateFactory.getInstance("X.509").generateCertificate(material.certificateFile.inputStream()) as X509Certificate
            assertEquals(0, ca.basicConstraints)
            assertNotNull(material.config.keyStore.getCertificateChain(material.config.keyAlias))
            val key = material.config.keyStore.getKey(material.config.keyAlias, null) as PrivateKey
            assertNull("TLS private key must not be exportable", key.encoded)
            assertEquals(material.fingerprint, provider.load("rikkihub-test").fingerprint)
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", ca) }
            val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }
            server = startWebServer(0, "127.0.0.1", material.config) {
                routing { get("/local-smoke") { call.respondText("android-tls-smoke") } }
            }.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            val connection = java.net.URL("https://localhost:$port/local-smoke").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = ssl.socketFactory
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            assertEquals("android-tls-smoke", connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            server.stop(100, 1000); server = null
            provider.rotate()
            val rotated = provider.load("rikkihub-test")
            assertNotEquals(material.fingerprint, rotated.fingerprint)
        } finally {
            server?.stop(100, 1000)
            provider.rotate()
            certificateFile?.delete()
        }
    }
}
