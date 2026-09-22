package com.tyust.course.ui.system

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class DialogPresentation { Center, Bottom, Page }
class DialogHandle internal constructor(internal val key: String = java.util.UUID.randomUUID().toString())

internal data class SheetFrame(val x: Float = 0f, val y: Float = 0f, val scaleX: Float = 1f, val scaleY: Float = 1f)

internal class HostedDialog(
    val handle: DialogHandle,
    val onDismiss: () -> Unit,
    val presentation: DialogPresentation,
    val bottomSheet: ScheduleBottomSheetState?,
    val content: @Composable () -> Unit
) {
    val visibility = MutableTransitionState(false).apply { targetState = true }
    var notifyOnClose = true
    var presence by mutableFloatStateOf(0f)
    var lastSheetFrame = SheetFrame()
    var lastPresence = 0f
    var exitSheetFrame by mutableStateOf<SheetFrame?>(null)
    var exitStartPresence = 1f
}

class DialogHostState {
    private val portalCount = androidx.compose.runtime.mutableIntStateOf(0)
    val hasBlockingSurface: Boolean get() = dialogs.isNotEmpty() || portalCount.intValue > 0
    fun hasBlockingSurfaceExcept(ownerKey: String): Boolean = dialogs.any { it.handle.key != ownerKey } || portalCount.intValue > 0
    internal fun beginPortal() { portalCount.intValue++ }
    internal fun endPortal() { portalCount.intValue = (portalCount.intValue - 1).coerceAtLeast(0) }
    internal val dialogs = mutableStateListOf<HostedDialog>()
    val currentDialog: (@Composable () -> Unit)? get() = dialogs.lastOrNull()?.content
    val isVisible: Boolean get() = dialogs.lastOrNull()?.visibility?.targetState == true
    val pageProgress: Float get() = dialogs.lastOrNull { it.presentation == DialogPresentation.Page }?.presence ?: 0f

    fun show(
        onDismiss: () -> Unit,
        presentation: DialogPresentation = DialogPresentation.Center,
        bottomSheet: ScheduleBottomSheetState? = null,
        saveableKey: String? = null,
        content: @Composable () -> Unit
    ): DialogHandle = (saveableKey?.let(::DialogHandle) ?: DialogHandle()).also { dialogs.add(HostedDialog(it, onDismiss, presentation, bottomSheet, content)) }

    fun dismiss(handle: DialogHandle? = dialogs.lastOrNull()?.handle, notify: Boolean = true) {
        val dialog = dialogs.firstOrNull { it.handle === handle } ?: return
        if (dialog.visibility.targetState && dialog.bottomSheet != null) {
            dialog.exitSheetFrame = dialog.lastSheetFrame
            dialog.exitStartPresence = dialog.lastPresence.coerceAtLeast(0.001f)
        }
        dialog.notifyOnClose = dialog.notifyOnClose && notify
        dialog.visibility.targetState = false
    }

    internal fun finishDismissal(handle: DialogHandle) {
        val dialog = dialogs.firstOrNull { it.handle === handle } ?: return
        if (dialog.visibility.targetState) return
        dialogs.remove(dialog)
        if (dialog.notifyOnClose) dialog.onDismiss()
    }
}

@Composable
fun rememberDialogHostState(): DialogHostState = remember { DialogHostState() }

val LocalDialogHost = compositionLocalOf<DialogHostState?> { null }
internal val LocalDialogProgress = compositionLocalOf<State<Float>?> { null }
internal val LocalDialogModuleProgress = compositionLocalOf<State<Float>?> { null }

