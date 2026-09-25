package com.tyust.course.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.ui.screen.WeekHeaderCompact
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ScheduleDateAlignmentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun datesRollForwardAndBackwardWithoutMovingTheColumnCenters() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        var week by mutableIntStateOf(1)
        var day by mutableIntStateOf(3)
        compose.setContent {
            CourseSelectorTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color(0xfff5f5fa))) {
                    Box(Modifier.width(390.dp)) {
                        WeekHeaderCompact(week, {}, {}, firstWeekDate = "2026-09-07", selectedDay = day,
                            onWeekSelect = {}, now = 1_790_899_200_000L)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val transitions = JSONArray()
        var frame = 0
        fun capture() {
            val bitmap = compose.onNodeWithTag("schedule-header").captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "schedule-date-motion-${frame++.toString().padStart(2, '0')}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        fun node(tag: String, text: String) = compose.onNode(hasTestTag(tag) and hasText(text, substring = false), useUnmergedTree = true)
        fun center(tag: String, text: String): Float {
            val match = node(tag, text)
            val layouts = mutableListOf<TextLayoutResult>()
            match.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            return match.fetchSemanticsNode().positionInRoot.x +
                (layout.getBoundingBox(0).left + layout.getBoundingBox(text.lastIndex).right) / 2
        }
        fun settle() {
            compose.mainClock.advanceTimeBy(800)
            compose.waitForIdle()
            compose.onAllNodesWithTag("schedule-date-title", useUnmergedTree = true).assertCountEquals(1)
        }
        fun changeTitle(nextWeek: Int, nextDay: Int, from: String, to: String, direction: Int, record: Boolean = false) {
            val beforeWidth = compose.onNodeWithTag("schedule-date-title-slot", useUnmergedTree = true).fetchSemanticsNode().size.width
            if (record) capture()
            compose.runOnUiThread { week = nextWeek; day = nextDay }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val slot = compose.onNodeWithTag("schedule-date-title-slot", useUnmergedTree = true).fetchSemanticsNode()
            val incoming = node("schedule-date-title", to).fetchSemanticsNode()
            val outgoing = node("schedule-date-title", from).fetchSemanticsNode()
            assertTrue("Incoming date must roll in the chronological direction", (incoming.positionInRoot.y - slot.positionInRoot.y) * direction > 1f)
            assertTrue("Previous date must roll out in the same direction", (outgoing.positionInRoot.y - slot.positionInRoot.y) * direction < -1f)
            val middleWidth = slot.size.width
            if (record) {
                capture()
                repeat(6) { compose.mainClock.advanceTimeBy(32); capture() }
            }
            settle()
            node("schedule-date-title", to).assertExists()
            val afterWidth = compose.onNodeWithTag("schedule-date-title-slot", useUnmergedTree = true).fetchSemanticsNode().size.width
            assertTrue("Title width must adapt without overshoot", middleWidth in minOf(beforeWidth, afterWidth)..maxOf(beforeWidth, afterWidth))
            if (from == "9月9日" && to == "9月10日") {
                assertTrue("The second digit needs room", afterWidth > beforeWidth)
                assertTrue("Digit-count change must animate the width", middleWidth > beforeWidth && middleWidth < afterWidth)
            }
            if (record) capture()
            transitions.put(JSONObject().put("from", from).put("to", to).put("direction", direction)
                .put("widthBefore", beforeWidth).put("widthDuring", middleWidth).put("widthAfter", afterWidth))
        }
        changeTitle(1, 4, "9月9日", "9月10日", 1, record = true)
        changeTitle(1, 3, "9月10日", "9月9日", -1)
        compose.runOnUiThread { week = 4; day = 3 }; settle()
        changeTitle(4, 4, "9月30日", "10月1日", 1, record = true)
        changeTitle(4, 3, "10月1日", "9月30日", -1)
        compose.runOnUiThread { week = 1; day = 3 }; settle()
        val columnWidth = compose.onNodeWithTag("schedule-day-number-3-slot", useUnmergedTree = true).fetchSemanticsNode().size.width
        for ((nextWeek, from, to) in listOf(Triple(2, "9", "16"), Triple(1, "16", "9"))) {
            compose.runOnUiThread { week = nextWeek }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(64)
            val weekday = center("schedule-weekday-label-3", "三")
            for (value in listOf(from, to)) assertEquals("Rolling numbers must stay centered under the weekday", weekday, center("schedule-day-number-3", value), 1f)
            assertEquals("One and two digits must use the same date column", columnWidth,
                compose.onNodeWithTag("schedule-day-number-3-slot", useUnmergedTree = true).fetchSemanticsNode().size.width)
            settle()
        }
        // Retarget before earlier transitions finish, including a reversal.
        compose.runOnUiThread { week = 4; day = 5 }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { week = 2; day = 1 }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnUiThread { week = 4; day = 4 }
        settle()
        node("schedule-date-title", "10月1日").assertExists()
        node("schedule-day-number-3", "30").assertExists()
        File(compose.activity.cacheDir, "schedule-date-motion-report.json").writeText(
            JSONObject().put("transitions", transitions).put("columnCentersStable", true)
                .put("rapidRetargeting", true).put("frames", frame).toString(2))
    }

    @Test fun weekdayAndDateShareTheirColumnCenterAcrossMonthAndFontChanges() {
        assumeTrue(BuildConfig.UI_PREVIEW)
        var width by mutableIntStateOf(390)
        var fontScale by mutableFloatStateOf(1f)
        var week by mutableIntStateOf(4)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CourseSelectorTheme(darkTheme = false) {
                    Box(Modifier.fillMaxSize().background(Color(0xfff5f5fa))) {
                        Box(Modifier.width(width.dp)) {
                            WeekHeaderCompact(week, {}, {}, firstWeekDate = "2026-09-07", selectedDay = 5,
                                onWeekSelect = {}, now = 1_790_899_200_000L)
                        }
                    }
                }
            }
        }
        fun geometry(tag: String): Pair<Float, Float> {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals("Date must stay on one line", 1, layout.lineCount)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            val text = layout.layoutInput.text.text
            val glyphs = text.indices.map(layout::getBoundingBox)
            // A non-filling weighted title can retain a wider paragraph allocation.
            // Check the actual glyphs against visible bounds, including every date digit.
            assertFalse("$tag must not ellipsize", layout.isLineEllipsized(0))
            assertTrue("$tag clips horizontally at $width dp / $fontScale / week $week: " +
                "glyphs=$glyphs, bounds=$bounds", glyphs.minOf { it.left } >= -1f && glyphs.maxOf { it.right } <= bounds.width + 1f)
            assertTrue("$tag clips vertically at $width dp / $fontScale / week $week: " +
                "glyphs=$glyphs, bounds=$bounds", glyphs.minOf { it.top } >= -1f && glyphs.maxOf { it.bottom } <= bounds.height + 1f)
            val center = bounds.left + (glyphs.first().left + glyphs.last().right) / 2
            return center to bounds.top + layout.firstBaseline
        }
        for (scale in listOf(1f, 1.3f, 1.8f)) for (w in listOf(340, 390)) for (selectedWeek in listOf(4, 5)) {
            compose.runOnIdle { fontScale = scale; width = w; week = selectedWeek }
            compose.waitForIdle()
            val preview = compose.onNodeWithTag("schedule-header").captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "schedule-date-last-case.png").outputStream().use {
                preview.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val baselines = mutableListOf<Float>()
            for (day in 1..7) {
                val label = geometry("schedule-weekday-label-$day")
                val number = geometry("schedule-day-number-$day")
                assertTrue("Weekday/date centers differ for day $day at $w dp / $scale", abs(label.first - number.first) <= 1f)
                baselines += number.second
            }
            assertTrue("Dates must share a baseline", baselines.max() - baselines.min() <= 1f)
            geometry("schedule-date-title")
            if (scale == 1f && w == 390 && selectedWeek == 4) {
                val bitmap = compose.onNodeWithTag("schedule-header").captureToImage().asAndroidBitmap()
                File(compose.activity.cacheDir, "schedule-date-alignment.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
