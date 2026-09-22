package com.tyust.course.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One clock per tab, shared by the visible glyph, optical copy and minimized capsule. */
@Stable
class NavigationIconPlayback(count: Int, private val scope: CoroutineScope) {
    private val clocks = List(count) { Animatable(1f) }
    private val jobs = arrayOfNulls<Job>(count)
    private val pending = BooleanArray(count)
    private var selected = -1
    private var reduced = false

    fun phase(index: Int): Float = clocks[index].value

    fun isRunning(index: Int): Boolean = jobs[index]?.isActive == true

    fun select(index: Int, reduceMotion: Boolean) {
        if (reduced != reduceMotion) {
            reduced = reduceMotion
            if (reduced) clocks.indices.forEach { stop(it) }
        }
        if (selected == index) return
        // Finish the current gesture at its original speed. Interrupting only discards
        // queued feedback, so both the pose and its velocity remain continuous.
        if (selected >= 0) pending[selected] = false
        selected = index
        replay(index)
    }

    fun replay(index: Int) {
        if (reduced) return
        if (isRunning(index)) {
            pending[index] = true
            return
        }
        val clock = clocks[index]
        jobs[index] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            do {
                pending[index] = false
                // The renderer has identical poses and zero velocity at 0 and 1.
                // Only a completed gesture can enter a new cycle.
                clock.snapTo(0f)
                clock.animateTo(1f, tween(480, easing = LinearEasing))
            } while (pending[index] && !reduced)
        }
    }

    private fun stop(index: Int) {
        pending[index] = false
        jobs[index]?.cancel()
        jobs[index] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            clocks[index].snapTo(1f)
        }
    }

    fun dispose() = jobs.forEach { it?.cancel() }
}

@Composable
fun rememberNavigationIconPlayback(count: Int, selected: Int, reduced: Boolean): NavigationIconPlayback {
    val scope = rememberCoroutineScope()
    val state = remember(count, scope) { NavigationIconPlayback(count, scope) }
    LaunchedEffect(state, selected, reduced) { state.select(selected, reduced) }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}
