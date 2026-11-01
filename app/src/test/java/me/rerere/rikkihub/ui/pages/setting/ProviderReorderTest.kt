package me.rerere.rikkihub.ui.pages.setting

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderReorderTest {
    private val a = ProviderSetting.OpenAI(name = "Alpha")
    private val b = ProviderSetting.OpenAI(name = "Beta")
    private val c = ProviderSetting.OpenAI(name = "Alpha proxy")
    private val d = ProviderSetting.OpenAI(name = "Delta")
    private val providers = listOf(a, b, c, d)

    @Test fun `filtered search never applies subset indices to full list`() {
        assertNull(reorderProvidersByKey(providers, "Alpha", a.id, c.id))
        assertEquals(listOf(a, b, c, d), providers)
    }

    @Test fun `nonblank search disables reordering even when every item matches`() {
        assertNull(reorderProvidersByKey(providers, "a", a.id, d.id))
    }

    @Test fun `empty search preserves forward drag behavior`() {
        assertEquals(listOf(b, c, a, d), reorderProvidersByKey(providers, "", a.id, c.id))
    }

    @Test fun `whitespace search uses unfiltered drag behavior`() {
        assertEquals(listOf(d, a, b, c), reorderProvidersByKey(providers, " \t\n", d.id, a.id))
    }

    @Test fun `backward drag moves only selected provider and preserves objects`() {
        val reordered = reorderProvidersByKey(providers, "", c.id, a.id)!!
        assertEquals(listOf(c, a, b, d), reordered)
        assertSame(c, reordered[0])
        assertSame(a, reordered[1])
        assertSame(b, reordered[2])
        assertSame(d, reordered[3])
        assertEquals(listOf(a, b, c, d), providers)
    }

    @Test fun `keys resolve on latest list after an insertion instead of stale indices`() {
        val inserted = ProviderSetting.OpenAI(name = "New")
        val latest = listOf(inserted) + providers
        assertEquals(listOf(inserted, b, c, a, d), reorderProvidersByKey(latest, "", a.id, c.id))
    }

    @Test fun `keys resolve on latest list after a removal`() {
        assertEquals(listOf(c, a, d), reorderProvidersByKey(listOf(a, c, d), "", c.id, a.id))
    }

    @Test fun `removed source or target rejects late callbacks`() {
        assertNull(reorderProvidersByKey(listOf(b, c, d), "", a.id, c.id))
        assertNull(reorderProvidersByKey(listOf(a, b, d), "", a.id, c.id))
    }

    @Test fun `missing or malformed keys never fall back to another provider`() {
        listOf(null, "0", a.id.toString(), Uuid.random(), 0).forEach {
            assertNull(reorderProvidersByKey(providers, "", it, c.id))
            assertNull(reorderProvidersByKey(providers, "", a.id, it))
        }
        assertNull(reorderProvidersByKey(emptyList(), "", a.id, b.id))
    }

    @Test fun `identical keys produce no persistence request`() {
        assertNull(reorderProvidersByKey(providers, "", a.id, a.id))
    }

    @Test fun `ambiguous duplicate IDs fail closed`() {
        assertNull(reorderProvidersByKey(listOf(a, b, a.copy(name = "Duplicate")), "", a.id, b.id))
    }
}
