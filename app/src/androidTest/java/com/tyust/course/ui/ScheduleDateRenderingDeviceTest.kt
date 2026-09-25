package com.tyust.course.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.ui.screen.WeekHeaderCompact
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.io.File
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect actual window pixels: semantics alone cannot catch an old glyph in a glass snapshot. */
@RunWith(AndroidJUnit4::class)
class ScheduleDateRenderingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedPreviewOnly() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun headingStaysLeftAlignedThroughSeptemberOctoberAndRapidReversal() {
        val day = mutableIntStateOf(3)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CourseSelectorTheme(darkTheme = false) {
                GlassWindowHost {
                    WeekHeaderCompact(4, {}, {}, firstWeekDate = "2026-09-07",
                        selectedDay = day.intValue, sampleBackdrop = LocalControlBackdrop.current)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        val dateNode = compose.onNodeWithTag("schedule-date-title", true)
        val width = dateNode.fetchSemanticsNode().boundsInRoot.width
        fun assertAligned(label: String, name: String) {
            dateNode.assertTextEquals(label)
            val weekNode = compose.onNodeWithTag("schedule-header-week", true)
            fun firstInk(bitmap: Bitmap): Int {
                for (x in 0 until bitmap.width) for (y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) < 110 && android.graphics.Color.green(pixel) < 130 &&
                        android.graphics.Color.blue(pixel) < 160) return x
                }
                error("No visible text in $name")
            }
            val dateLeft = dateNode.fetchSemanticsNode().boundsInRoot.left + firstInk(dateNode.captureToImage().asAndroidBitmap())
            val weekLeft = weekNode.fetchSemanticsNode().boundsInRoot.left + firstInk(weekNode.captureToImage().asAndroidBitmap())
            assertTrue("$label is indented by ${dateLeft - weekLeft}px", abs(dateLeft - weekLeft) <= density * 2 + 1)
            assertEquals("The chevron must not jump when digit counts change", width,
                dateNode.fetchSemanticsNode().boundsInRoot.width, 1f)
            save(name, compose.onNodeWithTag("schedule-header").captureToImage().asAndroidBitmap())
        }
        assertAligned("9月30日", "heading-september")
        compose.runOnIdle { day.intValue = 4 }
        compose.mainClock.advanceTimeBy(500)
        assertAligned("10月1日", "heading-october")
        compose.runOnIdle { day.intValue = 3 }
        compose.mainClock.advanceTimeBy(500)
        assertAligned("9月30日", "heading-return")
        compose.runOnIdle { day.intValue = 4 }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { day.intValue = 3 }
        compose.mainClock.advanceTimeBy(500)
        assertAligned("9月30日", "heading-reversed")
    }

    @Test fun selectedWeekdayPixelsMatchTheNewDateAfterPagingAndReversal() {
        val week = mutableIntStateOf(3)
        compose.setContent {
            CourseSelectorTheme(darkTheme = false) {
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color.Red)) {
                    GlassWindowHost {
                        Column {
                            Box(Modifier.testTag("live-header")) {
                                WeekHeaderCompact(week.intValue, {}, {}, firstWeekDate = "2026-09-07",
                                    selectedDay = 3, sampleBackdrop = LocalControlBackdrop.current)
                            }
                            // A freshly composed reference has no old animated digit to retain.
                            key(week.intValue) {
                                Box(Modifier.testTag("reference-header")) {
                                    WeekHeaderCompact(week.intValue, {}, {}, firstWeekDate = "2026-09-07",
                                        selectedDay = 3, sampleBackdrop = LocalControlBackdrop.current)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        fun assertCurrent(date: String, name: String) {
            compose.waitForIdle()
            fun pixels(parent: String): Bitmap {
                val node = compose.onNode(hasTestTag("schedule-day-number-3") and
                    hasAnyAncestor(hasTestTag(parent)), useUnmergedTree = true)
                node.assertTextEquals(date)
                return node.captureToImage().asAndroidBitmap()
            }
            val live = pixels("live-header")
            val reference = pixels("reference-header")
            save("$name-live", live)
            save("$name-reference", reference)
            assertEquals(reference.width, live.width)
            assertEquals(reference.height, live.height)
            fun ink(pixel: Int) = android.graphics.Color.red(pixel) > 180 &&
                android.graphics.Color.green(pixel) < 90 && android.graphics.Color.blue(pixel) < 90
            var glyphPixels = 0
            var mismatches = 0
            for (y in 0 until live.height) for (x in 0 until live.width) {
                val expected = ink(reference.getPixel(x, y))
                if (expected) glyphPixels++
                if (ink(live.getPixel(x, y)) != expected) mismatches++
            }
            assertTrue("Reference date is not visible", glyphPixels > 25)
            assertTrue("Selected date $date still paints an old glyph ($mismatches/$glyphPixels pixels)",
                mismatches <= glyphPixels * 0.08f + 3)
        }
        assertCurrent("23", "date-initial")
        compose.runOnIdle { week.intValue = 4 }
        compose.mainClock.advanceTimeBy(500)
        assertCurrent("30", "date-next-week")
        compose.runOnIdle { week.intValue = 5 }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { week.intValue = 4 }
        compose.mainClock.advanceTimeBy(500)
        assertCurrent("30", "date-reversed")
    }

    private fun save(name: String, bitmap: Bitmap) {
        val directory = File(compose.activity.getExternalFilesDir(null), "schedule-date-rendering").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
