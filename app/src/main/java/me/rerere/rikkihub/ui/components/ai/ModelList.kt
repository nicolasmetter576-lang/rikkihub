package me.rerere.rikkihub.ui.components.ai

import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkihub.ui.components.ui.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkihub.R
import me.rerere.rikkihub.Screen
import me.rerere.rikkihub.data.datastore.SettingsStore
import me.rerere.rikkihub.data.datastore.findModelById
import me.rerere.rikkihub.ui.components.ui.AutoAIIcon
import me.rerere.rikkihub.ui.components.ui.Tag
import me.rerere.rikkihub.ui.components.ui.TagType
import me.rerere.rikkihub.ui.components.ui.icons.HeartIcon
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.hooks.rememberSharedPreferenceString
import me.rerere.rikkihub.ui.theme.extendColors
import me.rerere.rikkihub.utils.toDp
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

/**
 * 模型选择弹层的空态分类。`enabled` 只决定选择器可见性，
 * 不影响已选模型的运行时解析，所以"全部停用"必须和"没有提供商"区分开。
 */
internal enum class ModelListEmptyReason {
    /** 一个 provider 都没有（或还在加载初始 settings） */
    NO_PROVIDERS,

    /** 有 provider，但全部被停用 */
    ALL_DISABLED,

    /** 有启用的 provider，但没有任何该类型的模型 */
    NO_MATCHING_MODELS,
}

internal fun modelListEmptyReason(
    allProviders: List<ProviderSetting>,
    type: ModelType,
): ModelListEmptyReason? {
    if (allProviders.isEmpty()) return ModelListEmptyReason.NO_PROVIDERS
    val enabledProviders = allProviders.fastFilter { it.enabled }
    if (enabledProviders.isEmpty()) return ModelListEmptyReason.ALL_DISABLED
    for (provider in allProviders) {
        if (provider.enabled && provider.models.fastAny { it.type == type }) return null
    }
    return ModelListEmptyReason.NO_MATCHING_MODELS
}

class ModelListState internal constructor(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
) {
    var modelId by mutableStateOf(modelId)
        private set

    var providers by mutableStateOf(providers)
        private set

    var type by mutableStateOf(type)
        private set

    var visible by mutableStateOf(false)
        private set

    // 不能用 derivedStateOf 缓存: update() 在组合期间写入新值后,
    // derived 的缓存不一定随快照应用即时刷新, 实测会读到旧模型(图标不更新)。
    // 直接 getter 与上游一致, 每帧扫描几个 provider 的开销可忽略。
    val currentModel: Model?
        get() = modelId?.let { providers.findModelById(it) }

    val filteredProviders: List<ProviderSetting>
        get() = providers.fastFilter { provider ->
            provider.enabled && provider.models.fastAny { model -> model.type == type }
        }

    fun open() {
        visible = true
    }

    fun close() {
        visible = false
    }

    internal fun update(
        modelId: Uuid?,
        providers: List<ProviderSetting>,
        type: ModelType,
    ) {
        this.modelId = modelId
        this.providers = providers
        this.type = type
    }
}

@Composable
fun rememberModelListState(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
): ModelListState {
    return remember {
        ModelListState(
            modelId = modelId,
            providers = providers,
            type = type,
        )
    }.also {
        it.update(
            modelId = modelId,
            providers = providers,
            type = type,
        )
    }
}

@Composable
fun ModelSelector(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    allowClear: Boolean = false,
    onSelect: (Model) -> Unit
) {
    val state = rememberModelListState(
        modelId = modelId,
        providers = providers,
        type = type,
    )

    ModelSelectorButton(
        state = state,
        modifier = modifier,
        onlyIcon = onlyIcon,
        allowClear = allowClear,
        onClear = { onSelect(Model()) },
    )

    ModelListSheet(
        state = state,
        onSelect = onSelect,
    )
}

