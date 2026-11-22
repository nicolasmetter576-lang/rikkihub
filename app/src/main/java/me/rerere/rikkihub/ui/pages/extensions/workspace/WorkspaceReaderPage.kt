package me.rerere.rikkihub.ui.pages.extensions.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.TextFont
import me.rerere.rikkihub.R
import me.rerere.rikkihub.RouteActivity
import me.rerere.rikkihub.data.repository.WorkspaceRepository
import me.rerere.rikkihub.ui.context.LocalNavController
import me.rerere.rikkihub.ui.context.LocalSettings
import me.rerere.rikkihub.ui.theme.LocalAmoledStrong
import me.rerere.rikkihub.ui.theme.amoledOutlineBorder
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/** The chapter file being read and whether to open it at its end (paging backwards from the next chapter). */
private data class ReaderChapter(val path: String, val fromEnd: Boolean = false)

/** Styled reading text together with per-line character slices in [text]. */
internal data class ReaderDocument(
    val text: AnnotatedString,
    val lineRanges: List<IntRange>,
) {
    fun line(index: Int): AnnotatedString = lineRanges[index].let { text.subSequence(it.first, it.last + 1) }
}

/** Minimal viewport geometry of one visible LazyColumn item for pure scroll-progress calculation. */
internal data class VisibleReaderItem(
    val index: Int,
    val offsetPx: Int,
    val sizePx: Int,
)

/**
 * Reading mode for a Markdown / plain text file: persistent top bar (never occluding body text),
 * switchable between horizontal pages and vertical scrolling. Left/right thirds turn or scroll one
 * screen; the middle third toggles the translucent bottom progress bar (with fast-scrub slider and
 * multi-chapter buttons annotated with chapter filenames).
 */
