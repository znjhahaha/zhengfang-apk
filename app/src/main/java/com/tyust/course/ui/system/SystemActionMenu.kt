package com.tyust.course.ui.system

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.tyust.course.ui.system.glass.glassSheet
import com.tyust.course.ui.theme.ModuleMotion
import com.tyust.course.ui.theme.MotionEasing

data class SystemMenuAction(val title: String, val icon: ImageVector, val onClick: () -> Unit, val destructive: Boolean = false, val enabled: Boolean = true)

@Composable
fun SystemActionMenu(description: String, actions: List<SystemMenuAction>, modifier: Modifier = Modifier,
    anchorWidth: Dp = 48.dp, trigger: (@Composable (() -> Unit) -> Unit)? = null,
    expanded: Boolean? = null, onExpandedChange: ((Boolean) -> Unit)? = null) {
    var localExpanded by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: localExpanded
    fun setExpanded(value: Boolean) { if (onExpandedChange != null) onExpandedChange(value) else localExpanded = value }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var opensUp by remember { mutableStateOf(false) }
    var space by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val motion = animateFloatAsState(if (isExpanded && space > 0f) 1f else 0f,
        if (reduced) snap() else if (isExpanded) com.tyust.course.ui.theme.MotionProfile.iconSpring()
        else tween(ModuleMotion.ExitMillis, easing = MotionEasing.Accelerate), label = "actionMenu")
    val progress = motion.value.coerceIn(0f, 1f)
    LaunchedEffect(isExpanded) {
        if (isExpanded) pendingAction = null
    }
    val rowHeight = (48f * density.fontScale.coerceAtLeast(1f)).dp
    val desired = rowHeight * actions.size + 8.dp
    val bodyHeight = minOf(desired, with(density) { space.toDp() }).coerceAtLeast(0.dp)
    val liveExpanded = rememberUpdatedState(isExpanded)
    val liveActions = rememberUpdatedState(actions)
    val liveBodyHeight = rememberUpdatedState(bodyHeight)
    val liveOpensUp = rememberUpdatedState(opensUp)
    val menu: @Composable () -> Unit = {
        // Read presentation state in the overlay composition, including its outer
        // measurement. A captured zero height can otherwise keep the first open hidden.
        val progress = motion.value.coerceIn(0f, 1f)
        val bodyHeight = liveBodyHeight.value
        val backdrop = LocalModalBackdrop.current?.takeIf { isBackdropSupported() && LocalOverlayBody.current }
        BoxWithConstraints(Modifier.fillMaxWidth().height(bodyHeight * progress).clip(RoundedCornerShape(0.dp))) {
            val finalWidth = maxWidth
            Column(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).width(finalWidth).requiredHeight(bodyHeight).graphicsLayer {
                    alpha = progress
                    scaleX = 0.96f + 0.04f * progress
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(1f, if (liveOpensUp.value) 1f else 0f)
                }.then(if (backdrop != null) Modifier.glassSheet(backdrop, 16.dp) else
                    Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)))
                .clip(RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                SystemActionMenuContent(liveActions.value, liveExpanded.value, rowHeight) { action ->
                    if (pendingAction == null) { pendingAction = action.onClick; setExpanded(false) }
                }
            }
        }
    }
    AnchoredGlassOverlay(
        active = isExpanded || progress > 0f,
        renderedHeight = bodyHeight * progress,
        desiredHeight = desired,
        width = 244.dp,
        onDismiss = { setExpanded(false) },
        onClosed = {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        },
        onSpace = { available, up -> space = available; opensUp = up },
        diagnosticTag = "action-menu",
        phase = if (!isExpanded) "closing" else if (progress >= .999f) "open" else "opening",
        modifier = modifier.width(anchorWidth).height(48.dp),
        anchor = {
            if (trigger != null) trigger { setExpanded(!isExpanded) }
            else IconButton(onClick = { setExpanded(!isExpanded) }, modifier = Modifier.size(48.dp)) {
                AnimatedLineIcon(AnimatedIconSpec.More, Modifier.size(22.dp),
                    state = if (isExpanded) IconVisualState.Expanded else IconVisualState.Idle, description = description)
            }
        }, body = menu
    )
}

/** Shared menu rows: the browser supplies its single overlay, schedule keeps its adapter. */
@Composable
internal fun ColumnScope.SystemActionMenuContent(
    actions: List<SystemMenuAction>, enabled: Boolean, rowHeight: Dp = 48.dp,
    onAction: (SystemMenuAction) -> Unit
) {
                actions.forEachIndexed { index, action ->
                    Row(Modifier.fillMaxWidth().heightIn(min = rowHeight).clickable(
                        enabled = enabled && action.enabled, role = Role.Button, onClick = { onAction(action) }
                    ).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val color = (if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            .copy(alpha = if (action.enabled) 1f else .38f)
                        Text(action.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = color)
                        ActionLineIcon(action.icon, null, Modifier.size(20.dp), tint = color)
                    }
                    if (index < actions.lastIndex) SystemDivider()
                }
}
