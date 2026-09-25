package com.tyust.course.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.theme.MotionProfile

private data class ScheduleDateLabel(val timestamp: Long, val text: String)

/** Date cells keep their column width; the title changes width with the same motion. */
@Composable
internal fun ScheduleDateText(
    text: String,
    timestamp: Long,
    tag: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fillColumn: Boolean = false
) {
    val reducedMotion = rememberGlassAccessibilityMode().reduceMotion
    val textModifier = if (fillColumn) Modifier.fillMaxWidth() else Modifier
    val alignment = if (fillColumn) TextAlign.Center else TextAlign.Start
    if (reducedMotion) {
        Text(text, modifier.then(textModifier).testTag(tag), color = color, style = style,
            textAlign = alignment, maxLines = 1, softWrap = false)
        return
    }
    AnimatedContent(
        targetState = ScheduleDateLabel(timestamp, text),
        modifier = modifier.testTag("$tag-slot").then(if (fillColumn) Modifier.clipToBounds() else
            Modifier.drawWithContent {
                // Clip only the rolling line vertically while the title grows a digit.
                clipRect(left = -size.width, top = 0f, right = size.width * 2, bottom = size.height) { this@drawWithContent.drawContent() }
            }),
        contentAlignment = Alignment.Center,
        contentKey = { it.text },
        transitionSpec = {
            // Compare complete dates, so Sep 30 -> Oct 1 still moves forward.
            val direction = if (targetState.timestamp >= initialState.timestamp) 1 else -1
            ((slideInVertically(tween(MotionProfile.IconMillis)) { direction * it } +
                fadeIn(tween(MotionProfile.IconMillis - 80, delayMillis = 80))) togetherWith
                (slideOutVertically(tween(MotionProfile.IconMillis)) { -direction * it } +
                    fadeOut(tween(MotionProfile.PressMillis))))
                .using(SizeTransform(clip = false) { _, _ -> tween(MotionProfile.IconMillis) })
        },
        label = "schedule-date"
    ) { rendered ->
        Text(rendered.text, textModifier.testTag(tag).semantics {
            if (rendered.text != text) hideFromAccessibility()
        }, color = color, style = style, textAlign = alignment, maxLines = 1, softWrap = false)
    }
}
