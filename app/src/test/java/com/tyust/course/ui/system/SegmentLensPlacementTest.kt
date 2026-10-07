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
