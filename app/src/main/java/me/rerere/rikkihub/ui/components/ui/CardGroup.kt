package me.rerere.rikkihub.ui.components.ui

import kotlinx.coroutines.delay
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import me.rerere.rikkihub.ui.theme.AppTopBarHeight
import androidx.compose.foundation.layout.heightIn
import me.rerere.rikkihub.ui.theme.AppSpacing
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.rikkihub.ui.theme.LocalAmoledStrong
import me.rerere.rikkihub.ui.theme.amoledOutline

internal val CardGroupCorner = 12.dp

private data class CardGroupItem(
    val onClick: (() -> Unit)?,
    val modifier: Modifier,
    val overlineContent: (@Composable () -> Unit)?,
    val headlineContent: (@Composable () -> Unit)?,
    val supportingContent: (@Composable () -> Unit)?,
    val leadingContent: (@Composable () -> Unit)?,
    val trailingContent: (@Composable () -> Unit)?,
    val colors: ListItemColors?,
    // 不为空时是表单项，标题行下方接这块内容
    val formContent: (@Composable ColumnScope.() -> Unit)? = null,
    val searchKey: Int? = null,
)

@DslMarker
private annotation class CardGroupDsl

@CardGroupDsl
interface CardGroupScope {
    fun item(
        onClick: (() -> Unit)? = null,
        modifier: Modifier = Modifier,
        overlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        colors: ListItemColors? = null,
        // 设置搜索用：这一项标题的字符串资源，搜索跳过来时滚到它并闪一下
        searchKey: Int? = null,
        headlineContent: @Composable () -> Unit,
    )

    // 表单项：标题行下方带一块自定义内容（输入框、滑块等），不传标题时整项都是自定义内容
    fun formItem(
        modifier: Modifier = Modifier,
        headlineContent: (@Composable () -> Unit)? = null,
        supportingContent: (@Composable () -> Unit)? = null,
        leadingContent: (@Composable () -> Unit)? = null,
        trailingContent: (@Composable () -> Unit)? = null,
        searchKey: Int? = null,
        content: @Composable ColumnScope.() -> Unit,
    )
}

private class CardGroupScopeImpl : CardGroupScope {
    val items = mutableListOf<CardGroupItem>()

    override fun item(
        onClick: (() -> Unit)?,
        modifier: Modifier,
        overlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        colors: ListItemColors?,
        searchKey: Int?,
        headlineContent: @Composable () -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = onClick,
                modifier = modifier,
                overlineContent = overlineContent,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = colors,
                searchKey = searchKey,
            )
        )
    }

    override fun formItem(
        modifier: Modifier,
        headlineContent: (@Composable () -> Unit)?,
        supportingContent: (@Composable () -> Unit)?,
        leadingContent: (@Composable () -> Unit)?,
        trailingContent: (@Composable () -> Unit)?,
        searchKey: Int?,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        items.add(
            CardGroupItem(
                onClick = null,
                modifier = modifier,
                overlineContent = null,
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                leadingContent = leadingContent,
                trailingContent = trailingContent,
                colors = null,
                formContent = content,
                searchKey = searchKey,
            )
        )
    }
}

// 带开关的项，点整行也能切换
fun CardGroupScope.switchItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    searchKey: Int? = null,
    headlineContent: @Composable () -> Unit,
) = item(
    onClick = if (enabled) {
        { onCheckedChange(!checked) }
    } else null,
    modifier = modifier,
    supportingContent = supportingContent,
    leadingContent = leadingContent,
    trailingContent = {
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    },
    searchKey = searchKey,
    headlineContent = headlineContent,
)

// 列表里第 index 项的形状（首项上角、末项下角圆角，其余直角）：给不方便走 CardGroup 的长列表用，项与项之间放 CardGroupDivider
fun cardGroupItemShape(index: Int, count: Int): Shape {
    val top = if (index == 0) CardGroupCorner else 0.dp
    val bottom = if (index == count - 1) CardGroupCorner else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 卡片组里相邻两项之间的分隔线：项与项紧贴不留空隙，只用这条细线分开 */
@Composable
fun CardGroupDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(horizontal = AppSpacing.sm),
        thickness = CardGroupDividerThickness,
        color = cardGroupDividerColor(),
    )
}

internal val CardGroupDividerThickness = 0.6.dp

// 普通模式各项是浅色底，线淡一些；AMOLED 强劲模式底色纯黑，用全不透明的轮廓色
@Composable
internal fun cardGroupDividerColor(): Color =
    if (LocalAmoledStrong.current) MaterialTheme.colorScheme.outlineVariant
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

// 列表项开头带圆形底色的图标
@Composable
fun CardGroupIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    val animatedContainerColor by animateColorAsState(
        targetValue = containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val animatedContentColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Box(
        modifier = modifier
            .size(40.dp)
            .background(animatedContainerColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = animatedContentColor,
        )
    }
}

// 标题行右侧显示当前取值的小标签，比如滑块的数值
@Composable
fun CardGroupValue(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            // 等宽数字，拖动滑块时标签宽度不跳
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
    }
}

