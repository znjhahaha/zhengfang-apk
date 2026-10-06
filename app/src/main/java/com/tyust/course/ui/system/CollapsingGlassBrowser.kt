package com.tyust.course.ui.system

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.ui.system.glass.*

internal val LocalBrowserPanelWidth = staticCompositionLocalOf { 280.dp }

/** Scroll geometry, not a time animation: reversing a drag immediately reverses the header. */
internal fun browserCollapse(index: Int, offset: Int, expandedHeight: Int): Float =
    if (index > 0) 1f else (offset.toFloat() / expandedHeight.coerceAtLeast(1)).coerceIn(0f, 1f)

/** List, sampled slab and foreground controls are siblings. None can sample itself. */
@Composable
internal fun CollapsingGlassBrowser(
    title: String,
    subtitle: String,
    state: LazyListState,
    onBack: () -> Unit,
    tabs: List<String>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    panelActive: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
    expandedControls: @Composable ColumnScope.() -> Unit,
    content: LazyListScope.() -> Unit
) {
    val density = LocalDensity.current
    val blocked = panelActive || LocalGlassPortals.current?.entries?.isNotEmpty() == true
    val status = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    val tabHeight = (48f * density.fontScale.coerceIn(1f, 2f)).dp
    val headerHeight = 52.dp + tabHeight + 8.dp
    val collapse by remember(state) { derivedStateOf {
        val first = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
        browserCollapse(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, first?.size ?: 1)
    } }
    val wallpaper = LocalAppBackdrop.current?.takeIf { isBackdropSupported() }
    val listLayer = if (wallpaper != null) rememberLayerBackdrop() else null
    val pageBackdrop = if (wallpaper != null && listLayer != null) rememberCombinedBackdrop(wallpaper, listLayer) else null
    val slab = if (pageBackdrop != null) rememberLayerBackdrop() else null
    val controlsBackdrop = if (pageBackdrop != null && slab != null) rememberCombinedBackdrop(pageBackdrop, slab) else null
    val freshness = remember { GlassLensFreshness() }
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress) }
            .collect { if (it.third) freshness.onScroll() else freshness.onSettled() }
    }
    val lens = if (controlsBackdrop != null) rememberGlassLensRegion("plugin-header", selectedTab,
        freshness = freshness) { drawBackdropSource(controlsBackdrop, density, it) } else null
    var toolbarBounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    )).imePadding().testTag("browser-window")) {
        CompositionLocalProvider(LocalBrowserPanelWidth provides minOf(400.dp, (minOf(maxWidth, 840.dp) - 24.dp).coerceAtLeast(1.dp))) {
            Box(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 840.dp)) {
                Box(Modifier.fillMaxSize().focusProperties { canFocus = !blocked }
                    .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()
                    .then(if (listLayer != null) Modifier.layerBackdrop(listLayer) else Modifier)
                    .testTag("plugin-list"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = status + headerHeight + 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item("browser-expanded-controls", contentType = "browser-controls") {
                        Column(Modifier.fillMaxWidth().testTag("browser-expanded"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineLarge)
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            expandedControls()
                        }
                    }
                    content()
                }
                }
                Box(Modifier.fillMaxWidth().height(status + headerHeight).testTag("browser-toolbar")
                    .glassLensAnchor(lens)
) {
                    // This sibling catches empty-toolbar taps without cancelling child gestures.
                    Box(Modifier.matchParentSize().pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                    })
                    Box(Modifier.matchParentSize().then(if (slab != null) Modifier.layerBackdrop(slab) else Modifier)) {
                        StatusBarFrost(status + 1.dp, collapse, pageBackdrop)
                        HeaderGlassSlab(collapse, pageBackdrop, 26.dp,
                            Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = status + 4.dp, bottom = 4.dp))
                    }
                    CompositionLocalProvider(LocalControlBackdrop provides controlsBackdrop, LocalGlassLensAnchor provides lens,
                        LocalPageGlassFreshness provides freshness, LocalOverlayControls provides toolbarBounds) {
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = status + 4.dp, bottom = 4.dp)) {
                            Row(Modifier.fillMaxWidth().height(52.dp).onGloballyPositioned {
                                toolbarBounds = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                            }, verticalAlignment = Alignment.CenterVertically) {
                                TopBarActionRail { action(0, Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) }
                                Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                                    if (collapse > 0f) Text(title, Modifier.graphicsLayer { alpha = collapse },
                                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                actions()
                            }
                            LiquidSegmentedControl(tabs, selectedTab, onTabChange, Modifier.fillMaxWidth()
                                .then(if (blocked) Modifier.clearAndSetSemantics {} else Modifier)
                                .testTag("plugin-tabs"), height = tabHeight, showTrack = false, refractLabels = false)
                        }
                    }
                }
            }
        }
    }
}
