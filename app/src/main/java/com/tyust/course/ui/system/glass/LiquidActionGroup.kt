package com.tyust.course.ui.system.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import kotlin.math.abs

/**
 * 按压时向邻居融合的液体按钮组。
 *
 * 职责切分（这是这个组件存在的唯一理由）：
 *
 *   父级 LiquidActionGroup  ->  知道所有按钮的几何位置、知道谁正被按下，
 *                               因此【桥由父级画】。子级画不出去：它的绘制
 *                               会被自身边界裁掉，两个圆之间那段空隙不属于
 *                               任何子级。
 *   子级 LiquidActionItem   ->  只声明自己是什么、被点了做什么，不知道邻居。
 *
 * 为什么不是"整组变一颗胶囊"：那是把四个独立操作在视觉上合并成一个，
 * 用户会失去"这是四个可点区域"的认知。液滴融合只发生在【被按的那一枚】
 * 和【它最近的邻居】之间，是局部的表面张力效应，不是整组形变。
 *
 * 为什么桥不参与 backdrop 折射：Backdrop 的 lens() 依赖解析式 SDF，
 * canUseLiquidLens 只放行 CornerBasedShape 与库的 RoundedRectangularShape。
 * 把"两圆并集 + 桥"喂成 GenericShape 会静默退化为 useLens = false，
 * 整组连现有折射一起丢掉。所以桥是绘制态的，取芯片静止表面的等效色。
 */

/**
 * 被按下的那一枚。桥需要读它的 opticalProgress，
 * 所以索引和 optics 必须一起上报——父级不再维护 optics 池。
 */
private class PressedAction(
    val index: Int,
    val optics: InteractiveOptics
)

/**
 * @param spacing 按钮间距。静止布局完全由它决定，融合不改变布局，
 *                只在绘制层补一段桥，所以按钮不会因为按压而移位。
 */
