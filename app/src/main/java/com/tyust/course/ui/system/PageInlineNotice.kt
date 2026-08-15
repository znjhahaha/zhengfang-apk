package com.tyust.course.ui.system

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.resolvePhysicalLens
import com.tyust.course.ui.theme.IOSBlueDark
import com.tyust.course.ui.theme.IOSBlueLight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Immutable
data class FloatingNotice(
    val message: String,
    val actionLabel: String = "重新登录",
    val onClick: () -> Unit
)

val LocalFloatingNotice = staticCompositionLocalOf<FloatingNotice?> { null }

// ═══════════════════════════════════════════════════════════
// 落位契约：顶栏上报底边，通知覆盖层据此落位
// ═══════════════════════════════════════════════════════════

/**
 * 通知是不参与测量的覆盖层，它本身不知道当前页面顶栏有多高。
 * 顶栏通过 [reportNoticeAnchor] 把自己的底边写进这里，覆盖层永远落在该线之下，
 * 从根本上避免压住顶栏里的按钮，也不需要各页面手填落点数字。
 */
@Stable
class NoticeAnchorState {
    var topBarBottom: Dp by mutableStateOf(Dp.Unspecified)
}

val LocalNoticeAnchor = staticCompositionLocalOf { NoticeAnchorState() }

