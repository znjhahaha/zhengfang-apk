package com.tyust.course.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.schedule.*
import com.tyust.course.ui.screen.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.io.File
import java.text.SimpleDateFormat
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleDayInteractionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedPreviewOnly() = assumeTrue(BuildConfig.UI_PREVIEW)
    private val now get() = SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-09-07 08:10")!!.time
    private val times = listOf(PeriodTimeUi(1, "08:00", "08:45"), PeriodTimeUi(2, "09:00", "09:45"))
    private val courses = listOf(
        ScheduleCourseUi("数据结构", "教师", "博学楼 A205", 1, 1, 1, "1-16周", Color(0xFF6585B3), id = "current"),
        ScheduleCourseUi("计算机网络", "教师", "明理楼 B302", 1, 2, 2, "1-16周", Color(0xFF799B87), id = "next"),
        ScheduleCourseUi("学术英语", "教师", "博学楼 203", 2, 1, 1, "1-16周", Color(0xFF9983B5), id = "tomorrow")
    )

    @Test fun daySwipesChangeDatesAndLiveStatusOnlyBelongsToToday() {
        var selectedWeek = 0
        var selectedDay = 0
        var longClicked: String? = null
        compose.setContent {
            var week by rememberSaveable { mutableIntStateOf(1) }
            var day by rememberSaveable { mutableIntStateOf(1) }
            var dayView by rememberSaveable { mutableStateOf(true) }
            var request by remember { mutableIntStateOf(0) }
            val observedWeek = week
            val observedDay = day
            SideEffect { selectedWeek = observedWeek; selectedDay = observedDay }
            CourseSelectorTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("day-preview")) {
                    ScheduleScreen(week, courses, false, times, 4, { week = it }, {},
                        firstWeekDate = "2026-09-07", weekRequestKey = "calendar|$request", now = now,
                        selectedDay = day, onDayChange = { day = it },
                        displayPreferences = ScheduleDisplayPreferences(dayView), onDisplayPreferences = { dayView = it.dayView },
                        onTodayClick = { week = 1; day = 1; request++ }, onCourseLongClick = { longClicked = it.id })
                }
            }
        }
        compose.onNodeWithText("今日 2 堂 · 还剩 2 堂").assertIsDisplayed()
        compose.onNodeWithText("正在上课").assertIsDisplayed()
        compose.onNodeWithText("下一节").assertIsDisplayed()
        capture("today-current-next")
        compose.onNodeWithTag("schedule-day-course-current").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("current", longClicked) }
        compose.onNodeWithTag("schedule-pager").performTouchInput { swipeLeft() }
        capture("day-after-swipe")
        compose.waitUntil(3_000) { selectedDay == 2 }
        compose.runOnIdle { assertEquals(1, selectedWeek); assertEquals(2, selectedDay) }
        compose.onNodeWithText("学术英语").assertIsDisplayed()
        compose.onNodeWithText("当日 1 堂").assertIsDisplayed()
        compose.onNodeWithText("正在上课").assertDoesNotExist()
        compose.onNodeWithText("下一节").assertDoesNotExist()
        compose.onNodeWithContentDescription("周视图").performClick()
        compose.onNodeWithContentDescription("星期二").performClick()
        compose.onNodeWithContentDescription("日视图").assertIsSelected()
        compose.onNodeWithText("学术英语").assertIsDisplayed()
        compose.onNodeWithContentDescription("回到今天").performClick()
        compose.runOnIdle { assertEquals(1, selectedDay) }
        compose.onNodeWithText("正在上课").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择日期与学期").performClick()
        compose.onNodeWithContentDescription("选择第 2 周").performClick()
        compose.onNodeWithText("当日 2 堂").assertIsDisplayed()
        compose.onNodeWithText("正在上课").assertDoesNotExist()
        compose.onNodeWithText("下一节").assertDoesNotExist()
    }

    @Test fun dayCardsKeepLongNamesAndRoomsReadableAtEveryWidthAndLargeFont() {
        val width = mutableIntStateOf(412)
        val font = mutableFloatStateOf(1f)
        val dark = mutableStateOf(false)
        val course = courses.first().copy(name = "数据结构与算法基础", location = "南校区综合教学楼 A 区 1205（实验室）")
        compose.setContent {
            val density = minOf(LocalDensity.current.density, LocalWindowInfo.current.containerSize.width.toFloat() / width.intValue)
            CompositionLocalProvider(LocalDensity provides Density(density, font.floatValue)) {
                CourseSelectorTheme(darkTheme = dark.value) {
                    Box(Modifier.requiredSize(width.intValue.dp, 720.dp).background(MaterialTheme.colorScheme.background).testTag("day-preview")) {
                        ScheduleScreen(1, listOf(course), false, times, 4, {}, {}, firstWeekDate = "2026-09-07", now = now, selectedDay = 1)
                    }
                }
            }
        }
        for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.6f)) for (night in listOf(false, true)) {
            compose.runOnIdle { width.intValue = w; font.floatValue = f; dark.value = night }
            for (text in listOf(course.name, course.location, "08:00", "08:45")) {
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val layout = results.single()
                if (layout.hasVisualOverflow) capture("day-overflow-$w-font$f")
                assertFalse("Clipped $text at $w/$f/$night: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)
                assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
            }
            capture("day-$w-font$f-${if (night) "dark" else "light"}")
        }
    }

    @Test fun missingCalendarShowsOneActionWithoutInventingATodaySchedule() {
        compose.setContent {
            CourseSelectorTheme {
                ScheduleScreen(1, courses, false, times, 4, {}, {}, firstWeekDate = null, now = now)
            }
        }
        compose.onNodeWithText("设置开学日期后显示当天课程").assertIsDisplayed()
        compose.onNodeWithText("设置开学日期").assertIsDisplayed()
        compose.onNodeWithTag("schedule-day-summary").assertDoesNotExist()
        compose.onNodeWithTag("schedule-day-course-current").assertDoesNotExist()
    }

    @Test fun dayScrollSurvivesSavedStateRestorationAndViewSwitches() {
        val restoration = StateRestorationTester(compose)
        val dayView = mutableStateOf(true)
        val many = (1..12).map { courses.first().copy(id = "period-$it", startPeriod = it, endPeriod = it) }
        restoration.setContent {
            CourseSelectorTheme {
                Box(Modifier.size(360.dp, 600.dp)) {
                    ScheduleScreen(1, many, false, times, 12, {}, {}, firstWeekDate = "2026-09-07", now = now,
                        selectedDay = 1, displayPreferences = ScheduleDisplayPreferences(dayView.value),
                        onDisplayPreferences = { dayView.value = it.dayView })
                }
            }
        }
        fun scroll() = compose.onNodeWithTag("schedule-day-list").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("schedule-day-list").performTouchInput { swipeUp() }
        val saved = scroll()
        assertTrue(saved > 0f)
        compose.onNodeWithContentDescription("周视图").performClick()
        compose.onNodeWithContentDescription("日视图").performClick()
        assertEquals(saved, scroll(), 2f)
        restoration.emulateSavedInstanceStateRestore()
        assertEquals(saved, scroll(), 2f)
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("day-preview").captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.getExternalFilesDir(null), "light-glass-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
