package com.tyust.course.academic

import org.junit.Assert.*
import org.junit.Test

class QzTimetableCellsTest {
    @Test fun visibleCoursesUsePairedMetadataAndDoNotDuplicateTooltips() {
        val cell = """<div class='kbcontent1'>课程甲<br><font title='周次(节次)'>测试班1-210-17(全部)</font><br><font title='教室'>教室甲</font>
            <br>-----------------<br>课程乙<br><font title='周次(节次)'>测试班31-5,7-9(双周)</font><br><font title='教室'>教室乙</font></div>
            <div class='kbcontent' style='display:none'>课程甲[必修][A]<br><font title='老师'>教师甲</font><br><font title='班级'>测试班1-2<span title='选课人数'>(30)<br>人数30</span></font>
            <br>---------------------<br>课程乙[选修][B]<br><font title='老师'>教师乙</font><br><font title='班级'>测试班3<span title='选课人数'>(20)</span></font></div>"""
        val result = AcademicStudyParser.htmlSchedule(grid("091011", cell))
        assertEquals(listOf("课程甲", "课程乙"), result.map { it.name })
        assertEquals(listOf("教师甲", "教师乙"), result.map { it.teacher })
        assertEquals(listOf("教室甲", "教室乙"), result.map { it.location })
        assertEquals(listOf("10-17周", "1-5,7-9周(双)"), result.map { it.weeks })
        assertTrue(result.all { it.day == 2 && it.startPeriod == 9 && it.endPeriod == 11 })
    }

    @Test fun chinesePeriodRowsAndSeparatedWeekRangesArePreserved() {
        val result = AcademicStudyParser.htmlSchedule(grid("第十二节", paired("1-3,6-16(全部)"))).single()
        assertEquals(12 to 12, result.startPeriod to result.endPeriod)
        assertEquals("1-3,6-16周", result.weeks)
    }

    @Test fun mismatchedTooltipCannotTurnClassNumbersIntoWeeks() {
        val html = grid("0102", paired("1-16(全部)").replace("课程甲[必修]", "课程乙[必修]"))
        try { AcademicStudyParser.htmlSchedule(html); fail() }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
    }

    @Test fun unknownWeekModesAndMissingPeriodRowsAreExplicitFailures() {
        for (html in listOf(grid("0102", paired("1-16(新规则)")), grid("未定义", paired("1-16(全部)")))) {
            try { AcademicStudyParser.htmlSchedule(html); fail() }
            catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        }
    }

    @Test fun weekOnlyCellsDoNotRequireAnAbsentClassTooltip() {
        val cell = """<div class='kbcontent1'>模拟课程<br><font title='周次(节次)'>1-4,7,9-12(周)</font><br><font title='教室'>模拟教室</font></div>
            <div class='kbcontent' style='display:none'>模拟课程[必修]<br><font title='老师'>模拟教师</font><br><font title='周次(节次)'>1-4,7,9-12(周)</font></div>"""
        val result = AcademicStudyParser.htmlSchedule(grid("0102", cell)).single()
        assertEquals("1-4,7,9-12周", result.weeks)
        assertEquals(1 to 2, result.startPeriod to result.endPeriod)
        assertEquals("模拟教师", result.teacher)
        assertEquals("模拟课程", result.name)
    }

    @Test fun bareWeekNotationStillRejectsClassPrefixesAndInvalidRanges() {
        for (raw in listOf("未知班级1-16(周)", "0-16(周)", "16-1(周)", "1-61(周)")) {
            val cell = "<div class='kbcontent1'>模拟课程<br><font title='周次(节次)'>$raw</font></div>"
            try { AcademicStudyParser.htmlSchedule(grid("0102", cell)); fail("Malformed week notation accepted") }
            catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
        }
    }

    @Test fun weekOnlyVisibleTimeCanAccompanyClassMetadataInTheTooltip() {
        val cell = paired("2,4,8-16,18-19(周)").replace("测试班2,4,8-16,18-19(周)", "2,4,8-16,18-19(周)")
        val result = AcademicStudyParser.htmlSchedule(grid("0102", cell)).single()
        assertEquals("2,4,8-16,18-19周", result.weeks)
        assertEquals("教师甲", result.teacher)
        assertEquals(1 to 2, result.startPeriod to result.endPeriod)
    }

    @Test fun bareChinesePairedPeriodsAndTheFinalSinglePeriodAreDistinct() {
        val cell = "<div class='kbcontent1'>模拟课程<br><font title='周次(节次)'>1-16(周)</font></div>"
        for ((label, expected) in mapOf("一二" to (1 to 2), "三四" to (3 to 4), "九十" to (9 to 10), "十一" to (11 to 11))) {
            val item = AcademicStudyParser.htmlSchedule(grid(label, cell)).single()
            assertEquals(label, expected, item.startPeriod to item.endPeriod)
        }
        try { AcademicStudyParser.htmlSchedule(grid("一三", cell)); fail("Unknown Chinese grouping accepted") }
        catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
    }

    @Test fun explicitSmallPeriodGroupsTakePrecedenceOverPrintedClockTimes() {
        val cell = "<div class='kbcontent1'>模拟课程<br><font title='周次(节次)'>1-16(周)</font></div>"
        for ((label, expected) in mapOf("第一二节 (01,02小节) 09:00-10:30" to (1 to 2),
            "第十一十二节 (11,12小节) 20:40-22:10" to (11 to 12))) {
            val item = AcademicStudyParser.htmlSchedule(grid(label, cell)).single()
            assertEquals(expected, item.startPeriod to item.endPeriod)
        }
        try {
            AcademicStudyParser.htmlSchedule(grid("(01,02小节)(03,04小节)", cell)); fail("Ambiguous section groups accepted")
        } catch (e: AcademicException) { assertEquals(AcademicStatus.PAGE_CHANGED, e.status) }
    }

    private fun grid(period: String, cell: String) = "<table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr><tr><th>$period</th><td></td><td>$cell</td></tr></table>"
    private fun paired(weeks: String) = """<div class='kbcontent1'>课程甲<br><font title='周次(节次)'>测试班$weeks</font><br><font title='教室'>教室甲</font></div>
        <div class='kbcontent' style='display:none'>课程甲[必修]<br><font title='老师'>教师甲</font><br><font title='班级'>测试班<span title='选课人数'>(20)</span></font></div>"""
}
