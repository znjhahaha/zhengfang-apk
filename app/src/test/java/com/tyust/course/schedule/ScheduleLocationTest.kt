package com.tyust.course.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleLocationTest {
    @Test fun compactAddressesKeepBuildingsAndRooms() {
        assertEquals("明理楼 B302", ScheduleLocation.compact("主校区 明理楼 B302（实验机房）"))
        assertEquals("教室待定", ScheduleLocation.compact(" "))
        assertEquals("线上教学", ScheduleLocation.compact("线上教学"))
        assertEquals("博学楼 A205", ScheduleLocation.compact("博学楼 A205"))
        assertEquals("敦行教学楼东区 302", ScheduleLocation.compact("五象校区 敦行教学楼东区三层 302 研讨教室（东侧进入）"))
    }
    @Test fun narrowLabelsShortenBuildingsBeforeRoomNumbers() {
        val address = "五象校区 敦行教学楼东区 A1208 多媒体教室"
        val single = ScheduleLocation.fit(address, 9f) { it.length.toFloat() }
        assertTrue(single.length <= 9)
        assertTrue(single.endsWith("A1208"))
        val two = ScheduleLocation.fit(address, 6f, 2) { it.length.toFloat() }
        assertEquals("A1208", two.lineSequence().last())
        assertTrue(two.lineSequence().all { it.length <= 6 })
    }
}