@Composable
fun LiquidActionGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 4.dp,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    content: @Composable LiquidActionGroupScope.() -> Unit
) {
    val isLight = !isSystemInDarkTheme()
    val accessibility = rememberGlassAccessibilityMode()
    // 几何与可用性分开存：bounds 由 onPlaced 上报，而 enabled 变化时
    // 几何往往没变、onPlaced 不会重跑。塞在同一个结构里会让 enabled 过期，
    // 表现为桥连到一枚刚刚被禁用的邻居。
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val enabledFlags = remember { mutableStateMapOf<Int, Boolean>() }
    val animationScope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf<PressedAction?>(null) }

    // 桥的颜色必须与芯片表面同源，否则就是"两个白圆中间贴一块灰矩形"。
    val bridgeBase = if (isLight) {
        GlassRecipe.ChipSurfaceAlphaLight
    } else {
        GlassRecipe.ChipSurfaceAlphaDark
    }
    val rimAlpha = if (isLight) {
        GlassRecipe.ChipRimAlphaLight
    } else {
        GlassRecipe.ChipRimAlphaDark
    }
    val allowMerge = !accessibility.reduceMotion

    val scope = remember(bounds, enabledFlags, animationScope, backdrop) {
        object : LiquidActionGroupScope {
            @Composable
            override fun action(
                index: Int,
                icon: ImageVector,
                contentDescription: String?,
                onClick: () -> Unit,
                enabled: Boolean,
                buttonSize: Dp,
                iconSize: Dp
            ) {
                action(
                    index = index,
                    contentDescription = contentDescription,
                    onClick = onClick,
                    enabled = enabled,
                    buttonSize = buttonSize
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = if (enabled) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current.copy(alpha = 0.38f)
                        }
                    )
                }
            }

            @Composable
            override fun action(
                index: Int,
                contentDescription: String?,
                onClick: () -> Unit,
                enabled: Boolean,
                buttonSize: Dp,
                content: @Composable () -> Unit
            ) {
                // 每枚按钮一套 optics：共用一套会让按下 A 时 B/C/D 一起形变，
                // 因为它们读到的是同一个 pressProgress。
                val itemOptics = remember(index) { InteractiveOptics(animationScope) }

                // 只在真的变了才写：enabledFlags 被 drawBehind 读取，
                // 每次重组都无条件写会让桥所在的绘制层白白失效一次。
                SideEffect {
                    if (enabledFlags[index] != enabled) {
                        enabledFlags[index] = enabled
                    }
                }
                DisposableEffect(index) {
                    onDispose {
                        bounds.remove(index)
                        enabledFlags.remove(index)
                    }
                }

                LiquidActionItem(
                    contentDescription = contentDescription,
                    onClick = onClick,
                    enabled = enabled,
                    buttonSize = buttonSize,
                    backdrop = backdrop,
                    optics = itemOptics,
                    onPressChange = { isDown ->
                        // 松手时只有"当前记录的就是自己"才清零，
                        // 否则快速在两枚之间滑动会出现后按下的先被清掉。
                        if (isDown) {
                            pressed = PressedAction(index, itemOptics)
                        } else if (pressed?.index == index) {
                            pressed = null
                        }
                    },
                    onBoundsChange = { rect -> bounds[index] = rect },
                    content = content
                )
            }
        }
    }

    Row(
        modifier = modifier.drawBehind {
            if (!allowMerge) return@drawBehind
            val active = pressed ?: return@drawBehind
            val progress = active.optics.opticalProgress
            if (progress < GlassRecipe.ActionGroupMergeThreshold) return@drawBehind

            val source = bounds[active.index] ?: return@drawBehind
            // 只连最近的一个邻居：连两侧会让被按的按钮看起来"融进了整条"，
            // 失去"这一枚被按下"的焦点。
            val neighbour = bounds.entries
                .filter { it.key != active.index && enabledFlags[it.key] == true }
                .minByOrNull { abs(it.value.center.x - source.center.x) }
                ?.value ?: return@drawBehind

            val gap = abs(neighbour.center.x - source.center.x) -
                (source.width + neighbour.width) / 2f
            val maxGap = source.width * GlassRecipe.ActionGroupMaxBridgeRatio
            if (gap > maxGap || gap < 0f) return@drawBehind

            // 融合强度：按压越深、间距越小，桥越饱满。
            // 距离项让远邻居即使被判定为最近也不会拉出一条明显的丝。
            val proximity = 1f - (gap / maxGap).coerceIn(0f, 1f)
            val strength = (progress * proximity).coerceIn(0f, 1f)
            if (strength < 0.02f) return@drawBehind

            drawLiquidBridge(
                from = source,
                to = neighbour,
                strength = strength,
                surfaceColor = Color.White.copy(alpha = bridgeBase * (1f + strength * 0.6f)),
                rimColor = Color.White.copy(alpha = rimAlpha * strength * 0.9f)
            )
        },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scope.content()
    }
}

interface LiquidActionGroupScope {
    /**
     * 图标操作。绝大多数调用点用这个。
     *
     * @param index 组内唯一且稳定的序号，用于定位相邻按钮。**调用方负责保证不重复、
     *              不跳号**——重复索引会让几何记录互相覆盖，融合连到错误的邻居。
     */
    @Composable
    fun action(
        index: Int,
        icon: ImageVector,
        contentDescription: String? = null,
        onClick: () -> Unit,
        enabled: Boolean = true,
        buttonSize: Dp = 34.dp,
        iconSize: Dp = 16.dp
    )

    /**
     * 自定义内容的操作。用于芯片里放的不是图标的场合——例如刷新中要把图标换成
     * 进度圈，又不想因此掉出这套玻璃材质和融合逻辑。
     *
     * 内容会自动获得与玻璃层一致的跟手形变，不需要调用方自己挂 graphicsLayer。
     */
    @Composable
    fun action(
        index: Int,
        contentDescription: String?,
        onClick: () -> Unit,
        enabled: Boolean = true,
        buttonSize: Dp = 34.dp,
        content: @Composable () -> Unit
    )
}

