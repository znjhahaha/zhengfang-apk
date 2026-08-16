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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
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
import kotlin.math.pow
import kotlin.math.roundToInt

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
    // 出场进度也要上报：桥要知道"哪一枚正在被吸收"，而这一位信息只有子级自己有。
    // 与 bounds 分开存的理由同 enabledFlags——presence 每帧在变，几何却未必。
    val presenceFlags = remember { mutableStateMapOf<Int, Float>() }
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

    val scope = remember(bounds, enabledFlags, presenceFlags, animationScope, backdrop) {
        object : LiquidActionGroupScope {
            @Composable
            override fun action(
                index: Int,
                icon: ImageVector,
                contentDescription: String?,
                onClick: () -> Unit,
                enabled: Boolean,
                buttonSize: Dp,
                iconSize: Dp,
                presence: Float
            ) {
                action(
                    index = index,
                    contentDescription = contentDescription,
                    onClick = onClick,
                    enabled = enabled,
                    buttonSize = buttonSize,
                    presence = presence
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
                presence: Float,
                content: @Composable () -> Unit
            ) {
                // 每枚按钮一套 optics：共用一套会让按下 A 时 B/C/D 一起形变，
                // 因为它们读到的是同一个 pressProgress。
                val itemOptics = remember(index) { InteractiveOptics(animationScope) }
                val appearance = presence.coerceIn(0f, 1f)

                // 只在真的变了才写：enabledFlags 被 drawBehind 读取，
                // 每次重组都无条件写会让桥所在的绘制层白白失效一次。
                //
                // 收到 presence ≈ 0 的那一枚要一并从"可连接的邻居"里除名：调用方允许把
                // 收拢完的芯片留在组里（摘掉它会让 spacedBy 的间距同帧消失、邻居跳一格），
                // 而零宽的幽灵芯片仍在 bounds 里——不除名，按压时的桥会连到一枚看不见的
                // 芯片上，画出一截通向虚空的短桥。
                SideEffect {
                    val connectable = enabled && appearance > 0.02f
                    if (enabledFlags[index] != connectable) {
                        enabledFlags[index] = connectable
                    }
                    if (presenceFlags[index] != appearance) {
                        presenceFlags[index] = appearance
                    }
                }
                DisposableEffect(index) {
                    onDispose {
                        bounds.remove(index)
                        enabledFlags.remove(index)
                        presenceFlags.remove(index)
                    }
                }

                LiquidActionItem(
                    contentDescription = contentDescription,
                    onClick = onClick,
                    enabled = enabled,
                    buttonSize = buttonSize,
                    presence = appearance,
                    // 邻居被吸收时自己要鼓一下。序号相邻即视为邻居——组内序号本来就
                    // 要求稳定有序（见 scope 的 KDoc），而绘制期才需要这个值，
                    // 用 lambda 读 → 只触发重绘、不触发重组。
                    mergePulse = {
                        maxOf(
                            absorbStrength(presenceFlags[index - 1] ?: 1f),
                            absorbStrength(presenceFlags[index + 1] ?: 1f)
                        )
                    },
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
            // 两条独立的融合来源：按下（局部表面张力）与增减（被邻居吸收）。
            // 它们各自可能提前退出，所以分成两个函数——写在同一个 lambda 里，
            // 前者的 early return 会顺手把后者也跳过。
            drawPressMerge(
                pressed = pressed,
                bounds = bounds,
                enabledFlags = enabledFlags,
                bridgeBase = bridgeBase,
                rimAlpha = rimAlpha
            )
            drawAbsorbMerge(
                presenceFlags = presenceFlags,
                bounds = bounds,
                enabledFlags = enabledFlags,
                bridgeBase = bridgeBase,
                rimAlpha = rimAlpha
            )
        },
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scope.content()
    }
}

/**
 * 按下时向最近邻居融合。**这段数学不要改**：它与芯片的按压光学（opticalProgress）
 * 是同一套手感，动了这里就得连带重调 [GlassRecipe.ActionGroupMergeThreshold] 与腰宽。
 */
private fun DrawScope.drawPressMerge(
    pressed: PressedAction?,
    bounds: Map<Int, Rect>,
    enabledFlags: Map<Int, Boolean>,
    bridgeBase: Float,
    rimAlpha: Float
) {
    val active = pressed ?: return
    val progress = active.optics.opticalProgress
    if (progress < GlassRecipe.ActionGroupMergeThreshold) return

    val source = bounds[active.index] ?: return
    // 只连最近的一个邻居：连两侧会让被按的按钮看起来"融进了整条"，
    // 失去"这一枚被按下"的焦点。
    val neighbour = bounds.entries
        .filter { it.key != active.index && enabledFlags[it.key] == true }
        .minByOrNull { abs(it.value.center.x - source.center.x) }
        ?.value ?: return

    val gap = abs(neighbour.center.x - source.center.x) -
        (source.width + neighbour.width) / 2f
    val maxGap = source.width * GlassRecipe.ActionGroupMaxBridgeRatio
    if (gap > maxGap || gap < 0f) return

    // 融合强度：按压越深、间距越小，桥越饱满。
    // 距离项让远邻居即使被判定为最近也不会拉出一条明显的丝。
    val proximity = 1f - (gap / maxGap).coerceIn(0f, 1f)
    val strength = (progress * proximity).coerceIn(0f, 1f)
    if (strength < 0.02f) return

    drawLiquidBridge(
        from = source,
        to = neighbour,
        strength = strength,
        surfaceColor = Color.White.copy(alpha = bridgeBase * (1f + strength * 0.6f)),
        rimColor = Color.White.copy(alpha = rimAlpha * strength * 0.9f)
    )
}

/**
 * 增减时的吸收融合：presence 落在 (0,1) 的那一枚正在被吃掉，
 * 与它之间拉一段随进度变细的颈。
 *
 * 只连【仍完整在场】的邻居：两枚同时在退场的芯片之间连桥，等于把两颗正在消失的液滴
 * 粘成一条，读起来是"整条在融化"而不是"被那一枚吃掉"。
 */
private fun DrawScope.drawAbsorbMerge(
    presenceFlags: Map<Int, Float>,
    bounds: Map<Int, Rect>,
    enabledFlags: Map<Int, Boolean>,
    bridgeBase: Float,
    rimAlpha: Float
) {
    presenceFlags.forEach { (index, presence) ->
        val strengthBase = absorbStrength(presence)
        if (strengthBase < 0.02f) return@forEach
        val layoutRect = bounds[index] ?: return@forEach
        val neighbour = bounds.entries
            .filter { entry ->
                entry.key != index &&
                    enabledFlags[entry.key] == true &&
                    (presenceFlags[entry.key] ?: 1f) >= 0.98f
            }
            .minByOrNull { abs(it.value.center.x - layoutRect.center.x) }
            ?.value ?: return@forEach

        // 上报的是布局矩形（高度仍是满高），画出来的液滴却被 chipPresenceScaleY 压过一次。
        // 桥必须贴【画出来的那一颗】，否则它会从液滴的上下缘探出去。
        val drawnHeight = layoutRect.height * chipPresenceScaleY(presence)
        val source = Rect(
            offset = Offset(
                layoutRect.left,
                layoutRect.center.y - drawnHeight / 2f
            ),
            size = Size(layoutRect.width, drawnHeight)
        )

        val gap = abs(neighbour.center.x - source.center.x) -
            (source.width + neighbour.width) / 2f
        val maxGap = neighbour.width * GlassRecipe.ActionGroupMaxBridgeRatio
        if (gap > maxGap || gap < 0f) return@forEach

        val proximity = 1f - (gap / maxGap).coerceIn(0f, 1f)
        val strength = (strengthBase * proximity).coerceIn(0f, 1f)
        if (strength < 0.02f) return@forEach

        drawLiquidBridge(
            from = source,
            to = neighbour,
            strength = strength,
            surfaceColor = Color.White.copy(alpha = bridgeBase * (1f + strength * 0.6f)),
            rimColor = Color.White.copy(alpha = rimAlpha * strength * 0.9f)
        )
    }
}

/**
 * 吸收强度：两端为 0、中途最饱满。
 *
 * 静止态（presence = 1）与收拢完（0）都不该有桥，所以它必须是个鼓包而不是单调曲线；
 * 用它同时驱动"退场那一枚拉出的颈"和"存活那一枚的吞咽回弹"，两者因此同相。
 */
private fun absorbStrength(presence: Float): Float =
    (4f * presence * (1f - presence)).coerceIn(0f, 1f)

/**
 * 被吸收时的纵向收缩。
 *
 * 横向【必须】等于上报宽度（线性），这是锚左缘那条不变量的另一半：绘制的右缘要始终
 * 等于 `x + width * presence`。纵向收得更快，于是液滴先被拉扁再断开——等比缩小读起来
 * 是"一枚按钮变小了"，不是"一颗液滴被吸走了"。
 */
internal fun chipPresenceScaleY(presence: Float): Float =
    presence.coerceIn(0f, 1f).pow(1.6f)

/** 透明度最后才让步：液滴不是"变透明"，是"被吃掉"。 */
internal fun chipPresenceAlpha(presence: Float): Float =
    (presence * 2.4f).coerceIn(0f, 1f)

interface LiquidActionGroupScope {
    /**
     * 图标操作。绝大多数调用点用这个。
     *
     * @param index 组内唯一且稳定的序号，用于定位相邻按钮。**调用方负责保证不重复、
     *              不跳号**——重复索引会让几何记录互相覆盖，融合连到错误的邻居。
     * @param presence 出场进度。1 = 常态；0 = 完全收起（宽度与绘制都归零）。
     *              收起途中会被相邻的那一枚"吸收"：拉出一段液体颈、自己被压扁、
     *              邻居鼓一下。**允许把 presence = 0 的那一枚一直留在组里**——摘掉
     *              composable 会让 `spacedBy` 的间距在同一帧消失，邻居跳一格。
     */
    @Composable
    fun action(
        index: Int,
        icon: ImageVector,
        contentDescription: String? = null,
        onClick: () -> Unit,
        enabled: Boolean = true,
        buttonSize: Dp = 34.dp,
        iconSize: Dp = 16.dp,
        presence: Float = 1f
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
        presence: Float = 1f,
        content: @Composable () -> Unit
    )
}

/**
 * @param presence 出场进度。**动画必须挂在这一个节点自己身上**，不能用
 *        `AnimatedVisibility` 或再套一个 Box 包起来：桥由父级 `drawBehind` 画，
 *        几何靠下面那个 `onPlaced { positionInParent() }` 上报；中间插一个布局节点，
 *        上报的坐标就变成"相对那个节点"的（≈0,0），桥会连到错误的位置。
 * @param mergePulse 邻居正被吸收的强度（绘制期读取）。液滴吞掉旁边那颗时会先鼓一下
 *        再回弹，这一下是"位置转换"读得出来的关键——少了它，存活的那一枚只是被布局
 *        推着平移，看不出发生过融合。
 */
@Composable
private fun LiquidActionItem(
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean,
    buttonSize: Dp,
    presence: Float,
    mergePulse: () -> Float,
    backdrop: Backdrop?,
    optics: InteractiveOptics,
    onPressChange: (Boolean) -> Unit,
    onBoundsChange: (Rect) -> Unit,
    content: @Composable () -> Unit
) {
    val animateContent = enabled && !rememberGlassAccessibilityMode().reduceMotion
    val appearance = presence.coerceIn(0f, 1f)
    // 收拢途中就不再接受点击：presence 很小时可点区域已经不足一指宽，
    // 命中它只会让人以为点错了。
    val reachable = enabled && appearance > 0.6f
    Box(
        modifier = Modifier
            .size(buttonSize)
            .then(
                if (appearance >= 1f) {
                    // 自己完整在场：只剩"邻居被吃掉时鼓一下"这一层形变。
                    // graphicsLayer 的 block 是绘制期 lambda，读 mergePulse 只触发重绘。
                    Modifier.graphicsLayer {
                        if (!animateContent) return@graphicsLayer
                        val pulse = mergePulse().coerceIn(0f, 1f)
                        if (pulse < 0.01f) return@graphicsLayer
                        // 朝被吸收的方向被拽长、纵向同时被挤——近似体积守恒，
                        // 幅度必须小：大了就从"吞咽"变成"按钮在抖"。
                        scaleX = 1f + 0.07f * pulse
                        scaleY = 1f - 0.03f * pulse
                    }
                } else {
                    Modifier
                        // 子级仍按满尺寸测量（图标不会被挤扁），只把【上报给父级的宽度】
                        // 按进度收窄，于是邻居连续滑过来、整组宽度跟着收。
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width = (placeable.width * appearance).roundToInt()
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            // 横向线性、纵向更快、透明度最后走：这三条一起才是"被吸收"，
                            // 曲线与桥的几何共用（见 chipPresenceScaleY 的注释）。
                            alpha = chipPresenceAlpha(appearance)
                            scaleX = appearance
                            scaleY = chipPresenceScaleY(appearance)
                            // 必须锚左缘：上面上报的宽度是 width * presence，绘制也就跟着
                            // 从左缘收，两者右缘始终重合。锚右缘的话绘制会留在原处，
                            // 而邻居已经滑过来了——收拢途中两枚芯片会叠在一起。
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                }
            )
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
                enabled = reachable,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                role = Role.Button,
                onClick = onClick
            )
            .pressReporter(enabled = reachable, onPressChange = onPressChange)
            // 收拢完的那一枚不留语义：它零宽零绘制，但语义节点仍会被读屏摸到，
            // 于是"已选"页会念出一枚根本不存在的搜索钮。
            .semantics {
                if (reachable && contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
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