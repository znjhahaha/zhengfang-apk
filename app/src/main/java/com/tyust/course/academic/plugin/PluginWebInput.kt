package com.tyust.course.academic.plugin

import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier

/** Scaffold.safeDrawing already includes IME: apply and consume its inset once. */
internal fun Modifier.pluginWebViewport(padding: PaddingValues): Modifier =
    fillMaxSize().padding(padding).consumeWindowInsets(padding)

internal fun View.updatePluginWebInput(interactive: Boolean) {
    isEnabled = interactive
    // setFocusable(false) also clears FOCUSABLE_IN_TOUCH_MODE. Restore both after
    // the first document becomes visible, without requesting focus on recomposition.
    isFocusable = interactive
    isFocusableInTouchMode = interactive
    importantForAccessibility = if (interactive) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
}
