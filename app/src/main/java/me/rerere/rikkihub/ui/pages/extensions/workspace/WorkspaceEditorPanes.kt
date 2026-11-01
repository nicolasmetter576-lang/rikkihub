package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.rikkihub.R
import me.rerere.rikkihub.ui.theme.CustomColors
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PANE_ANIMATION_MS = 240
private const val PANE_VELOCITY_THRESHOLD = 900f

/** Swipes must start this far from the screen's left and right edges: those belong to the system back gesture. */
private val PaneEdgeExclusion = 24.dp

/**
 * Two full-width sides next to each other. A mostly horizontal swipe toward the other side slides to it,
 * following the finger and settling by speed or by the halfway point. A swipe only starts where nothing
 * below took the drag (the toolbar's horizontal scroll, a list, a selection), not after a long press
 * (that is text selection), and not at the screen edges. The second side is composed the first time it
 * is needed and then kept, so its file, scroll position and sheets survive sliding back and forth; the
 * side off screen is hidden from accessibility. [first]/[second] get whether they are the side on screen.
 */
@Composable
internal fun WorkspaceEditorPanes(
    showSecond: Boolean,
    onShowSecondChange: (Boolean) -> Unit,
    first: @Composable (active: Boolean) -> Unit,
    second: @Composable (active: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val scope = rememberCoroutineScope()
        // 0 = the first side on screen, width = the second.
        val offset = remember(width) { Animatable(if (showSecond) width else 0f) }
        var dragging by remember { mutableStateOf(false) }
        var secondComposed by rememberSaveable { mutableStateOf(showSecond) }
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val latestShowSecond by rememberUpdatedState(showSecond)
        val latestChange by rememberUpdatedState(onShowSecondChange)

        LaunchedEffect(showSecond, width) {
            if (showSecond) secondComposed = true
            focusManager.clearFocus()
            keyboard?.hide()
            if (!dragging) offset.animateTo(if (showSecond) width else 0f, tween(PANE_ANIMATION_MS))
        }

        fun settle(velocityX: Float) {
            val toSecond = when {
                velocityX < -PANE_VELOCITY_THRESHOLD -> true
                velocityX > PANE_VELOCITY_THRESHOLD -> false
                else -> offset.value > width / 2
            }
            latestChange(toSecond)
            scope.launch {
                offset.animateTo(if (toSecond) width else 0f, tween(PANE_ANIMATION_MS))
                dragging = false
            }
        }

        Box(
            Modifier.fillMaxSize().pointerInput(width) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis
                val edge = PaneEdgeExclusion.toPx()
                awaitEachGesture {
                    // Main pass: children (scrollables, text selection) have had the event first.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var started = false
                    var lastX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        tracker.addPosition(change.uptimeMillis, change.position)
                        if (!change.pressed) {
                            if (started) settle(tracker.calculateVelocity().x)
                            break
                        }
                        if (!started) {
                            if (change.isConsumed || event.changes.size > 1) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (abs(dy) > slop && abs(dy) >= abs(dx)) break
                            if (abs(dx) <= slop || abs(dx) < abs(dy) * 1.5f) continue
                            // Held still first: that was a long press starting a text selection.
                            if (change.uptimeMillis - down.uptimeMillis > longPress) break
                            // Only toward the other side.
                            if ((dx < 0) == latestShowSecond) break
                            started = true
                            dragging = true
                            secondComposed = true
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }
                        change.consume()
                        val delta = change.position.x - lastX
                        lastX = change.position.x
                        scope.launch { offset.snapTo((offset.value - delta).coerceIn(0f, width)) }
                    }
                }
            },
        ) {
            val firstActive = !showSecond
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(-offset.value.roundToInt(), 0) }
                    .then(if (firstActive) Modifier else Modifier.clearAndSetSemantics {}),
            ) { first(firstActive) }
            if (secondComposed) Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset((width - offset.value).roundToInt(), 0) }
                    .then(if (firstActive) Modifier.clearAndSetSemantics {} else Modifier)
                    .testTag("workspace_reference_side"),
            ) { second(!firstActive) }
        }
    }
}

/**
 * The reference side before a file is picked: the workspace file list (the same browser as the workspace
 * page), starting at the main file's folder. A text file opens on this side through [onOpen]. The back
 * key and arrow go up a folder, or slide home at the top.
 */
@Composable
internal fun WorkspaceReferenceBrowser(
    workspaceId: String,
    startArea: WorkspaceStorageArea,
    startDirectory: String,
    active: Boolean,
    onOpen: (WorkspaceStorageArea, String) -> Unit,
    onShowMain: () -> Unit,
) {
    val vm: WorkspaceDetailVM = koinViewModel(
        key = "workspace-reference-files-$workspaceId",
        parameters = { parametersOf(workspaceId) },
    )
    LaunchedEffect(vm) { vm.openDirectoryOnce(startArea, startDirectory) }
    val state by vm.state.collectAsStateWithLifecycle()
    val canGoUp = state.path.isNotBlank()
    fun back() = if (canGoUp) vm.goUp() else onShowMain()
    BackHandler(enabled = active, onBack = ::back)
    Scaffold(
        topBar = {
            // The editor side's compact bar, so both sides line up while sliding.
            WorkspaceEditorTopBar(
                navigation = {
                    IconButton(onClick = ::back) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Text(
                        state.workspace?.name ?: stringResource(R.string.workspace_reference_open),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(HugeIcons.Refresh01, contentDescription = null) }
                },
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        WorkspaceFilesBrowser(
            workspaceId = workspaceId,
            vm = vm,
            state = state,
            contentPadding = innerPadding,
            onOpenTextFile = onOpen,
            backEnabled = active,
        )
    }
}
