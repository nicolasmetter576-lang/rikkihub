package me.rerere.rikkihub.ui.pages.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.rerere.rikkihub.ui.pages.extensions.workspace.WorkspaceFilesBrowser
import me.rerere.rikkihub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.abs
import kotlin.math.roundToInt

private val EdgeStripWidth = 24.dp
private val OpenStripBottomInset = 96.dp
private val OpenStripTopInset = 56.dp
private const val AnimationMs = 240

/**
 * Full-screen workspace files panel that slides in from the right.
 *
 * Open/close drags are owned by a parent [pointerInput] that wraps both chat
 * and the panel. The modifier is keyed only on workspace id / width so an
 * in-progress open swipe is not cancelled when the panel is composed.
 */
@Composable
fun ChatWorkspaceFilesHost(
    workspaceId: String?,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    gesturesEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (workspaceId == null) {
        content()
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val scope = rememberCoroutineScope()
        val offset = remember(panelWidthPx) { Animatable(if (isOpen) 0f else panelWidthPx) }
        var dragging by remember { mutableStateOf(false) }
        val latestOpenChange = rememberUpdatedState(onOpenChange)
        val latestIsOpen = rememberUpdatedState(isOpen)
        val latestDragging = rememberUpdatedState(dragging)
        val latestGesturesEnabled = rememberUpdatedState(gesturesEnabled)
        val density = LocalDensity.current
        val edgePx = with(density) { EdgeStripWidth.toPx() }
        val bottomInsetPx = with(density) { OpenStripBottomInset.toPx() }
        val topInsetPx = with(density) { OpenStripTopInset.toPx() }

        fun snapBy(delta: Float) {
            val next = (offset.value + delta).coerceIn(0f, panelWidthPx)
            scope.launch { offset.snapTo(next) }
        }

        fun settle(velocity: Float) {
            val shouldOpen = ChatWorkspaceSwipe.shouldOpenAfterDrag(
                offset = offset.value,
                panelWidth = panelWidthPx,
                velocity = velocity,
            )
            latestOpenChange.value(shouldOpen)
            scope.launch {
                offset.animateTo(
                    if (shouldOpen) 0f else panelWidthPx,
                    animationSpec = tween(durationMillis = AnimationMs),
                )
                dragging = false
            }
        }

        LaunchedEffect(isOpen, panelWidthPx) {
            if (dragging) return@LaunchedEffect
            val target = if (isOpen) 0f else panelWidthPx
            if (offset.value != target) {
                offset.animateTo(target, animationSpec = tween(durationMillis = AnimationMs))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemGestureExclusion { coords ->
                    val size = coords.size
                    Rect(
                        left = size.width - edgePx,
                        top = topInsetPx,
                        right = size.width.toFloat(),
                        bottom = (size.height - bottomInsetPx).coerceAtLeast(topInsetPx),
                    )
                }
                .pointerInput(workspaceId, panelWidthPx) {
                    val touchSlop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val open = latestIsOpen.value || latestDragging.value
                        if (!open) {
                            if (!latestGesturesEnabled.value) return@awaitEachGesture
                            if (!ChatWorkspaceSwipe.isOpenGestureOrigin(
                                    x = down.position.x,
                                    y = down.position.y,
                                    width = size.width.toFloat(),
                                    height = size.height.toFloat(),
                                    edgePx = edgePx,
                                    topInsetPx = topInsetPx,
                                    bottomInsetPx = bottomInsetPx,
                                )
                            ) {
                                return@awaitEachGesture
                            }
                        }
                        trackHorizontalDrag(
                            downPosition = down.position,
                            downTime = down.uptimeMillis,
                            pointerId = down.id,
                            touchSlop = touchSlop,
                            requireLeftwardStart = !open,
                            onDragStarted = { dragging = true },
                            onDelta = { snapBy(it) },
                            onDragStopped = { settle(it) },
                        )
                    }
                },
        ) {
            content()
            ChatWorkspaceFilesPane(
                workspaceId = workspaceId,
                isOpen = isOpen,
                dragging = dragging,
                offset = offset,
                panelWidthPx = panelWidthPx,
                onOpenChange = onOpenChange,
            )
        }
    }
}

