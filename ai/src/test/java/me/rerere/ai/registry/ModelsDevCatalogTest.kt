package me.rerere.ai.registry

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevCatalogTest {
    private fun catalog(entries: String, version: Int = ModelsDevCatalog.FORMAT_VERSION) =
        ModelsDevCatalog.decode("""{"version":$version,"source":"test","entries":{$entries}}""")

    @Test
    fun `flags decode to modalities and abilities`() {
        val catalog = catalog(
            """
            "plain":[0,0],
            "vision":[1,128000],
            "image-maker":[3,0],
            "agent":[13,200000],
            "tools":[4,8000],
            "thinker":[8,0]
            """,
        )
        catalog.find("plain")!!.let {
            assertEquals(setOf(Modality.TEXT), it.inputModalities)
            assertEquals(setOf(Modality.TEXT), it.outputModalities)
            assertEquals(emptySet<ModelAbility>(), it.abilities)
            assertNull(it.contextLength)
        }
        catalog.find("vision")!!.let {
            assertEquals(setOf(Modality.TEXT, Modality.IMAGE), it.inputModalities)
            assertEquals(setOf(Modality.TEXT), it.outputModalities)
            assertEquals(128000, it.contextLength)
        }
        assertEquals(setOf(Modality.TEXT, Modality.IMAGE), catalog.find("image-maker")!!.outputModalities)
        catalog.find("agent")!!.let {
            assertEquals(setOf(Modality.TEXT, Modality.IMAGE), it.inputModalities)
            assertEquals(setOf(ModelAbility.TOOL, ModelAbility.REASONING), it.abilities)
            assertEquals(200000, it.contextLength)
        }
        assertEquals(setOf(ModelAbility.TOOL), catalog.find("tools")!!.abilities)
        assertEquals(setOf(ModelAbility.REASONING), catalog.find("thinker")!!.abilities)
        assertEquals(6, catalog.size)
    }

    @Test
    fun `lookup ignores case, whitespace, provider prefixes and channel suffixes`() {
        val catalog = catalog(""""claude-sonnet-4.5":[13,200000],"meta/llama-4":[4,1000000],"llama-4":[4,1000000]""")
        assertNotNull(catalog.find("Claude-Sonnet-4.5"))
        assertNotNull(catalog.find("  claude-sonnet-4.5 "))
        assertNotNull(catalog.find("anthropic/claude-sonnet-4.5"))
        assertNotNull(catalog.find("anthropic/claude-sonnet-4.5:thinking"))
        assertNotNull(catalog.find("claude-sonnet-4.5:free"))
        assertNotNull(catalog.find("openrouter/meta/llama-4:free"))
        assertNotNull(catalog.find("meta/llama-4"))
    }

    @Test
    fun `an unknown or blank id finds nothing`() {
        val catalog = catalog(""""known":[4,0]""")
        assertNull(catalog.find("unknown"))
        assertNull(catalog.find(""))
        assertNull(catalog.find("   "))
        assertNull(catalog.find("known-but-longer"))
    }

    @Test
    fun `entries that are not understood are skipped, not fatal`() {
        val catalog = catalog(""""ok":[4,8000],"short":[4],"text":"x","object":{"a":1},"words":["a","b"],"float":[1.5,2]""")
        assertNotNull(catalog.find("ok"))
        assertNull(catalog.find("short"))
        assertNull(catalog.find("text"))
        assertNull(catalog.find("object"))
        assertNull(catalog.find("words"))
    }

    @Test
    fun `a snapshot of another format is refused`() {
        assertThrows(IllegalArgumentException::class.java) { catalog(""""a":[0,0]""", version = 2) }
        assertThrows(Exception::class.java) { ModelsDevCatalog.decode("""{"entries":{}}""") }
        assertThrows(Exception::class.java) { ModelsDevCatalog.decode("not json") }
    }

    @Test
    fun `an empty snapshot is valid and empty`() {
        assertEquals(0, catalog("").size)
    }

    @Test
    fun `streams decode as utf8`() {
        val catalog = ModelsDevCatalog.decode(""" {"version":1,"entries":{"模型-1":[4,0]}} """.trim().byteInputStream())
        assertTrue(catalog.find("模型-1")!!.abilities.contains(ModelAbility.TOOL))
    }
}
