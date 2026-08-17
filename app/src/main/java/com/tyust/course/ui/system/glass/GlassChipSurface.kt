package com.tyust.course.ui.system.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.tyust.course.ui.system.rememberGlassDarkTheme
import com.tyust.course.ui.system.GlassMaterialRole
import com.tyust.course.ui.system.GlassMaterials
import com.tyust.course.ui.system.GlassRecipe
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.isRuntimeShaderTrulySupported
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import kotlin.math.abs

/**
 * 液体玻璃芯片：顶栏图标钮、圆钮共用的唯一材质入口。
 *
 * 两条路径，取决于身后有没有可采样的 Backdrop：
 *
 *   有 backdrop -> liquidChip：vibrancy → blur → lens → surface → Fresnel rim
 *                  玻璃感主要来自【真实环境折射】，表面白雾压到极低只作打底
 *   无 backdrop -> glassChip：纯边缘光
 *                  玻璃感只能来自【边缘】，因为没有环境可折射
 *
 * 两者不是叠加关系而是互斥的：表面白雾一重，折射就被盖死。所以 liquidChip
 * 的 surfaceAlpha 必须比 glassChip 低得多，按压时还要继续降——手指按下去
 * 应该看到更多环境，而不是更多白色。
 */

/**
 * 带真实背景折射的液体芯片。
 *
 * @param optics 交互状态源。折射高度、色散、边缘光位置、形变全部由它驱动，
 *               保证松手时这些量是同一个物理时刻，而不是各自播放的动画。
 */
@Composable
fun Modifier.liquidChip(
    backdrop: Backdrop,
    shape: Shape,
    optics: InteractiveOptics,
    enabled: Boolean = true,
    elevation: Dp = 0.dp,
    interactive: Boolean = true
): Modifier {
    val isLight = !rememberGlassDarkTheme()
    val accessibility = rememberGlassAccessibilityMode()
    val hasRealLens = isRuntimeShaderTrulySupported()
    val allowInteraction = interactive && enabled && !accessibility.reduceMotion

    val baseSurfaceAlpha = if (isLight) {
        GlassRecipe.ChipSurfaceAlphaLight
    } else {
        GlassRecipe.ChipSurfaceAlphaDark
    }
    val disabledScale = if (enabled) 1f else GlassRecipe.ChipDisabledSurfaceScale
    val rimStrength = if (enabled) 1f else GlassRecipe.ChipDisabledRimScale
    val shadowColor = Color.Black.copy(alpha = 0.16f)
    val useShadow = enabled && !isLight && elevation > 0.dp

    return this
        .then(
            if (useShadow) {
                Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor
                )
            } else {
                Modifier
            }
        )
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                val material = GlassMaterials.resolve(
                    role = GlassMaterialRole.Interactive,
                    accessibility = accessibility,
                    interactionProgress = optics.opticalProgress
                )
                val params = resolvePhysicalLens(
                    density = this,
                    material = material,
                    shape = shape,
                    minCornerRadiusPx = size.minDimension / 2f,
                    minDimensionPx = size.minDimension,
                    interactionProgress = optics.opticalProgress,
                    motionIntensity = optics.motionIntensity(
                        material.optics.velocityForFullEffect
                    ),
                    // 小芯片上重 blur 会把折射糊成一片灰，只在无真 lens 时靠它撑质感
                    enableBlur = !hasRealLens,
                    allowChromaticAberration = allowInteraction,
                    // 静止不色散：静止态该像一枚干净玻璃，彩边是交互时才出现的动态特征
                    chromaticAberrationAtRest = false,
                    // 静止保留 floor 折射，按压抬到满额
                    pressScalesRefraction = true,
                    refractionFloor = 0.62f
                )
                if (params.blurPx > 0f) blur(params.blurPx)
                if (params.useLens) {
                    lens(
                        params.refractionHeightPx,
                        params.refractionAmountPx,
                        params.chromaticAberration
                    )
                } else if (params.fringePx > 0f) {
                    chromaticFringe(params.fringePx)
                }
            },
            layerBlock = if (allowInteraction) {
                {
                    applyChipGlassDeformation(
                        optics = optics,
                        travelPx = GlassRecipe.ChipDragTravelDp.dp.toPx(),
                        swellPx = GlassRecipe.ChipPressSwellDp.dp.toPx(),
                        stretch = GlassRecipe.ChipDragStretch
                    )
                }
            } else {
                null
            },
            onDrawSurface = {
                // 按压让位给折射：surface 越薄，环境越清楚
                val press = optics.opticalProgress
                val alpha = baseSurfaceAlpha * disabledScale *
                    (1f - press * (1f - GlassRecipe.ChipPressedSurfaceScale))
                drawRect(Color.White.copy(alpha = alpha))
            }
        )
        .glassRim(
            shape = shape,
            intensity = rimStrength,
            isLightTheme = isLight,
            pressProgress = { optics.pressProgress },
            pointerOffset = { if (allowInteraction) optics.pointerPosition else Offset.Unspecified }
        )
        .then(if (allowInteraction) optics.gestureModifier else Modifier)
}

