package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginCoordinatesTest {
    @Test fun knownWgsCoordinateAndInverseRespectTheDeclaredSystems() {
        val wgs = PluginCoordinates.Point(39.9, 116.4)
        val gcj = PluginCoordinates.convert(wgs, "WGS84", "GCJ02")
        assertEquals(39.9014035298494, gcj.latitude, 1e-9)
        assertEquals(116.40624278491117, gcj.longitude, 1e-9)
        val bd = PluginCoordinates.convert(wgs, "WGS84", "BD09")
        assertEquals(39.90773378164862, bd.latitude, 1e-9)
        assertEquals(116.41262802928223, bd.longitude, 1e-9)
        val back = PluginCoordinates.convert(bd, "BD09", "WGS84")
        assertEquals(wgs.latitude, back.latitude, 2e-6)
        assertEquals(wgs.longitude, back.longitude, 2e-6)
        assertEquals(bd, PluginCoordinates.convert(bd, "BD09", "BD09"))
    }

    @Test fun outsideChinaWgsGcjIsUnchangedAndInvalidInputIsRejected() {
        val point = PluginCoordinates.Point(51.5, -0.1)
        assertEquals(point, PluginCoordinates.convert(point, "WGS84", "GCJ02"))
        for (invalid in listOf(PluginCoordinates.Point(Double.NaN, 1.0), PluginCoordinates.Point(91.0, 10.0), PluginCoordinates.Point(1.0, 181.0))) {
            assertThrows(IllegalArgumentException::class.java) { PluginCoordinates.convert(invalid, "WGS84", "BD09") }
        }
        assertThrows(IllegalArgumentException::class.java) { PluginCoordinates.convert(point, "unknown", "BD09") }
    }
}
