package me.rerere.rikkihub.web.tls

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import me.rerere.rikkihub.web.WebTlsConfig
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Local CA + server key stay in the host UID's non-exportable Android Keystore. */
class AndroidTlsMaterial(private val context: Context, private val aliasPrefix: String = "rikkihub.local") {
    init { require(aliasPrefix.length in 1..160 && aliasPrefix.all { it.isLetterOrDigit() || it == '.' || it == '-' }) }
    private val lock = Any()
    private val caAlias = "$aliasPrefix.ca.v1"
    private val serverAlias = "$aliasPrefix.server.v1"

    data class Material(val config: WebTlsConfig, val fingerprint: String, val certificateFile: File)

    fun load(serviceName: String): Material = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // Keys created without the TLS digest set cannot complete a handshake: replace them.
        if (listOf(caAlias, serverAlias).any { store.containsAlias(it) && !supportsTlsSigning(store, it) }) {
            store.deleteEntry(serverAlias)
            store.deleteEntry(caAlias)
        }
        if (!store.containsAlias(caAlias)) generate(caAlias)
        var ca = store.getCertificate(caAlias) as X509Certificate
        val caKey = store.getKey(caAlias, null) as PrivateKey
        if (ca.basicConstraints < 0) {
            ca = LocalCertificates.ca(ca.publicKey, caKey)
            store.setKeyEntry(caAlias, caKey, null, arrayOf(ca))
        }
        ca.verify(ca.publicKey)
        ca.checkValidity()
        if (!store.containsAlias(serverAlias)) generate(serverAlias)
        val serverKey = store.getKey(serverAlias, null) as PrivateKey
        val oldServer = store.getCertificate(serverAlias) as X509Certificate
        val hostLabel = java.net.IDN.toASCII(serviceName)
        val dns = setOf("localhost", "$hostLabel.local").also { names ->
            require(names.all { it.length <= 253 && it.all { ch -> ch.isLetterOrDigit() || ch == '.' || ch == '-' } })
        }
        val addresses = localAddresses()
        val expected = dns + addresses.map { it.hostAddress!!.substringBefore('%') }
        val existing = oldServer.subjectAlternativeNames.orEmpty().mapNotNull { row -> row.getOrNull(1) as? String }.toSet()
        val valid = oldServer.issuerX500Principal == ca.subjectX500Principal && existing.containsAll(expected) &&
            oldServer.notAfter.after(Date(System.currentTimeMillis() + 86_400_000)) &&
            runCatching { oldServer.verify(ca.publicKey) }.isSuccess
        if (!valid) {
            val leaf = LocalCertificates.server(oldServer.publicKey, caKey, ca, dns, addresses)
            store.setKeyEntry(serverAlias, serverKey, null, arrayOf(leaf, ca))
        }
        // Only the PUBLIC trust certificate enters the explicitly shared cache subtree.
        val directory = File(context.cacheDir, "shared-export/tls").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "$aliasPrefix-ca.crt")
        file.writeBytes(ca.encoded)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(ca.encoded)
            .joinToString(":") { "%02X".format(it) }
        Material(WebTlsConfig(store, serverAlias, { charArrayOf() }, { charArrayOf() }), fingerprint, file)
    }

    /** Caller must stop the listener and revoke auth sessions BEFORE rotation. */
    fun rotate() = synchronized(lock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(serverAlias)
        store.deleteEntry(caAlias)
    }

    private fun supportsTlsSigning(store: KeyStore, alias: String): Boolean = runCatching {
        val key = store.getKey(alias, null) as PrivateKey
        val info = java.security.KeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        info.digests.toSet().containsAll(TLS_SIGNING_DIGESTS.toSet())
    }.getOrDefault(false)

    private fun generate(alias: String) {
        val now = System.currentTimeMillis()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // Conscrypt signs TLS handshakes as NONEwithECDSA over its own hash, and the
                // negotiated scheme may use any SHA-2 size: authorize all of them.
                .setDigests(*TLS_SIGNING_DIGESTS)
                .setCertificateSubject(X500Principal("CN=Rikkihub pending local certificate"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 300_000)).setCertificateNotAfter(Date(now + 5L * 365 * 86_400_000))
                .setUserAuthenticationRequired(false).build())
            generateKeyPair()
        }
    }

    private fun localAddresses(): Set<InetAddress> = buildSet {
        add(InetAddress.getByName("127.0.0.1")); add(InetAddress.getByName("::1"))
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { network ->
            network.inetAddresses.toList().filterNot { it.isAnyLocalAddress || it.isMulticastAddress }.forEach { add(it) }
        }
    }

    private companion object {
        val TLS_SIGNING_DIGESTS = arrayOf(
            KeyProperties.DIGEST_NONE,
            KeyProperties.DIGEST_SHA256,
            KeyProperties.DIGEST_SHA384,
            KeyProperties.DIGEST_SHA512,
        )
    }
}
