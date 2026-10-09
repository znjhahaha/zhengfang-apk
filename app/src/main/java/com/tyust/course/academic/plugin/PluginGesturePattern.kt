package com.tyust.course.academic.plugin

import kotlin.math.abs

/** Nine fixed cells, numbered by row. Values are input data, never submission actions. */
internal class PluginGesturePattern(initial: String = "") {
    data class Point(val x: Float, val y: Float)
    private val selected = mutableListOf<Int>()
    val value: String get() = selected.joinToString("")
    init { require(valid(initial)); selected.addAll(initial.map { it.digitToInt() }) }

    fun select(cell: Int) {
        if (cell !in 1..9 || cell in selected) return
        selected.lastOrNull()?.let { last ->
            val row = (last - 1) / 3; val column = (last - 1) % 3
            val nextRow = (cell - 1) / 3; val nextColumn = (cell - 1) % 3
            if ((abs(row - nextRow) == 2 && abs(column - nextColumn) in setOf(0, 2)) ||
                (row == nextRow && abs(column - nextColumn) == 2)) {
                val middle = (row + nextRow) / 2 * 3 + (column + nextColumn) / 2 + 1
                if (middle !in selected) selected += middle
            }
        }
        selected += cell
    }

    /** Segment hit testing keeps fast moves from skipping cells between touch samples. */
    fun trace(from: Point, to: Point) {
        if (!listOf(from.x, from.y, to.x, to.y).all { it.isFinite() }) return
        val dx = to.x - from.x; val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        (1..9).mapNotNull { cell ->
            val center = center(cell)
            val along = if (lengthSquared == 0f) 0f else
                (((center.x - from.x) * dx + (center.y - from.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            val x = from.x + along * dx - center.x; val y = from.y + along * dy - center.y
            if (x * x + y * y <= HIT_RADIUS * HIT_RADIUS) along to cell else null
        }.sortedBy { it.first }.forEach { select(it.second) }
    }

    companion object {
        private const val HIT_RADIUS = 0.11f
        fun center(cell: Int) = Point(((cell - 1) % 3 + .5f) / 3, ((cell - 1) / 3 + .5f) / 3)
        fun valid(value: String) = value.length <= 9 && value.all { it in '1'..'9' } && value.toSet().size == value.length
    }
}