@Composable
fun WorkspaceReaderPage(id: String, area: WorkspaceStorageArea, path: String) {
    val repository = koinInject<WorkspaceRepository>()
    val nav = LocalNavController.current
    val folder = path.substringBeforeLast('/', missingDelimiterValue = "")
    var chapter by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<ReaderChapter, String>(
        save = { it.path },
        restore = { ReaderChapter(it) },
    )) { mutableStateOf(ReaderChapter(path)) }
    var progressVisible by rememberSaveable { mutableStateOf(false) }
    var typographyOpen by remember { mutableStateOf(false) }
    if (typographyOpen) {
        WorkspaceEditorTypographySheet(
            onDismiss = { typographyOpen = false },
            topRows = {
                Text(stringResource(R.string.setting_workspace_reader), style = MaterialTheme.typography.titleMedium)
                WorkspaceReaderModeRow()
                HorizontalDivider()
                WorkspaceReaderVolumeKeysRow()
            },
        )
    }

    val chapters by produceState<List<String>>(listOf(path), id, area, folder) {
        value = runCatching { repository.listFiles(id, area, folder) }.getOrNull()
            ?.filter { !it.isDirectory && isReadableName(it.name) }
            ?.sortedWith(compareBy(naturalNameOrder) { it.name })
            ?.map { it.path }
            ?.takeIf { path in it }
            ?: listOf(path)
    }
    val position = chapters.indexOf(chapter.path)
    val previous = chapters.getOrNull(position - 1)
    val next = chapters.getOrNull(position + 1)
    val previousTitle = previous?.substringAfterLast('/')
    val nextTitle = next?.substringAfterLast('/')
    val verticalScroll = LocalSettings.current.displaySetting.workspaceReaderVerticalScroll

    // Explicit onSurface: outside a Surface the content color is black, unreadable on dark themes.
    val style = rememberWorkspaceEditorTextStyle(MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface))
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val quotedColor = workspaceQuotedTextColor()

    BackHandler(enabled = progressVisible) { progressVisible = false }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding(),
    ) {
        ReaderTopBar(
            fileName = chapter.path.substringAfterLast('/'),
            chapterIndex = position,
            chapterCount = chapters.size,
            onBack = { nav.popBackStack() },
            onOpenTypography = { typographyOpen = true },
        )

        // Each chapter loads from scratch: no frame shows the new chapter with the previous one's text.
        key(chapter.path) {
            var anchorCharOffset by rememberSaveable(chapter) {
                mutableIntStateOf(if (chapter.fromEnd) Int.MAX_VALUE else 0)
            }
            val content by produceState<Result<String>?>(null) {
                value = runCatching { repository.readTextSnapshotForPreview(id, area, chapter.path).content }
            }
            val loaded = content
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    loaded == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    loaded.isFailure -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.workspace_editor_unavailable), color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        val markdown = chapter.path.substringAfterLast('.').lowercase().let { it == "md" || it == "markdown" }
                        val document = remember(loaded, markdown, style, quotedColor) {
                            buildReaderDocument(loaded.getOrThrow(), markdown, style, quotedColor)
                        }
                        if (verticalScroll) {
                            ReaderScrollContent(
                                chapter = chapter,
                                document = document,
                                style = style,
                                initialCharOffset = anchorCharOffset,
                                onAnchorChange = { anchorCharOffset = it },
                                fileName = chapter.path.substringAfterLast('/'),
                                chapterIndex = position,
                                chapterCount = chapters.size,
                                previousTitle = previousTitle,
                                nextTitle = nextTitle,
                                progressVisible = progressVisible,
                                onToggleProgress = { progressVisible = !progressVisible },
                                onSwipePrevious = { previous?.let { chapter = ReaderChapter(it, fromEnd = true) } },
                                onJumpPrevious = { previous?.let { chapter = ReaderChapter(it) } },
                                onJumpNext = { next?.let { chapter = ReaderChapter(it) } },
                            )
                        } else {
                            ReaderPagedContent(
                                chapter = chapter,
                                text = document.text,
                                style = style,
                                measurer = measurer,
                                initialCharOffset = anchorCharOffset,
                                onAnchorChange = { anchorCharOffset = it },
                                fileName = chapter.path.substringAfterLast('/'),
                                chapterIndex = position,
                                chapterCount = chapters.size,
                                previousTitle = previousTitle,
                                nextTitle = nextTitle,
                                progressVisible = progressVisible,
                                onToggleProgress = { progressVisible = !progressVisible },
                                onSwipePrevious = { previous?.let { chapter = ReaderChapter(it, fromEnd = true) } },
                                onJumpPrevious = { previous?.let { chapter = ReaderChapter(it) } },
                                onJumpNext = { next?.let { chapter = ReaderChapter(it) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    fileName: String,
    chapterIndex: Int,
    chapterCount: Int,
    onBack: () -> Unit,
    onOpenTypography: () -> Unit,
) {
    val amoled = LocalAmoledStrong.current
    Surface(
        color = if (amoled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = if (amoled) 0.dp else 2.dp,
        modifier = Modifier.fillMaxWidth().testTag("workspace_reader_top_bar"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 2.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("workspace_reader_back")) {
                    Icon(HugeIcons.ArrowLeft01, contentDescription = stringResource(R.string.back))
                }
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (chapterCount > 1 && chapterIndex >= 0) {
                        Text(
                            text = stringResource(R.string.workspace_reader_chapter, chapterIndex + 1, chapterCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onOpenTypography, modifier = Modifier.testTag("workspace_reader_typography")) {
                    Icon(HugeIcons.TextFont, contentDescription = stringResource(R.string.editor_typography_title))
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (amoled) 0.7f else 0.45f),
            )
        }
    }
}

/** Registers the volume-key page-turning listener for this reader mode while [onTurn] is active. */
@Composable
private fun ReaderVolumeKeyEffect(onTurn: (Int) -> Unit) {
    val volumeKeys by rememberUpdatedState(LocalSettings.current.displaySetting.workspaceReaderVolumeKeys)
    val latestTurn by rememberUpdatedState(onTurn)
    val activity = LocalActivity.current as? RouteActivity
    DisposableEffect(activity) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (volumeKeys) latestTurn(if (isVolumeUp) -1 else 1)
            volumeKeys
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose { activity?.volumeKeyListeners?.remove(listener) }
    }
}

/** Splits reading-surface taps into left/right thirds (backward/forward) and middle third (progress bar toggle). */
@Composable
private fun Modifier.readerTapZones(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleProgress: () -> Unit,
): Modifier {
    val latestPrevious by rememberUpdatedState(onPrevious)
    val latestNext by rememberUpdatedState(onNext)
    val latestToggle by rememberUpdatedState(onToggleProgress)
    return pointerInput(Unit) {
        detectTapGestures { offset ->
            when {
                offset.x < size.width / 3f -> latestPrevious()
                offset.x > size.width * 2f / 3f -> latestNext()
                else -> latestToggle()
            }
        }
    }
}

@Composable
private fun ColumnScope.ReaderFooterText(text: String, style: TextStyle, hidden: Boolean) {
    Text(
        text = text,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(Alignment.End)
            .padding(vertical = 6.dp)
            .alpha(if (hidden) 0f else 1f)
            .testTag("workspace_reader_page"),
    )
}

/**
 * Horizontal paged reading view, plus a turning page at each end when there is a chapter on that
 * side: settling on it switches chapters (to the first page forwards, the last page backwards).
 */
@Composable
private fun ReaderPagedContent(
    chapter: ReaderChapter,
    text: AnnotatedString,
    style: TextStyle,
    measurer: TextMeasurer,
    initialCharOffset: Int,
    onAnchorChange: (Int) -> Unit,
    fileName: String,
    chapterIndex: Int,
    chapterCount: Int,
    previousTitle: String?,
    nextTitle: String?,
    progressVisible: Boolean,
    onToggleProgress: () -> Unit,
    onSwipePrevious: () -> Unit,
    onJumpPrevious: () -> Unit,
    onJumpNext: () -> Unit,
) {
    val footerStyle = MaterialTheme.typography.labelSmall
    val latestAnchorOffset by rememberUpdatedState(initialCharOffset)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val horizontalPaddingPx = with(density) { 40.dp.toPx() }
        val topPaddingPx = with(density) { 6.dp.toPx() }
        val footerHeight = with(density) { (footerStyle.lineHeight.takeIf { it.isSpecified } ?: 16.sp).toPx() } + with(density) { 12.dp.toPx() }
        val width = (constraints.maxWidth - horizontalPaddingPx).toInt().coerceAtLeast(1)
        val height = (constraints.maxHeight - topPaddingPx - footerHeight).toInt().coerceAtLeast(1)
        val pages by produceState<Pair<String, List<IntRange>>?>(null, text, style, width, height) {
            value = text.text to withContext(Dispatchers.Default) { paginate(measurer, text, style, width, height) }
        }
        // produceState keeps its last value until the new pagination arrives: ranges of another text
        // could reach past this one's end. A new style keeps the text, so its old ranges stay usable meanwhile.
        val ranges = pages?.takeIf { it.first == text.text }?.second
        if (ranges == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@BoxWithConstraints
        }
        val lead = if (previousTitle != null) 1 else 0
        val count = ranges.size + lead + if (nextTitle != null) 1 else 0
        // A new pager per chapter and layout, anchored to the current reading character offset.
        key(chapter, ranges) {
            val initialContentPage = remember(chapter, ranges) {
                pageForCharOffset(ranges, latestAnchorOffset)
            }
            val pager = rememberPagerState(initialPage = lead + initialContentPage) { count }
            val scope = rememberCoroutineScope()
            fun turn(by: Int) = scope.launch { pager.animateScrollToPage((pager.currentPage + by).coerceIn(0, count - 1)) }
            ReaderVolumeKeyEffect(::turn)

            LaunchedEffect(pager.settledPage, ranges) {
                when {
                    previousTitle != null && pager.settledPage == 0 -> onSwipePrevious()
                    nextTitle != null && pager.settledPage == count - 1 -> onJumpNext()
                    else -> {
                        val contentPage = pager.settledPage - lead
                        if (contentPage in ranges.indices && contentPage != pageForCharOffset(ranges, latestAnchorOffset)) {
                            onAnchorChange(ranges[contentPage].first)
                        }
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp).testTag("workspace_reader_pager"),
                ) { index ->
                    val page = index - lead
                    Box(
                        Modifier
                            .fillMaxSize()
                            .readerTapZones(
                                onPrevious = { turn(-1) },
                                onNext = { turn(1) },
                                onToggleProgress = onToggleProgress,
                            ),
                    ) {
                        when {
                            page < 0 -> ChapterTurn(previousTitle.orEmpty())
                            page > ranges.lastIndex -> ChapterTurn(nextTitle.orEmpty())
                            else -> Text(text.subSequence(ranges[page].first, ranges[page].last + 1), style = style)
                        }
                    }
                }
                val current = (pager.currentPage - lead).coerceIn(0, ranges.lastIndex)
                ReaderFooterText(text = "${current + 1} / ${ranges.size}", style = footerStyle, hidden = progressVisible)
            }

            val current = (pager.currentPage - lead).coerceIn(0, ranges.lastIndex)
            var draggingPage by remember(ranges) { mutableStateOf<Float?>(null) }
            var scrollJob by remember(ranges) { mutableStateOf<Job?>(null) }
            val shownPage = draggingPage?.let { sliderValueToPage(it, ranges.size) } ?: current
            val percent = readerPagePercent(shownPage, ranges.size)

            ReaderProgressOverlay(
                visible = progressVisible,
                fileName = fileName,
                chapterIndex = chapterIndex,
                chapterCount = chapterCount,
                progressLabel = "${shownPage + 1} / ${ranges.size} · $percent%",
                sliderValue = if (ranges.size <= 1) 0f else (draggingPage ?: current.toFloat()),
                sliderRange = 0f..(ranges.lastIndex.coerceAtLeast(1)).toFloat(),
                sliderEnabled = ranges.size > 1,
                onSliderChange = { raw ->
                    draggingPage = raw
                    val target = sliderValueToPage(raw, ranges.size)
                    val pagerTarget = lead + target
                    scrollJob?.cancel()
                    if (pager.currentPage != pagerTarget) {
                        scrollJob = scope.launch { pager.scrollToPage(pagerTarget) }
                    }
                },
                onSliderFinished = {
                    val raw = draggingPage ?: return@ReaderProgressOverlay
                    val target = sliderValueToPage(raw, ranges.size)
                    scrollJob?.cancel()
                    scrollJob = scope.launch {
                        pager.scrollToPage(lead + target)
                        onAnchorChange(ranges[target].first)
                        draggingPage = null
                    }
                },
                previousTitle = previousTitle,
                nextTitle = nextTitle,
                onPreviousChapter = onJumpPrevious,
                onNextChapter = onJumpNext,
            )
        }
    }
}

/**
 * Continuous vertical scroll reading view built over line slices in [document], supporting touch
 * scrolling, left/right-third viewport taps, middle-third progress bar toggle, and fast-scrubbing.
 */
@Composable
private fun ReaderScrollContent(
    chapter: ReaderChapter,
    document: ReaderDocument,
    style: TextStyle,
    initialCharOffset: Int,
    onAnchorChange: (Int) -> Unit,
    fileName: String,
    chapterIndex: Int,
    chapterCount: Int,
    previousTitle: String?,
    nextTitle: String?,
    progressVisible: Boolean,
    onToggleProgress: () -> Unit,
    onSwipePrevious: () -> Unit,
    onJumpPrevious: () -> Unit,
    onJumpNext: () -> Unit,
) {
    val footerStyle = MaterialTheme.typography.labelSmall
    val latestAnchorOffset by rememberUpdatedState(initialCharOffset)
    val lineRanges = document.lineRanges
    val lineWeights = remember(lineRanges) { lineRanges.map { (it.last - it.first + 1).coerceAtLeast(1) } }
    val cumStarts = remember(lineWeights) { cumulativeWeights(lineWeights) }
    val totalWeight = remember(lineWeights, cumStarts) { (cumStarts.lastOrNull() ?: 0) + (lineWeights.lastOrNull() ?: 0) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = lineIndexForCharOffset(lineRanges, initialCharOffset),
    )
    LaunchedEffect(chapter, lineRanges) {
        if (initialCharOffset == Int.MAX_VALUE && lineRanges.isNotEmpty()) {
            listState.scrollToItem(lineRanges.lastIndex, 100_000)
        }
    }

    val currentFraction by remember(listState, lineWeights, cumStarts, totalWeight) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.map { VisibleReaderItem(it.index, it.offset, it.size) }
            verticalScrollFraction(
                visibleItems = visible,
                viewportStartPx = info.viewportStartOffset,
                viewportEndPx = info.viewportEndOffset,
                lineWeights = lineWeights,
                cumStarts = cumStarts,
                totalWeight = totalWeight,
            )
        }
    }
    val canScroll by remember(listState) {
        derivedStateOf { listState.canScrollBackward || listState.canScrollForward }
    }
    LaunchedEffect(listState.isScrollInProgress, listState.firstVisibleItemIndex, lineRanges) {
        if (!listState.isScrollInProgress && lineRanges.isNotEmpty()) {
            val idx = listState.firstVisibleItemIndex.coerceIn(0, lineRanges.lastIndex)
            if (idx != lineIndexForCharOffset(lineRanges, latestAnchorOffset)) {
                onAnchorChange(lineRanges[idx].first)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val horizontalPaddingPx = with(density) { 40.dp.toPx() }
        val footerHeight = with(density) { (footerStyle.lineHeight.takeIf { it.isSpecified } ?: 16.sp).toPx() } + with(density) { 12.dp.toPx() }
        val contentWidthPx = (constraints.maxWidth - horizontalPaddingPx).coerceAtLeast(1f)
        val viewportHeightPx = (constraints.maxHeight - footerHeight).coerceAtLeast(1f)
        val fontSizePx = with(density) { (style.fontSize.takeIf { it.isSpecified } ?: 16.sp).toPx() }
        val lineHeightPx = with(density) { (style.lineHeight.takeIf { it.isSpecified } ?: 24.sp).toPx() }.coerceAtLeast(fontSizePx)
        val approxCharsPerLine = (contentWidthPx / (fontSizePx * 0.85f)).coerceAtLeast(10f)
        val scope = rememberCoroutineScope()

        fun scrollStep(by: Int, volumeTriggered: Boolean) {
            scope.launch {
                when {
                    volumeTriggered && by > 0 && !listState.canScrollForward && nextTitle != null -> onJumpNext()
                    volumeTriggered && by < 0 && !listState.canScrollBackward && previousTitle != null -> onSwipePrevious()
                    else -> listState.animateScrollBy(by * viewportHeightPx * 0.88f)
                }
            }
        }
        ReaderVolumeKeyEffect { by -> scrollStep(by, volumeTriggered = true) }

        var draggingFraction by remember(chapter.path) { mutableStateOf<Float?>(null) }
        var dragVisibleSpan by remember(chapter.path) { mutableStateOf(0f) }
        var scrollJob by remember(chapter.path) { mutableStateOf<Job?>(null) }
        val shownFraction = draggingFraction ?: currentFraction
        val totalListItems = lineRanges.size + if (chapterCount > 1) 1 else 0

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .readerTapZones(
                        onPrevious = { scrollStep(-1, volumeTriggered = false) },
                        onNext = { scrollStep(1, volumeTriggered = false) },
                        onToggleProgress = onToggleProgress,
                    ),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("workspace_reader_scroll"),
                ) {
                    items(count = lineRanges.size, key = { it }) { index ->
                        val topPad = if (index == 0) 6.dp else 0.dp
                        val bottomPad = if (index == lineRanges.lastIndex) 12.dp else 0.dp
                        Text(
                            text = document.line(index),
                            style = style,
                            modifier = Modifier.fillMaxWidth().padding(top = topPad, bottom = bottomPad),
                        )
                    }
                    if (chapterCount > 1) {
                        item(key = "chapter_footer") {
                            ReaderScrollChapterFooter(
                                previousTitle = previousTitle,
                                nextTitle = nextTitle,
                                onPrevious = onJumpPrevious,
                                onNext = onJumpNext,
                            )
                        }
                    }
                }
            }

            ReaderFooterText(text = "${readerScrollPercent(shownFraction)}%", style = footerStyle, hidden = progressVisible)
        }

        suspend fun jumpToFraction(fraction: Float, visibleSpan: Float) {
            if (lineRanges.isEmpty()) return
            when {
                fraction <= 0.001f -> listState.scrollToItem(0, 0)
                fraction >= 0.999f -> listState.scrollToItem((totalListItems - 1).coerceAtLeast(0), 100_000)
                else -> {
                    val (targetIdx, inLineFraction) = verticalScrollTarget(
                        fraction = fraction,
                        lineWeights = lineWeights,
                        cumStarts = cumStarts,
                        totalWeight = totalWeight,
                        visibleWeightSpan = visibleSpan,
                    )
                    val estLines = ceil(lineWeights[targetIdx] / approxCharsPerLine).coerceAtLeast(1f)
                    val subOffset = (inLineFraction * estLines * lineHeightPx).roundToInt().coerceAtLeast(0)
                    listState.scrollToItem(targetIdx, subOffset)
                }
            }
        }

        ReaderProgressOverlay(
            visible = progressVisible,
            fileName = fileName,
            chapterIndex = chapterIndex,
            chapterCount = chapterCount,
            progressLabel = "${readerScrollPercent(shownFraction)}%",
            sliderValue = if (canScroll) shownFraction else 1f,
            sliderRange = 0f..1f,
            sliderEnabled = canScroll,
            onSliderChange = { raw ->
                val span = if (draggingFraction == null) {
                    estimateVisibleWeight(listState.layoutInfo, lineWeights).also { dragVisibleSpan = it }
                } else dragVisibleSpan
                draggingFraction = raw
                scrollJob?.cancel()
                scrollJob = scope.launch { jumpToFraction(raw, span) }
            },
            onSliderFinished = {
                val raw = draggingFraction ?: return@ReaderProgressOverlay
                val span = dragVisibleSpan
                scrollJob?.cancel()
                scrollJob = scope.launch {
                    jumpToFraction(raw, span)
                    if (lineRanges.isNotEmpty()) {
                        val idx = listState.firstVisibleItemIndex.coerceIn(0, lineRanges.lastIndex)
                        onAnchorChange(lineRanges[idx].first)
                    }
                    draggingFraction = null
                }
            },
            previousTitle = previousTitle,
            nextTitle = nextTitle,
            onPreviousChapter = onJumpPrevious,
            onNextChapter = onJumpNext,
        )
    }
}

