package com.tyust.course.ui.system.glass

import android.app.Application
import android.opengl.EGL14
import android.opengl.EGLDisplay
import android.opengl.EGLContext
import android.opengl.EGLSurface
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the native modifier's early-return path; no EGL/readback is requested. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassLensFallbackTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nonNullUnpositionedAnchorCannotSuppressBackgroundIncludingAfterAReadyFrame() {
        // Robolectric has no EGL handles. Supply inert sentinel objects only; this
        // test never calls ensureReady, records a source or runs a GPU renderer.
        ReflectionHelpers.setStaticField(EGL14::class.java, "EGL_NO_DISPLAY",
            ReflectionHelpers.callConstructor(EGLDisplay::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L)))
        ReflectionHelpers.setStaticField(EGL14::class.java, "EGL_NO_CONTEXT",
            ReflectionHelpers.callConstructor(EGLContext::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L)))
        ReflectionHelpers.setStaticField(EGL14::class.java, "EGL_NO_SURFACE",
            ReflectionHelpers.callConstructor(EGLSurface::class.java, ClassParameter.from(Long::class.javaPrimitiveType, 0L)))
        val source = GlassLensSource()
        val anchor = GlassLensAnchor({}, Density(1f), source, "test-missing-layout")
        val state = GlassLensDrawState()
        lateinit var view: View
        compose.setContent {
            val current = LocalView.current
            SideEffect { view = current }
            Box(Modifier.size(100.dp).testTag("lens")
                .glassLens(anchor, optics = { _, _ -> GlassLensOptics(20f, 5f, 7f, 0f) }, drawState = state)
                .drawBehind { if (!state.hasBackground) drawRect(Color.Magenta) })
        }
        val bounds = compose.onNodeWithTag("lens").fetchSemanticsNode().boundsInRoot
        try {
            repeat(2) { index ->
                compose.runOnIdle { if (index == 1) state.hasBackground = true }
                compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.invalidate(); view.draw(Canvas(bitmap))
                        assertFalse(state.hasBackground)
                        assertEquals(Color.Magenta.toArgb(), bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
                    } finally { bitmap.recycle() }
                }
            }
        } finally { source.release(); anchor.clearFallback() }
    }
}
