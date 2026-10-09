package com.tyust.course.academic.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginLocationInputTest {
    @Test fun pastedCoordinatesPreserveTheExplicitLongitudeLatitudeOrder() {
        for (value in listOf("112.5,37.85", "112.5，37.85", "112.5 37.85", "经度：112.5 纬度：37.85", "latitude=37.85 longitude=112.5".replace('=', ':'))) {
            assertEquals(PluginCoordinates.Point(37.85, 112.5), PluginLocationInput.parse(value))
        }
        assertEquals(PluginCoordinates.Point(-33.8, 151.2), PluginLocationInput.parse("151.2, -33.8"))
    }
    @Test fun invalidAndAmbiguousExtraValuesAreNotSilentlyUsed() {
        for (value in listOf("37.85,112.5", "181,37", "112,91", "NaN,37", "112,37,5", "学校 A101")) assertNull(value, PluginLocationInput.parse(value))
    }
}
