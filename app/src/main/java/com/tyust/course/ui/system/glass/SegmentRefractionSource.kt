package com.tyust.course.ui.system.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** One optical input: local glass material with the actual, accent-tinted Text layouts.
 * Spectral sampling must see the material underneath each glyph's transparent pixels.
 * The empty sibling records glyphs only for sampling; it never paints a second label row.
 */
@Composable
internal fun SegmentRefractionSource(material: Backdrop, labels: SegmentLabelSource, color: Color): Backdrop {
    val glyphs = rememberLayerBackdrop(onDraw = { labels.draw(this, color) })
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}.layerBackdrop(glyphs))
    return rememberCombinedBackdrop(material, glyphs)
}