/**
 * 无背景玻璃：纯色底上的玻璃感来自【边缘】，不是来自【体积】。
 *
 * 这里踩过一个坑：用径向渐变在中下部造聚焦亮区，结果做出来是一颗珍珠。
 * 真实的玻璃圆片是【扁平】的——光在边缘因 Fresnel 效应聚集成一圈亮线，
 * 盘面本身几乎均匀。所以：
 *
 *   内缘亮线  -> 光感的唯一来源，紧贴边界，不会产生球体感
 *   外轮廓线  -> 界定形状，一条均匀的细灰线
 *   表面      -> 近乎平整，只留极弱的上下梯度
 *   阴影      -> 极轻甚至没有；重了就从"玻璃"变成"浮起的白球"
 *
 * @param dimmed 禁用态。容器保留，只压暗——容器直接消失会让按钮失去可点区域的暗示
 * @param pressProgress 按压进度。用 lambda 读取，形变只触发重绘不触发重组
 */
@Composable
fun Modifier.glassChip(
    shape: Shape,
    elevation: Dp = 0.dp,
    rimIntensity: Float = 1f,
    dimmed: Boolean = false,
    pressProgress: () -> Float = { 0f }
): Modifier {
    val isLight = !rememberGlassDarkTheme()
    val strength = if (dimmed) GlassRecipe.ChipDisabledSurfaceScale else 1f
    val rim = rimIntensity * if (dimmed) GlassRecipe.ChipDisabledRimScale else 1f
    // 浅色主题彻底不投影。参考图那枚返回键是没有阴影的，
    // 只要有阴影就会在浅底上糊出一圈晕，立刻从"玻璃片"变成"浮起的球"。
    val shadowColor = Color.Black.copy(alpha = 0.18f * strength)
    val useShadow = !dimmed && !isLight && elevation > 0.dp

    // 盘面要够淡。浅色背景上白色表面越实，轮廓线越糊，最后就是一颗白球。
    // 参考图里盘面只比背景亮一点点，形状全靠轮廓线交代。
    val surfaceBrush = if (isLight) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.18f * strength),
            1.0f to Color.White.copy(alpha = 0.26f * strength)
        )
    } else {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.07f * strength),
            1.0f to Color.White.copy(alpha = 0.13f * strength)
        )
    }

    return this
        .then(
            if (useShadow) {
                Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    clip = false,
                    ambientColor = shadowColor,
                    spotColor = shadowColor
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(surfaceBrush)
        .glassRim(shape, rim, isLight, pressProgress)
}

/**
 * 玻璃边缘：外轮廓线 + 内缘亮线。
 *
 * 内缘亮线是关键——它把光锁在边界一圈之内，于是盘面保持平整，
 * 既有玻璃的通透光泽，又不会鼓成一颗球。顶部亮于底部只是很轻的偏置，
 * 用来暗示光源方向，幅度必须小，大了立刻又变立体。
 *
 * 直接用 [Shape.createOutline]，所以对 Capsule、连续曲率 squircle 的 Generic path、
 * CircleShape 一视同仁；不走 backdrop 库那条只认 CornerBasedShape 的圆角判断，
 * 也不依赖 AGSL，API 31/32 同样有效。
 *
 * @param pointerOffset 触点位置。给定时内缘亮线的最亮处偏向手指，
 *                      这是"光被手指压过去"的暗示；Unspecified 时保持顶部偏置。
 */
fun Modifier.glassRim(
    shape: Shape,
    intensity: Float = 1f,
    isLightTheme: Boolean = true,
    pressProgress: () -> Float = { 0f },
    pointerOffset: () -> Offset = { Offset.Unspecified }
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val contourWidth = 1.dp.toPx()

    // 内缘：把轮廓整体内缩一圈再描边，光就贴在玻璃内壁上
    val insetPx = 1.1.dp.toPx()
    val innerSize = Size(
        (size.width - insetPx * 2f).coerceAtLeast(0.1f),
        (size.height - insetPx * 2f).coerceAtLeast(0.1f)
    )
    val innerOutline = shape.createOutline(innerSize, layoutDirection, this)
    val innerWidth = 1.2.dp.toPx()

    // 浅色主题下白色内缘线画在白色盘面上等于没画（白叠白），只留一点点；
    // 深色主题反过来，白线才是唯一能看见的光。
    val baseContour = if (isLightTheme) {
        GlassRecipe.ChipContourAlphaLight
    } else {
        GlassRecipe.ChipContourAlphaDark
    }
    val baseInner = if (isLightTheme) {
        GlassRecipe.ChipRimAlphaLight
    } else {
        GlassRecipe.ChipRimAlphaDark
    }

    onDrawWithContent {
        drawContent()
        val p = pressProgress().coerceIn(0f, 1f)

        // 内缘亮线：默认顶部略强，按压时最亮处移向手指
        val innerAlpha = baseInner * intensity * (1f + GlassRecipe.ChipPressedRimBoost * p)
        val pointer = pointerOffset()
        val innerBrush = if (pointer != Offset.Unspecified && p > 0.01f && size.minDimension > 0f) {
            // 用触点为中心的径向渐变去调制边缘线的亮度分布：
            // 靠近手指的那一段更亮。半径取整个控件，保证渐变覆盖全边缘。
            val travel = GlassRecipe.ChipHighlightTravel * p
            val center = Offset(
                size.width / 2f + (pointer.x - size.width / 2f) * travel,
                size.height / 2f + (pointer.y - size.height / 2f) * travel
            )
            Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = innerAlpha * 1.25f),
                    Color.White.copy(alpha = innerAlpha * 0.55f)
                ),
                center = center,
                radius = size.maxDimension * 0.85f
            )
        } else {
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.White.copy(alpha = innerAlpha),
                    0.50f to Color.White.copy(alpha = innerAlpha * 0.55f),
                    1.00f to Color.White.copy(alpha = innerAlpha * 0.75f)
                )
            )
        }
        translate(insetPx, insetPx) {
            drawOutline(innerOutline, brush = innerBrush, style = Stroke(innerWidth))
        }

        // 外轮廓：均匀一圈，界定形状。按下时收紧变实，像被压出的应力边
        val contourColor = if (isLightTheme) {
            Color.Black.copy(alpha = baseContour * intensity * (1f + 0.5f * p))
        } else {
            Color.White.copy(alpha = baseContour * intensity * (1f + 0.5f * p))
        }
        drawOutline(outline, color = contourColor, style = Stroke(contourWidth))
    }
}

