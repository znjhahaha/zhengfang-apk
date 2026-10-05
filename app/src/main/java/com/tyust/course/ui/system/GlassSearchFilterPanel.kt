package com.tyust.course.ui.system

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.glass.glassSheet

internal enum class BrowserPanel { Search, Filters, More }

/**
 * Local M3E-inspired pilot. Material3 1.4.0 MotionScheme is Kotlin-internal; use public
 * Compose spring primitives until that API is public in the chosen BOM. No reflection,
 * suppressed visibility checks, global motion retuning or 103 dropdown recipe changes.
 */
@Composable
internal fun GlassSearchFilterPanel(
    panel: BrowserPanel?,
    onPanelChange: (BrowserPanel?) -> Unit,
    onClosed: () -> Unit = {},
    reduceMotion: Boolean = rememberGlassAccessibilityMode().reduceMotion,
    search: @Composable () -> Unit,
    filters: @Composable () -> Unit
) {
    val focus = LocalFocusManager.current
    val open = panel == BrowserPanel.Search || panel == BrowserPanel.Filters
    LaunchedEffect(panel) { if (!open) focus.clearFocus(force = true) }
    val spatial by animateFloatAsState(if (open) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = 0.9f, stiffness = 700f), label = "browser-panel-bounds")
    val effects by animateFloatAsState(if (open) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 1600f), label = "browser-panel-opacity")
    var retained by remember { mutableStateOf(panel?.takeIf { it != BrowserPanel.More } ?: BrowserPanel.Search) }
    if (open) SideEffect { retained = requireNotNull(panel) }
    var space by remember { mutableFloatStateOf(0f) }
    var opensUp by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val desired = (if (retained == BrowserPanel.Search) 190f else 380f).dp * density.fontScale.coerceAtLeast(1f)
    val animatedHeight by animateDpAsState(desired, if (reduceMotion) snap() else spring(dampingRatio = 0.9f, stiffness = 700f), label = "browser-panel-mode")
    val height = minOf(animatedHeight, with(density) { space.toDp() }).coerceAtLeast(0.dp)
    val progress = spatial.coerceIn(0f, 1f)
    val active = open || progress > 0f || effects > 0f
    val body: @Composable () -> Unit = {
        val backdrop = LocalModalBackdrop.current?.takeIf { isBackdropSupported() && LocalOverlayBody.current }
        // Bounds/shape follow the spatial spring; alpha follows the non-overshooting effects spec.
        Box(Modifier.fillMaxWidth().height(height * progress).clip(RoundedCornerShape(0.dp))) {
            Column(Modifier.fillMaxWidth().height(height).graphicsLayer {
                    alpha = effects.coerceIn(0f, 1f)
                    scaleX = 0.9f + 0.1f * progress
                    transformOrigin = TransformOrigin(1f, if (opensUp) 1f else 0f)
                }.then(if (backdrop != null) Modifier.glassSheet(backdrop, (28f - 4f * progress).dp)
                    else Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)))
                .clip(RoundedCornerShape((28f - 4f * progress).dp))
                .then(if (!open) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                } else Modifier.semantics { paneTitle = if (panel == BrowserPanel.Search) "搜索插件" else "筛选插件" })
                .focusProperties { canFocus = open }.verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser-panel"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (retained == BrowserPanel.Search) "搜索插件" else "筛选插件", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ onPanelChange(null) }, enabled = open) { Icon(Icons.Outlined.Close, "关闭面板") }
                }
                if (retained == BrowserPanel.Search) search() else filters()
            }
        }
    }
    AnchoredGlassOverlay(active, height * progress, desired, LocalBrowserPanelWidth.current,
        onDismiss = { if (open) onPanelChange(null) }, onClosed = onClosed,
        onSpace = { available, up -> space = available; opensUp = up },
        diagnosticTag = if (retained == BrowserPanel.Search) "plugin-search" else "plugin-filter",
        phase = if (!open) "closing" else if (progress >= .999f) "open" else "opening",
        modifier = Modifier.width(100.dp).height(48.dp), anchor = {
            TopBarActionRail {
                action(0, Icons.Outlined.Search, "搜索插件", { onPanelChange(if (panel == BrowserPanel.Search) null else BrowserPanel.Search) })
                action(1, Icons.Outlined.FilterList, "筛选插件", { onPanelChange(if (panel == BrowserPanel.Filters) null else BrowserPanel.Filters) })
            }
        }, body = body)
}
