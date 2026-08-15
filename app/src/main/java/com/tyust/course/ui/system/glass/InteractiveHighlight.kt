package com.tyust.course.ui.system.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope

/**
 * 玻璃控件的按压光学状态源。
 *
 * 这里只提供【物理量】：按压进度、指针位置、相对按压起点的位移，
 * 供 drawBackdrop 的 layerBlock 做真实的形变与折射缩放。
 *
 * 不再向表面叠加任何人造光斑：贴上去的白色 Plus 光会随手指游走，
 * 在实色按钮上呈现为塑料反光，观感是假的。亮度变化统一由
 * [com.tyust.course.ui.system.GlassPressIndication] 承担。
 */
class InteractiveHighlight(
    animationScope: CoroutineScope,
    @Suppress("unused") val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val optics = InteractiveOptics(animationScope)

    val pressProgress: Float
        get() = optics.pressProgress

    val velocityX: Float
        get() = optics.velocityX

    val pointerPosition: Offset
        get() = optics.pointerPosition

    /** 指针相对按压起点的位移，供 layerBlock 做官方拖拽形变。 */
    val offset: Offset
        get() = optics.offset

    val gestureModifier: Modifier = optics.gestureModifier
}