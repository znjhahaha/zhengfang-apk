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
                        WeekHeaderCompact(24, {}, {}, collapseFraction = collapse.floatValue, firstWeekDate = "2026-03-02")
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
                actionBounds.forEach { assertEquals(actionBounds.first().center.y, it.center.y, 1f) }
                for (day in 1..7) {
                    val label = compose.onNodeWithTag("schedule-weekday-$day", true).fetchSemanticsNode().boundsInRoot
                    val card = compose.onNodeWithTag("schedule-course-day-$day", true).fetchSemanticsNode().boundsInRoot
                    assertTrue("Weekday $day is shifted at $w/$f/$p", abs(label.center.x - card.center.x) <= 2f)
                }
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText("第 24 周", useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val titleResult = results.single()
                // A wrap-content Text may retain a wider paragraph after intrinsic
                // measurement. Check the actual glyphs, not that empty paragraph area.
                assertFalse("Week title height clipped at $w/$f/$p", titleResult.didOverflowHeight)
                assertTrue("Week title width clipped at $w/$f/$p", titleResult.getLineRight(0) <= titleResult.size.width + 1f)
                assertEquals("第 24 周".length, titleResult.getLineEnd(0))
            }
            compose.runOnIdle { collapse.floatValue = 0f }
            capture("header-${w}-font$f")
        }
    }

    @Test fun longClassroomsFitWithoutEllipsisEvenInSinglePeriodCards() {
        val width = mutableIntStateOf(360)
        val height = mutableIntStateOf(720)
        val font = mutableFloatStateOf(1f)
        val courses = listOf(
            ScheduleCourseUi("思想道德与法治", "教师", "五象校区 敦行教学楼东区 A1208 多媒体教室", 2, 1, 1,
                "1-25周", Color(0xFF63ADEC), id = "long-one"),
            ScheduleCourseUi("跨学科联合研讨与实验课程", "教师", "五象校区 敦行教学楼东区三层 302 研讨教室（从东侧走廊进入）", 4, 1, 2,
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
                val results = mutableListOf<TextLayoutResult>()
                compose.onNodeWithTag("schedule-location-${course.id}", true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                val result = results.single()
                if (result.hasVisualOverflow) capture("classroom-overflow-${w}-font$f")
                assertFalse("Location clipped at $w/$f/$landscape: ${course.location}; size=${result.size}, " +
                    "widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, " +
                    "paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, constraints=${result.layoutInput.constraints}", result.hasVisualOverflow)
                assertEquals(course.location.length, result.getLineEnd(result.lineCount - 1))
                assertFalse(result.isLineEllipsized(result.lineCount - 1))
            }
            if (!landscape && (f == 1f || w == 320)) capture("classroom-${w}-font$f")
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("schedule-viewport").captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.getExternalFilesDir(null), "light-glass-validation").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
