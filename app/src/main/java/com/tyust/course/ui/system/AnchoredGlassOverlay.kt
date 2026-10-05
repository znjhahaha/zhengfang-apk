package com.tyust.course.ui.system

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.tyust.course.ui.system.glass.LocalGlassLensAnchor
import com.tyust.course.ui.system.glass.LocalGlassLensModalAnchor

internal val LocalOverlayControls = staticCompositionLocalOf { Rect.Zero }
internal val LocalOverlayBody = staticCompositionLocalOf { false }

/** Unlike the connected picker portal, an optical panel NEVER lives in its anchor's subtree. */
@Composable
internal fun AnchoredGlassOverlay(
    active: Boolean,
    renderedHeight: Dp,
    desiredHeight: Dp,
    width: Dp,
    onDismiss: () -> Unit,
    onSpace: (Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onClosed: () -> Unit = {},
    diagnosticTag: String = "glass-panel",
    phase: String = "open",
    anchor: @Composable () -> Unit,
    body: @Composable () -> Unit
) {
    val host = LocalGlassPortals.current
    val dialogs = LocalDialogHost.current
    val density = LocalDensity.current
    val controls = LocalOverlayControls.current
    val currentBody by rememberUpdatedState(body)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentSpace by rememberUpdatedState(onSpace)
    val currentClosed by rememberUpdatedState(onClosed)
    val locals by rememberUpdatedState(currentCompositionLocalContext)
    val entry = remember {
        GlassPortalEntry(content = {
            CompositionLocalProvider(locals) {
                CompositionLocalProvider(LocalOverlayBody provides true) { currentBody() }
            }
        }, dismiss = { currentDismiss() }, onSpace = { space, up -> currentSpace(space, up) })
            .apply { bodyOnly = true }
    }
    DisposableEffect(active, dialogs) {
        if (active) dialogs?.beginPortal()
        onDispose { if (active) dialogs?.endPortal() }
    }
    SideEffect {
        entry.header = 0f
        entry.rendered = with(density) { renderedHeight.toPx() }
        entry.desiredBody = with(density) { desiredHeight.toPx() }
        entry.preferredWidth = with(density) { width.toPx() }
        entry.controls = controls.takeIf { it.width > 0f } ?: entry.anchor
        if (host != null) {
            if (active && entry !in host.entries) {
                host.entries.toList().forEach { it.dismiss() }
                host.entries.add(entry)
            } else if (!active) host.entries.remove(entry)
        }
    }
    DisposableEffect(host, entry) { onDispose { host?.entries?.remove(entry) } }
    val registered = host?.entries?.contains(entry) == true
    val context = LocalContext.current
    LaunchedEffect(active, registered, phase, diagnosticTag) {
        if (active) com.tyust.course.diagnostics.AppDiagnostics.markGlassPanel(context, diagnosticTag,
            if (host != null && !registered) "waiting" else phase,
            if (host == null || !isBackdropSupported()) "solid" else if (android.os.Build.VERSION.SDK_INT < 33) "render-effect" else "runtime-lens",
            entry.preferredWidth?.toInt() ?: 0, entry.rendered.toInt())
    }
    LaunchedEffect(active, registered) { if (!active && !registered) currentClosed() }
    Box(modifier.onGloballyPositioned {
        entry.anchor = Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }) { anchor() }

    if (host == null && active) {
        // A different window cannot sample this page's RenderNodes. A readable solid
        // fallback also preserves close/back semantics when a caller omitted the host.
        val available = with(density) { (LocalConfiguration.current.screenHeightDp.dp * .65f).toPx() }
        SideEffect { currentSpace(available, false) }
        Dialog(onDismissRequest = onDismiss) {
            CompositionLocalProvider(LocalModalBackdrop provides null, LocalControlBackdrop provides null,
                LocalAppBackdrop provides null, LocalGlassLensAnchor provides null,
                LocalGlassLensModalAnchor provides null, LocalOverlayBody provides true) {
                Box(Modifier.widthIn(max = width).height(renderedHeight)) {
                    if (renderedHeight > 1.dp) currentBody()
                }
            }
        }
    }
}
