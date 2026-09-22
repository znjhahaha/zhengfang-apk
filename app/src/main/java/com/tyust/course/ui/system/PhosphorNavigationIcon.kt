package com.tyust.course.ui.system

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import com.tyust.course.ui.system.NavigationIconMotion.flourish

/**
 * Phosphor Icons duotone geometry, split into native drawing layers for motion.
 * https://github.com/phosphor-icons/core/tree/main/assets/duotone
 * Copyright (c) 2023 Phosphor Icons. MIT license: assets/licenses/phosphor-icons.txt.
 * No animation state lives in this renderer: all sampling copies draw the same frame.
 */
@Composable
fun PhosphorNavigationIcon(
    spec: AppSymbolSpec,
    selection: Float,
    phase: () -> Float,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val t = phase().coerceIn(0f, 1f)
        val weight = selection.coerceIn(0f, 1f)
        val fill = tint.copy(alpha = tint.alpha * (0.08f + 0.16f * weight))
        scale(size.width / 256f, size.height / 256f, Offset.Zero) {
            when (spec) {
                AppSymbolSpec.Courses -> drawBooks(t, tint, fill)
                AppSymbolSpec.Schedule -> drawCalendar(t, tint, fill)
                AppSymbolSpec.Grab -> drawLightning(t, tint, fill)
                AppSymbolSpec.Grades -> drawChart(t, tint, fill)
                AppSymbolSpec.Settings -> drawSliders(t, tint, fill)
            }
        }
    }
}

