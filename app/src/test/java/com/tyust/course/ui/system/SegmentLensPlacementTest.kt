package com.tyust.course.ui.system

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.*
import com.tyust.course.ui.system.glass.GlassLensCaptureObserver
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the real glass branch; plain foreground tests never mount either lens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentLensPlacementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun partialDragDoesNotChangeGlyphsAndReleaseStaysIdle() {
        var selected by mutableIntStateOf(0)
        var changes = 0
        GlassLensCaptureObserver.onGlyphRevision = { changes++ }
        try {
            compose.setContent { MaterialTheme {
                val background = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().layerBackdrop(background)) { drawRect(Color(0xFFDDD5EE)) }
                    LiquidSegmentedControl(listOf("发现", "已安装"), selected, { selected = it },
                        Modifier.width(360.dp).testTag("segments"), backdrop = background,
                        refractLabels = false, stableLabelRefraction = true, showTrack = false)
                }
            } }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            fun frames(n: Int) = repeat(n) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val bounds = compose.onNodeWithTag("segments").fetchSemanticsNode().boundsInRoot
            val start = androidx.compose.ui.geometry.Offset(bounds.width / 4f, bounds.height / 2f)
            val before = changes
            compose.onNodeWithTag("segments").performTouchInput { down(start) }
            frames(6)
            // Cross the former 0.55 font-weight threshold, but remain on the same tab.
            compose.onNodeWithTag("segments").performTouchInput {
                moveTo(start + androidx.compose.ui.geometry.Offset((bounds.width - 10f) / 2f * .47f, 0f))
            }
            frames(20)
            compose.runOnIdle { assertEquals("A held partial drag kept invalidating the text snapshot", before, changes) }
            compose.onNodeWithTag("segments").performTouchInput { up() }
            frames(100)
            compose.runOnIdle { assertEquals(0, selected); assertEquals(before, changes) }
            compose.onNodeWithText("已安装").performTouchInput { click() }
            frames(100)
            val afterSelection = changes
            compose.runOnIdle { assertEquals(1, selected); assertTrue(afterSelection > before) }
            frames(100)
            compose.runOnIdle { assertEquals("Completed selection still invalidates glyphs", afterSelection, changes) }
            compose.onAllNodesWithText("发现").assertCountEquals(1)
            compose.onAllNodesWithText("已安装").assertCountEquals(1)
        } finally { GlassLensCaptureObserver.onGlyphRevision = null }
    }

    @Test fun lensRemainsCenteredOnSelectedLabelAfterTapsAndResize() {
        var selected by mutableIntStateOf(0)
        var width by mutableStateOf(360.dp)
        var y by mutableStateOf(90.dp)
        val labels = listOf("发现", "已安装")
        compose.setContent { MaterialTheme {
            val background = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(background)) { drawRect(Color(0xFFDDD5EE)) }
                LiquidSegmentedControl(labels, selected, { selected = it },
                    Modifier.offset(y = y).width(width).testTag("segments"), backdrop = background,
                    refractLabels = false, stableLabelRefraction = true, showTrack = false)
            }
        } }
        compose.waitForIdle()
        fun verify() {
            val label = compose.onNodeWithText(labels[selected]).fetchSemanticsNode().boundsInRoot
            val background = compose.onNodeWithTag("segment-background-lens").fetchSemanticsNode().boundsInRoot
            assertEquals("Lens centered between tabs instead of on the selected label", label.center.x, background.center.x, 1f)
            assertEquals(label.center.y, background.center.y, 1f)
            labels.forEach { compose.onAllNodesWithText(it).assertCountEquals(1) }
        }
        verify()
        for (w in listOf(220.dp, 360.dp)) {
            compose.runOnIdle { width = w; y += 25.dp }
            compose.waitForIdle()
            labels.forEach { label ->
                compose.onNodeWithText(label).performTouchInput { click() }
                compose.waitForIdle()
                verify()
            }
        }
    }
}
