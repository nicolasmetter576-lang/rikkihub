package me.rerere.rikkihub.ui.components.ai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelListTest {

    private fun chatModel() = Model(modelId = "m", displayName = "m", type = ModelType.CHAT)

    private fun imageModel() = Model(modelId = "i", displayName = "i", type = ModelType.IMAGE)

    @Test
    fun `empty provider list reports NO_PROVIDERS`() {
        assertEquals(
            ModelListEmptyReason.NO_PROVIDERS,
            modelListEmptyReason(emptyList(), ModelType.CHAT)
        )
    }

    @Test
    fun `all providers disabled reports ALL_DISABLED`() {
        val providers = listOf(
            ProviderSetting.OpenAI(enabled = false, models = listOf(chatModel())),
            ProviderSetting.Google(enabled = false, models = listOf(chatModel())),
        )
        assertEquals(
            ModelListEmptyReason.ALL_DISABLED,
            modelListEmptyReason(providers, ModelType.CHAT)
        )
    }

    @Test
    fun `enabled providers without matching model type report NO_MATCHING_MODELS`() {
        val providers = listOf(
            ProviderSetting.OpenAI(enabled = true, models = listOf(imageModel())),
        )
        assertEquals(
            ModelListEmptyReason.NO_MATCHING_MODELS,
            modelListEmptyReason(providers, ModelType.CHAT)
        )
    }

    @Test
    fun `enabled provider with empty models reports NO_MATCHING_MODELS`() {
        val providers = listOf(ProviderSetting.OpenAI(enabled = true, models = emptyList()))
        assertEquals(
            ModelListEmptyReason.NO_MATCHING_MODELS,
            modelListEmptyReason(providers, ModelType.CHAT)
        )
    }

    @Test
    fun `disabled provider holding the only chat model still reports ALL_DISABLED`() {
        // 回归: 停用的提供商不影响已选模型对话, 但必须让选择器说清原因
        val providers = listOf(
            ProviderSetting.OpenAI(enabled = false, models = listOf(chatModel())),
            ProviderSetting.OpenAI(enabled = true, models = listOf(imageModel())),
        )
        assertEquals(
            ModelListEmptyReason.NO_MATCHING_MODELS,
            modelListEmptyReason(providers, ModelType.CHAT)
        )
    }

    @Test
    fun `enabled provider with a matching model reports null`() {
        val providers = listOf(
            ProviderSetting.OpenAI(enabled = false, models = listOf(chatModel())),
            ProviderSetting.OpenAI(enabled = true, models = listOf(chatModel())),
        )
        assertNull(modelListEmptyReason(providers, ModelType.CHAT))
    }

    @Test
    fun `image type uses matching image models`() {
        val providers = listOf(
            ProviderSetting.OpenAI(enabled = true, models = listOf(chatModel())),
        )
        assertEquals(
            ModelListEmptyReason.NO_MATCHING_MODELS,
            modelListEmptyReason(providers, ModelType.IMAGE)
        )
        assertNull(
            modelListEmptyReason(
                listOf(ProviderSetting.OpenAI(enabled = true, models = listOf(imageModel()))),
                ModelType.IMAGE
            )
        )
    }

    @Test
    fun `currentModel follows update`() {
        // 回归: update() 后 currentModel 必须立即反映新 modelId
        // (此前 derivedStateOf 缓存导致聊天页底部模型图标不随切换更新)
        val m1 = chatModel()
        val m2 = chatModel().copy(modelId = "other", displayName = "other")
        val providers = listOf(ProviderSetting.OpenAI(models = listOf(m1, m2)))
        val state = ModelListState(modelId = m1.id, providers = providers, type = ModelType.CHAT)
        assertEquals(m1.id, state.currentModel?.id)
        state.update(m2.id, providers, ModelType.CHAT)
        assertEquals(m2.id, state.currentModel?.id)
        assertEquals(m2.modelId, state.currentModel?.modelId)
    }

    @Test
    fun `filteredProviders follows provider updates`() {
        val m1 = chatModel()
        val enabled = ProviderSetting.OpenAI(models = listOf(m1))
        val disabled = ProviderSetting.Google(enabled = false, models = listOf(m1))
        val state = ModelListState(modelId = m1.id, providers = listOf(enabled, disabled), type = ModelType.CHAT)
        assertEquals(1, state.filteredProviders.size)
        state.update(m1.id, listOf(enabled, disabled.copyProvider(enabled = true)), ModelType.CHAT)
        assertEquals(2, state.filteredProviders.size)
    }
}
