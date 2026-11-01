package me.rerere.rikkihub.data.creator

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkihub.data.creator.vault.CreatorVaultKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 规范化 digest + 回收密钥加密封存。 */
class CreatorVaultKeystoreTest {

    @Test
    fun `digest is canonical and order-insensitive`() {
        val a = buildJsonObject { put("b", 1); put("a", "x") }
        val b = buildJsonObject { put("a", "x"); put("b", 1) }
        assertEquals(
            me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer.canonicalDigest(a),
            me.rerere.rikkihub.data.creator.session.CreatorCanonicalizer.canonicalDigest(b),
        )
    }

    // ---- keystore ----

    @Test
    fun `seal open roundtrip`() {
        val dir = kotlin.io.path.createTempDirectory("creator-key").toFile()
        val ks = CreatorVaultKeyStore(File(dir, "k.bin"))
        val sealed = ks.seal("secret-before-image".toByteArray())
        assertEquals("secret-before-image", String(ks.open(sealed)!!))
    }

    @Test
    fun `tampered payload does not open`() {
        val dir = kotlin.io.path.createTempDirectory("creator-key").toFile()
        val ks = CreatorVaultKeyStore(File(dir, "k.bin"))
        val sealed = ks.seal("data".toByteArray())
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertNull(ks.open(sealed))
    }

    @Test
    fun `key file persists across instances`() {
        val dir = kotlin.io.path.createTempDirectory("creator-key").toFile()
        val file = File(dir, "k.bin")
        val sealed = CreatorVaultKeyStore(file).seal("x".toByteArray())
        val reopen = CreatorVaultKeyStore(file)
        assertEquals("x", String(reopen.open(sealed)!!))
    }
}
