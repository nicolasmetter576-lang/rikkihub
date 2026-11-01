package me.rerere.rikkihub.web.routes

import me.rerere.ai.provider.MAX_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.registry.ModelsDevCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `GET /providers/model-capabilities` answers with exactly what the app's add-model dialog would fill in. */
class ModelCapabilitiesRouteTest {
    @After
    fun uninstall() {
        ModelRegistry.installCatalog { null }
    }

    @Test
    fun `a model the rules know is described by the rules`() {
        val capabilities = modelCapabilitiesFor("gpt-4o")
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), capabilities.inputModalities)
        assertEquals(listOf(Modality.TEXT), capabilities.outputModalities)
        assertEquals(listOf(ModelAbility.TOOL), capabilities.abilities)
    }

    @Test
    fun `an unknown model is plain text with no window`() {
        val capabilities = modelCapabilitiesFor("nobody-has-heard-of-this")
        assertEquals(listOf(Modality.TEXT), capabilities.inputModalities)
        assertEquals(listOf(Modality.TEXT), capabilities.outputModalities)
        assertEquals(emptyList<ModelAbility>(), capabilities.abilities)
        assertNull(capabilities.contextLength)
    }

    @Test
    fun `the models dev snapshot fills in models the rules do not know, window included`() {
        ModelRegistry.installCatalog {
            ModelsDevCatalog.decode("""{"version":1,"entries":{"acme-turbo":[13,64000],"huge":[0,999999999]}}""")
        }
        val capabilities = modelCapabilitiesFor("acme-turbo")
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), capabilities.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), capabilities.abilities)
        assertEquals(64000, capabilities.contextLength)
        assertEquals(MAX_MODEL_CONTEXT_LENGTH, modelCapabilitiesFor("huge").contextLength)
    }
}
