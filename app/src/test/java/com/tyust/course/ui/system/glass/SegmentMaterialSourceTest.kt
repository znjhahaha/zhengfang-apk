package com.tyust.course.ui.system.glass

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual local LayerBackdrop replay on a software canvas. No GPU blur/refraction claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentMaterialSourceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun localSourceReplaysItsRegionRatherThanBottomOfWindow() {
        lateinit var view: View
        var y by mutableStateOf(60.dp)
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            val source = rememberLayerBackdrop()
            val density = LocalDensity.current
            var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(source)) {
                    drawRect(Color.Green)
                    drawRect(Color.Red, Offset(0f, 400.dp.toPx()), Size(size.width, size.height - 400.dp.toPx()))
                }
                Box(Modifier.offset(y = y).size(200.dp, 48.dp).onGloballyPositioned { coords = it }.testTag("material-region")) {
                    val material = SegmentMaterialSource(source, Color.White.copy(alpha = .2f))
                    Box(Modifier.fillMaxSize().drawBehind {
                        coords?.let { drawBackdropSource(material, density, it) }
                    })
                }
            }
        }
        for (position in listOf(60.dp, 460.dp, 180.dp, 60.dp)) {
            compose.runOnIdle { y = position }
            val bounds = compose.onNodeWithTag("material-region").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    repeat(2) { view.invalidate(); view.draw(Canvas(bitmap)) }
                    val pixel = bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt())
                    val red = android.graphics.Color.red(pixel); val green = android.graphics.Color.green(pixel)
                    if (position < 400.dp) assertTrue("Sampled the bottom red marker", green > red)
                    else assertTrue("Retained the old position's green pixels", red > green)
                    assertTrue("Local neutral material was not applied", minOf(red, green) > 0)
                } finally { bitmap.recycle() }
            }
        }
    }
}
