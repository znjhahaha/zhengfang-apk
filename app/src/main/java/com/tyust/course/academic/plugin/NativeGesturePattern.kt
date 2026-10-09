package com.tyust.course.academic.plugin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** Draw locally, then deliver one input event on release. Cancellation keeps the previous value. */
@Composable
internal fun NativeGesturePattern(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit) {
    var preview by remember(value) { mutableStateOf(value) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var drawing by remember { mutableStateOf(false) }
    val emit by rememberUpdatedState(onValueChange)
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val ink = colors.primary.copy(alpha = if (enabled) 1f else .38f)
    fun commit(pattern: String) { preview = pattern; emit(pattern) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton({ commit("") }, enabled = enabled && !drawing && preview.isNotEmpty()) { Text("清空重画") }
        }
        Text(if (preview.isEmpty()) "按住圆点，滑动连接老师设置的图案" else "已连接 ${preview.length} 个点，可重新滑动绘制",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).testTag("native-gesture-pad")
                .semantics {
                    contentDescription = label
                    stateDescription = if (preview.isEmpty()) "尚未绘制手势" else "已连接 ${preview.length} 个点"
                    if (!enabled) disabled()
                }
                .pointerInput(enabled, value) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        val pattern = PluginGesturePattern()
                        fun point(position: Offset) = PluginGesturePattern.Point(position.x / size.width, position.y / size.height)
                        var previous = down.position
                        fun trace(position: Offset) {
                            val before = pattern.value.length
                            pattern.trace(point(previous), point(position))
                            previous = position
                            preview = pattern.value
                            finger = position
                            if (preview.length > before) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        var completed = false
                        try {
                            down.consume()
                            drawing = true
                            preview = ""
                            trace(down.position)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                                change.historical.forEach { trace(it.position) }
                                trace(change.position)
                                change.consume()
                                if (!change.pressed) { completed = pattern.value.isNotEmpty(); break }
                            }
                            if (completed) commit(pattern.value)
                        } finally {
                            finger = null
                            drawing = false
                            if (!completed) preview = value
                        }
                    }
                }) {
                Canvas(Modifier.matchParentSize()) {
                    fun center(cell: Int) = PluginGesturePattern.center(cell).let { Offset(it.x * size.width, it.y * size.height) }
                    val points = preview.map { center(it.digitToInt()) }
                    points.zipWithNext().forEach { (from, to) -> drawLine(ink, from, to, 3.dp.toPx(), StrokeCap.Round) }
                    finger?.takeIf { points.isNotEmpty() }?.let { drawLine(ink.copy(alpha = .6f), points.last(), it, 3.dp.toPx(), StrokeCap.Round) }
                    for (cell in 1..9) {
                        val selected = cell.digitToChar() in preview
                        val center = center(cell)
                        if (selected) {
                            drawCircle(ink.copy(alpha = .10f), 22.dp.toPx(), center)
                            drawCircle(ink.copy(alpha = .65f), 21.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                        }
                        drawCircle(if (selected) ink else colors.onSurfaceVariant.copy(alpha = if (enabled) .65f else .3f),
                            if (selected) 7.dp.toPx() else 6.dp.toPx(), center)
                    }
                }
                // Semantic actions give screen-reader users the same input without a keyboard.
                Column(Modifier.matchParentSize()) {
                    repeat(3) { row ->
                        Row(Modifier.fillMaxWidth().weight(1f)) {
                            repeat(3) { column ->
                                val cell = row * 3 + column + 1
                                Box(Modifier.weight(1f).fillMaxHeight().semantics {
                                    contentDescription = "第 $cell 个点"
                                    role = Role.Button
                                    selected = cell.digitToChar() in preview
                                    if (!enabled) disabled() else onClick("连接此点") {
                                        if (drawing) false else {
                                            val pattern = PluginGesturePattern(preview)
                                            pattern.select(cell)
                                            commit(pattern.value)
                                            true
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
