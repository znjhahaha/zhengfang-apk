package com.tyust.course.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.ui.system.*
import com.tyust.course.ui.system.glass.*
import com.tyust.course.ui.theme.MotionProfile
import kotlin.math.roundToInt

/** Reserve horizontal space before the segment row reaches the action row. */
internal fun gradesActionReservation(collapse: Float, titleRowHeight: Float, gap: Float, actionHeight: Float): Float {
    // Actions begin vertically centered in the title row and move upward too.
    // Reserve the full rail before either moving row's bounds can meet.
    val separation = titleRowHeight + 2f * gap + actionHeight
    val overlapAt = ((titleRowHeight + 2f * gap - actionHeight) / separation.coerceAtLeast(1f))
        .coerceAtLeast(0.01f) * 0.95f
    return (collapse / overlapAt).coerceIn(0f, 1f)
}

@Composable
internal fun MeasuredGradesHeader(
    subtitle: String, tabs: List<String>, selected: Int, onSelect: (Int) -> Unit,
    collapse: Float, backdrop: Backdrop?, showShare: Boolean, shareEnabled: Boolean,
    refreshing: Boolean, onShare: () -> Unit, onRefresh: () -> Unit,
    onHeights: (Dp, Dp) -> Unit
) {
    val density = LocalDensity.current
    val wallpaperRegion = rememberWallpaperRegionState()
    val appearance = rememberWallpaperRegionAppearance(wallpaperRegion)
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val p = collapse.coerceIn(0f, 1f)
    val share by animateFloatAsState(if (showShare) 1f else 0f,
        if (reduced) androidx.compose.animation.core.snap() else MotionProfile.iconSpring(), label = "grade-share")
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val effectiveBackdrop = backdrop?.takeIf { isBackdropSupported() }
    val layer = if (effectiveBackdrop != null) rememberLayerBackdrop() else null
    val controlBackdrop = if (effectiveBackdrop != null && layer != null)
        rememberCombinedBackdrop(effectiveBackdrop, layer) else null
    val anchor = if (controlBackdrop != null) rememberGlassLensRegion("grades-chips", selected, refreshing, showShare,
        (p * 8).toInt(), freshness = LocalPageGlassFreshness.current,
        drawSource = { drawBackdropSource(controlBackdrop, density, it) }) else null
    val measurer = rememberTextMeasurer()
    val tabStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
    val minTabWidth = tabs.sumOf { label ->
        maxOf(with(density) { 48.dp.toPx() }.roundToInt(), measurer.measure(label, tabStyle).size.width + with(density) { 24.dp.roundToPx() })
    }

    ProvideWallpaperAppearance(appearance) {
    Box(Modifier.fillMaxWidth().wallpaperRegion(wallpaperRegion).glassLensAnchor(anchor).then(wallpaperHeaderScrim())) {
        Box(Modifier.matchParentSize().then(if (layer != null) Modifier.layerBackdrop(layer) else Modifier)) {
            StatusBarFrost(statusBar + 1.dp, p, effectiveBackdrop)
            HeaderGlassSlab(((p - 0.35f) / 0.65f).coerceIn(0f, 1f), effectiveBackdrop, 26.dp,
                Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = statusBar + 6.dp, bottom = 4.dp))
        }
        CompositionLocalProvider(LocalControlBackdrop provides controlBackdrop, LocalGlassLensAnchor provides anchor) {
            Layout(modifier = Modifier.fillMaxWidth().testTag("grades-header"), content = {
                Column(Modifier.testTag("grades-title").semantics { if (p > 0.95f) hideFromAccessibility() }) {
                    Text("成绩与考试", fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp, color = appearance.onSurface, maxLines = 2)
                    Spacer(Modifier.height(3.dp))
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, lineHeight = 18.sp,
                        color = appearance.onSurfaceVariant, maxLines = 2)
                }
                LiquidSegmentedControl(tabs, selected, onSelect,
                    modifier = Modifier.testTag("grades-segments"), height = 52.dp - 4.dp * p,
                    refractLabels = false)
                TopBarActionRail(modifier = Modifier.testTag("grades-actions")) {
                    action(0, Icons.Default.Share, "导出成绩", onShare, enabled = showShare && shareEnabled && !refreshing,
                        presence = share)
                    action(1, contentDescription = if (refreshing) "正在刷新" else "刷新", onClick = onRefresh, enabled = !refreshing) {
                        AnimatedLineIcon(AnimatedIconSpec.Refresh, Modifier.size(TopBarLayoutMetrics.IconSize),
                            state = if (refreshing) IconVisualState.Running else IconVisualState.Idle)
                    }
                }
                Text("成绩与考试", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = appearance.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { if (p < 0.95f) hideFromAccessibility() })
            }) { children, constraints ->
                val inset = PagePadding.roundToPx()
                val width = constraints.maxWidth
                val innerWidth = (width - inset * 2).coerceAtLeast(1)
                val gap = 10.dp.roundToPx()
                val top = statusBar.roundToPx() + 10.dp.roundToPx()
                val bottom = 10.dp.roundToPx()
                val actions = children[2].measure(Constraints(maxWidth = innerWidth, minHeight = 48.dp.roundToPx(), maxHeight = 48.dp.roundToPx()))
                val titleWidth = (innerWidth - actions.width - gap).coerceAtLeast(1)
                val title = children[0].measure(Constraints(maxWidth = titleWidth))
                val smallTitle = children[3].measure(Constraints(maxWidth = titleWidth))
                val titleRow = maxOf(title.height, actions.height)
                val stacked = innerWidth < minTabWidth + actions.width + gap
                val expanded = 10.dp.roundToPx() + titleRow + gap + 52.dp.roundToPx() + bottom
                val collapsed = 10.dp.roundToPx() + (if (stacked) actions.height + gap else 0) + 48.dp.roundToPx() + bottom
                onHeights(expanded.toDp(), collapsed.toDp())
                val reserve = if (stacked) 0f else gradesActionReservation(p, titleRow.toFloat(), gap.toFloat(), actions.height.toFloat())
                val segmentWidth = (innerWidth - (actions.width + gap) * reserve).roundToInt().coerceAtLeast(1)
                val segmentHeight = (52.dp.toPx() - 4.dp.toPx() * p).roundToInt()
                val segments = children[1].measure(Constraints.fixed(segmentWidth, segmentHeight))
                val segmentStart = top + titleRow + gap
                val segmentEnd = top + if (stacked) actions.height + gap else 0
                val segmentY = (segmentStart + (segmentEnd - segmentStart) * p).roundToInt()
                val actionStart = top + (titleRow - actions.height) / 2
                val actionY = (actionStart + (top - actionStart) * p).roundToInt()
                val height = statusBar.roundToPx() + (expanded + (collapsed - expanded) * p).roundToInt()
                layout(width, height) {
                    title.placeRelativeWithLayer(inset, top) {
                        scaleX = 1f - p; scaleY = scaleX; alpha = 1f - p
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    segments.placeRelative(inset, segmentY)
                    actions.placeRelative(width - inset - actions.width, actionY)
                    smallTitle.placeRelativeWithLayer(inset, top + (actions.height - smallTitle.height) / 2) {
                        alpha = if (stacked) ((p - 0.65f) / 0.35f).coerceIn(0f, 1f) else 0f
                    }
                }
            }
        }
    }
}

}