private val outlineStroke = Stroke(16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private fun path(data: String): Path = PathParser().parsePathString(data).toPath()
private object PhosphorPaths {
    val bookFill = path("M48,72h64V184H48Z")
    val leaningFill = path("M190.64,38.39a8,8,0,0,0-9.5-6.21l-46.81,10a8.07,8.07,0,0,0-6.15,9.57L139.79,107l62.46-13.42Z")
    // Absolute contour starts avoid Android 12's rMoveTo-after-close behavior,
    // which joins the book cutouts into a triangle below the original silhouette.
    val book = path("M104,32H56A16,16,0,0,0,40,48V208a16,16,0,0,0,16,16h48a16,16,0,0,0,16-16V48A16,16,0,0,0,104,32ZM56,48h48V64H56ZM56,80h48v96H56ZM104,208H56V192h48v16Z")
    val leaningBook = path("M231.65,194.55,198.46,36.75a16,16,0,0,0-19-12.39L132.65,34.42a16.08,16.08,0,0,0-12.3,19l33.19,157.8A16,16,0,0,0,169.16,224a16.25,16.25,0,0,0,3.38-.36l46.81-10.06A16.09,16.09,0,0,0,231.65,194.55ZM136,50.15c0-.06,0-.09,0-.09l46.8-10,3.33,15.87L139.33,66ZM142.62,81.62l46.82-10.05,3.34,15.9L146,97.53ZM149.26,113.19l46.82-10.06,13.3,63.24-46.82,10.06ZM216,197.94l-46.8,10-3.33-15.87L212.67,182,216,197.85C216,197.91,216,197.94,216,197.94Z")
    val boltFill = path("M96,240l16-80L48,136,160,16,144,96l64,24Z")
    val bolt = path("M215.79,118.17a8,8,0,0,0-5-5.66L153.18,90.9l14.66-73.33a8,8,0,0,0-13.69-7l-112,120a8,8,0,0,0,3,13l57.63,21.61L88.16,238.43a8,8,0,0,0,13.69,7l112-120A8,8,0,0,0,215.79,118.17ZM109.37,214l10.47-52.38a8,8,0,0,0-5-9.06L62,132.71l84.62-90.66L136.16,94.43a8,8,0,0,0,5,9.06l52.8,19.8Z")
}

private fun DrawScope.drawBooks(t: Float, tint: Color, fill: Color) {
    val move = flourish(t)
    rotate(-10f * move, Offset(80f, 216f)) {
        translate(-3f * move - 4f, -18f * move) {
            drawPath(PhosphorPaths.bookFill, fill)
            drawPath(PhosphorPaths.book, tint)
        }
    }
    rotate(14f * move, Offset(184f, 212f)) {
        translate(4f * move - 4f, -12f * move) {
            drawPath(PhosphorPaths.leaningFill, fill)
            drawPath(PhosphorPaths.leaningBook, tint)
        }
    }
}

private fun DrawScope.drawCalendar(t: Float, tint: Color, fill: Color) {
    drawRect(fill, Offset(40f, 40f), Size(176f, 48f))
    drawRoundRect(tint, Offset(40f, 40f), Size(176f, 176f), CornerRadius(8f), style = outlineStroke)
    drawLine(tint, Offset(40f, 88f), Offset(216f, 88f), 16f)
    for ((i, x) in listOf(80f, 176f).withIndex()) {
        val ringT = ((t - i * 0.06f) / (1f - i * 0.06f)).coerceIn(0f, 1f)
        val bounce = -16f * flourish(ringT)
        drawLine(tint, Offset(x, 24f + bounce), Offset(x, 56f + bounce), 16f, StrokeCap.Round)
    }
    val dots = listOf(Offset(128f, 132f), Offset(172f, 132f), Offset(84f, 172f), Offset(128f, 172f), Offset(172f, 172f))
    dots.forEachIndexed { index, point ->
        val dotT = ((t - index * 0.09f) / 0.48f).coerceIn(0f, 1f)
        val light = NavigationIconMotion.charge(dotT)
        drawCircle(tint.copy(alpha = tint.alpha * (1f - 0.68f * light.coerceAtLeast(0f))), 12f - 9f * light, point)
    }
}

private fun DrawScope.drawLightning(t: Float, tint: Color, fill: Color) {
    val charge = NavigationIconMotion.charge(t)
    val scale = 0.92f * (1f - 0.10f * charge)
    rotate(-8f * charge, Offset(128f, 128f)) {
    scale(scale, scale, Offset(128f, 128f)) {
        drawPath(PhosphorPaths.boltFill, fill)
        clipRect(0f, 0f, 256f, 16f + 224f * (t / 0.70f).coerceIn(0f, 1f)) {
            drawPath(PhosphorPaths.boltFill, tint.copy(alpha = tint.alpha * 0.72f * NavigationIconMotion.envelope(t)))
        }
        drawPath(PhosphorPaths.bolt, tint)
    }
    }
}

private fun DrawScope.drawChart(t: Float, tint: Color, fill: Color) {
    val bars = listOf(Triple(48f, 136f, 48f), Triple(96f, 88f, 56f), Triple(152f, 40f, 56f))
    bars.forEachIndexed { index, (x, top, width) ->
        val p = ((t - index * 0.07f) / (1f - index * 0.07f)).coerceIn(0f, 1f)
        // Move geometry rather than the canvas, keeping the 16-unit stroke uniform.
        val movingTop = 208f - (208f - top) * NavigationIconMotion.barScale(p)
        drawRect(if (index == 2) fill else fill.copy(alpha = fill.alpha * 0.5f), Offset(x, movingTop), Size(width, 208f - movingTop))
        val bar = Path().apply { moveTo(x, 208f); lineTo(x, movingTop); lineTo(x + width, movingTop); lineTo(x + width, 208f) }
        drawPath(bar, tint, style = outlineStroke)
    }
    drawLine(tint, Offset(32f, 208f), Offset(224f, 208f), 16f, StrokeCap.Round)
}

private fun DrawScope.drawSliders(t: Float, tint: Color, fill: Color) {
    val shift = 42f * flourish(t)
    for ((x, y) in listOf(104f + shift to 80f, 168f - shift to 176f)) {
        drawLine(tint, Offset(40f, y), Offset(x - 24f, y), 16f, StrokeCap.Round)
        drawLine(tint, Offset(x + 24f, y), Offset(216f, y), 16f, StrokeCap.Round)
        drawCircle(fill, 24f, Offset(x, y))
        drawCircle(tint, 24f, Offset(x, y), style = outlineStroke)
    }
}
