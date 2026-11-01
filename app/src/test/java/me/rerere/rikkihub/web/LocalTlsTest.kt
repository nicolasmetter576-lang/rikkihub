package me.rerere.rikkihub.web

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import me.rerere.rikkihub.web.tls.Der
import me.rerere.rikkihub.web.tls.LocalCertificates
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import javax.net.ssl.*

class LocalTlsTest {
    private fun pair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private class Fixture {
        val caPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val serverPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ca = LocalCertificates.ca(caPair.public, caPair.private)
        val server = LocalCertificates.server(serverPair.public, caPair.private, ca,
            setOf("localhost", "rikkihub.local"), setOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")))
        val password = "fixture-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(null); setKeyEntry("server", serverPair.private, password, arrayOf(server, ca))
        }
        val trust = KeyStore.getInstance("PKCS12").apply { load(null); setCertificateEntry("ca", ca) }
        val ssl = SSLContext.getInstance("TLS").apply {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            init(null, factory.trustManagers, null)
        }
    }

    @Test fun caAndLeafHaveVerifiedSignaturesAndExplicitConstraints() {
        val f = Fixture()
        f.ca.verify(f.caPair.public); f.server.verify(f.caPair.public)
        f.ca.checkValidity(); f.server.checkValidity()
        assertEquals(0, f.ca.basicConstraints)
        assertEquals(-1, f.server.basicConstraints)
        assertTrue(f.ca.keyUsage[5]); assertTrue(f.server.keyUsage[0])
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), f.server.extendedKeyUsage)
        val sans = f.server.subjectAlternativeNames.map { it[0] to it[1] }
        assertTrue(2 to "localhost" in sans)
        assertTrue(7 to "127.0.0.1" in sans)
        assertFalse(f.ca.encoded.contentEquals(f.server.encoded))
    }

    @Test fun expiredLeafIsRejectedAndOtherCaCannotVerifyIt() {
        val f = Fixture()
        val expired = LocalCertificates.server(f.serverPair.public, f.caPair.private, f.ca,
            setOf("localhost"), setOf(InetAddress.getByName("127.0.0.1")), Instant.now().minusSeconds(366L * 86400))
        assertThrows(Exception::class.java) { expired.checkValidity() }
        assertThrows(Exception::class.java) { f.server.verify(pair().public) }
    }

    @Test fun malformedDerLengthCannotOverrunReader() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x30), byteArrayOf(0x30, 0x80.toByte()),
            byteArrayOf(0x30, 0x84.toByte(), 0x7f, 0x7f, 0x7f, 0x7f), byteArrayOf(0x30, 10, 1))) {
            assertNull(Der.readTlv(bytes))
        }
        assertNull(Der.readTlv(byteArrayOf(0x30, 0), -1))
    }

    @Test(timeout = 30_000) fun actualNettyListenerOnlyAcceptsTrustedHostnameVerifiedTls() = runBlocking<Unit> {
        val f = Fixture()
        val server = startWebServer(0, "127.0.0.1", WebTlsConfig(f.keys, "server", { f.password }, { f.password })) {
            routing { get("/smoke") { call.respondText("local-tls-smoke") } }
        }.start(wait = false)
        try {
            val port = server.engine.resolvedConnectors().single().port
            val connection = java.net.URL("https://localhost:$port/smoke").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = f.ssl.socketFactory
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            assertEquals("local-tls-smoke", connection.inputStream.bufferedReader().use { it.readText() })
            connection.disconnect()
            Socket("127.0.0.1", port).use { transport ->
                val wrongHost = f.ssl.socketFactory.createSocket(transport, "not-the-rikkihub-host.invalid", port, true) as SSLSocket
                wrongHost.use { socket ->
                    socket.soTimeout = 5000
                    val parameters = socket.sslParameters
                    parameters.endpointIdentificationAlgorithm = "HTTPS"
                    parameters.serverNames = listOf(SNIHostName("not-the-rikkihub-host.invalid"))
                    socket.sslParameters = parameters
                    assertThrows(SSLHandshakeException::class.java) { socket.startHandshake() }
                }
            }
            val untrusted = java.net.URL("https://localhost:$port/smoke").openConnection() as HttpsURLConnection
            untrusted.connectTimeout = 5000; untrusted.readTimeout = 5000
            assertThrows(SSLHandshakeException::class.java) { untrusted.inputStream.close() }
            untrusted.disconnect()
            Socket("127.0.0.1", port).use { plain ->
                plain.soTimeout = 3000
                plain.getOutputStream().write("GET /smoke HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                val response = runCatching { plain.getInputStream().readNBytes(64).toString(Charsets.UTF_8) }.getOrDefault("")
                assertFalse("plaintext reached the API", response.contains("local-tls-smoke") || response.startsWith("HTTP/1.1 200"))
            }
        } finally { server.stop(100, 1000) }
    }

    @Test(timeout = 20_000) fun plainHttpLanListenerServesWhenHttpsIsOff() {
        val server = startWebServer(0, "0.0.0.0") {
            routing { get("/smoke") { call.respondText("plain-ok") } }
        }.start(wait = false)
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            val connection = java.net.URL("http://127.0.0.1:$port/smoke").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            assertEquals("plain-ok", connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            connection.disconnect()
        } finally { server.stop(100, 1000) }
    }
}
