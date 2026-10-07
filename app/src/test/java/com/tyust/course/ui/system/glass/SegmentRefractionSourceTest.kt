package com.tyust.course.ui.system.glass

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rasterizes the production optical INPUT; does not claim Android GPU output coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentRefractionSourceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dispersionInputContainsMaterialBehindEveryGlyphAndNeverOriginalTextColour() {
        val labels = SegmentLabelSource(1)
        val accent = Color(0xFF1876C0)
        var dark by mutableStateOf(false)
        var width by mutableStateOf(220.dp)
        var y by mutableStateOf(80.dp)
        var text by mutableStateOf("发现")
        var root: LayoutCoordinates? = null
        var child: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
        lateinit var view: View
        var input: Bitmap? = null
        fun sync() {
            val r = root; val c = child; val l = layout
            if (r?.isAttached == true && c?.isAttached == true && l != null) labels.update(0, l, r.localPositionOf(c))
        }
        compose.setContent { MaterialTheme {
            val current = LocalView.current
            SideEffect { view = current }
            val density = LocalDensity.current
            val material = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize().layerBackdrop(material)) {
                    drawRect(if (dark) Color(0xFF282832) else Color(0xFFF0F0F8))
                }
                Box(Modifier.offset(y = y).width(width).height(52.dp).testTag("optical-input")
                    .onGloballyPositioned { root = it; labels.resize(it.size); sync() }) {
                    val composed = SegmentRefractionSource(material, labels, accent)
                    // Original text is deliberately black; only its measured layout
                    // may enter the lens, recoloured to the same accent as course tabs.
                    Text(text, color = Color.Black, modifier = Modifier.align(Alignment.Center)
                        .graphicsLayer { alpha = 0f }.onGloballyPositioned { child = it; sync() },
                        onTextLayout = { layout = it; sync() })
                    Box(Modifier.fillMaxSize().drawBehind {
                        root?.let { coords ->
                            input?.recycle()
                            // Capture the optical input on TRANSPARENT pixels, not
                            // the view's already-painted opaque wallpaper. Otherwise
                            // a glyph-only source would falsely pass this regression.
                            val bitmap = Bitmap.createBitmap(size.width.toInt(), size.height.toInt(), Bitmap.Config.ARGB_8888)
                            input = bitmap
                            CanvasDrawScope().draw(density, layoutDirection,
                                androidx.compose.ui.graphics.Canvas(Canvas(bitmap)), size) {
                                drawBackdropSource(composed, density, coords)
                            }
                        }
                    })
                }
            }
        } }
        for (night in listOf(false, true)) for (w in listOf(220.dp, 160.dp)) {
            compose.runOnIdle { dark = night; width = w; y += 40.dp; text = if (night) "已安装" else "发现" }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(labels.ready)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    repeat(2) { view.invalidate(); view.draw(Canvas(bitmap)) }
                    val opticalInput = requireNotNull(input)
                    val bg = if (night) Color(0xFF282832).toArgb() else Color(0xFFF0F0F8).toArgb()
                    var tinted = 0
                    var materialPixels = 0
                    for (py in 1 until opticalInput.height-1)
                        for (px in 1 until opticalInput.width-1) {
                            val p = opticalInput.getPixel(px, py)
                            assertEquals("Transparent holes would feed zero RGB into dispersion", 255, android.graphics.Color.alpha(p))
                            assertNotEquals("Original black glyph leaked into refraction source", Color.Black.toArgb(), p)
                            if (p == bg) materialPixels++ else {
                                assertTrue("Expected accent-blue glyphs", android.graphics.Color.blue(p) > android.graphics.Color.red(p))
                                tinted++
                            }
                        }
                    assertTrue("No measured glyphs were composited", tinted > 10)
                    assertTrue("Material disappeared behind transparent glyph pixels", materialPixels > tinted)
                } finally { bitmap.recycle() }
            }
        }
        compose.runOnIdle { input?.recycle(); input = null }
    }
}
