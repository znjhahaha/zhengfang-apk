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

internal enum class BrowserPanel { Search, Filters }

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
    LaunchedEffect(panel) { if (panel == null) focus.clearFocus(force = true) }
    val spatial by animateFloatAsState(if (panel != null) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = 0.9f, stiffness = 700f), label = "browser-panel-bounds")
    val effects by animateFloatAsState(if (panel != null) 1f else 0f,
        if (reduceMotion) snap() else spring(dampingRatio = 1f, stiffness = 1600f), label = "browser-panel-opacity")
    var retained by remember { mutableStateOf(panel ?: BrowserPanel.Search) }
    if (panel != null) SideEffect { retained = panel }
    var space by remember { mutableFloatStateOf(0f) }
    var opensUp by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val desired = (if (retained == BrowserPanel.Search) 190f else 380f).dp * density.fontScale.coerceAtLeast(1f)
    val animatedHeight by animateDpAsState(desired, if (reduceMotion) snap() else spring(dampingRatio = 0.9f, stiffness = 700f), label = "browser-panel-mode")
    val height = minOf(animatedHeight, with(density) { space.toDp() }).coerceAtLeast(0.dp)
    val progress = spatial.coerceIn(0f, 1f)
    val active = panel != null || progress > 0f || effects > 0f
    val backdrop = LocalModalBackdrop.current?.takeIf { isBackdropSupported() }
    val body: @Composable () -> Unit = {
        // Bounds/shape follow the spatial spring; alpha follows the non-overshooting effects spec.
        Box(Modifier.fillMaxWidth().height((height + 8.dp) * progress).clip(RoundedCornerShape(0.dp))) {
            Column(Modifier.padding(top = if (opensUp) 0.dp else 8.dp, bottom = if (opensUp) 8.dp else 0.dp)
                .fillMaxWidth().height(height).graphicsLayer {
                    alpha = effects.coerceIn(0f, 1f)
                    scaleX = 0.9f + 0.1f * progress
                    transformOrigin = TransformOrigin(1f, if (opensUp) 1f else 0f)
                }.then(if (backdrop != null) Modifier.glassSheet(backdrop, (28f - 4f * progress).dp)
                    else Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp)))
                .clip(RoundedCornerShape((28f - 4f * progress).dp))
                .then(if (panel == null) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                } else Modifier.semantics { paneTitle = if (panel == BrowserPanel.Search) "搜索插件" else "筛选插件" })
                .focusProperties { canFocus = panel != null }.verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser-panel"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (retained == BrowserPanel.Search) "搜索插件" else "筛选插件", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ onPanelChange(null) }, enabled = panel != null) { Icon(Icons.Outlined.Close, "关闭面板") }
                }
                if (retained == BrowserPanel.Search) search() else filters()
            }
        }
    }
    AnchoredGlassPortal(active, 48.dp, 48.dp + (height + 8.dp) * progress, desired,
        onDismiss = { onPanelChange(null) }, onClosed = onClosed,
        onSpaceAvailable = { available, up -> space = available; opensUp = up },
        modifier = Modifier.width(100.dp).height(48.dp), popupWidth = if (active) LocalBrowserPanelWidth.current else null) {
        Column(Modifier.fillMaxWidth()) {
            if (opensUp && active) body()
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterEnd) {
                TopBarActionRail {
                    action(0, Icons.Outlined.Search, "搜索插件", { onPanelChange(if (panel == BrowserPanel.Search) null else BrowserPanel.Search) })
                    action(1, Icons.Outlined.FilterList, "筛选插件", { onPanelChange(if (panel == BrowserPanel.Filters) null else BrowserPanel.Filters) })
                }
            }
            if (!opensUp && active) body()
        }
    }
}