/** 顶栏挂在最外层：上报底边坐标。未接入的页面自动回落到默认落点。 */
@Composable
fun Modifier.reportNoticeAnchor(): Modifier {
    val anchor = LocalNoticeAnchor.current
    val density = LocalDensity.current
    return this.onGloballyPositioned { coordinates ->
        val bottomPx = coordinates.boundsInRoot().bottom
        if (bottomPx > 0f) {
            anchor.topBarBottom = with(density) { bottomPx.toDp() }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 形态状态机
// ═══════════════════════════════════════════════════════════

private enum class NoticePhase {
    /** 完整胶囊，展示文案与操作。 */
    Expanded,

    /** 药丸，只留一个警示点。 */
    Collapsed
}

/** 展开后自动收起的时长：只够读完一行字，不长期占位。 */
private const val AUTO_COLLAPSE_MS = 3600L

private val PillSize = 26.dp
private val CapsuleHeight = 38.dp

/** 顶栏未上报时的兜底落点。 */
private val FallbackTop = 128.dp

/** 与屏幕右缘的间距：药丸与胶囊右端共用，形变时右边缘不动。 */
private val EdgeInset = 14.dp

/** 判定为"收起"的右滑距离。 */
private val DismissThreshold = 72.dp

/** 警示强调色：琥珀，仅用于图标点缀，表面保持中性玻璃。 */
@Composable
private fun noticeAccentColor(): Color =
    if (!isSystemInDarkTheme()) Color(0xFFC26A00) else Color(0xFFFFB340)

/**
 * 悬浮玻璃通知：单个元素在胶囊与药丸之间连续形变，右边缘始终贴右缘对齐，
 * 所以收起就是"向右收拢"而不是两块 UI 硬切。落位由顶栏上报的底边决定，
 * 不覆盖任何顶栏操作；右滑收起为药丸，点击药丸重新展开。
 */
@Composable
fun FloatingNoticeHost(modifier: Modifier = Modifier) {
    val notice = LocalFloatingNotice.current ?: return
    val anchor = LocalNoticeAnchor.current
    val accessibility = rememberGlassAccessibilityMode()
    val motion = !accessibility.reduceMotion

    var phase by remember(notice.message) { mutableStateOf(NoticePhase.Expanded) }

    // 只依赖通知本身：切 tab 不再重新弹出，避免反复占住同一块区域
    LaunchedEffect(notice.message, phase) {
        if (phase == NoticePhase.Expanded) {
            delay(AUTO_COLLAPSE_MS)
            phase = NoticePhase.Collapsed
        }
    }

    val expanded = phase == NoticePhase.Expanded
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(density) { DismissThreshold.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        var capsuleWidth by remember { mutableStateOf(Dp.Unspecified) }
        val restingWidth = if (capsuleWidth.isSpecified) capsuleWidth else 208.dp

        val baseTop = if (anchor.topBarBottom.isSpecified) {
            anchor.topBarBottom + 6.dp
        } else {
            FallbackTop
        }

        val sizeSpec = spring<Dp>(dampingRatio = 0.84f, stiffness = 430f)
        val posSpec = spring<Dp>(dampingRatio = 0.82f, stiffness = 380f)

        val width by animateDpAsState(
            targetValue = if (expanded) restingWidth else PillSize,
            animationSpec = if (motion) sizeSpec else snap(),
            label = "noticeWidth"
        )
        val height by animateDpAsState(
            targetValue = if (expanded) CapsuleHeight else PillSize,
            animationSpec = if (motion) sizeSpec else snap(),
            label = "noticeHeight"
        )
        // 收起时向上吸 4dp，形成"贴回顶栏"的落位感
        val topPadding by animateDpAsState(
            targetValue = if (expanded) baseTop else baseTop - 4.dp,
            animationSpec = if (motion) posSpec else snap(),
            label = "noticeTop"
        )
        val capsuleAlpha by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 120),
            label = "noticeCapsuleAlpha"
        )
        val pillAlpha by animateFloatAsState(
            targetValue = if (expanded) 0f else 1f,
            animationSpec = tween(
                durationMillis = 150,
                delayMillis = if (expanded) 0 else 60
            ),
            label = "noticePillAlpha"
        )

        val isLightTheme = !isSystemInDarkTheme()
        // 与底栏选中滑块同一档材质：Interactive 的 blurDp 为 0，靠折射与色散成形，
        // 而不是 Modal 那种磨砂。表面必须够薄，否则会把折射盖掉。
        val material = remember(accessibility) {
            GlassMaterials.resolve(GlassMaterialRole.Interactive, accessibility)
                .copy(surfaceAlpha = 0.16f)
        }
        val surfaceColor = if (isLightTheme) {
            Color.White.copy(alpha = material.surfaceAlpha)
        } else {
            Color(0xFF1E2024).copy(alpha = material.surfaceAlpha + 0.06f)
        }
        val fallbackSurface = if (isLightTheme) {
            Color.White.copy(alpha = 0.88f)
        } else {
            Color(0xFF1E2024).copy(alpha = 0.90f)
        }
        val shadowColor = Color.Black.copy(alpha = if (isLightTheme) 0.10f else 0.24f)
        val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        val accentColor = noticeAccentColor()
        val shape: Shape = RoundedCornerShape(percent = 50)
        // 采样含页面内容的捕获层而非纯壁纸：折射里要有东西可看，才是镜片不是雾。
        // 通知位于该捕获层之外，不会自采样。
        val backdrop = (LocalModalBackdrop.current ?: LocalAppBackdrop.current)
            ?.takeIf { isBackdropSupported() }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topPadding, end = EdgeInset)
                .size(width = width, height = height)
                .graphicsLayer {
                    translationX = dismissOffset.value
                    alpha = 1f - (dismissOffset.value / dismissThresholdPx).coerceIn(0f, 1f) * 0.85f
                }
                .then(
                    if (backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                val params = resolvePhysicalLens(
                                    density = this,
                                    material = material,
                                    shape = shape,
                                    minCornerRadiusPx = size.minDimension / 2f,
                                    minDimensionPx = size.minDimension,
                                    interactionProgress = 0f,
                                    enableBlur = false,
                                    allowChromaticAberration = true,
                                    chromaticAberrationAtRest = true,
                                    pressScalesRefraction = true,
                                    refractionFloor = 0.42f
                                )
                                vibrancy()
                                if (params.blurPx > 0f) blur(params.blurPx)
                                if (params.useLens) {
                                    lens(
                                        refractionHeight = params.refractionHeightPx,
                                        refractionAmount = params.refractionAmountPx,
                                        chromaticAberration = params.chromaticAberration
                                    )
                                }
                            },
                            highlight = { Highlight.Default.copy(alpha = 0.32f) },
                            shadow = { Shadow(radius = 10.dp, color = shadowColor) },
                            // drawBackdrop 一直有这个参数但从未传：缺了内阴影，
                            // 玻璃就只是一层贴纸，没有壁厚
                            innerShadow = {
                                InnerShadow(
                                    radius = 8.dp,
                                    offset = DpOffset(0.dp, 2.dp),
                                    color = Color.Black.copy(
                                        alpha = if (isLightTheme) 0.07f else 0.16f
                                    )
                                )
                            },
                            onDrawSurface = { drawRect(surfaceColor) }
                        )
                            // 壁纸是平滑渐变，折射无内容可折射；边缘光不依赖背景，
                            // 是这里唯一稳定成立的玻璃特征
                            .glassRim(shape, intensity = 0.9f, isLightTheme = isLightTheme)
                    } else {
                        Modifier
                            .clip(shape)
                            .background(fallbackSurface)
                            .border(width = 0.5.dp, color = borderColor, shape = shape)
                    }
                )
                .pointerInput(phase) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                // 右滑越过阈值只是"收起"，不是抹掉：
                                // 通知本身是 cookie 失效的持续状态，不该被一次手势永久丢弃
                                if (dismissOffset.value >= dismissThresholdPx) {
                                    phase = NoticePhase.Collapsed
                                }
                                dismissOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = 0.72f,
                                        stiffness = 520f
                                    )
                                )
                            }
                        },
                        onDragCancel = {
                            scope.launch { dismissOffset.animateTo(0f) }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                dismissOffset.snapTo(
                                    (dismissOffset.value + dragAmount).coerceAtLeast(0f)
                                )
                            }
                        }
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = {
                        if (expanded) notice.onClick() else phase = NoticePhase.Expanded
                    }
                )
                .semantics {
                    contentDescription = if (expanded) {
                        "${notice.message}，${notice.actionLabel}，右滑收起"
                    } else {
                        "${notice.message}，点击展开"
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // 展开态内容：收拢过程中不重排，保持自然尺寸整体淡出
            Row(
                modifier = Modifier
                    .wrapContentSize(align = Alignment.Center, unbounded = true)
                    .graphicsLayer { alpha = capsuleAlpha }
                    .onSizeChanged {
                        if (it.width > 0) capsuleWidth = with(density) { it.width.toDp() }
                    }
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = notice.message,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = notice.actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isLightTheme) IOSBlueLight else IOSBlueDark
                )
            }

            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier
                    .graphicsLayer { alpha = pillAlpha }
                    .size(13.dp)
            )
        }
    }
}