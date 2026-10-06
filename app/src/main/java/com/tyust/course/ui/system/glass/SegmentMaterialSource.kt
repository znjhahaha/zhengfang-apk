package com.tyust.course.ui.system.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Local material ONLY. No glyphs or controls enter this sibling capture layer.
 * LayerBackdrop records onDraw separately from the (empty) visible Box content.
 * Both GLES readback and runtime lenses consume this exact processed layer.
 */
@Composable
internal fun SegmentMaterialSource(source: Backdrop, surface: Color): Backdrop {
    val density = LocalDensity.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var geometry by remember { mutableStateOf<GlassLensCaptureGeometry?>(null) }
    val draw by rememberUpdatedState<ContentDrawScope.() -> Unit>({
        // Coordinates objects are mutable; observe their value snapshot too so a
        // moved header rerecords the material even when object identity is unchanged.
        val placed = geometry
        coordinates?.takeIf { placed != null && it.isAttached && it.size.width > 0 && it.size.height > 0 }?.let { coords ->
            drawBlurred(8.dp.toPx()) { drawBackdropSource(source, density, coords) }
        }
        drawRect(surface)
    })
    val processed = rememberLayerBackdrop(onDraw = { draw() })
    Box(Modifier.fillMaxSize().clearAndSetSemantics {}
        .onGloballyPositioned { coordinates = it; geometry = GlassLensCaptureGeometry.from(it) }.layerBackdrop(processed))
    return processed
}
