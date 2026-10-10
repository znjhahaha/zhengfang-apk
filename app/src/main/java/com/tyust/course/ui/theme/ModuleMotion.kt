package com.tyust.course.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.dp

/** A navigation-owned timeline; lazy items never own or restart entrance animations. */
val LocalModuleEntrance = compositionLocalOf<(() -> Float)?> { null }

object ModuleMotion {
    const val EnterMillis = 380
    const val StaggerMillis = 75
    const val MaxGroups = 4
    const val TimelineMillis = EnterMillis + StaggerMillis * (MaxGroups - 1)
    const val ExitMillis = 170

    fun progress(timeline: Float, group: Int): Float {
        val elapsed = timeline.coerceIn(0f, 1f) * TimelineMillis
        val phase = ((elapsed - group.coerceIn(0, MaxGroups - 1) * StaggerMillis) / EnterMillis).coerceIn(0f, 1f)
        return MotionEasing.FastOutSlowIn.transform(phase)
    }

    fun expand(reduced: Boolean): EnterTransition = if (reduced) EnterTransition.None else
        fadeIn(tween(EnterMillis)) + expandVertically(tween(EnterMillis, easing = MotionEasing.FastOutSlowIn))

    fun collapse(reduced: Boolean): ExitTransition = if (reduced) ExitTransition.None else
        fadeOut(tween(ExitMillis)) + shrinkVertically(tween(ExitMillis, easing = MotionEasing.Accelerate))
}

@Composable
fun Modifier.moduleEntrance(group: Int, progress: (() -> Float)? = null): Modifier {
    val timeline = progress ?: LocalModuleEntrance.current ?: return this
    // Auto clips an alpha layer to its raster bounds. Reserve transparent pixels
    // for the card's shadow without changing its measured size or placement.
    // ModulateAlpha avoids that clip but regresses nested text on API 31/32.
    return layout { measurable, constraints ->
        val outset = 24.dp.roundToPx()
        val placeable = measurable.measure(constraints.offset(outset * 2, outset * 2))
        layout((placeable.width - outset * 2).coerceAtLeast(0), (placeable.height - outset * 2).coerceAtLeast(0)) {
            placeable.place(-outset, -outset)
        }
    }.graphicsLayer {
        compositingStrategy = CompositingStrategy.Auto
        val p = ModuleMotion.progress(timeline(), group)
        alpha = p
        translationY = 16.dp.toPx() * (1f - p)
    }.layout { measurable, constraints ->
        val outset = 24.dp.roundToPx()
        val placeable = measurable.measure(constraints.offset(-outset * 2, -outset * 2))
        layout(placeable.width + outset * 2, placeable.height + outset * 2) {
            placeable.place(outset, outset)
        }
    }
}
