package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.tyust.course.ui.system.glass.glassChip
import com.tyust.course.ui.system.glass.rememberInteractiveOptics

/** A readable choice on a glass container. No nested page readback for every filter chip. */
@Composable
internal fun GlassFilterCapsule(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    toggle: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailing: ImageVector? = null
) {
    val accessibility = rememberGlassAccessibilityMode()
    val reduced = accessibility.reduceMotion
    val colors = MaterialTheme.colorScheme
    val appearance = LocalWallpaperAppearanceColors.current
    val overPanel = LocalOverlayBody.current
    val chosen = selected == true
    val emphasis by animateFloatAsState(if (chosen) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = .9f, stiffness = 700f), label = "filter-shape")
    val surface by animateColorAsState(when {
        !enabled -> colors.surfaceContainerHigh
        chosen -> colors.primary.copy(alpha = .12f).compositeOver(colors.surface)
        accessibility.highContrast || !isBackdropSupported() -> colors.surfaceContainerHigh
        else -> appearance.surface
    }, if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 1600f), label = "filter-surface")
    val foreground by animateColorAsState(when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = .62f)
        chosen -> colors.primary
        else -> if (overPanel || !isBackdropSupported()) colors.onSurface else appearance.onSurface
    }, if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 1600f), label = "filter-text")
    val optics = rememberInteractiveOptics()
    val source = remember { MutableInteractionSource() }
    val interaction = when {
        selected == null -> Modifier.clickable(source, null, enabled, role = Role.Button, onClick = onClick)
        toggle -> Modifier.toggleable(selected, source, null, enabled, Role.Checkbox) { onClick() }
        else -> Modifier.selectable(selected, source, null, enabled, Role.RadioButton, onClick)
    }
    val shape = Capsule()
    Row(modifier.heightIn(min = 48.dp).graphicsLayer {
        if (!reduced && enabled) {
            scaleX = 1f + .012f * emphasis - .035f * optics.pressProgress
            scaleY = 1f - .012f * emphasis - .035f * optics.pressProgress
        }
    }.then(if (enabled && !reduced) optics.gestureModifier else Modifier)
        .glassChip(shape, dimmed = !enabled, pressProgress = { if (reduced) 0f else optics.pressProgress })
        .clip(shape).background(surface).then(interaction).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // Reserve the check slot: changing selection must not reflow every chip.
        if (selected != null) {
            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp).graphicsLayer { alpha = emphasis.coerceIn(0f, 1f) }, tint = foreground)
        } else if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = foreground)
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f, fill = false))
        if (trailing != null) Icon(trailing, null, Modifier.size(18.dp), tint = foreground)
    }
}
