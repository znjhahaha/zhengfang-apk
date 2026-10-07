package com.tyust.course.academic.plugin

import android.app.Application
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginWebInputTest {
    @Test fun firstContentRestoresTouchModeFocusAfterTheLoadingGate() {
        val view = View(RuntimeEnvironment.getApplication())
        view.isFocusableInTouchMode = true
        view.updatePluginWebInput(false)
        assertFalse(view.isFocusableInTouchMode)
        view.updatePluginWebInput(true)
        assertTrue(view.isEnabled)
        assertTrue(view.isFocusable)
        assertTrue(view.isFocusableInTouchMode)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, view.importantForAccessibility)
    }

    @Test fun repeatedUpdatesDoNotRequestFocusAndRetryRestoresInteractivity() {
        val view = object : View(RuntimeEnvironment.getApplication()) {
            var focusRequests = 0
            override fun requestFocus(direction: Int, rect: android.graphics.Rect?): Boolean {
                focusRequests++
                return super.requestFocus(direction, rect)
            }
        }
        repeat(20) { view.updatePluginWebInput(true) }
        assertEquals(0, view.focusRequests)
        view.updatePluginWebInput(false)
        assertFalse(view.isEnabled)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, view.importantForAccessibility)
        view.updatePluginWebInput(true)
        assertTrue(view.isFocusableInTouchMode)
        assertEquals(0, view.focusRequests)
    }
}
