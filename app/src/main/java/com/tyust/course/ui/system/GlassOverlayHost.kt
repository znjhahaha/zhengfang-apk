package com.tyust.course.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class PortalPlacement(val x: Float, val y: Float, val width: Float, val bodySpace: Float, val opensUp: Boolean)

internal fun resolvePortalPlacement(anchor: Rect, safe: Rect, desiredBody: Float, header: Float, rendered: Float, gap: Float, requestedWidth: Float = anchor.width): PortalPlacement {
    val below = (safe.bottom - anchor.bottom - gap).coerceAtLeast(0f)
    val above = (anchor.top - safe.top - gap).coerceAtLeast(0f)
    val up = below < desiredBody && above > below
    val width = requestedWidth.coerceAtMost(safe.width).coerceAtLeast(1f)
    return PortalPlacement(
        (anchor.right - width).coerceIn(safe.left, (safe.right - width).coerceAtLeast(safe.left)),
        if (up) anchor.top - (rendered - header) else anchor.top,
        width, if (up) above else below, up
    )
}

internal class GlassPortalEntry(
    val content: @Composable () -> Unit,
    val dismiss: () -> Unit,
    val onSpace: (Float, Boolean) -> Unit
) {
    var bodyOnly = false
    var controls by mutableStateOf(Rect.Zero)
    var anchor by mutableStateOf(Rect.Zero)
    var header by mutableFloatStateOf(0f)
    var rendered by mutableFloatStateOf(0f)
    var desiredBody by mutableFloatStateOf(0f)
    var preferredWidth by mutableStateOf<Float?>(null)
}

internal class GlassPortalState {
    val entries = mutableStateListOf<GlassPortalEntry>()
}

internal val LocalGlassPortals = staticCompositionLocalOf<GlassPortalState?> { null }

/** Entries stay in this window, so optical source and target coordinates remain compatible. */
@Composable
fun GlassOverlayHost(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = remember { GlassPortalState() }
    val density = LocalDensity.current
    var window by remember { mutableStateOf(Rect.Zero) }
    val systemBars = WindowInsets.systemBars
    val margin = with(density) { 12.dp.toPx() }
    val top = systemBars.getTop(density).toFloat()
    val bottom = maxOf(systemBars.getBottom(density), WindowInsets.ime.getBottom(density)).toFloat()
    CompositionLocalProvider(LocalGlassPortals provides state) {
        Box(modifier.fillMaxSize().onGloballyPositioned {
            window = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
        }) {
            content()
            state.entries.forEach { entry ->
                key(entry) {
                    if (window.height > 0f && entry.anchor.width > 0f) {
                        val safe = Rect(window.left + margin, window.top + top + margin, window.right - margin, maxOf(window.top + top + margin, window.bottom - bottom - margin))
                        val placement = resolvePortalPlacement(entry.anchor, safe, entry.desiredBody, entry.header, entry.rendered, margin, entry.preferredWidth ?: entry.anchor.width)
                        SideEffect { entry.onSpace(placement.bodySpace, placement.opensUp) }
                        BackHandler(enabled = state.entries.lastOrNull() === entry) { entry.dismiss() }
                        // Stable-anchor panels leave only their toolbar interactive. The rest
                        // of the window consumes taps, including while the panel closes.
                        val holes = if (entry.bodyOnly) entry.controls else Rect.Zero
                        dismissRegions(window, holes).forEach { region ->
                            Box(Modifier.offset { IntOffset((region.left - window.left).roundToInt(), (region.top - window.top).roundToInt()) }
                                .size(with(density) { region.width.toDp() }, with(density) { region.height.toDp() })
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    onClick = entry.dismiss))
                        }
                        val y = if (!entry.bodyOnly) placement.y else if (placement.opensUp)
                            entry.anchor.top - margin - entry.rendered else entry.anchor.bottom + margin
                        // onSpace above still runs at zero height; no optical body is composed
                        // until both the host and the measured anchor are ready.
                        if (entry.rendered > 1f) Box(Modifier.offset {
                            IntOffset((placement.x - window.left).roundToInt(), (y - window.top).roundToInt())
                        }.requiredSize(with(density) { placement.width.toDp() }, with(density) { entry.rendered.toDp() })) {
                            entry.content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AnchoredGlassPortal(
    active: Boolean,
    anchorHeight: Dp,
    renderedHeight: Dp,
    desiredBodyHeight: Dp,
    onDismiss: () -> Unit,
    onSpaceAvailable: (Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    popupWidth: Dp? = null,
    onClosed: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val host = LocalGlassPortals.current
    val dialogHost = LocalDialogHost.current
    DisposableEffect(active, dialogHost) {
        if (active) dialogHost?.beginPortal()
        onDispose { if (active) dialogHost?.endPortal() }
    }
    val density = LocalDensity.current
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentSpace by rememberUpdatedState(onSpaceAvailable)
    val currentClosed by rememberUpdatedState(onClosed)
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val movable = remember { movableContentOf { CompositionLocalProvider(locals) { currentContent() } } }
    val entry = remember { GlassPortalEntry(movable, { currentDismiss() }, { space, up -> currentSpace(space, up) }) }
    SideEffect {
        entry.header = with(density) { anchorHeight.toPx() }
        entry.rendered = with(density) { renderedHeight.toPx() }
        entry.desiredBody = with(density) { desiredBodyHeight.toPx() }
        entry.preferredWidth = popupWidth?.let { with(density) { it.toPx() } }
        if (host != null) {
            if (active && entry !in host.entries) {
                host.entries.toList().forEach { it.dismiss() }
                host.entries.add(entry)
            } else if (!active) host.entries.remove(entry)
        }
    }
    DisposableEffect(host, entry) { onDispose { host?.entries?.remove(entry) } }
    val inPortal = host?.entries?.contains(entry) == true
    LaunchedEffect(active, inPortal) {
        // The next surface may open only after this entry has left the overlay host.
        if (!active && !inPortal) currentClosed?.invoke()
    }
    BoxWithConstraints(modifier.height(anchorHeight).onGloballyPositioned {
        entry.anchor = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }) {
        if (host == null || entry !in host.entries) {
            Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(maxWidth, renderedHeight)) { movable() }
        }
    }
}

/** Four disjoint hit regions around a live toolbar; never forward a tap to page content. */
internal fun dismissRegions(window: Rect, controls: Rect): List<Rect> {
    val hole = controls.intersect(window)
    if (hole.width <= 0f || hole.height <= 0f) return listOf(window)
    return listOf(
        Rect(window.left, window.top, window.right, hole.top),
        Rect(window.left, hole.bottom, window.right, window.bottom),
        Rect(window.left, hole.top, hole.left, hole.bottom),
        Rect(hole.right, hole.top, window.right, hole.bottom)
    ).filter { it.width > 0f && it.height > 0f }
}
