package com.tyust.course.ui.system

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Software canvas/foreground checks, not refraction or device GPU evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentedForegroundTest {
    @get:Rule val compose = createComposeRule()
    @Test fun stationaryForegroundHasOneLabelPerSegmentAndVisibleSelectedCapsule() {
        var selected by mutableIntStateOf(0)
        var width by mutableStateOf(360.dp)
        lateinit var view: View
        val labels = listOf("本学期", "全部", "考试")
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color.White)) {
                LiquidSegmentedControl(labels, selected, { selected = it }, Modifier.width(width).testTag("segments"),
                    refractLabels = false, showTrack = false)
            } }
        }
        for (w in listOf(360.dp, 220.dp, 360.dp)) {
            compose.runOnIdle { width = w }
            for ((index, label) in labels.withIndex()) {
                compose.onNodeWithText(label).performClick().assertIsSelected()
                labels.forEach { compose.onAllNodesWithText(it).assertCountEquals(1) }
                val box = compose.onNodeWithTag("segments").fetchSemanticsNode().boundsInRoot
                val selectedBounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
                compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        val selectedPixel = bitmap.getPixel(selectedBounds.center.x.toInt(), (box.top + 9).toInt())
                        val otherBounds = (box.width - 10f) / 3
                        val otherX = box.left + 5f + otherBounds * (((index + 1) % 3) + .5f)
                        val otherPixel = bitmap.getPixel(otherX.toInt(), (box.top + 9).toInt())
                        assertNotEquals("Selected capsule disappeared at width $w", otherPixel, selectedPixel)
                        val segmentWidth = (box.width - 10f) / 3
                        assertEquals(box.left + 5f + segmentWidth * (index + .5f), selectedBounds.center.x, 2f)
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
