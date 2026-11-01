package me.rerere.ai.registry

import me.rerere.ai.provider.DEFAULT_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.MAX_MODEL_CONTEXT_LENGTH
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The models.dev snapshot only answers for models the hand-written rules do not recognise. */
class ModelRegistryCatalogTest {
    // `acme-turbo` matches no rule in ModelRegistry; `gpt-4o` and `qwen3-8b` do
    private val catalogJson = """
        {"version":1,"entries":{
          "acme-turbo":[13,64000],
          "plain-model":[0,0],
          "huge":[4,999999999],
          "gpt-4o":[0,1234],
          "qwen3-8b":[13,5678]
        }}
    """.trimIndent()

    @After
    fun uninstall() {
        ModelRegistry.installCatalog { null }
    }

    private fun install() = ModelRegistry.installCatalog { ModelsDevCatalog.decode(catalogJson) }

    private val text = listOf(Modality.TEXT)
    private val textAndImage = listOf(Modality.TEXT, Modality.IMAGE)

    @Test
    fun `without a snapshot unknown models are plain text`() {
        assertEquals(text, ModelRegistry.MODEL_INPUT_MODALITIES.getData("acme-turbo"))
        assertEquals(text, ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("acme-turbo"))
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("acme-turbo"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("acme-turbo"))
    }

    @Test
    fun `an unknown model is filled in from the snapshot`() {
        install()
        assertEquals(textAndImage, ModelRegistry.MODEL_INPUT_MODALITIES.getData("acme-turbo"))
        assertEquals(text, ModelRegistry.MODEL_OUTPUT_MODALITIES.getData("acme-turbo"))
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), ModelRegistry.MODEL_ABILITIES.getData("acme-turbo"))
        assertEquals(64000, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("acme-turbo"))
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), ModelRegistry.MODEL_ABILITIES.getData("vendor/ACME-Turbo:free"))
    }

    @Test
    fun `a model the hand-written rules know keeps their answer, whatever the snapshot says`() {
        val before = listOf(
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("gpt-4o"),
            ModelRegistry.MODEL_ABILITIES.getData("gpt-4o"),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3-8b"),
            ModelRegistry.MODEL_ABILITIES.getData("qwen3-8b"),
        )
        install()
        val after = listOf(
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("gpt-4o"),
            ModelRegistry.MODEL_ABILITIES.getData("gpt-4o"),
            ModelRegistry.MODEL_INPUT_MODALITIES.getData("qwen3-8b"),
            ModelRegistry.MODEL_ABILITIES.getData("qwen3-8b"),
        )
        assertEquals(before, after)
        // gpt-4o is text+image with tools by rule although the snapshot above says plain text
        assertEquals(textAndImage, after[0])
        assertEquals(listOf(ModelAbility.TOOL), after[1])
    }

    @Test
    fun `a model the snapshot lists as plain text stays plain text`() {
        install()
        assertEquals(text, ModelRegistry.MODEL_INPUT_MODALITIES.getData("plain-model"))
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("plain-model"))
        assertEquals(null, ModelRegistry.MODEL_CONTEXT_LENGTH.getData("plain-model"))
    }

    @Test
    fun `a snapshot that cannot be read leaves the hand-written rules alone`() {
        ModelRegistry.installCatalog { error("corrupt") }
        assertEquals(text, ModelRegistry.MODEL_INPUT_MODALITIES.getData("acme-turbo"))
        assertEquals(textAndImage, ModelRegistry.MODEL_INPUT_MODALITIES.getData("gpt-4o"))
        ModelRegistry.installCatalog { ModelsDevCatalog.decode("garbage") }
        assertEquals(emptyList<ModelAbility>(), ModelRegistry.MODEL_ABILITIES.getData("acme-turbo"))
    }

    @Test
    fun `the snapshot is read once and only when first needed`() {
        var loads = 0
        ModelRegistry.installCatalog {
            loads += 1
            ModelsDevCatalog.decode(catalogJson)
        }
        assertEquals(0, loads)
        ModelRegistry.MODEL_ABILITIES.getData("acme-turbo")
        ModelRegistry.MODEL_INPUT_MODALITIES.getData("acme-turbo")
        ModelRegistry.MODEL_CONTEXT_LENGTH.getData("another")
        assertEquals(1, loads)
    }

    @Test
    fun `detected capabilities replace the model's own, and a known window replaces the default`() {
        install()
        val detected = ModelRegistry.withDetectedCapabilities(Model(modelId = "acme-turbo", displayName = "acme-turbo"))
        assertEquals(textAndImage, detected.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), detected.abilities)
        assertEquals(64000, detected.contextLength)
        assertEquals("acme-turbo", detected.displayName)
    }

    @Test
    fun `an unknown window keeps what the model already has`() {
        install()
        val fresh = ModelRegistry.withDetectedCapabilities(Model(modelId = "plain-model"))
        assertEquals(DEFAULT_MODEL_CONTEXT_LENGTH, fresh.contextLength)
        val edited = ModelRegistry.withDetectedCapabilities(Model(modelId = "nobody-knows-me", contextLength = 32000))
        assertEquals(32000, edited.contextLength)
    }

    @Test
    fun `a window beyond the supported maximum is capped`() {
        install()
        assertEquals(MAX_MODEL_CONTEXT_LENGTH, ModelRegistry.withDetectedCapabilities(Model(modelId = "huge")).contextLength)
    }

    @Test
    fun `other fields of the model are untouched`() {
        install()
        val original = Model(modelId = "acme-turbo", displayName = "My Turbo", ccDisguise = true)
        val detected = ModelRegistry.withDetectedCapabilities(original)
        assertEquals(original.id, detected.id)
        assertEquals(original.displayName, detected.displayName)
        assertEquals(original.type, detected.type)
        assertEquals(true, detected.ccDisguise)
    }
}
