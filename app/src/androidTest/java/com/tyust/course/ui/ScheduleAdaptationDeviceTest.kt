package com.tyust.course.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.ui.screen.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ScheduleAdaptationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedPreviewOnly() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun headerUsesParentWidthAndWeekdaysStayAlignedWithEveryCourseColumn() {
        val width = mutableIntStateOf(412)
        val font = mutableFloatStateOf(1f)
        val collapse = mutableFloatStateOf(0f)
        val courses = (1..7).map { ScheduleCourseUi("课程", "教师", "A101", it, 1, 2, "1-25周", Color.Blue, id = "day-$it") }
        compose.setContent {
            val density = minOf(LocalDensity.current.density, LocalWindowInfo.current.containerSize.width.toFloat() / width.intValue)
            CompositionLocalProvider(LocalDensity provides Density(density, font.floatValue)) {
                CourseSelectorTheme {
                    Column(Modifier.requiredSize(width.intValue.dp, 720.dp).background(MaterialTheme.colorScheme.background)
                        .testTag("schedule-viewport")) {
                        WeekHeaderCompact(24, {}, {}, collapseFraction = collapse.floatValue, firstWeekDate = "2026-03-02", showToday = true)
                        Box(Modifier.weight(1f)) { ScheduleGrid(courses, 25, periodCount = 4, onCourseClick = {}) }
                    }
                }
            }
        }
        for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.3f, 1.6f)) {
            compose.runOnIdle { width.intValue = w; font.floatValue = f }
            for (p in listOf(0f, 0.4f, 0.75f, 1f)) {
                compose.runOnIdle { collapse.floatValue = p }
                val viewport = compose.onNodeWithTag("schedule-viewport").fetchSemanticsNode().boundsInRoot
                val title = compose.onNodeWithTag("schedule-header-title", true).fetchSemanticsNode().boundsInRoot
                val actions = compose.onNodeWithTag("schedule-header-actions", true).fetchSemanticsNode().boundsInRoot
                assertTrue("Title overlaps actions at $w/$f/$p", title.right <= actions.left + 1f || title.bottom <= actions.top + 1f)
                assertTrue(actions.left >= viewport.left && actions.right <= viewport.right + 1f)
                assertTrue("Date and controls share the first row", title.right <= actions.left + 1f)
                val actionBounds = listOf("日视图", "周视图", "更多课表操作").map {
                    compose.onNodeWithContentDescription(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                }
                compose.onNodeWithTag("schedule-today", useUnmergedTree = true).assertIsDisplayed()
                actionBounds.forEach { assertEquals(actionBounds.first().center.y, it.center.y, 1f) }
                for (day in 1..7) {
                    val label = compose.onNodeWithTag("schedule-weekday-$day", true).fetchSemanticsNode().boundsInRoot
                    val card = compose.onNodeWithTag("schedule-course-day-$day", true).fetchSemanticsNode().boundsInRoot
                    assertTrue("Weekday $day is shifted at $w/$f/$p", abs(label.center.x - card.center.x) <= 2f)
                }
                val weekLabel = compose.onNodeWithText("第 24 周", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("Week label clipped at $w/$f/$p", weekLabel.left >= title.left && weekLabel.right <= title.right + 1f)

            }
            compose.runOnIdle { collapse.floatValue = 0f }
            capture("header-${w}-font$f")
        }
    }

    @Test fun collapsedActionsRemainReachableAndControlsRestoreAfterReversingDrag() {
        val collapse = mutableFloatStateOf(0f)
        val day = mutableStateOf(false)
        var todayClicks = 0
        var pixelsPerDp = 1f
        compose.setContent {
            val density = minOf(LocalDensity.current.density, LocalWindowInfo.current.containerSize.width.toFloat() / 320)
            pixelsPerDp = density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                CourseSelectorTheme {
                    Column(Modifier.requiredSize(320.dp, 720.dp).testTag("schedule-viewport")) {
                        WeekHeaderCompact(24, {}, {}, collapseFraction = collapse.floatValue, firstWeekDate = "2026-03-02",
                            showToday = true, dayView = day.value, onDayView = { day.value = it }, onTodayClick = { todayClicks++ })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { collapse.floatValue = 0.75f }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { collapse.floatValue = 0.2f }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("日视图", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("schedule-today", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { collapse.floatValue = 1f }
        compose.mainClock.advanceTimeBy(1000)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("日视图", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(day.value) }
        val bounds = compose.onNodeWithTag("schedule-today").fetchSemanticsNode().boundsInRoot
        assertTrue("Today must retain a 48 dp target", bounds.width >= 48 * pixelsPerDp - 1 && bounds.height >= 48 * pixelsPerDp - 1)
        compose.onNodeWithTag("schedule-today").performClick()
        compose.runOnIdle { assertEquals(1, todayClicks); collapse.floatValue = 0f }
        compose.onNodeWithContentDescription("周视图", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("schedule-today", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun fullNamesAndTeachersKeepRoomNumbersAtEveryWidthAndFontSize() {
        val width = mutableIntStateOf(360)
        val height = mutableIntStateOf(720)
        val font = mutableFloatStateOf(1f)
        val courses = listOf(
            ScheduleCourseUi("思想道德与法治", "张文博、李思远", "五象校区 敦行教学楼东区 A1208 多媒体教室", 2, 1, 1,
                "1-25周", Color(0xFF63ADEC), id = "long-one"),
            ScheduleCourseUi("跨学科联合研讨与实验课程", "王若宁、陈建明", "五象校区 敦行教学楼东区三层 302 研讨教室（从东侧走廊进入）", 4, 1, 2,
                "1-25周", Color(0xFFE8B553), isCustom = true, customId = "long-two", id = "long-two")
        )
        compose.setContent {
            val density = minOf(LocalDensity.current.density, LocalWindowInfo.current.containerSize.width.toFloat() / width.intValue)
            CompositionLocalProvider(LocalDensity provides Density(density, font.floatValue)) {
                CourseSelectorTheme {
                    Box(Modifier.requiredSize(width.intValue.dp, height.intValue.dp)
                        .background(MaterialTheme.colorScheme.background).testTag("schedule-viewport")) {
                        ScheduleGrid(courses, 1, periodCount = 4, onCourseClick = {})
                    }
                }
            }
        }
        for (landscape in listOf(false, true)) for (w in listOf(320, 360, 412)) for (f in listOf(1f, 1.3f, 1.6f)) {
            compose.runOnIdle { width.intValue = if (landscape) 640 else w; height.intValue = if (landscape) w else 720; font.floatValue = f }
            courses.forEach { course ->
                for ((tag, text) in listOf("schedule-name-${course.id}" to course.name, "schedule-teacher-${course.id}" to course.teacher)) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    assertEquals(text, layout.layoutInput.text.text)
                    assertFalse("Full text clipped at $w/$f/$landscape: $text", layout.hasVisualOverflow)
                    assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
                }
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("schedule-location-${course.id}", true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val result = results.single()
                if (result.hasVisualOverflow) capture("classroom-overflow-${w}-font$f")
                assertFalse("Location clipped at $w/$f/$landscape: ${course.location}; size=${result.size}, " +
                    "widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, " +
                    "paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, label=${result.layoutInput.text}, constraints=${result.layoutInput.constraints}", result.hasVisualOverflow)
                val room = requireNotNull(com.tyust.course.schedule.ScheduleLocation.room(course.location))
                assertTrue("Room number was lost", result.layoutInput.text.text.endsWith(room))
                assertEquals(result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1))
                assertFalse(result.isLineEllipsized(result.lineCount - 1))
            }
            if (!landscape && (f == 1f || w == 320)) capture("classroom-${w}-font$f")
        }
    }

    private fun capture(name: String) {
        val directory = File(compose.activity.getExternalFilesDir(null), "light-glass-validation").apply { mkdirs() }
        if (android.os.Build.VERSION.SDK_INT < 26) {
            androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
                .takeScreenshot(File(directory, "$name.png"))
            return
        }
        val bitmap = compose.onNodeWithTag("schedule-viewport").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
