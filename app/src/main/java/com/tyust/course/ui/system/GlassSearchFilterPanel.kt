package com.tyust.course.ui.system

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.glass.glassSheet

internal enum class BrowserPanel { Search, Filters, More }

/** Snapshot read INSIDE the overlay subcomposition, not frozen in a transferred lambda. */
private data class BrowserPanelPresentation(
    val mode: BrowserPanel, val open: Boolean, val progress: Float, val effects: Float,
    val available: androidx.compose.ui.unit.Dp, val opensUp: Boolean,
    val measurement: MutableState<androidx.compose.ui.unit.Dp>,
    val actions: List<SystemMenuAction>, val dismiss: () -> Unit,
    val search: @Composable () -> Unit, val filters: @Composable () -> Unit
)

/** One overlay owns all browser tools. Geometry is measured independently of the reveal. */
@Composable
internal fun GlassSearchFilterPanel(
    panel: BrowserPanel?,
    onPanelChange: (BrowserPanel?) -> Unit,
    onClosed: () -> Unit = {},
    reduceMotion: Boolean = rememberGlassAccessibilityMode().reduceMotion,
    moreActions: List<SystemMenuAction>? = null,
    search: @Composable () -> Unit,
    filters: @Composable () -> Unit
) {
    val focus = LocalFocusManager.current
    val open = panel != null && (panel != BrowserPanel.More || moreActions != null)
    var retained by remember { mutableStateOf(panel ?: BrowserPanel.Search) }
    // A new selection cancels any pending action before a stale close can run it.
    var pending by remember { mutableStateOf<SystemMenuAction?>(null) }
    LaunchedEffect(panel) { if (open) pending = null else focus.clearFocus(force = true) }
    if (open) SideEffect { retained = requireNotNull(panel) }
    val mode = if (open) requireNotNull(panel) else retained
    val currentPanel by rememberUpdatedState(panel)
    var space by remember { mutableFloatStateOf(0f) }
    var opensUp by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val anchors = remember { mutableStateMapOf<BrowserPanel, Rect>() }
    val anchor = anchors[mode] ?: Rect.Zero
    val ready = space > 1f && anchor.width > 0f && anchor.height > 0f
    val spatial by animateFloatAsState(if (open && ready) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = .9f, stiffness = 700f), label = "browser-panel-bounds")
    val effects by animateFloatAsState(if (open && ready) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 1600f), label = "browser-panel-opacity")
    val progress = spatial.coerceIn(0f, 1f)
    val desired = (if (mode == BrowserPanel.Search) 190f else if (mode == BrowserPanel.More)
        48f * moreActions.orEmpty().size + 8f else 380f).dp * density.fontScale.coerceAtLeast(1f)
    val available = minOf(desired, with(density) { space.toDp() }).coerceAtLeast(0.dp)
    val measurement = remember(mode, available) { mutableStateOf(available) }
    val height = minOf(measurement.value, available)
    val animatedHeight by animateDpAsState(height,
        if (reduceMotion) snap() else spring(dampingRatio = .9f, stiffness = 700f), label = "browser-panel-height")
    val revealedHeight = minOf(animatedHeight, with(density) { space.toDp() }).coerceAtLeast(0.dp) * progress
    val active = open || progress > 0f || effects > 0f
    fun close() { if (currentPanel == mode) onPanelChange(null) }
    val live = rememberUpdatedState(BrowserPanelPresentation(mode, open, progress, effects,
        available, opensUp, measurement, moreActions.orEmpty(), ::close, search, filters))
    val body: @Composable () -> Unit = {
        val backdrop = LocalModalBackdrop.current?.takeIf { isBackdropSupported() && LocalOverlayBody.current }
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("browser-panel-reveal")) {
            val frame = live.value
            val mode = frame.mode
            val title = when (mode) { BrowserPanel.Search -> "搜索插件"; BrowserPanel.Filters -> "筛选插件"; BrowserPanel.More -> "更多" }
            val density = LocalDensity.current
            val finalWidth = maxWidth
            // wrapContentSize removes the animated parent's height constraint. The
            // column is measured against the FINAL available height, never progress.
            Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).width(finalWidth)
                .heightIn(max = frame.available).onSizeChanged { frame.measurement.value = with(density) { it.height.toDp() } }
                .graphicsLayer {
                    alpha = frame.effects.coerceIn(0f, 1f)
                    scaleX = .96f + .04f * frame.progress
                    transformOrigin = TransformOrigin(1f, if (frame.opensUp) 1f else 0f)
                }.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .then(if (backdrop != null) Modifier.glassSheet(backdrop, 24.dp) else Modifier)
                .then(if (!frame.open) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                } else Modifier.semantics { paneTitle = title })
                .focusProperties { canFocus = frame.open }.verticalScroll(rememberScrollState())
                .padding(if (mode == BrowserPanel.More) 4.dp else 16.dp).testTag("browser-panel"),
                verticalArrangement = Arrangement.spacedBy(if (mode == BrowserPanel.More) 0.dp else 12.dp)) {
                if (mode == BrowserPanel.More) {
                    SystemActionMenuContent(frame.actions, frame.open, 48.dp * density.fontScale.coerceAtLeast(1f)) {
                        if (pending == null) { pending = it; frame.dismiss() }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton({ frame.dismiss() }, enabled = frame.open) { Icon(Icons.Outlined.Close, "关闭面板") }
                    }
                    if (mode == BrowserPanel.Search) frame.search() else frame.filters()
                }
            }
        }
    }
    AnchoredGlassOverlay(active, revealedHeight, desired, LocalBrowserPanelWidth.current,
        onDismiss = { close() }, onClosed = {
            if (currentPanel == null) {
                val action = pending; pending = null
                if (action?.enabled == true) action.onClick()
                onClosed()
            }
        }, onSpace = { value, up -> space = value; opensUp = up }, anchorBounds = anchor,
        diagnosticTag = when (mode) { BrowserPanel.Search -> "plugin-search"; BrowserPanel.Filters -> "plugin-filter"; BrowserPanel.More -> "action-menu" },
        phase = if (!open) "closing" else if (!ready) "waiting" else if (progress >= .999f) "open" else "opening",
        modifier = Modifier.height(48.dp), anchor = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tools = listOf(Triple(BrowserPanel.Search, Icons.Outlined.Search, "搜索插件"),
                    Triple(BrowserPanel.Filters, Icons.Outlined.FilterList, "筛选插件")) +
                    if (moreActions != null) listOf(Triple(BrowserPanel.More, Icons.Outlined.MoreHoriz, "更多")) else emptyList()
                tools.forEach { (target, icon, description) ->
                    TopBarActionRail(Modifier.size(48.dp).onGloballyPositioned {
                        anchors[target] = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                    }) { action(0, icon, description, { onPanelChange(if (panel == target) null else target) }) }
                }
            }
        }, body = body)
}
