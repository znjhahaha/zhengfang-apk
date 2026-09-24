package com.tyust.course.ui.system

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.theme.MotionProfile
import com.tyust.course.ui.theme.MotionDuration
import com.tyust.course.ui.theme.MotionEasing

private data class NumberGlyph(val character: Char, val order: Double)
private val NumberParts = Regex("[0-9]+(?:\\.[0-9]+)?|[^0-9]+")

/**
 * Fixed-width digit slots. Dates pass their civil timestamp so 31 -> 1 rolls forward.
 * A left-aligned title can move the leading number's unused slots after the whole label.
 */
@Composable
fun AnimatedNumberText(value: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface, style: TextStyle = MaterialTheme.typography.bodyMedium,
    minDigits: Int = 2, directionKey: Long? = null, padLeadingNumber: Boolean = true) {
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val effectiveStyle = androidx.compose.material3.LocalTextStyle.current.merge(style).copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digitWidth = remember(effectiveStyle, density) {
        with(density) { ('0'..'9').maxOf { measurer.measure(it.toString(), effectiveStyle).size.width }.toDp() }
    }
    Row(modifier.clearAndSetSemantics { text = AnnotatedString(value) }, verticalAlignment = Alignment.CenterVertically) {
        var trailingSlots = 0
        NumberParts.findAll(value).forEachIndexed { index, match -> key(index) {
            val part = match.value
            if (part.first().isDigit()) {
                val integerLength = part.substringBefore('.').length
                var capacity by remember { mutableIntStateOf(maxOf(minDigits, integerLength)) }
                val slots = maxOf(capacity, minDigits, integerLength)
                SideEffect { capacity = slots }
                val order = directionKey?.toDouble() ?: part.toDoubleOrNull() ?: 0.0
                val paddedPart = if (index == 0 && !padLeadingNumber) {
                    trailingSlots = slots - integerLength
                    part
                } else part.padStart(part.length + slots - integerLength)
                paddedPart.forEachIndexed { slot, char -> key(slot) {
                    if (char == '.') Text(".", color = color, style = effectiveStyle, maxLines = 1)
                    else Box(Modifier.width(digitWidth).clipToBounds(), contentAlignment = Alignment.Center) {
                        if (reduced) Text(char.toString(), color = color, style = effectiveStyle, maxLines = 1)
                        else AnimatedContent(NumberGlyph(char, order), contentKey = { it.character },
                            transitionSpec = {
                                val direction = if (targetState.order >= initialState.order) 1 else -1
                                ((slideInVertically(tween(MotionDuration.Number, easing = MotionEasing.Standard)) { direction * it } +
                                    fadeIn(tween(MotionDuration.Number))) togetherWith
                                    (slideOutVertically(tween(MotionDuration.Number, easing = MotionEasing.Standard)) { -direction * it } +
                                        fadeOut(tween(MotionDuration.Fast)))).using(null)
                            }, label = "number-digit") { glyph ->
                            Text(glyph.character.toString(), color = color, style = effectiveStyle, maxLines = 1)
                        }
                    }
                } }
            } else Text(part, color = color, style = effectiveStyle, maxLines = 1)
        } }
        if (trailingSlots > 0) Spacer(Modifier.width(digitWidth * trailingSlots))
    }
}

@Composable
fun AnimatedValueText(value: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    if (value.matches(Regex("[0-9]+(?:\\.[0-9]+)?[+%]?"))) {
        AnimatedNumberText(value, modifier, color, style)
        return
    }
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    AnimatedContent(value, modifier, transitionSpec = {
        if (reduced) EnterTransition.None togetherWith ExitTransition.None
        else ((fadeIn(tween(MotionDuration.Medium)) + slideInVertically(tween(MotionDuration.Medium)) { it / 4 }) togetherWith
            (fadeOut(tween(MotionProfile.PressMillis)) + slideOutVertically(tween(MotionDuration.Medium)) { -it / 4 }))
            .using(SizeTransform(clip = false))
    }, label = "value-change") { Text(it, color = color, style = style, maxLines = 1) }
}

@Composable
fun FilterCountBadge(count: Int, modifier: Modifier = Modifier) {
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    AnimatedVisibility(count > 0, modifier, enter = if (reduced) EnterTransition.None else
        fadeIn(tween(MotionProfile.PressMillis)) + scaleIn(MotionProfile.iconSpring(), initialScale = 0.65f),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(MotionProfile.PressMillis)) + scaleOut(targetScale = 0.65f)) {
        Box(Modifier.testTag("filter-count-badge")
            .semantics { stateDescription = count.toString() + " 个筛选条件已生效" }
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 4.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
            AnimatedValueText(if (count > 9) "9+" else count.toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold))
        }
    }
}
