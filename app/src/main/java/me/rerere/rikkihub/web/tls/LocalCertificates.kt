package me.rerere.rikkihub.web.tls

import java.math.BigInteger
import java.net.InetAddress
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Small audited DER builder: EC P-256/SHA-256 local CA and hostname-verified leaf. */
internal object LocalCertificates {
    private val algorithm = Der.writer().seq(Der.writer().oid(1, 2, 840, 10045, 4, 3, 2).encoded()).encoded()
    private val time = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
    private fun name(commonName: String) = Der.writer().seq(Der.writer().set(Der.writer().seq(
        Der.writer().oid(2, 5, 4, 3).encoded(), Der.writer().utf8(commonName).encoded()).encoded()).encoded()).encoded()
    private fun extension(oid: IntArray, inner: ByteArray, critical: Boolean = false) = Der.writer().seq(
        Der.writer().oid(*oid).encoded(), if (critical) Der.writer().boolean(true).encoded() else byteArrayOf(),
        Der.writer().octetString(inner).encoded()).encoded()

    fun ca(publicKey: PublicKey, privateKey: PrivateKey, now: Instant = Instant.now()): X509Certificate = issue(
        subject = name("Rikkihub Local CA"), issuer = name("Rikkihub Local CA"),
        publicKey = publicKey, signer = privateKey, now = now, validSeconds = 5L * 365 * 86_400,
        extensions = listOf(
            extension(intArrayOf(2, 5, 29, 19), Der.writer().seq(Der.writer().boolean(true).encoded(),
                Der.writer().intBytes(byteArrayOf(0)).encoded()).encoded(), true),
            extension(intArrayOf(2, 5, 29, 15), byteArrayOf(0x03, 0x02, 0x01, 0x06), true),
        ),
    )

    fun server(
        publicKey: PublicKey, caPrivateKey: PrivateKey, ca: X509Certificate,
        dnsNames: Set<String>, addresses: Set<InetAddress>, now: Instant = Instant.now(),
    ): X509Certificate {
        require(dnsNames.isNotEmpty() && dnsNames.size <= 16 && addresses.size <= 64)
        dnsNames.forEach { require(it.length in 1..253 && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' }) }
        val sans = dnsNames.sorted().map { Der.writer().context(2, it.toByteArray(Charsets.US_ASCII), false).encoded() } +
            addresses.sortedBy { it.hostAddress }.map { Der.writer().context(7, it.address, false).encoded() }
        return issue(name("Rikkihub Local HTTPS"), ca.subjectX500Principal.encoded,
            publicKey, caPrivateKey, now, 365L * 86_400, listOf(
                extension(intArrayOf(2, 5, 29, 19), Der.writer().seq().encoded(), true),
                extension(intArrayOf(2, 5, 29, 15), byteArrayOf(0x03, 0x02, 0x07, 0x80.toByte()), true),
                extension(intArrayOf(2, 5, 29, 37), Der.writer().seq(Der.writer().oid(1, 3, 6, 1, 5, 5, 7, 3, 1).encoded()).encoded()),
                extension(intArrayOf(2, 5, 29, 17), Der.writer().seq(*sans.toTypedArray()).encoded()),
            ))
    }

    private fun issue(
        subject: ByteArray, issuer: ByteArray, publicKey: PublicKey, signer: PrivateKey,
        now: Instant, validSeconds: Long, extensions: List<ByteArray>,
    ): X509Certificate {
        val serial = BigInteger(159, SecureRandom()).add(BigInteger.ONE).toByteArray()
        val tbs = Der.writer().seq(
            Der.writer().explicit(0, Der.writer().intBytes(byteArrayOf(2)).encoded()).encoded(),
            Der.writer().intBytes(serial).encoded(), algorithm, issuer,
            Der.writer().seq(Der.writer().generalizedTime(time.format(now.minusSeconds(300))).encoded(),
                Der.writer().generalizedTime(time.format(now.plusSeconds(validSeconds))).encoded()).encoded(),
            subject, publicKey.encoded,
            Der.writer().explicit(3, Der.writer().seq(*extensions.toTypedArray()).encoded()).encoded(),
        ).encoded()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(signer); update(tbs); sign() }
        val encoded = Der.writer().seq(tbs, algorithm, Der.writer().bitString(signature).encoded()).encoded()
        return CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    }
}
