package com.tyust.course.ui.system

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.sin

val LocalLoadingRoute = compositionLocalOf { "" }
val LocalLoadingActive = compositionLocalOf { true }

/** Drawing reads the animation value, so the surrounding loading layout does not recompose per frame. */
@Composable fun PageLoadingIcon(route: String = LocalLoadingRoute.current, modifier: Modifier = Modifier.size(40.dp),
    active: Boolean = LocalLoadingActive.current) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val running = active && !reduced && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val phase = if (running) rememberInfiniteTransition(label = "page-icon").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "page-icon-phase") else remember { mutableFloatStateOf(0f) }
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "加载中" }) {
        val u = size.minDimension / 32f
        val p = phase.value
        fun wave(i: Int) = ((sin((p * 2 * PI - i * 0.7)) + 1) / 2).toFloat()
        if (route.contains("grades")) {
            repeat(3) { i ->
                val h = (if (running) 8 + wave(i) * 15 else 10f + i * 5) * u
                drawRoundRect(color.copy(alpha = 0.62f + i * 0.17f), Offset((4 + i * 9) * u, 28 * u - h), Size(6 * u, h), CornerRadius(2 * u))
            }
        } else if (route.contains("courses")) {
            val turn = if (running) wave(0) * 2 * u else u
            val left = Path().apply {
                moveTo(16 * u, 8 * u); quadraticTo(10 * u, 3 * u, 3 * u, 6 * u)
                lineTo(3 * u, 26 * u); quadraticTo(10 * u, 23 * u, 16 * u, 28 * u); close()
            }
            val right = Path().apply {
                moveTo(16 * u, 8 * u); quadraticTo(22 * u, 3 * u + turn, 29 * u, 6 * u + turn)
                lineTo(29 * u, 26 * u); quadraticTo(22 * u, 23 * u, 16 * u, 28 * u); close()
            }
            drawPath(left, color, style = Stroke(2 * u))
            drawPath(right, color, style = Stroke(2 * u))
            repeat(3) { i ->
                val tint = color.copy(alpha = if (running) 0.3f + wave(i) * 0.7f else 0.7f)
                drawLine(tint, Offset(7 * u, (11 + i * 5) * u), Offset(12 * u, (12 + i * 5) * u), 1.8f * u, StrokeCap.Round)
                drawLine(tint, Offset(20 * u, (12 + i * 5) * u), Offset(25 * u, (11 + i * 5) * u), 1.8f * u, StrokeCap.Round)
            }
        } else if (route.contains("grab")) {
            val bolt = Path().apply {
                moveTo(19 * u, 3 * u); lineTo(6 * u, 18 * u); lineTo(14 * u, 18 * u)
                lineTo(12 * u, 29 * u); lineTo(26 * u, 13 * u); lineTo(18 * u, 13 * u); close()
            }
            drawPath(bolt, color.copy(alpha = if (running) 0.15f + wave(0) * 0.35f else 0.25f))
            drawPath(bolt, color, style = Stroke(2 * u, cap = StrokeCap.Round))
        } else {
            drawRoundRect(color, Offset(4 * u, 6 * u), Size(24 * u, 23 * u), CornerRadius(5 * u), style = Stroke(2 * u))
            if (route.contains("schedule")) {
                drawLine(color, Offset(4 * u, 13 * u), Offset(28 * u, 13 * u), 2 * u)
                for (x in listOf(10f, 22f)) drawLine(color, Offset(x * u, 3 * u), Offset(x * u, 8 * u), 2.4f * u, StrokeCap.Round)
                repeat(3) { i -> drawCircle(color.copy(alpha = if (running) 0.25f + wave(i) * 0.75f else 0.75f), 2 * u, Offset((10 + i * 6) * u, 21 * u)) }
            } else repeat(3) { i ->
                val alpha = if (running) 0.3f + wave(i) * 0.7f else 0.75f
                drawCircle(color.copy(alpha = alpha), u, Offset(9 * u, (12 + i * 6) * u))
                drawLine(color.copy(alpha = alpha), Offset(14 * u, (12 + i * 6) * u), Offset(23 * u, (12 + i * 6) * u), 2 * u, StrokeCap.Round)
            }
        }
    }
}