@Composable
private fun ChatWorkspaceFilesPane(
    workspaceId: String,
    isOpen: Boolean,
    dragging: Boolean,
    offset: Animatable<Float, AnimationVector1D>,
    panelWidthPx: Float,
    onOpenChange: (Boolean) -> Unit,
) {
    // 组合期不读 offset.value：拖动/动画每帧都变，用 derivedStateOf 只在可见性翻转时重组
    val panelVisible by remember(isOpen, dragging, offset, panelWidthPx) {
        derivedStateOf { isOpen || dragging || offset.value < panelWidthPx - 1f }
    }
    if (!panelVisible) return

    val vm: WorkspaceDetailVM = koinViewModel(
        key = "workspace-files-$workspaceId",
        parameters = { parametersOf(workspaceId) },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val canGoUp = state.path.isNotBlank()

    BackHandler(enabled = isOpen) {
        when (ChatWorkspaceSwipe.backAction(
            filesDrawerOpen = true,
            canNavigateUpInFiles = canGoUp,
            conversationDrawerOpen = false,
        )) {
            ChatWorkspaceSwipe.BackAction.NavigateUpInFiles -> vm.goUp()
            else -> onOpenChange(false)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f)
            .offset { IntOffset(offset.value.roundToInt(), 0) },
        color = CustomColors.topBarColors.containerColor,
        shadowElevation = 4.dp,
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = state.workspace?.name
                                ?: stringResource(R.string.workspace_detail_tab_files),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (canGoUp) vm.goUp() else onOpenChange(false)
                            },
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowLeft01,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::refresh) {
                            Icon(HugeIcons.Refresh01, contentDescription = null)
                        }
                    },
                    colors = CustomColors.topBarColors,
                )
            },
            containerColor = CustomColors.topBarColors.containerColor,
        ) { innerPadding ->
            WorkspaceFilesBrowser(
                workspaceId = workspaceId,
                vm = vm,
                state = state,
                interceptBackToGoUp = false,
                contentPadding = innerPadding,
            )
        }
    }
}

private suspend fun AwaitPointerEventScope.trackHorizontalDrag(
    downPosition: Offset,
    downTime: Long,
    pointerId: PointerId,
    touchSlop: Float,
    requireLeftwardStart: Boolean,
    onDragStarted: () -> Unit,
    onDelta: (Float) -> Unit,
    onDragStopped: (Float) -> Unit,
) {
    val velocityTracker = VelocityTracker()
    velocityTracker.addPosition(downTime, downPosition)
    var draggingPanel = false
    var lastPosition = downPosition
    var currentId = pointerId
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == currentId }
            ?: event.changes.firstOrNull()
            ?: break
        currentId = change.id
        if (!change.pressed) {
            if (draggingPanel) {
                onDragStopped(velocityTracker.calculateVelocity().x)
            }
            break
        }
        val delta = change.position - lastPosition
        lastPosition = change.position
        velocityTracker.addPosition(change.uptimeMillis, change.position)
        if (!draggingPanel) {
            val fromDown = change.position - downPosition
            if (abs(fromDown.y) > touchSlop && abs(fromDown.y) > abs(fromDown.x)) {
                break
            }
            val isHorizontal = abs(fromDown.x) > touchSlop && abs(fromDown.x) > abs(fromDown.y)
            val isLeftward = fromDown.x < 0f
            if (isHorizontal && (!requireLeftwardStart || isLeftward)) {
                draggingPanel = true
                onDragStarted()
                change.consume()
                onDelta(fromDown.x)
            }
        } else {
            change.consume()
            onDelta(delta.x)
        }
    }
}