@Composable
private fun LiquidActionItem(
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    buttonSize: Dp,
    backdrop: Backdrop?,
    optics: InteractiveOptics,
    onPressChange: (Boolean) -> Unit,
    onBoundsChange: (Rect) -> Unit,
    content: @Composable () -> Unit
) {
    val animateContent = enabled && !rememberGlassAccessibilityMode().reduceMotion
    Box(
        modifier = Modifier
            .size(buttonSize)
            .onPlaced { coordinates ->
                val position = coordinates.positionInParent()
                onBoundsChange(
                    Rect(
                        offset = position,
                        size = Size(
                            coordinates.size.width.toFloat(),
                            coordinates.size.height.toFloat()
                        )
                    )
                )
            }
            .adaptiveGlassChip(
                backdrop = backdrop,
                shape = CircleShape,
                optics = optics,
                enabled = enabled,
                interactive = enabled
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .pressReporter(enabled = enabled, onPressChange = onPressChange)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                if (!animateContent) return@graphicsLayer
                // 形变挂在内容【外层】而不是 Icon 上：这样进度圈之类的自定义内容
                // 也能自动跟随玻璃，调用方不需要各自再挂一遍 graphicsLayer。
                applyChipContentDeformation(
                    optics = optics,
                    travelPx = GlassRecipe.ChipDragTravelDp.dp.toPx(),
                    stretch = GlassRecipe.ChipDragStretch,
                    pressDepth = GlassRecipe.ChipIconPressDepth,
                    damping = GlassRecipe.ChipContentDeformDamping
                )
            },
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * 只上报"按没按"，不做动画。融合需要知道【哪一枚】被按下，
 * 而 optics 只知道【有没有】被按下，这一位信息必须由子级回传父级。
 */
private fun Modifier.pressReporter(
    enabled: Boolean,
    onPressChange: (Boolean) -> Unit
): Modifier = if (!enabled) {
    this
} else {
    pointerInput(onPressChange) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            onPressChange(true)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.none { change -> change.pressed }) break
                }
            } finally {
                // 手势被父级滚动抢走时也要复位，否则桥会永久留在屏幕上
                onPressChange(false)
            }
        }
    }
}

/**
 * 液体连接桥：两圆之间的双曲内凹腰身。
 *
 * 形状取自表面张力下两液滴接触时的轮廓——不是矩形，也不是等宽胶囊，
 * 而是【中间细、两端外扩】并与圆相切。用二次贝塞尔的控制点落在腰部
 * 就能得到这条内凹曲线，成本远低于真正的 metaball 场求解，
 * 在 38dp 的尺度下肉眼无法区分。
 */
private fun DrawScope.drawLiquidBridge(
    from: Rect,
    to: Rect,
    strength: Float,
    surfaceColor: Color,
    rimColor: Color
) {
    val leftIsFrom = from.center.x < to.center.x
    val left = if (leftIsFrom) from else to
    val right = if (leftIsFrom) to else from

    val startX = left.center.x + left.width / 2f * 0.72f
    val endX = right.center.x - right.width / 2f * 0.72f
    if (endX <= startX) return

    val centerY = (left.center.y + right.center.y) / 2f
    // 腰宽随强度增长：刚开始只是一条细丝，压满才接近芯片直径的一半
    val waist = left.height * GlassRecipe.ActionGroupBridgeWaist * strength
    if (waist < 0.5f) return

    val edgeHalf = left.height / 2f * 0.92f
    val midX = (startX + endX) / 2f

    val path = Path().apply {
        moveTo(startX, centerY - edgeHalf)
        // 上缘：从左圆边缘内凹到腰部，再外扩到右圆边缘
        quadraticTo(midX, centerY - waist, endX, centerY - edgeHalf)
        lineTo(endX, centerY + edgeHalf)
        quadraticTo(midX, centerY + waist, startX, centerY + edgeHalf)
        close()
    }

    drawPath(path, color = surfaceColor)
    // 桥的上下缘要有和芯片同源的亮线，否则桥看起来是"贴上去的"而不是同一块玻璃
    val rimPath = Path().apply {
        moveTo(startX, centerY - edgeHalf)
        quadraticTo(midX, centerY - waist, endX, centerY - edgeHalf)
    }
    val rimPathBottom = Path().apply {
        moveTo(startX, centerY + edgeHalf)
        quadraticTo(midX, centerY + waist, endX, centerY + edgeHalf)
    }
    val stroke = Stroke(width = 1.2.dp.toPx())
    drawPath(rimPath, color = rimColor, style = stroke)
    drawPath(rimPathBottom, color = rimColor.copy(alpha = rimColor.alpha * 0.7f), style = stroke)
}