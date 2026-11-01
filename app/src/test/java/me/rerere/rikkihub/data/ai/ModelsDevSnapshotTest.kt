package me.rerere.rikkihub.data.ai

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelsDevCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The snapshot that ships in the APK (built by scripts/models-dev/update.py) must be readable and sane. */
class ModelsDevSnapshotTest {
    private val file = File("src/main/assets/models_dev.json")

    private val catalog by lazy { file.inputStream().use(ModelsDevCatalog::decode) }

    @Test
    fun `the shipped snapshot decodes and is not empty`() {
        assertTrue("snapshot missing: ${file.absolutePath}", file.isFile)
        assertTrue("only ${catalog.size} entries", catalog.size > 1000)
        assertTrue("snapshot is unexpectedly large: ${file.length()} bytes", file.length() < 1_000_000)
    }

    @Test
    fun `well-known models carry the capabilities everyone agrees on`() {
        val gpt4o = assertNotNull(catalog.find("gpt-4o"))
        assertTrue(Modality.IMAGE in gpt4o.inputModalities)
        assertTrue(ModelAbility.TOOL in gpt4o.abilities)
        assertTrue((gpt4o.contextLength ?: 0) >= 100_000)

        val sonnet = assertNotNull(catalog.find("claude-sonnet-4-5"))
        assertTrue(Modality.IMAGE in sonnet.inputModalities)
        assertTrue(ModelAbility.REASONING in sonnet.abilities)

        val o3 = assertNotNull(catalog.find("o3"))
        assertTrue(ModelAbility.REASONING in o3.abilities)
    }

    @Test
    fun `provider prefixes and channel suffixes resolve`() {
        assertNotNull(catalog.find("openai/gpt-4o"))
        assertNotNull(catalog.find("anthropic/claude-sonnet-4-5"))
    }

    private fun <T : Any> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }
}