@Composable
internal fun ModelSelectorButton(
    state: ModelListState,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    allowClear: Boolean = false,
    onClear: () -> Unit = {},
) {
    val model = state.currentModel

    if (!onlyIcon) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    state.open()
                },
                modifier = modifier
            ) {
                model?.modelId?.let {
                    AutoAIIcon(
                        it, Modifier
                            .padding(end = 4.dp)
                            .size(36.dp),
                        color = Color.Transparent
                    )
                }
                Text(
                    text = model?.displayName ?: stringResource(R.string.model_list_select_model),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (allowClear && model != null) {
                IconButton(
                    onClick = onClear,
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Clear"
                    )
                }
            }
        }
    } else {
        IconButton(
            onClick = {
                state.open()
            },
        ) {
            if (model != null) {
                AutoAIIcon(
                    modifier = Modifier.size(36.dp),
                    name = model.modelId,
                    color = Color.Transparent
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Brain02,
                    contentDescription = stringResource(R.string.setting_model_page_chat_model),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ModelListSheet(
    state: ModelListState,
    onSelect: (Model) -> Unit,
) {
    if (!state.visible) return

    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    fun dismiss() {
        coroutineScope.launch {
            sheetState.hide()
            state.close()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            state.close()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxHeight(0.8f)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModelList(
                currentModel = state.modelId,
                providers = state.filteredProviders,
                allProviders = state.providers,
                modelType = state.type,
                onSelect = {
                    onSelect(it)
                    dismiss()
                },
                onDismiss = {
                    dismiss()
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.ModelList(
    currentModel: Uuid? = null,
    providers: List<ProviderSetting>,
    allProviders: List<ProviderSetting>,
    modelType: ModelType,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()
    val settings = settingsStore.settingsFlow
        .collectAsStateWithLifecycle()

    val settingsValue = settings.value

    val favoriteModelIds = remember(settingsValue.favoriteModels) {
        settingsValue.favoriteModels.toSet()
    }

    val favoriteModels = remember(settingsValue.favoriteModels, settingsValue.providers, modelType) {
        // modelId → model 和 modelId → provider 的索引，避免每个收藏项都线性扫描全部 provider
        val modelsById = buildMap<Uuid, Model> {
            settingsValue.providers.forEach { p ->
                p.models.forEach { m -> putIfAbsent(m.id, m) }
            }
        }
        val providerByModelId = buildMap<Uuid, ProviderSetting> {
            settingsValue.providers.forEach { p ->
                p.models.forEach { m -> putIfAbsent(m.id, p) }
            }
        }
        settingsValue.favoriteModels.mapNotNull { modelId ->
            val model = modelsById[modelId] ?: return@mapNotNull null
            if (model.type != modelType) return@mapNotNull null
            // 停用 provider 的模型在选择器中隐藏，收藏里同样不可选
            val provider = providerByModelId[modelId]?.takeIf { it.enabled }
                ?: return@mapNotNull null
            model to provider
        }
    }

    var searchKeywords by remember { mutableStateOf("") }

    val collapsedProvidersPref by rememberSharedPreferenceString(MODEL_LIST_COLLAPSED_PROVIDERS_KEY, "")
    val collapsedProviders = remember(collapsedProvidersPref) {
        decodeModelListCollapsedProviders(collapsedProvidersPref)
    }
    val context = LocalContext.current
    val collapsePreferences = remember(context) {
        context.getSharedPreferences("rikkihub.preferences", Context.MODE_PRIVATE)
    }
    fun toggleCollapsed(providerId: Uuid) {
        toggleModelListProviderCollapse(
            providerId = providerId,
            searchKeywords = searchKeywords,
            readLatest = { collapsePreferences.getString(MODEL_LIST_COLLAPSED_PROVIDERS_KEY, "") },
            write = { value ->
                collapsePreferences.edit().putString(MODEL_LIST_COLLAPSED_PROVIDERS_KEY, value).apply()
            },
        )
    }

    val searchFilteredModelsByProvider = remember(providers, modelType, searchKeywords) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.type == modelType && it.displayName.contains(searchKeywords, true)
            }
        }
    }

    val emptyReason = modelListEmptyReason(allProviders, modelType)
    // 调用方 settings 还没加载完（VM 初始值 providers=emptyList）时显示加载态，
    // 避免闪现"没有可用AI提供商"
    val isLoading = settingsValue.init ||
        (allProviders.isEmpty() && settingsValue.providers.isNotEmpty())
    val disabledProviders = allProviders.fastFilter { provider ->
        !provider.enabled && provider.models.fastAny { it.type == modelType }
    }
    val prefixItemCount = (if (isLoading || emptyReason != null) 1 else 0) +
        (if (disabledProviders.isEmpty()) 0 else 1 + disabledProviders.size)
    val visibilityProjection = remember(
        providers, searchFilteredModelsByProvider, collapsedProviders, searchKeywords,
        favoriteModels, prefixItemCount,
    ) {
        projectModelListVisibility(
            providers = providers,
            matchingModelsByProvider = searchFilteredModelsByProvider,
            collapsedProviders = collapsedProviders,
            searchKeywords = searchKeywords,
            favoriteModelIds = favoriteModels.map { it.first.id },
            prefixItemCount = prefixItemCount,
        )
    }
    val selectedModelPosition = visibilityProjection.selectedIndex(currentModel)

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedModelPosition
    )
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 计算favorite models在列表中的位置偏移
        val favoriteStartIndex = visibilityProjection.favoriteStartIndex

        val fromIndex = from.index - favoriteStartIndex
        val toIndex = to.index - favoriteStartIndex

        // 只处理favorite models范围内的拖拽
        if (fromIndex >= 0 && toIndex >= 0 &&
            fromIndex < favoriteModels.size && toIndex < favoriteModels.size
        ) {
            val newFavoriteModels = settings.value.favoriteModels.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            coroutineScope.launch {
                settingsStore.tryUpdate { oldSettings ->
                    oldSettings.copy(favoriteModels = newFavoriteModels)
                }
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    val providerPositions = visibilityProjection.providerPositions

    Surface(
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        OutlinedTextField(
            value = searchKeywords,
            onValueChange = { searchKeywords = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.model_list_search_placeholder),
                )
            },
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            leadingIcon = {
                Icon(HugeIcons.Search01, null)
            },
            maxLines = 1,
        )
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        when {
            isLoading -> {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }

            emptyReason != null -> {
                item {
                    ModelListEmptyState(
                        reason = emptyReason,
                        modelType = modelType,
                        onDismiss = onDismiss,
                    )
                }
            }
        }

        // 停用 provider 的模型不参与选择（enabled 只影响选择器可见性，
        // 不影响已选模型的运行时解析），但列出来可以直接重新启用——
        // 否则"已停用"和"没配提供商"共用一句文案，只能干瞪眼
        if (disabledProviders.isNotEmpty()) {
            stickyHeader(key = "header:disabled") {
                Text(
                    text = stringResource(R.string.model_list_disabled_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
                )
            }
            items(
                items = disabledProviders,
                key = { "disabled:" + it.id.toString() },
                contentType = { "disabled_provider" }
            ) { provider ->
                DisabledProviderItem(
                    provider = provider,
                    onEnable = {
                        coroutineScope.launch {
                            settingsStore.tryUpdate { current ->
                                current.copy(
                                    providers = current.providers.map {
                                        if (it.id == provider.id) {
                                            it.copyProvider(enabled = true)
                                        } else it
                                    }
                                )
                            }
                        }
                    },
                )
            }
        }

        if (favoriteModels.isNotEmpty()) {
            stickyHeader {
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = 4.dp, top = 8.dp)
                )
            }

            items(
                items = favoriteModels,
                key = { "favorite:" + it.first.id.toString() },
                contentType = { "favorite_model" }
            ) { (model, provider) ->
                ReorderableItem(
                    state = reorderableState,
                    key = "favorite:" + model.id.toString()
                ) { isDragging ->
                    ModelItem(
                        model = model,
                        onSelect = onSelect,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        providerSetting = provider,
                        select = model.id == currentModel,
                        onDismiss = {
                            onDismiss()
                        },
                        tail = {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        settingsStore.tryUpdate { settings ->
                                            settings.copy(
                                                favoriteModels = settings.favoriteModels.filter { it != model.id }
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        dragHandle = {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        providers.fastForEach { providerSetting ->
            val section = visibilityProjection.sections.first { it.providerId == providerSetting.id }
            stickyHeader(key = "header:${providerSetting.id}") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.clickable(
                            enabled = searchKeywords.isBlank(),
                            role = Role.Button,
                            onClickLabel = stringResource(
                                if (section.collapsed) R.string.model_list_expand_provider
                                else R.string.model_list_collapse_provider,
                                providerSetting.name,
                            ),
                            onClick = { toggleCollapsed(providerSetting.id) },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (section.collapsed) HugeIcons.ArrowRight01 else HugeIcons.ArrowDown01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = providerSetting.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    ProviderBalanceText(
                        providerSetting = providerSetting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            items(
                items = section.visibleModels,
                key = { it.id },
                contentType = { "model" }
            ) { model ->
                val favorite = model.id in favoriteModelIds
                ModelItem(
                    model = model,
                    onSelect = onSelect,
                    modifier = Modifier.animateItem(),
                    providerSetting = providerSetting,
                    select = currentModel == model.id,
                    onDismiss = {
                        onDismiss()
                    },
                    tail = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    settingsStore.tryUpdate { settings ->
                                        if (favorite) {
                                            settings.copy(
                                                favoriteModels = settings.favoriteModels.filter { it != model.id }
                                            )

                                        } else {
                                            settings.copy(
                                                favoriteModels = settings.favoriteModels + model.id
                                            )
                                        }
                                    }
                                }
                            }
                        ) {
                            if (favorite) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Favourite,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    // 供应商Badge行
    val providerBadgeListState = rememberLazyListState()
    LaunchedEffect(lazyListState, providerPositions, providers) {
        // 当LazyColumn滚动时，LazyRow也跟随滚动
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(100) // 防抖处理
            .collect { index ->
                if (index > 0) {
                    val currentProvider = providerPositions.entries.findLast {
                        index >= it.value
                    }
                    val index = providers.indexOfFirst { it.id == currentProvider?.key }
                    if (index >= 0) {
                        providerBadgeListState.animateScrollToItem(index)
                    } else {
                        providerBadgeListState.requestScrollToItem(0)
                    }
                } else {
                    providerBadgeListState.requestScrollToItem(0)
                }
            }
    }
    if (providers.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            state = providerBadgeListState
        ) {
            items(providers, key = { it.id }, contentType = { "provider_chip" }) { provider ->
                AssistChip(
                    onClick = {
                        val position = providerPositions[provider.id] ?: 0
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(position)
                        }
                    },
                    label = {
                        Text(provider.name)
                    },
                    leadingIcon = {
                        AutoAIIcon(name = provider.name, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelListEmptyState(
    reason: ModelListEmptyReason,
    modelType: ModelType,
    onDismiss: () -> Unit,
) {
    val navController = LocalNavController.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
    ) {
        Text(
            text = stringResource(
                when (reason) {
                    ModelListEmptyReason.NO_PROVIDERS -> R.string.model_list_no_providers
                    ModelListEmptyReason.ALL_DISABLED -> R.string.model_list_all_disabled
                    ModelListEmptyReason.NO_MATCHING_MODELS -> R.string.model_list_no_matching_models
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.extendColors.gray6,
        )
        if (reason == ModelListEmptyReason.NO_MATCHING_MODELS) {
            Text(
                text = stringResource(
                    R.string.model_list_no_matching_models_hint,
                    stringResource(modelTypeNameRes(modelType)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.extendColors.gray6,
            )
        }
        if (reason != ModelListEmptyReason.ALL_DISABLED) {
            TextButton(
                onClick = {
                    onDismiss()
                    navController.navigate(Screen.SettingProvider)
                },
            ) {
                Text(stringResource(R.string.model_list_goto_providers))
            }
        }
    }
}

internal fun modelTypeNameRes(type: ModelType): Int = when (type) {
    ModelType.CHAT -> R.string.setting_provider_page_chat_model
    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
    ModelType.IMAGE -> R.string.setting_provider_page_image_model
}

@Composable
private fun DisabledProviderItem(
    provider: ProviderSetting,
    onEnable: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                AutoAIIcon(
                    name = provider.name,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(32.dp)
                )
            }
            Text(
                text = provider.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = false,
                onCheckedChange = { onEnable() },
            )
        }
    }
}

@Composable
private fun ModelItem(
    model: Model,
    providerSetting: ProviderSetting,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (select) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (select) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        enabled = true,
                        onLongClick = {
                            onDismiss()
                            navController.navigate(
                                Screen.SettingProviderDetail(
                                    providerSetting.id.toString()
                                )
                            )
                        },
                        onClick = { onSelect(model) },
                        interactionSource = interactionSource,
                        indication = LocalIndication.current
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ModelTypeTag(model = model)

                        ModelModalityTag(model = model)

                        ModelAbilityTag(model = model)
                    }
                }
                tail()
            }
            dragHandle?.let { it() }
        }
    }
}

@Composable
fun ModelTypeTag(model: Model) {
    Tag(
        type = TagType.INFO
    ) {
        Text(
            text = stringResource(modelTypeNameRes(model.type))
        )
    }
}

@Composable
fun ModelModalityTag(model: Model) {
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
        )
        model.outputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun ModelAbilityTag(model: Model) {
    model.abilities.fastForEach { ability ->
        when (ability) {
            ModelAbility.TOOL -> {
                Tag(
                    type = TagType.WARNING
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
                    )
                }
            }

            ModelAbility.REASONING -> {
                Tag(
                    type = TagType.INFO
                ) {
                    Icon(
                        painter = painterResource(R.drawable.deepthink),
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}
