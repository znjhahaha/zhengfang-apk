package com.tyust.course.ui.system.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

/** Frame handoff without EGL: checks background refresh, invalidation and settled queues. */
class GlassLensFrameIdentityTest {
    private val original = GlassLensFrameIdentity(GlassLensCaptureGeometry(IntSize(360, 52),
        Offset(12f, 80f), Offset(1f, 0f), Offset(0f, 1f)), 7)

    @Test fun backgroundUploadsDoNotRemoveLastCompletedOpticalFrame() {
        var rendered = original
        val queue = GlassFrameQueue<Int>()
        repeat(20) { upload ->
            // A newer background is uploaded before the async optical result exists.
            val uploaded = original.copy()
            assertTrue(canRetainLensFrame(rendered, uploaded, true))
            queue.request(upload)
            val request = requireNotNull(queue.next())
            repeat(4) { assertTrue(canRetainLensFrame(rendered, uploaded, true)) }
            rendered = uploaded
            assertFalse(queue.finish(request, true))
            repeat(5) { assertFalse("Frame completion cannot become a perpetual render clock", queue.request(upload)) }
        }
    }

    @Test fun changedGlyphsNeverReuseOldOpticalText() {
        assertFalse(canRetainLensFrame(original, original.copy(overlayRevision = 8), true))
    }

    @Test fun moveResizeAndAncestorTransformInvalidatePreviousSamplingGeometry() {
        for (geometry in listOf(original.geometry.copy(origin = Offset(12f, 70f)),
            original.geometry.copy(size = IntSize(320, 52)),
            original.geometry.copy(xAxis = Offset(.8f, 0f)))) {
            assertFalse(canRetainLensFrame(original, original.copy(geometry = geometry), true))
        }
    }

    @Test fun missingFramesAndLegacyStrictPolicyKeepFallbackProtection() {
        assertFalse(canRetainLensFrame(null, original, true))
        assertFalse(canRetainLensFrame(original, null, true))
        assertFalse(canRetainLensFrame(original, original, false))
    }
}
