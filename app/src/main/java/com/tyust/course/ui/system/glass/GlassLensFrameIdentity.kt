package com.tyust.course.ui.system.glass

/** Background refreshes may reuse an optical frame; new glyphs/geometry may not. */
internal data class GlassLensFrameIdentity(
    val geometry: GlassLensCaptureGeometry,
    val overlayRevision: Int
)

internal fun canRetainLensFrame(
    rendered: GlassLensFrameIdentity?,
    current: GlassLensFrameIdentity?,
    retainCompatibleBackground: Boolean
): Boolean = retainCompatibleBackground && rendered != null && rendered == current
