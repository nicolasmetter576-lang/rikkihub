package me.rerere.rikkihub.ui.pages.setting.components

import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelPricing
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import me.rerere.rikkihub.data.datastore.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OAuthProviderModelsTest {
    private val price = ModelPricing(2.0, 10.0, 0.2)

    @Before fun install() {
        ModelRegistry.installCatalog {
            ModelsDevCatalog.decode("""{"version":1,"entries":{
                "gpt-5.2":[13,400000,2,10,0.2],"gpt-6-sol":[13,1000000,2,10,0.2]
            }}""")
        }
    }

    @After fun uninstall() = ModelRegistry.installCatalog { null }

    @Test fun `late OAuth refresh preserves current model UUIDs prices and other provider edits`() {
        val original = Model(modelId = "gpt-6-sol")
        val currentModel = original.copy(pricing = price, displayName = "Local name")
        val locallyAdded = Model(modelId = "local")
        val current = ProviderSetting.Devin(name = "Renamed", enabled = true,
            customHeaders = listOf(CustomHeader("X-Test", "local")), models = listOf(currentModel, locallyAdded))
        val remote = Model(modelId = original.modelId, abilities = listOf(ModelAbility.TOOL))
        val newlyDiscovered = Model(modelId = "MODEL_GPT_5_2")
        val settings = Settings(providers = listOf(current))
            .withRefreshedOAuthModels(current.id, listOf(remote, newlyDiscovered, newlyDiscovered))
        val updated = settings.providers.single()
        assertEquals(current.id, updated.id)
        assertEquals(current.name, updated.name)
        assertEquals(current.enabled, updated.enabled)
        assertEquals(current.customHeaders, updated.customHeaders)
        assertEquals(original.id, updated.models[0].id)
        assertEquals(price, updated.models[0].pricing)
        assertEquals(currentModel.displayName, updated.models[0].displayName)
        assertEquals(remote.abilities, updated.models[0].abilities)
        assertEquals(listOf(locallyAdded, newlyDiscovered), updated.models.drop(1))
    }

    @Test fun `fill action applies protocol UID reference prices for all three OAuth providers`() {
        for (provider in listOf(ProviderSetting.Devin(), ProviderSetting.Codex(), ProviderSetting.Antigravity())) {
            val model = Model(modelId = "MODEL_GPT_5_2")
            val initial = Settings(providers = listOf(provider.copyProvider(models = listOf(model))))
            val result = initial.withProviderReferencePrices(provider.id).providers.single()
            assertEquals(model.id, result.models.single().id)
            assertEquals(price, result.models.single().pricing)
            assertNull(initial.providers.single().models.single().pricing)
        }
    }

    @Test fun `bulk filling preserves manual partial and free prices and skips images and unknown models`() {
        val models = listOf(
            Model(modelId = "gpt-6-sol"),
            Model(modelId = "gpt-6-sol", pricing = ModelPricing()),
            Model(modelId = "gpt-6-sol", pricing = ModelPricing(input = 9.0)),
            Model(modelId = "gpt-6-sol", pricing = ModelPricing(0.0, 0.0)),
            Model(modelId = "gpt-6-sol", type = ModelType.IMAGE),
            Model(modelId = "unknown"),
        )
        val provider = ProviderSetting.Devin(models = models)
        val other = ProviderSetting.Codex(models = listOf(Model(modelId = "gpt-6-sol")))
        val result = Settings(providers = listOf(provider, other))
            .withProviderReferencePrices(provider.id)
        assertEquals(price, result.providers[0].models[0].pricing)
        assertEquals(price, result.providers[0].models[1].pricing)
        assertEquals(models.drop(2), result.providers[0].models.drop(2))
        assertEquals(other, result.providers[1])
    }

    @Test fun `refresh and filling serialize on latest settings without undoing either change`() {
        val model = Model(modelId = "gpt-6-sol")
        val provider = ProviderSetting.Devin(models = listOf(model))
        val initial = Settings(providers = listOf(provider))
        val remote = listOf(Model(modelId = model.modelId, abilities = listOf(ModelAbility.TOOL)))
        val fillThenRefresh = initial.withProviderReferencePrices(provider.id)
            .withRefreshedOAuthModels(provider.id, remote)
        val refreshThenFill = initial.withRefreshedOAuthModels(provider.id, remote)
            .withProviderReferencePrices(provider.id)
        assertEquals(fillThenRefresh, refreshThenFill)
        val latest = fillThenRefresh.providers.single()
        assertEquals(price, latest.models.single().pricing)
        assertEquals(model.id, latest.models.single().id)
    }

    @Test fun `late results never recreate a removed provider`() {
        val provider = ProviderSetting.Devin()
        val empty = Settings(providers = emptyList())
        assertTrue(empty.withRefreshedOAuthModels(provider.id, listOf(Model(modelId = "gpt-6-sol"))).providers.isEmpty())
        assertTrue(empty.withProviderReferencePrices(provider.id).providers.isEmpty())
    }
}
