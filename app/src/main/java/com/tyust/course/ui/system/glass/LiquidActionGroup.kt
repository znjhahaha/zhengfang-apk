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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.tyust.course.ui.system.rememberGlassDarkTheme
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
    val isLight = !rememberGlassDarkTheme()
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
 * 按下时向最近邻居融合。按压阈值与 [GlassRecipe.ActionGroupMergeThreshold] 和芯片的
 * 按压光学（opticalProgress）是同一套手感，动了这里就得连带重调那两处。
 */
private fun DrawScope.drawPressMerge(
    pressed: PressedAction?,
    bounds: Map<Int, Rect>,
    enabledFlags: Map<Int, Boolean>,
    bridgeBase: Float,
    rimAlpha: Float
) {
    val active = pressed ?: return
    val optics = active.optics
    val progress = optics.opticalProgress
    if (progress < GlassRecipe.ActionGroupMergeThreshold) return

    val layoutSource = bounds[active.index] ?: return
    // 只连最近的一个邻居：连两侧会让被按的按钮看起来"融进了整条"，
    // 失去"这一枚被按下"的焦点。
    val neighbour = bounds.entries
        .filter { it.key != active.index && enabledFlags[it.key] == true }
        .minByOrNull { abs(it.value.center.x - layoutSource.center.x) }
        ?.value ?: return

    // 桥要贴【画出来的那一颗】：被按芯片绘制期有跟手平移和按压膨胀
    // （drawBackdrop layerBlock 里的同一套公式），静态布局 bounds 会在
    // 按住拖动时与芯片错位、从圆边外露出一段悬空的桥。
    // 在 drawBehind 里读 optics 的状态只触发重绘，不额外引发重组。
    val travel = optics.dragTravel(GlassRecipe.ChipDragTravelDp.dp.toPx())
    val swell = 1f + optics.pressProgress *
        GlassRecipe.ChipPressSwellDp.dp.toPx() / layoutSource.height
    val pressedRadius = layoutSource.width / 2f * swell
    val pressedCenter = layoutSource.center + travel
    val source = Rect(
        offset = Offset(pressedCenter.x - pressedRadius, pressedCenter.y - pressedRadius),
        size = Size(pressedRadius * 2f, pressedRadius * 2f)
    )

    val gap = (neighbour.center - source.center).getDistance() -
        (source.width + neighbour.width) / 2f
    val maxGap = maxOf(source.width, neighbour.width) * GlassRecipe.ActionGroupMaxBridgeRatio
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
        val maxGap = maxOf(source.width, neighbour.width) * GlassRecipe.ActionGroupMaxBridgeRatio
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

/**
 * 滑行果冻拉伸因子（x 拉长量，y 等体积收窄量）。速度取绝对值——芯片只在
 * 横轴上被父级推动，方向不影响"沿运动方向拉长"的读法。
 */
internal fun glideStretchFactors(velocityPxMs: Float): Pair<Float, Float> {
    val s = (abs(velocityPxMs) / GlassRecipe.ChipGlideFullVelocityPxMs).coerceIn(0f, 1f)
    val k = GlassRecipe.ChipGlideStretch * s
    return (1f + k) to (1f - k * 0.75f)
}

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

    // 组收拢/展开时，芯片的位移来自父级重排——推力不在自己身上，速度只有
    // onPlaced 的根坐标差分能观察到。EMA 抑制单帧抖动；帧循环负责把速度
    // 衰减回零：布局停稳后 onPlaced 不再触发，没有衰减速度会冻结在最后
    // 一个采样值上，拉伸就永远回不去。
    val glideVelocity = remember { mutableFloatStateOf(0f) }
    var glideSampleX by remember { mutableFloatStateOf(Float.NaN) }
    var glideSampleT by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            val v = glideVelocity.floatValue
            if (v != 0f) {
                val decayed = v * 0.78f
                glideVelocity.floatValue = if (abs(decayed) < 0.006f) 0f else decayed
            }
        }
    }

    Box(
        modifier = Modifier
            .then(
                if (appearance >= 1f) {
                    Modifier
                } else {
                    // 宽度收窄的 layout 必须在 size【外面】：size 是强制尺寸，
                    // 挂在链首会把内层上报的收窄宽度重新顶回满宽——那版收拢
                    // 只剩绘制层缩放，邻居永远滑不过来，组中央一直留着满宽
                    // 空槽（课程页"已选"态刷新芯片停在原地铁证）。
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val width = (placeable.width * appearance).roundToInt()
                            layout(width, placeable.height) { placeable.place(0, 0) }
                        }
                }
            )
            .size(buttonSize)
            .graphicsLayer {
                // 滑行拉伸对两种状态都成立：完整的那枚被推着走、退场的那枚
                // 被拽着走，都是横轴上的液体位移。
                val glideX: Float
                val glideY: Float
                if (animateContent) {
                    val glide = glideStretchFactors(glideVelocity.floatValue)
                    glideX = glide.first
                    glideY = glide.second
                } else {
                    glideX = 1f
                    glideY = 1f
                }
                if (appearance >= 1f) {
                    val pulse = if (animateContent) mergePulse().coerceIn(0f, 1f) else 0f
                    // 朝被吸收的方向被拽长、纵向同时被挤——近似体积守恒，
                    // 幅度必须小：大了就从"吞咽"变成"按钮在抖"。
                    scaleX = (1f + 0.07f * pulse) * glideX
                    scaleY = (1f - 0.03f * pulse) * glideY
                } else {
                    // 横向线性、纵向更快、透明度最后走：这三条一起才是"被吸收"，
                    // 曲线与桥的几何共用（见 chipPresenceScaleY 的注释）。
                    alpha = chipPresenceAlpha(appearance)
                    scaleX = appearance * glideX
                    scaleY = chipPresenceScaleY(appearance) * glideY
                    // 必须锚左缘：上面上报的宽度是 width * presence，绘制也就跟着
                    // 从左缘收，两者右缘始终重合。锚右缘的话绘制会留在原处，
                    // 而邻居已经滑过来了——收拢途中两枚芯片会叠在一起。
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
            }
            .onPlaced { coordinates ->
                val rootX = coordinates.positionInRoot().x
                val now = System.nanoTime() / 1_000_000L
                if (animateContent && !glideSampleX.isNaN()) {
                    val dt = (now - glideSampleT).coerceIn(1L, 64L)
                    val v = (rootX - glideSampleX) / dt
                    glideVelocity.floatValue = glideVelocity.floatValue * 0.55f + v * 0.45f
                }
                glideSampleX = rootX
                glideSampleT = now
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
 * 液体连接桥：两圆 SDF 的 smooth-min 等值面轮廓（Apple Liquid Glass「控件融合」/
 * 社区 metaball 同款算法，几何见 [LiquidMergeGeometry]）。
 *
 * strength 不再直接决定腰宽，而是映射到平滑半径 k：出生点（k = 2.02×边距）之上
 * 桥是一条细丝，随按压连续长到腰宽约 3/4 直径——替代旧的"贝塞尔控制点落在腰部"
 * 近似（锚点几何在圆外，衔接处会出翼片尖刺，且最小腰就有半直径宽、没有细丝阶段）。
 *
 * 轮廓端站在圆心正上/下方，竖直端边整段藏进芯片后面被盖住，无需相切计算。
 * 采样 16 站 × 12 步二分 ≈ 每帧两百次场求值，仅按压/吸收动画期间执行。
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

    val leftCircle = LiquidMergeGeometry.MergeCircle(
        center = left.center,
        radius = left.width / 2f
    )
    val rightCircle = LiquidMergeGeometry.MergeCircle(
        center = right.center,
        radius = right.width / 2f
    )
    val gap = (rightCircle.center - leftCircle.center).getDistance() -
        leftCircle.radius - rightCircle.radius
    if (gap <= 0f) return

    val kMin = GlassRecipe.ActionGroupBridgeKBirthGapRatio * gap
    val kMax = GlassRecipe.ActionGroupBridgeKFullGapRatio * gap +
        GlassRecipe.ActionGroupBridgeKFullRadiusRatio *
        (leftCircle.radius + rightCircle.radius) / 2f
    val k = kMin + (kMax - kMin) * strength.coerceIn(0f, 1f)

    val points = LiquidMergeGeometry.mergeOutlinePoints(leftCircle, rightCircle, k)
        // 颈未形成（strength 贴地或几何退化）时不画——桥必须从细丝"长"出来
        ?: return
    val stationCount = points.size / 2

    drawPath(closedSmoothPath(points), color = surfaceColor)

    // 上下缘亮线与芯片 rim 同源：上亮下暗，暗示光源方向；两端藏进圆内的部分
    // 会被芯片盖住，实际可见的只有间隙段。
    val stroke = Stroke(width = 1.2.dp.toPx())
    val upper = points.subList(0, stationCount)
    val lower = points.subList(stationCount, points.size)
    // 屏幕坐标系 y 向下：靠下的那一段更暗
    val upperIsFirstHalf = upper.first().y < lower.first().y
    val firstCurve = openSmoothPath(if (upperIsFirstHalf) upper else lower)
    val secondCurve = openSmoothPath(if (upperIsFirstHalf) lower else upper)
    drawPath(firstCurve, color = rimColor, style = stroke)
    drawPath(
        secondCurve,
        color = rimColor.copy(alpha = rimColor.alpha * 0.7f),
        style = stroke
    )
}

/**
 * 采样折线 → 平滑闭合 Path：段中点之间连二次贝塞尔，控制点取原采样点。
 * 端站之间的两条竖直边藏在芯片后面，平滑不平滑都看不见。
 */
private fun closedSmoothPath(points: List<Offset>): Path {
    val path = Path()
    path.moveTo(points.first().x, points.first().y)
    appendSmoothSegment(path, points)
    path.close()
    return path
}

/** 采样折线 → 平滑开放曲线（只描边缘亮线用）。 */
private fun openSmoothPath(points: List<Offset>): Path {
    val path = Path()
    path.moveTo(points.first().x, points.first().y)
    appendSmoothSegment(path, points)
    return path
}

private fun appendSmoothSegment(path: Path, points: List<Offset>) {
    for (i in 1 until points.size - 1) {
        val midX = (points[i].x + points[i + 1].x) / 2f
        val midY = (points[i].y + points[i + 1].y) / 2f
        path.quadraticTo(points[i].x, points[i].y, midX, midY)
    }
    path.lineTo(points.last().x, points.last().y)
}