@Composable
private fun CardGroupFormItem(
    item: CardGroupItem,
    formContent: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = item.modifier
            .fillMaxWidth()
            .settingSearchReveal(item.searchKey),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            if (item.headlineContent != null || item.leadingContent != null || item.trailingContent != null) {
                Row(
                    // 和 ListItem 一致：带说明文字时，两侧内容靠上对齐
                    verticalAlignment = if (item.supportingContent != null) Alignment.Top else Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    item.leadingContent?.invoke()
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        item.headlineContent?.let { headline ->
                            ProvideTextStyle(MaterialTheme.typography.bodyLarge, headline)
                        }
                        item.supportingContent?.let { supporting ->
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                            ) {
                                ProvideTextStyle(MaterialTheme.typography.bodySmall, supporting)
                            }
                        }
                    }
                    item.trailingContent?.invoke()
                }
            }
            formContent()
        }
    }
}

@Composable
private fun CardGroupListItem(item: CardGroupItem) {
    CompactListItem(
        headlineContent = item.headlineContent ?: {},
        modifier = item.modifier
            .settingSearchReveal(item.searchKey)
            .then(if (item.onClick != null) Modifier.clickable(onClick = item.onClick) else Modifier),
        overlineContent = item.overlineContent,
        supportingContent = item.supportingContent,
        leadingContent = item.leadingContent,
        trailingContent = item.trailingContent,
        colors = item.colors ?: CustomColors.listItemColors,
    )
}

/**
 * 参数同 M3 ListItem，但更紧凑：单行约 48dp、左右 12dp（M3 单行 56dp、两行 72dp、左右 16dp）。
 * 点击由调用方在 modifier 上加。
 */
@Composable
fun CompactListItem(
    headlineContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.containerColor)
            .heightIn(min = 48.dp)
            // 带说明文字的两行项上下各 10dp（约 62dp，原 M3 为 72dp）
            .padding(horizontal = AppSpacing.sm, vertical = if (supportingContent != null) 10.dp else AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        leadingContent?.let { leading ->
            CompositionLocalProvider(LocalContentColor provides colors.leadingContentColor, content = leading)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            overlineContent?.let { overline ->
                CompositionLocalProvider(LocalContentColor provides colors.overlineContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.labelSmall, overline)
                }
            }
            CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
                ProvideTextStyle(MaterialTheme.typography.bodyLarge, headlineContent)
            }
            supportingContent?.let { supporting ->
                CompositionLocalProvider(LocalContentColor provides colors.supportingContentColor) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall, supporting)
                }
            }
        }
        trailingContent?.let { trailing ->
            CompositionLocalProvider(LocalContentColor provides colors.trailingContentColor) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium, trailing)
            }
        }
    }
}

/** 设置搜索要跳到的项（标题的字符串资源）：目标页里 searchKey 相同的项滚到可见处、闪一下，随即清空 */
object SettingSearchTarget {
    var key by mutableStateOf<Int?>(null)
}

@Composable
private fun Modifier.settingSearchReveal(searchKey: Int?): Modifier {
    if (searchKey == null) return this
    val requester = remember { BringIntoViewRequester() }
    val flash = remember { Animatable(0f) }
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(SettingSearchTarget.key) {
        if (SettingSearchTarget.key != searchKey) return@LaunchedEffect
        SettingSearchTarget.key = null
        requester.bringIntoView()
        flash.snapTo(0.16f)
        delay(1000)
        flash.animateTo(0f, tween(900))
    }
    return bringIntoViewRequester(requester).drawWithContent {
        drawContent()
        if (flash.value > 0f) drawRect(color.copy(alpha = flash.value))
    }
}

/**
 * 设置页列表用的状态：从设置搜索跳过来、目标项还没排进屏幕时，一项一项往下翻直到它出现
 * （它出现后自己滚到可见处并清空目标）；翻到底还没有就放弃。
 */
@Composable
fun rememberSettingSearchListState(): LazyListState {
    val state = rememberLazyListState()
    LaunchedEffect(Unit) {
        withFrameNanos { }
        while (SettingSearchTarget.key != null && state.canScrollForward) {
            state.scrollToItem(state.firstVisibleItemIndex + 1)
            withFrameNanos { }
        }
        SettingSearchTarget.key = null
    }
    return state
}

@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    content: CardGroupScope.() -> Unit,
) {
    val scope = CardGroupScopeImpl()
    scope.content()

    Column(modifier = modifier) {
        if (title != null) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                ProvideTextStyle(MaterialTheme.typography.titleSmallEmphasized) {
                    Box(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        title()
                    }
                }
            }
        }
        // 整组一张卡：外圈圆角裁出，项与项紧贴、中间一条细分隔线；AMOLED 强劲模式下再描一圈边
        val shape = RoundedCornerShape(CardGroupCorner)
        Column(
            modifier = Modifier
                .clip(shape)
                .background(CustomColors.listItemColors.containerColor)
                .amoledOutline(shape)
        ) {
            scope.items.fastForEachIndexed { index, item ->
                val formContent = item.formContent
                if (formContent != null) {
                    CardGroupFormItem(item = item, formContent = formContent)
                } else {
                    CardGroupListItem(item = item)
                }
                if (index != scope.items.lastIndex) CardGroupDivider()
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun CardGroupPreview() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Card Group")
                },
                colors = CustomColors.topBarColors,
                expandedHeight = AppTopBarHeight,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { Text("About") },
            ) {
                item(
                    headlineContent = { Text("第一项") },
                )
                item(
                    headlineContent = { Text("第二项") },
                    supportingContent = { Text("支持文本") },
                )
                item(
                    onClick = {},
                    headlineContent = { Text("第三项") },
                    trailingContent = { Text("→") },
                )
            }
        }
    }
}
