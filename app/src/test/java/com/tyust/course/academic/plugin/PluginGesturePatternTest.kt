package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginGesturePatternTest {
    @Test fun fastSwipesIncludeIntermediateCellsInOrder() {
        val pattern = PluginGesturePattern()
        pattern.trace(PluginGesturePattern.center(1), PluginGesturePattern.center(3))
        pattern.trace(PluginGesturePattern.center(3), PluginGesturePattern.center(9))
        assertEquals("12369", pattern.value)
    }

    @Test fun diagonalAndReverseStrokesInsertTheMiddleOnlyOnce() {
        val pattern = PluginGesturePattern()
        pattern.select(9); pattern.select(1); pattern.select(9); pattern.select(3)
        assertEquals("95123", pattern.value)
        val crossing = PluginGesturePattern()
        crossing.select(2); crossing.select(1); crossing.select(3)
        assertEquals("213", crossing.value)
    }

    @Test fun malformedAndOutsideInputCannotAddCells() {
        val pattern = PluginGesturePattern()
        pattern.select(0); pattern.select(10)
        pattern.trace(PluginGesturePattern.Point(-1f, -1f), PluginGesturePattern.Point(-.5f, -.5f))
        pattern.trace(PluginGesturePattern.Point(Float.NaN, 0f), PluginGesturePattern.center(1))
        assertEquals("", pattern.value)
        assertTrue(PluginGesturePattern.valid("")); assertTrue(PluginGesturePattern.valid("12369"))
        for (invalid in listOf("1000", "12331", "a", "1234567891")) assertFalse(PluginGesturePattern.valid(invalid))
    }
}