/**
 * 材质入口选择：有可采样 backdrop 就走真折射，否则退回边缘光。
 * 组件不该自己判断这件事，否则每个按钮都要复制一遍平台能力判定。
 *
 * 两条路径的【交互能力必须等价】。曾经漏过一次：回退分支把 optics.pressProgress
 * 读进了 glassChip，却没挂 optics.gestureModifier——只有 liquidChip 挂了——
 * 于是 API 31 以下、backdrop 为空、运行时降级这三种情况下 pressProgress
 * 永远停在 0，按下去毫无反应，而模拟器走的是玻璃路径，测不出来。
 */
@Composable
fun Modifier.adaptiveGlassChip(
    backdrop: Backdrop?,
    shape: Shape,
    optics: InteractiveOptics,
    enabled: Boolean = true,
    elevation: Dp = 0.dp,
    interactive: Boolean = true
): Modifier {
    val usable = backdrop?.takeIf { isBackdropSupported() }
    if (usable != null) {
        return liquidChip(
            backdrop = usable,
            shape = shape,
            optics = optics,
            enabled = enabled,
            elevation = elevation,
            interactive = interactive
        )
    }

    val accessibility = rememberGlassAccessibilityMode()
    val allowInteraction = interactive && enabled && !accessibility.reduceMotion
    return this
        .then(
            // 必须在 glassChip 之前：graphicsLayer 只变换它右侧的内容，
            // 放在后面就只压到了图标，芯片自己的绘制不动。
            if (allowInteraction) {
                Modifier.graphicsLayer {
                    applyPressSquash(
                        progress = optics.pressProgress,
                        depth = GlassRecipe.ChipFallbackPressDepth
                    )
                }
            } else {
                Modifier
            }
        )
        .glassChip(
            shape = shape,
            elevation = elevation,
            dimmed = !enabled,
            pressProgress = { optics.pressProgress }
        )
        .then(if (allowInteraction) optics.gestureModifier else Modifier)
}