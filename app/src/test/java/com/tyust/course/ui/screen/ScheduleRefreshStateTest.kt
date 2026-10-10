package com.tyust.course.ui.screen

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.tyust.course.schedule.ScheduleDisplayPreferences
import com.tyust.course.schedule.ScheduleViewPosition
import com.tyust.course.schedule.schedulePageEntryKey
import com.tyust.course.ui.system.InitialPageLoad
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w360dp-h640dp")
class ScheduleRefreshStateTest {
    @get:Rule val compose = createComposeRule()
    private val token = mutableStateOf("initial-token")
    private val revision = mutableLongStateOf(0)
    private val refreshing = mutableStateOf(false)
    private val courses = mutableStateOf((1..6).map { i ->
        ScheduleCourseUi("课程 $i", "教师", "A$i", 2, i * 2 - 1, i * 2, "1-16", Color.Blue, id = "c$i")
    })
    private val times = (1..12).map { i -> PeriodTimeUi(i, "%02d:00".format(i + 6), "%02d:45".format(i + 6)) }
    private var latest = ScheduleViewPosition(0, 0, 0, 0, "")

    @Composable private fun Content() {
        MaterialTheme {
            InitialPageLoad(schedulePageEntryKey("app.schedule", "account-a", token.value, revision.longValue),
                "课表", active = true, transitionFinished = true, prepare = {}) {
                var week by rememberSaveable { mutableIntStateOf(6) }
                var day by rememberSaveable { mutableIntStateOf(2) }
                var dayView by rememberSaveable { mutableStateOf(true) }
                ScheduleScreen(week, courses.value, refreshing.value, times,
                    onWeekChange = { week = it }, onCourseClick = {},
                    firstWeekDate = "2026-09-07", selectedDay = day, onDayChange = { day = it },
                    displayPreferences = ScheduleDisplayPreferences(dayView = dayView),
                    onDisplayPreferences = { dayView = it.dayView },
                    positionKey = "account-a|term", positionCalendar = "term|2026-09-07",
                    onPositionChange = { latest = it }, hasCachedSchedule = true,
                    now = 1791216000000L)
            }
        }
    }

    private fun ready() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("schedule-header").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun dayList() = compose.onNode(hasTestTag("schedule-day-list") and
        hasAnyDescendant(hasTestTag("schedule-day-course-c1")), useUnmergedTree = true)
    private fun offset(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun scroll(node: SemanticsNodeInteraction): Float {
        node.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 360f) }
        compose.waitForIdle()
        return offset(node).also { assertTrue("The selected view must actually scroll", it > 100f) }
    }
    private fun refresh() {
        compose.runOnIdle { refreshing.value = true; token.value = "renewed-token"; revision.longValue++ }
        compose.waitForIdle()
        compose.runOnIdle {
            courses.value = courses.value.map { it.copy(location = "新教室") }
            refreshing.value = false
        }
        compose.waitForIdle()
    }

    @Test fun tokenAndDataRefreshKeepBothPresentationsAtTheBrowsedDateAndScroll() {
        compose.setContent { Content() }; ready()
        val dayOffset = scroll(dayList())
        refresh()
        assertEquals(dayOffset, offset(dayList()), 2f)
        assertEquals(6, latest.week); assertEquals(2, latest.day)
        compose.onNodeWithContentDescription("周视图").performClick(); compose.waitForIdle()
        val grid = compose.onNodeWithTag("schedule-grid-6")
        val weekOffset = scroll(grid)
        refresh()
        assertEquals(weekOffset, offset(grid), 2f)
        assertEquals(6, latest.week); assertEquals(2, latest.day)
        compose.onNodeWithContentDescription("日视图").performClick(); compose.waitForIdle()
        assertEquals(dayOffset, offset(dayList()), 2f)
    }

    @Test fun activityRestorationKeepsWeekModeAndItsScroll() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Content() }; ready()
        compose.onNodeWithContentDescription("周视图").performClick(); compose.waitForIdle()
        val before = scroll(compose.onNodeWithTag("schedule-grid-6"))
        restoration.emulateSavedInstanceStateRestore(); ready()
        assertEquals(before, offset(compose.onNodeWithTag("schedule-grid-6")), 2f)
        assertEquals(6, latest.week); assertEquals(2, latest.day)
    }
}
