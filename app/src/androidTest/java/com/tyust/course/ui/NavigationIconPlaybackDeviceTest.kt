package com.tyust.course.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import com.tyust.course.ui.system.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationIconPlaybackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun isolatedVariant() = assumeTrue(BuildConfig.UI_PREVIEW)

    @Test fun interruptionKeepsVelocityClicksCoalesceAndIdleStopsScheduling() {
        lateinit var playback: NavigationIconPlayback
        compose.setContent { playback = rememberNavigationIconPlayback(5, 0, false) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { playback.replay(0) }
        compose.mainClock.advanceTimeBy(96)
        var previous = 0f
        compose.runOnIdle {
            previous = playback.phase(0)
            repeat(50) { playback.replay(0) }
            assertEquals(previous, playback.phase(0), 0f)
            playback.select(1, false)
            assertEquals(previous, playback.phase(0), 0f)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { assertEquals(48f / 480f, playback.phase(0) - previous, 0.01f) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertFalse(playback.isRunning(0)); assertFalse(playback.isRunning(1)) }
        compose.runOnIdle { playback.replay(1) }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle { repeat(50) { playback.replay(1) } }
        compose.mainClock.advanceTimeBy(1100)
        compose.runOnIdle { assertFalse("clicks must queue only one feedback", playback.isRunning(1)) }
        compose.runOnIdle {
            playback.replay(2)
            playback.select(2, true)
            for (index in 0..4) { assertEquals(1f, playback.phase(index), 0f); assertFalse(playback.isRunning(index)) }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { for (index in 0..4) assertFalse(playback.isRunning(index)) }
    }

    @Test fun finalFramesAndStaticCopiesAgreeInBothThemes() {
        val phase = mutableFloatStateOf(0f)
        val dark = mutableStateOf(false)
        val symbols = listOf(AppSymbolSpec.Courses, AppSymbolSpec.Schedule, AppSymbolSpec.Grab, AppSymbolSpec.Grades, AppSymbolSpec.Settings)
        compose.setContent {
            Row(Modifier.background(if (dark.value) Color(0xFF18191C) else Color.White).testTag("glyphs")) {
                symbols.forEach { symbol ->
                    PhosphorNavigationIcon(symbol, 1f, { phase.floatValue }, if (dark.value) Color(0xFFB2D4FF) else Color(0xFF185CAC), Modifier.size(48.dp))
                }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark.value = night; phase.floatValue = 0f }
            val start = capture()
            for (sample in listOf(0.90f, 0.94f, 0.96f, 0.98f, 0.99f, 1f)) {
                compose.runOnIdle { phase.floatValue = sample }
                val frame = capture()
                val file = File(compose.activity.getExternalFilesDir(null), "navigation-frames/${if (night) "dark" else "light"}-$sample.png")
                file.parentFile!!.mkdirs()
                file.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (sample == 1f) assertTrue("resting frame changed", start.sameAs(frame))
            }
        }
    }

    private fun capture() = compose.onNodeWithTag("glyphs").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
}
