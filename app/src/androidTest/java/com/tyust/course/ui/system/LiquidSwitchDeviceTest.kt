package com.tyust.course.ui.system

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.ui.theme.CourseSelectorTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidSwitchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun trackTapsAnimateTheThumbAndRapidReversalKeepsItsCurrentPosition() {
        val checked = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
                    Row(Modifier.width(280.dp).height(100.dp).testTag("switch-preview"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("课程提醒", style = MaterialTheme.typography.titleMedium)
                        LiquidSwitch(checked.value, { checked.value = it; calls++ }, Modifier.testTag("switch"), backdrop = null)
                    }
                }
            }
        }
        fun center() = compose.onNodeWithTag("liquid-switch-thumb", true).getUnclippedBoundsInRoot().let { (it.left.value + it.right.value) / 2 }
        val start = center()
        compose.mainClock.autoAdvance = false
        capture("off")
        // The whole 64 x 48 dp control responds, including track outside the thumb.
        compose.onNodeWithTag("switch").performTouchInput { click(Offset(width - 1f, centerY)) }
        compose.runOnIdle { assertTrue(checked.value); assertEquals(1, calls) }
        assertEquals("Releasing a tap must not teleport the thumb", start, center(), 0.5f)
        compose.mainClock.advanceTimeBy(64)
        val middle = center()
        assertTrue("Tap needs an intermediate thumb position: $start -> $middle", middle > start + 0.25f && middle < start + 19.75f)
        capture("tap-middle")
        compose.mainClock.advanceTimeBy(1200)
        val end = center()
        assertEquals(20f, end - start, 0.5f)
        capture("on")
        compose.onNodeWithTag("switch").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(64)
        val returning = center()
        assertTrue(returning > start && returning < end)
        compose.onNodeWithTag("switch").performTouchInput { click() }
        assertEquals("Reversal must continue from the displayed position", returning, center(), 0.5f)
        compose.mainClock.advanceTimeBy(1200)
        assertEquals(end, center(), 0.5f)
        compose.runOnIdle { assertTrue(checked.value); assertEquals(3, calls) }
    }

    @Test fun accessibleChangesAnimateAndDisablingTheControlPreventsTouchChanges() {
        val checked = mutableStateOf(false)
        val enabled = mutableStateOf(true)
        var calls = 0
        compose.setContent {
            CourseSelectorTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LiquidSwitch(checked.value, { checked.value = it; calls++ }, Modifier.testTag("switch"),
                        enabled = enabled.value, backdrop = null)
                }
            }
        }
        fun center() = compose.onNodeWithTag("liquid-switch-thumb", true).getUnclippedBoundsInRoot().let { (it.left.value + it.right.value) / 2 }
        val start = center()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("switch").performClick()
        compose.mainClock.advanceTimeBy(64)
        assertTrue(center() > start && center() < start + 20f)
        compose.runOnIdle { checked.value = false; enabled.value = false }
        compose.mainClock.advanceTimeBy(1200)
        assertEquals(start, center(), 0.5f)
        compose.onNodeWithTag("switch").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertFalse(checked.value); assertEquals(1, calls) }
    }

    private fun capture(name: String) {
        val folder = File(compose.activity.getExternalFilesDir(null), "switch-validation").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            compose.onNodeWithTag("switch-preview").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
