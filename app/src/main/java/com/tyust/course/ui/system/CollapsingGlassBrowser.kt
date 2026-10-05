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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val LocalBrowserPanelWidth = staticCompositionLocalOf { 280.dp }

/** Scroll geometry, not a time animation: reversing a drag immediately reverses the header. */
internal fun browserCollapse(index: Int, offset: Int, expandedHeight: Int): Float =
    if (index > 0) 1f else (offset.toFloat() / expandedHeight.coerceAtLeast(1)).coerceIn(0f, 1f)

/**
 * Opt-in browser shell. The large heading and filters ARE the first lazy item, so they cannot
 * reserve fixed screen space. Only a 52dp action rail and accessible tabs remain pinned.
 * Each caller owns its list states and business effects; scrolling never invokes an action.
 * The clipped list viewport ends below the toolbar: offscreen fields have no hit/focus area.
 */
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
    val tabHeight = (48f * density.fontScale.coerceIn(1f, 2f)).dp
    val collapse by remember(state) { derivedStateOf {
        val first = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
        browserCollapse(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, first?.size ?: 1)
    } }
    val wallpaper = LocalAppBackdrop.current
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(
        WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom
    )).imePadding().testTag("browser-window")) {
        // The panel ends before the trailing 48dp More action. Bound its width to the
        // actual parent (including split windows), so opening cannot displace its triggers.
        CompositionLocalProvider(LocalBrowserPanelWidth provides minOf(400.dp, (minOf(maxWidth, 840.dp) - 80.dp).coerceAtLeast(1.dp))) {
        Column(Modifier.fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 840.dp).then(if (panelActive) Modifier.clearAndSetSemantics {} else Modifier)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("browser-toolbar")) {
                // Wallpaper-only sample, a sibling of all foreground controls. No self capture.
                HeaderGlassSlab(collapse, wallpaper, 24.dp, Modifier.matchParentSize())
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                        TopBarActionRail { action(0, Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack) }
                        Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                            if (collapse > 0f) Text(title, Modifier.graphicsLayer { alpha = collapse },
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        actions()
                    }
                    LiquidSegmentedControl(tabs, selectedTab, onTabChange, Modifier.fillMaxWidth()
                        .testTag("plugin-tabs"), height = tabHeight, refractLabels = false)
                }
            }
            LazyColumn(state = state, modifier = Modifier.weight(1f).fillMaxWidth().testTag("plugin-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
    }
}