@Composable
private fun ReaderScrollChapterFooter(
    previousTitle: String?,
    nextTitle: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ReaderChapterButton(
                label = stringResource(R.string.workspace_reader_previous_chapter),
                fileName = previousTitle,
                enabled = previousTitle != null,
                isPrevious = true,
                onClick = onPrevious,
                modifier = Modifier.weight(1f),
            )
            ReaderChapterButton(
                label = stringResource(R.string.workspace_reader_next_chapter),
                fileName = nextTitle,
                enabled = nextTitle != null,
                isPrevious = false,
                onClick = onNext,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BoxScope.ReaderProgressOverlay(
    visible: Boolean,
    fileName: String,
    chapterIndex: Int,
    chapterCount: Int,
    progressLabel: String,
    sliderValue: Float,
    sliderRange: ClosedFloatingPointRange<Float>,
    sliderEnabled: Boolean,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    previousTitle: String?,
    nextTitle: String?,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        val amoled = LocalAmoledStrong.current
        val barColor = (if (amoled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer)
            .copy(alpha = 0.92f)
        val border = amoledOutlineBorder()
            ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = barColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = if (amoled) 0.dp else 4.dp,
            border = border,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .pointerInput(Unit) { detectTapGestures {} }
                .testTag("workspace_reader_progress_bar"),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val headerText = if (chapterCount > 1 && chapterIndex >= 0) {
                        stringResource(R.string.workspace_reader_chapter, chapterIndex + 1, chapterCount)
                    } else fileName
                    Text(
                        text = headerText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 12.dp).testTag("workspace_reader_progress_label"),
                    )
                }

                Slider(
                    value = sliderValue.coerceIn(sliderRange.start, sliderRange.endInclusive),
                    onValueChange = onSliderChange,
                    onValueChangeFinished = onSliderFinished,
                    valueRange = sliderRange,
                    enabled = sliderEnabled,
                    modifier = Modifier.fillMaxWidth().testTag("workspace_reader_progress_slider"),
                )

                if (chapterCount > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReaderChapterButton(
                            label = stringResource(R.string.workspace_reader_previous_chapter),
                            fileName = previousTitle,
                            enabled = previousTitle != null,
                            isPrevious = true,
                            onClick = onPreviousChapter,
                            modifier = Modifier.weight(1f).testTag("workspace_reader_prev_chapter"),
                        )
                        ReaderChapterButton(
                            label = stringResource(R.string.workspace_reader_next_chapter),
                            fileName = nextTitle,
                            enabled = nextTitle != null,
                            isPrevious = false,
                            onClick = onNextChapter,
                            modifier = Modifier.weight(1f).testTag("workspace_reader_next_chapter"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderChapterButton(
    label: String,
    fileName: String?,
    enabled: Boolean,
    isPrevious: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.40f),
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isPrevious) {
                Icon(HugeIcons.ArrowLeft01, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = if (isPrevious) Alignment.Start else Alignment.End,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = fileName ?: stringResource(R.string.workspace_reader_no_chapter),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!isPrevious) {
                Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ChapterTurn(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Reading text with Markdown headings bold and a little larger, quoted text in [quoted] (when specified); everything else in the body style. */
internal fun buildReaderDocument(content: String, markdown: Boolean, style: TextStyle, quoted: Color): ReaderDocument {
    val lines = readingLines(content, markdown)
    val code = mutableListOf<WorkspaceMarkdownSpan>()
    val lineRanges = ArrayList<IntRange>(lines.size)
    val text = buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            val start = length
            line.code.forEach { code += WorkspaceMarkdownSpan(length + it.first, length + it.last + 1, WorkspaceMarkdownStyle.CODE) }
            if (line.heading > 0) {
                val scale = if (line.heading <= 2) 1.3f else 1.15f
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (style.fontSize.isSpecified) scale * style.fontSize else 18.sp))
                append(line.text)
                pop()
            } else {
                append(line.text)
            }
            lineRanges += start until length
            if (index < lines.lastIndex) append('\n')
        }
        if (quoted.isSpecified) {
            val quotedStyle = SpanStyle(color = quoted)
            workspaceQuotedSpans(toAnnotatedString().text, code).forEach { addStyle(quotedStyle, it.start, it.end) }
        }
    }
    return ReaderDocument(text = text, lineRanges = lineRanges)
}

/**
 * Page ranges (character offsets, inclusive) of [text] laid out at [width] × [height] px: each
 * page starts on a line start and holds the lines that fit, so a page re-laid out alone wraps the
 * same. An empty text is one empty page.
 */
private fun paginate(measurer: TextMeasurer, text: AnnotatedString, style: TextStyle, width: Int, height: Int): List<IntRange> {
    if (text.isEmpty()) return listOf(0 until 0)
    val layout = measurer.measure(text, style, constraints = Constraints(maxWidth = width), skipCache = true)
    val starts = mutableListOf(0)
    var pageTop = 0f
    for (line in 0 until layout.lineCount) {
        if (layout.getLineBottom(line) - pageTop > height && layout.getLineStart(line) > starts.last()) {
            starts += layout.getLineStart(line)
            pageTop = layout.getLineTop(line)
        }
    }
    return starts.mapIndexed { index, start ->
        start until (starts.getOrNull(index + 1) ?: text.length)
    }
}

internal fun sliderValueToPage(value: Float, pageCount: Int): Int {
    if (pageCount <= 1 || !value.isFinite()) return 0
    return value.roundToInt().coerceIn(0, pageCount - 1)
}

internal fun readerPagePercent(pageIndex: Int, pageCount: Int): Int {
    if (pageCount <= 1) return 100
    val clamped = pageIndex.coerceIn(0, pageCount - 1)
    return ((clamped + 1) * 100f / pageCount).roundToInt().coerceIn(0, 100)
}

internal fun readerScrollPercent(fraction: Float): Int {
    if (!fraction.isFinite()) return 0
    return (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
}

internal fun pageForCharOffset(ranges: List<IntRange>, charOffset: Int): Int {
    if (ranges.isEmpty() || charOffset <= 0) return 0
    return ranges.indexOfLast { it.first <= charOffset }.coerceIn(0, ranges.lastIndex)
}

internal fun lineIndexForCharOffset(lineRanges: List<IntRange>, charOffset: Int): Int {
    if (lineRanges.isEmpty() || charOffset <= 0) return 0
    return lineRanges.indexOfLast { it.first <= charOffset }.coerceIn(0, lineRanges.lastIndex)
}

internal fun cumulativeWeights(weights: List<Int>): List<Int> {
    val result = ArrayList<Int>(weights.size)
    var sum = 0
    for (w in weights) {
        result += sum
        sum += w
    }
    return result
}

private fun estimateVisibleWeight(layoutInfo: LazyListLayoutInfo, lineWeights: List<Int>): Float {
    val content = layoutInfo.visibleItemsInfo.filter { it.index in lineWeights.indices }
    if (content.isEmpty()) return 0f
    val first = content.first()
    val last = content.last()
    val firstTopClip = (layoutInfo.viewportStartOffset - first.offset).coerceIn(0, first.size)
    val firstRatio = if (first.size > 0) 1f - firstTopClip.toFloat() / first.size else 1f
    val lastVisiblePx = (layoutInfo.viewportEndOffset - last.offset).coerceIn(0, last.size)
    val lastRatio = if (last.size > 0) lastVisiblePx.toFloat() / last.size else 1f
    if (first.index == last.index) {
        return ((firstRatio + lastRatio - 1f).coerceIn(0f, 1f)) * lineWeights[first.index]
    }
    var mid = 0
    for (i in (first.index + 1) until last.index) mid += lineWeights[i]
    return firstRatio * lineWeights[first.index] + mid + lastRatio * lineWeights[last.index]
}

/**
 * Monotonic 0f..1f scroll progress through [lineWeights]: `before / (before + after)`, returning `1f`
 * when the whole chapter fits inside one viewport. Non-content items (index outside [lineWeights])
 * are ignored.
 */
internal fun verticalScrollFraction(
    visibleItems: List<VisibleReaderItem>,
    viewportStartPx: Int,
    viewportEndPx: Int,
    lineWeights: List<Int>,
    cumStarts: List<Int>,
    totalWeight: Int,
): Float {
    if (lineWeights.isEmpty() || totalWeight <= 0) return 1f
    val content = visibleItems.filter { it.index in lineWeights.indices }
    if (content.isEmpty()) {
        return if (visibleItems.any { it.index >= lineWeights.size }) 1f else 0f
    }
    val first = content.first()
    val last = content.last()
    val topClipPx = (viewportStartPx - first.offsetPx).coerceIn(0, first.sizePx)
    val topRatio = if (first.sizePx > 0) topClipPx.toFloat() / first.sizePx else 0f
    val before = (cumStarts[first.index] + topRatio * lineWeights[first.index]).coerceIn(0f, totalWeight.toFloat())

    val bottomVisiblePx = (viewportEndPx - last.offsetPx).coerceIn(0, last.sizePx)
    val bottomRatio = if (last.sizePx > 0) bottomVisiblePx.toFloat() / last.sizePx else 1f
    val bottomWeight = (cumStarts[last.index] + bottomRatio * lineWeights[last.index]).coerceIn(before, totalWeight.toFloat())
    val after = (totalWeight - bottomWeight).coerceAtLeast(0f)

    val denom = before + after
    return if (denom <= 0f) 1f else (before / denom).coerceIn(0f, 1f)
}

internal fun verticalScrollTarget(
    fraction: Float,
    lineWeights: List<Int>,
    cumStarts: List<Int>,
    totalWeight: Int,
    visibleWeightSpan: Float,
): Pair<Int, Float> {
    if (lineWeights.isEmpty() || fraction <= 0f) return 0 to 0f
    if (fraction >= 1f) return lineWeights.lastIndex to 1f
    val maxStart = (totalWeight - visibleWeightSpan.coerceAtLeast(0f)).coerceAtLeast(0f)
    val targetWeight = fraction.coerceIn(0f, 1f) * maxStart
    var idx = cumStarts.binarySearch { it.compareTo(targetWeight.toInt()) }
    if (idx < 0) idx = (-idx - 2).coerceIn(0, lineWeights.lastIndex)
    // Adjust if binarySearch landed past or before decimal fractional weight.
    while (idx < lineWeights.lastIndex && cumStarts[idx + 1] <= targetWeight) idx++
    val weightInLine = (targetWeight - cumStarts[idx]).coerceAtLeast(0f)
    val lineFrac = (weightInLine / lineWeights[idx].coerceAtLeast(1)).coerceIn(0f, 1f)
    return idx to lineFrac
}
