package com.tyust.course.ui.system.glass

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises actual Compose Text layout and immutable local glyph rasterization, not device lens output. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SegmentLabelSourceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun tinyCoordinateNoiseIsIgnoredButRealMovementAndFontChangesInvalidate() {
        val source = SegmentLabelSource(1)
        lateinit var measurer: androidx.compose.ui.text.TextMeasurer
        compose.setContent { measurer = androidx.compose.ui.text.rememberTextMeasurer() }
        compose.runOnIdle {
            source.resize(IntSize(240, 52))
            val layout = measurer.measure("成绩", androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Normal))
            val position = androidx.compose.ui.geometry.Offset(20f, 10f)
            source.update(0, layout, position)
            val revision = source.revision
            repeat(100) { n ->
                val noise = if (n % 2 == 0) .0001f else -.0001f
                source.update(0, layout, position + androidx.compose.ui.geometry.Offset(noise, noise))
            }
            assertEquals(revision, source.revision)
            source.update(0, layout, position + androidx.compose.ui.geometry.Offset(2f, 0f))
            assertEquals(revision + 1, source.revision)
            val bold = measurer.measure("成绩", androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.ExtraBold))
            source.update(0, bold, position + androidx.compose.ui.geometry.Offset(2f, 0f))
            assertEquals("Real font changes cannot reuse stale glyphs", revision + 2, source.revision)
        }
    }
    @Test fun foregroundColourAnimationDoesNotInvalidateIdenticalTintedGlyphs() {
        val source = SegmentLabelSource(1)
        lateinit var measurer: androidx.compose.ui.text.TextMeasurer
        compose.setContent { measurer = androidx.compose.ui.text.rememberTextMeasurer() }
        compose.runOnIdle {
            source.resize(IntSize(240, 52))
            val original = measurer.measure("成绩", androidx.compose.ui.text.TextStyle(color = Color.Black))
            source.update(0, original, androidx.compose.ui.geometry.Offset(20f, 10f))
            val revision = source.revision
            repeat(30) { frame ->
                val tinted = measurer.measure("成绩", androidx.compose.ui.text.TextStyle(color = Color(frame / 30f, 0f, 1f)))
                assertSame("This is a paint change, not a new paragraph layout", original.multiParagraph, tinted.multiParagraph)
                source.update(0, tinted, androidx.compose.ui.geometry.Offset(20f, 10f))
                assertEquals("Foreground colour must not continuously invalidate the accent-tinted optical source", revision, source.revision)
            }
        }
    }

    @Test fun sharedTextLayoutProducesLocalPixelsAcrossResizeAndFontChanges() {
        val source = SegmentLabelSource(1)
        var width by mutableStateOf(240.dp)
        var text by mutableStateOf("成绩")
        var bold by mutableStateOf(false)
        var y by mutableStateOf(20.dp)
        var root: LayoutCoordinates? = null
        var child: LayoutCoordinates? = null
        var layout: TextLayoutResult? = null
        var density: Density = Density(1f)
        fun sync() { val r=root; val c=child; val l=layout
            if(r?.isAttached==true && c?.isAttached==true && l!=null) source.update(0,l,r.localPositionOf(c))
        }
        compose.setContent { MaterialTheme {
            density=LocalDensity.current
            Box(Modifier.offset(y=y).width(width).height(50.dp).onGloballyPositioned {root=it;source.resize(it.size);sync()}) {
                Text(text,color=Color.Red,fontWeight=if(bold) FontWeight.Bold else FontWeight.Normal,
                    modifier=Modifier.align(androidx.compose.ui.Alignment.Center).onGloballyPositioned {child=it;sync()},onTextLayout={layout=it;sync()})
            }
        } }
        fun pixels(): Int {
            assertTrue("Text source should contain current measured glyphs",source.ready)
            val bitmap=Bitmap.createBitmap(source.size.width,source.size.height,Bitmap.Config.ARGB_8888)
            try {
                CanvasDrawScope().draw(density,LayoutDirection.Ltr,Canvas(android.graphics.Canvas(bitmap)),androidx.compose.ui.geometry.Size(source.size.width.toFloat(),source.size.height.toFloat())) {source.draw(this,Color.Red)}
                val data=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(data,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                return data.count {android.graphics.Color.alpha(it)>0}
            }finally{bitmap.recycle()}
        }
        var before=0
        compose.runOnIdle {before=pixels();assertTrue(before>10)}
        compose.runOnIdle {y=300.dp};compose.waitForIdle()
        compose.runOnIdle {assertEquals("Window movement must not move local glyph pixels",before,pixels())}
        compose.runOnIdle {width=180.dp;text="历史成绩";bold=true};compose.waitForIdle()
        compose.runOnIdle {assertTrue(pixels()>before);source.resize(IntSize(20,20));assertFalse("Old glyphs cannot survive a geometry reset",source.ready)}
    }
}
