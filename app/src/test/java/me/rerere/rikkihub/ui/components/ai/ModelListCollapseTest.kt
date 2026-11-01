package me.rerere.rikkihub.ui.components.ai

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ModelListCollapseTest {
    private val a = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val b = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val m1 = Model(modelId = "one")
    private val m2 = Model(modelId = "two")
    private val m3 = Model(modelId = "three")
    private val providers = listOf(
        ProviderSetting.OpenAI(id = a, models = listOf(m1, m2)),
        ProviderSetting.Google(id = b, models = listOf(m3)),
    )
    private val matching = mapOf(a to listOf(m1, m2), b to listOf(m3))

    private fun project(
        folds: Set<Uuid> = emptySet(),
        search: String = "",
        favorites: List<Uuid> = emptyList(),
        prefix: Int = 0,
        models: Map<Uuid, List<Model>> = matching,
    ) = projectModelListVisibility(providers, models, folds, search, favorites, prefix)

    @Test fun `empty and null preference default open`() {
        assertEquals(emptySet<Uuid>(), decodeModelListCollapsedProviders(null))
        assertEquals(emptySet<Uuid>(), decodeModelListCollapsedProviders(""))
        assertTrue(project().sections.none { it.collapsed })
    }

    @Test fun `codec ignores damaged entries and deduplicates valid UUIDs`() {
        assertEquals(setOf(a, b), decodeModelListCollapsedProviders("broken,$a,,$b, $a ,not-a-uuid"))
        assertEquals(emptySet<Uuid>(), decodeModelListCollapsedProviders("null,garbage,0000"))
    }

    @Test fun `codec canonical stable round trip`() {
        val encoded = encodeModelListCollapsedProviders(linkedSetOf(b, a))
        assertEquals("$a,$b", encoded)
        assertEquals(setOf(a, b), decodeModelListCollapsedProviders(encoded))
        assertEquals("", encodeModelListCollapsedProviders(emptySet()))
    }

    @Test fun `new provider defaults open without pruning unknown saved IDs`() {
        val saved = decodeModelListCollapsedProviders(a.toString())
        val projection = project(saved)
        assertTrue(projection.sections[0].collapsed)
        assertFalse(projection.sections[1].collapsed)
        assertEquals(listOf(m3), projection.sections[1].visibleModels)
        assertEquals(setOf(a), saved)
    }

    @Test fun `collapsed provider keeps header and hides only model rows`() {
        val projection = project(setOf(a))
        assertEquals(emptyList<Model>(), projection.sections[0].visibleModels)
        assertEquals(listOf(m1, m2), projection.sections[0].matchingModels)
        assertEquals(mapOf(a to 0, b to 1), projection.providerPositions)
        assertEquals(listOf(m3), projection.sections[1].visibleModels)
    }

    @Test fun `expanded providers preserve model and provider order`() {
        val projection = project()
        assertEquals(listOf(a, b), projection.sections.map { it.providerId })
        assertEquals(listOf(m1, m2, m3), projection.sections.flatMap { it.visibleModels })
        assertEquals(mapOf(a to 0, b to 3), projection.providerPositions)
    }

    @Test fun `search temporarily expands folds and clearing restores them`() {
        val saved = setOf(a, b)
        val searching = project(saved, "two", models = mapOf(a to listOf(m2)))
        assertTrue(searching.sections.none { it.collapsed })
        assertEquals(listOf(m2), searching.sections[0].visibleModels)
        assertEquals(mapOf(a to 0, b to 2), searching.providerPositions)
        assertEquals(1, searching.selectedIndex(m2.id))
        assertEquals(0, searching.selectedIndex(m1.id))
        assertTrue(project(saved).sections.all { it.collapsed })
        assertEquals(setOf(a, b), saved)
    }

    @Test fun `whitespace search does not force expansion`() {
        assertTrue(project(setOf(a), " \t").sections[0].collapsed)
    }

    @Test fun `collapsed selected model maps to its header`() {
        assertEquals(0, project(setOf(a)).selectedIndex(m2.id))
        assertEquals(3, project(setOf(b)).selectedIndex(m3.id))
        assertEquals(1, project(setOf(a, b)).selectedIndex(m3.id))
    }

    @Test fun `expanded selection uses same projection as rendered rows`() {
        val projection = project(setOf(a))
        assertEquals(2, projection.selectedIndex(m3.id))
        assertEquals(2, project().selectedIndex(m2.id))
        assertEquals(4, project().selectedIndex(m3.id))
    }

    @Test fun `favorite selection precedes collapsed provider selection`() {
        val projection = project(setOf(a), favorites = listOf(m3.id, m2.id))
        assertEquals(1, projection.selectedIndex(m3.id))
        assertEquals(2, projection.selectedIndex(m2.id))
        assertEquals(3, projection.selectedIndex(m1.id))
        assertEquals(mapOf(a to 3, b to 4), projection.providerPositions)
    }

    @Test fun `diagnostic disabled section and favorite offsets are shared`() {
        // One diagnostic + disabled header + two recovery rows precede favorites.
        val projection = project(setOf(a), favorites = listOf(m2.id), prefix = 4)
        assertEquals(5, projection.favoriteStartIndex)
        assertEquals(5, projection.selectedIndex(m2.id))
        assertEquals(6, projection.selectedIndex(m1.id))
        assertEquals(8, projection.selectedIndex(m3.id))
        assertEquals(mapOf(a to 6, b to 7), projection.providerPositions)
    }

    @Test fun `no favorites do not add a phantom header`() {
        val projection = project(prefix = 3)
        assertEquals(3, projection.favoriteStartIndex)
        assertEquals(mapOf(a to 3, b to 6), projection.providerPositions)
        assertEquals(7, projection.selectedIndex(m3.id))
    }

    @Test fun `null unknown selection and empty provider projection are safe`() {
        assertEquals(0, project().selectedIndex(null))
        assertEquals(0, project().selectedIndex(Uuid.random()))
        val empty = projectModelListVisibility(emptyList(), emptyMap(), setOf(a), "", emptyList(), 1)
        assertTrue(empty.sections.isEmpty())
        assertTrue(empty.providerPositions.isEmpty())
        assertEquals(0, empty.selectedIndex(m1.id))
    }

    @Test fun `empty matching provider still occupies its actual header`() {
        val projection = project(models = mapOf(b to listOf(m3)))
        assertEquals(mapOf(a to 0, b to 1), projection.providerPositions)
        assertEquals(2, projection.selectedIndex(m3.id))
        assertTrue(projection.sections[0].visibleModels.isEmpty())
    }

    @Test fun `rapid two toggles read actual latest value not captured flow`() {
        var actualPreference: String? = "bad,$b"
        val staleFlow = actualPreference
        val read = { actualPreference }
        val write: (String) -> Unit = { actualPreference = it }
        toggleModelListProviderCollapse(a, "", read, write)
        assertEquals(setOf(a, b), decodeModelListCollapsedProviders(actualPreference))
        toggleModelListProviderCollapse(a, "", read, write)
        assertEquals(setOf(b), decodeModelListCollapsedProviders(actualPreference))
        assertEquals("bad,$b", staleFlow)
    }

    @Test fun `independent picker callbacks merge latest preferences`() {
        var actual: String? = ""
        val pickerOne = { toggleModelListProviderCollapse(a, "", { actual }, { actual = it }) }
        val pickerTwo = { toggleModelListProviderCollapse(b, "", { actual }, { actual = it }) }
        pickerOne()
        pickerTwo()
        assertEquals(setOf(a, b), decodeModelListCollapsedProviders(actual))
        pickerOne()
        assertEquals(setOf(b), decodeModelListCollapsedProviders(actual))
    }

    @Test fun `search fold action neither reads nor writes preferences`() {
        toggleModelListProviderCollapse(a, "match", { error("must not read") }, { error("must not write") })
    }

    @Test fun `concurrent picker toggles serialize latest read and write`() {
        var actual: String? = ""
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(a, b).map { id ->
                pool.submit {
                    start.await()
                    repeat(101) {
                        toggleModelListProviderCollapse(id, "", { actual }, { value ->
                            Thread.yield()
                            actual = value
                        })
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(setOf(a, b), decodeModelListCollapsedProviders(actual))
        } finally {
            pool.shutdownNow()
        }
    }

    private fun appFile(relative: String): File {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "app/src/main").isDirectory }
        return File(root, "app/$relative")
    }

    @Test fun `UI wiring uses actual preferences and one projection with current badge tracking`() {
        val source = appFile("src/main/java/me/rerere/rikkihub/ui/components/ai/ModelList.kt").readText()
        assertTrue(source.contains("rememberSharedPreferenceString(MODEL_LIST_COLLAPSED_PROVIDERS_KEY"))
        assertTrue(source.contains("getSharedPreferences(\"rikkihub.preferences\", Context.MODE_PRIVATE)"))
        assertTrue(source.contains("readLatest = { collapsePreferences.getString("))
        assertTrue(source.contains("collapsePreferences.edit().putString(MODEL_LIST_COLLAPSED_PROVIDERS_KEY, value).apply()"))
        assertTrue(source.contains("items = section.visibleModels"))
        assertTrue(source.contains("visibilityProjection.selectedIndex(currentModel)"))
        assertTrue(source.contains("visibilityProjection.providerPositions"))
        assertTrue(source.contains("visibilityProjection.favoriteStartIndex"))
        assertTrue(source.contains("LaunchedEffect(lazyListState, providerPositions, providers)"))
        assertTrue(source.contains("index >= it.value"))
        assertTrue(source.contains("enabled = searchKeywords.isBlank()"))
        assertTrue(source.contains("role = Role.Button"))
    }

    @Test fun `expand collapse labels are available in English and Chinese`() {
        val source = appFile("src/main/java/me/rerere/rikkihub/ui/components/ai/ModelList.kt").readText()
        for (locale in listOf("values", "values-zh")) {
            val xml = appFile("src/main/res/$locale/strings.xml").readText()
            for (key in listOf("model_list_expand_provider", "model_list_collapse_provider")) {
                assertTrue(source.contains("R.string.$key"))
                val label = Regex("<string name=\"$key\">([^<]+)</string>").find(xml)?.groupValues?.get(1)
                assertNotNull(label)
                assertTrue(label!!.contains("%1\$s"))
                assertTrue(label.contains(if (locale == "values") {
                    if (key.contains("expand")) "Expand" else "Collapse"
                } else {
                    if (key.contains("expand")) "展开" else "折叠"
                }))
            }
        }
    }
}