@Composable
fun DialogHost(state: DialogHostState, modifier: Modifier = Modifier) {
    val reducedMotion = rememberGlassAccessibilityMode().reduceMotion
    val density = LocalDensity.current
    state.dialogs.forEach { dialog ->
        key(dialog.handle.key) {
            val visibility = dialog.visibility
            LaunchedEffect(visibility.isIdle, visibility.currentState, visibility.targetState) {
                if (visibility.isIdle && !visibility.currentState && !visibility.targetState) {
                    state.finishDismissal(dialog.handle)
                }
            }
            val bottom = dialog.presentation == DialogPresentation.Bottom
            val page = dialog.presentation == DialogPresentation.Page
            val backProgress = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            var hostSize by remember { mutableStateOf(IntSize.Zero) }
            var hostOrigin by remember { mutableStateOf(Offset.Zero) }
            PredictiveBackHandler(enabled = state.dialogs.lastOrNull() === dialog && visibility.targetState) { events ->
                try {
                    events.collect {
                        if (dialog.bottomSheet != null) dialog.bottomSheet.predictiveProgress(it.progress)
                        else if (!reducedMotion && (page || bottom)) {
                            backProgress.snapTo(it.progress)
                        }
                    }
                    state.dismiss(dialog.handle)
                } catch (_: CancellationException) {
                    scope.launch {
                        dialog.bottomSheet?.restore() ?: backProgress.animateTo(0f, com.tyust.course.ui.theme.MotionSpring.snappy())
                    }
                }
            }
            AnimatedVisibility(visibleState = visibility, enter = EnterTransition.None, exit = ExitTransition.None) {
                val progress = transition.animateFloat(transitionSpec = {
                    when {
                        reducedMotion -> snap()
                        page -> com.tyust.course.ui.theme.MotionProfile.hierarchySpring()
                        targetState != EnterExitState.Visible -> tween(com.tyust.course.ui.theme.MotionProfile.SheetExitMillis)
                        bottom && dialog.bottomSheet != null -> tween(220,
                            easing = com.tyust.course.ui.theme.MotionEasing.FastOutSlowIn)
                        bottom -> com.tyust.course.ui.theme.MotionProfile.sheetSpring()
                        else -> spring(0.80f, 420f)
                    }
                }, label = "dialog-presence") { if (it == EnterExitState.Visible) 1f else 0f }
                val modules = transition.animateFloat(transitionSpec = {
                    when {
                        reducedMotion || dialog.bottomSheet == null -> snap()
                        targetState != EnterExitState.Visible -> tween(com.tyust.course.ui.theme.MotionProfile.SheetExitMillis)
                        else -> tween(240, easing = LinearEasing)
                    }
                }, label = "dialog-modules") { if (it == EnterExitState.Visible) 1f else 0f }
                LaunchedEffect(dialog) {
                    snapshotFlow { progress.value.coerceIn(0f, 1f) * (1f - backProgress.value) }
                        .collect { dialog.presence = it }
                }
                Box(
                    modifier.fillMaxSize().onGloballyPositioned { hostSize = it.size; hostOrigin = it.positionInRoot() },
                    contentAlignment = if (bottom) Alignment.BottomCenter else Alignment.Center
                ) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                        .background(Color.Black.copy(alpha = 0.22f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { state.dismiss(dialog.handle) }).clearAndSetSemantics {})
                    Box(
                        Modifier.graphicsLayer {
                                val presence = progress.value
                                val back = backProgress.value
                                val above = state.dialogs.lastOrNull { it.presentation == DialogPresentation.Page }
                                    ?.takeIf { state.dialogs.indexOf(it) > state.dialogs.indexOf(dialog) }?.presence ?: 0f
                                translationX = (if (page) with(density) { com.tyust.course.ui.theme.MotionProfile.HierarchyEnterDp.dp.toPx() } *
                                    (1f - presence + back) else 0f) - with(density) { com.tyust.course.ui.theme.MotionProfile.HierarchyBehindDp.dp.toPx() } * above
                                translationY = if (bottom) {
                                    val drag = dialog.bottomSheet?.offset ?: 0f
                                    if (dialog.bottomSheet != null) drag + (size.height - drag) * (1f - presence.coerceIn(0f, 1f))
                                    else size.height * ((1f - presence) / 3f + 0.18f * back)
                                } else 0f
                                scaleX = if (!page && !bottom) 0.94f + 0.06f * presence else 1f
                                scaleY = scaleX
                                if (bottom && dialog.bottomSheet != null && !reducedMotion) {
                                    val sheet = dialog.bottomSheet
                                    val p = presence.coerceIn(0f, 1f)
                                    val exit = dialog.exitSheetFrame
                                    val source = sheet.sourceBounds
                                    if (exit != null) {
                                        val fraction = (1f - p / dialog.exitStartPresence).coerceIn(0f, 1f)
                                        translationX = exit.x * (1f - fraction)
                                        translationY = exit.y + (size.height - exit.y) * fraction
                                        scaleX = exit.scaleX + (0.97f - exit.scaleX) * fraction
                                        scaleY = exit.scaleY + (0.97f - exit.scaleY) * fraction
                                    } else if (source != null && hostSize.height > 0) {
                                        val sourceCenter = source.center - hostOrigin
                                        translationX += (sourceCenter.x - hostSize.width / 2f) * (1f - p)
                                        translationY = sheet.offset + (sourceCenter.y - (hostSize.height - size.height / 2f)) * (1f - p)
                                        val initialX = (source.width / size.width).coerceIn(0.20f, 0.90f)
                                        val initialY = (source.height / size.height).coerceIn(0.16f, 0.75f)
                                        scaleX = initialX + (1f - initialX) * p
                                        scaleY = initialY + (1f - initialY) * p
                                    }
                                    dialog.lastSheetFrame = SheetFrame(translationX, translationY, scaleX, scaleY)
                                    dialog.lastPresence = p
                                }
                                alpha = presence.coerceIn(0f, 1f) * (1f - 0.12f * back) * (1f - 0.03f * above)
                            }
                            .then(if (page) Modifier.fillMaxSize() else Modifier.windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)).padding(vertical = 12.dp))
                            .semantics {
                                paneTitle = if (dialog.bottomSheet != null) "课程详情" else "对话框"
                                isTraversalGroup = true
                                if (!visibility.targetState || state.dialogs.lastOrNull() !== dialog) hideFromAccessibility()
                            }
                            // Eat only unhandled taps; a clickable parent would merge the
                            // switch's semantics and replace its accessibility action with a no-op.
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    ) {
                        CompositionLocalProvider(LocalDialogProgress provides progress, LocalDialogModuleProgress provides modules) { dialog.content() }
                    }
                    if (!visibility.targetState) {
                        Box(Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}
                        ).clearAndSetSemantics {})
                    }
                }
            }
        }
    }
}
