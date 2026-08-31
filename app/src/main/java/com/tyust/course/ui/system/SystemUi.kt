package com.tyust.course.ui.system

import android.graphics.BlurMaskFilter
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.tyust.course.ui.system.glass.adaptiveGlassChip
import com.tyust.course.ui.system.glass.applyChipContentDeformation
import com.tyust.course.ui.system.glass.applyPressSquash
import com.tyust.course.ui.system.glass.glassRim
import com.tyust.course.ui.system.glass.rememberInteractiveOptics
import com.tyust.course.ui.theme.GlassArcHighlight
import com.tyust.course.ui.theme.GlassBorderDark
import com.tyust.course.ui.theme.GlassBorderLight
import com.tyust.course.ui.theme.GlassHighlight
import com.tyust.course.ui.theme.GlassOverlay
import com.tyust.course.ui.theme.IOSBlueDark
import com.tyust.course.ui.theme.IOSBlueLight
import com.tyust.course.ui.theme.IOSRedDark
import com.tyust.course.ui.theme.IOSRedLight
import com.tyust.course.ui.theme.NeuDarkShadow
import com.tyust.course.ui.theme.NeuDivider
import com.tyust.course.ui.theme.NeuInsetBackground
import com.tyust.course.ui.theme.NeuLightShadow
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.NeuSurface
import com.tyust.course.ui.theme.Neutral200
import com.tyust.course.ui.theme.Neutral500
import com.tyust.course.ui.theme.Neutral900
import com.tyust.course.ui.theme.SemanticDanger
import com.tyust.course.ui.theme.SemanticDangerContainer
import com.tyust.course.ui.theme.SemanticInfo
import com.tyust.course.ui.theme.SemanticInfoContainer
import com.tyust.course.ui.theme.SemanticSuccess
import com.tyust.course.ui.theme.SemanticSuccessContainer
import com.tyust.course.ui.theme.SemanticWarning
import com.tyust.course.ui.theme.SemanticWarningContainer
import com.tyust.course.ui.theme.SurfaceWhite
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.tyust.course.ui.system.glass.LocalGlassLensAnchor
import com.tyust.course.ui.system.glass.LocalGlassLensModalAnchor
import com.tyust.course.ui.system.glass.drawBackdropSource
import com.tyust.course.ui.system.glass.glassLensAnchor
import com.tyust.course.ui.system.glass.glassLens
import com.tyust.course.ui.system.glass.glassLensOpticsFrom
import com.tyust.course.ui.system.glass.lensCornerRadiusPx
import kotlinx.coroutines.delay

val PagePadding = 20.dp
val SectionSpacing = 20.dp
val CardPadding = 16.dp

// ═══════════════════════════════════════════════════════════
// 新拟态阴影 Modifier
// 参考模板：Neumorphism 新拟态 — 左上角亮影+右下角暗影
// ═══════════════════════════════════════════════════════════

/**
 * 兼容路径只保留单层环境阴影，不再绘制新拟态的双向光晕。
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.neumorphicShadow(
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 6.dp,
    lightColor: Color = NeuLightShadow,
    darkColor: Color = NeuDarkShadow
): Modifier = this.shadow(
    elevation = elevation,
    shape = RoundedCornerShape(cornerRadius),
    clip = false,
    ambientColor = darkColor.copy(alpha = 0.16f),
    spotColor = darkColor.copy(alpha = 0.22f)
)

enum class SystemTone {
    Info,
    Success,
    Warning,
    Danger,
    Neutral
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SystemTopBar(
    title: String,
    subtitle: String? = null,
    collapseFraction: Float = 0f,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backdrop: Backdrop? = LocalAppBackdrop.current
) {
    val useGlass = backdrop != null && isBackdropSupported()
    val regionState = rememberWallpaperRegionState()
    val appearance = rememberWallpaperRegionAppearance(regionState)
    val isLightTheme = appearance.usesDarkForeground
    val titleColor = appearance.onSurface

    // iOS 26 分离式头部：展开态大标题直接浮在内容上（无背景），
    // 滚动折叠时标题缩小、浮出细玻璃条。折叠进度由滚动偏移连续驱动、
    // 全程跟手；高刚度临界阻尼弹簧只负责抹平 LazyList 快滚时的跳变。
    val collapse by animateFloatAsState(
        targetValue = collapseFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "headerCollapse"
    )
    val surfaceTint = appearance.surface.copy(
        alpha = maxOf(appearance.surface.alpha, if (isLightTheme) 0.46f else 0.34f)
    )

    ProvideWallpaperAppearance(appearance) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wallpaperRegion(regionState)
            .reportNoticeAnchor()
    ) {
        val showShell = collapse > 0.01f
        val shellModifier = when {
            useGlass && backdrop != null && showShell -> Modifier
                // 下缘【齐边】收尾：渐隐抹在模糊结果上只是把"模糊的那一份"按 alpha 混到
                // 清晰的原图上，两份图像叠在一起就是一条重影带（iOS 渐变的是模糊半径，
                // 一次 drawBackdrop 做不到）。玻璃条本来就该有边——那圈默认高光已关掉。
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RoundedCornerShape(0.dp) },
                    effects = {
                        vibrancy()
                        val radius = 10.dp.toPx() * collapse
                        if (radius > 0.5f) blur(radius)
                    },
                    // 库的默认值不是 null：全宽直角矩形的那圈边缘高光在屏幕上只剩
                    // "标题下面一道亮线"。投影同理，而 Offscreen 遮罩也会把它裁掉。
                    highlight = { null },
                    shadow = { null },
                    innerShadow = { null },
                    onDrawSurface = {
                        // 平铺即可：渐隐由 glassEdgeFadeBottom 统一做，
                        // 这里再叠一条渐变会让尾巴衰减得比线性更快。
                        drawRect(surfaceTint.copy(alpha = surfaceTint.alpha * collapse))
                    }
                )
            !useGlass && showShell -> Modifier.background(
                Brush.verticalGradient(
                    0f to appearance.solidSurface.copy(alpha = collapse),
                    0.86f to appearance.solidSurface.copy(alpha = collapse),
                    1f to Color.Transparent
                )
            )
            else -> Modifier
        }
        Box(modifier = Modifier.fillMaxWidth().then(shellModifier)) {
            // 展开态：大标题背后铺一层自上而下的软渐变，
            // 内容滚入标题区域时被渐隐吞没而不是直接撞字；随折叠淡出交棒给玻璃条。
            if (collapse < 0.99f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            // 四个色标、尾巴拉长：三色标那版在渐变末端有一处可见的斜率突变，
                            // 在壁纸上读成"标题块的下边界"。
                            Brush.verticalGradient(
                                colors = listOf(
                                    surfaceTint.copy(alpha = surfaceTint.alpha * 0.85f * (1f - collapse)),
                                    surfaceTint.copy(alpha = surfaceTint.alpha * 0.45f * (1f - collapse)),
                                    surfaceTint.copy(alpha = surfaceTint.alpha * 0.12f * (1f - collapse)),
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(
                        start = PagePadding,
                        end = PagePadding,
                        top = lerpDp(10.dp, 6.dp, collapse),
                        bottom = lerpDp(10.dp, 8.dp, collapse)
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                navigationIcon?.invoke()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = lerpSp(28f, 17f, collapse),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = titleColor,
                        maxLines = 1
                    )
                    if (!subtitle.isNullOrBlank()) {
                        // 折叠时副标题高度收拢并渐隐
                        Box(
                            modifier = Modifier
                                .height(lerpDp(22.dp, 0.dp, collapse))
                                .graphicsLayer { alpha = (1f - collapse * 1.6f).coerceIn(0f, 1f) },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(NeuPrimary.copy(alpha = 0.65f))
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = appearance.onSurfaceVariant.copy(alpha = 0.80f),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions
                )
            }
        }
    }
    }
}

// lerpDp / lerpSp 在同包的 CollapsingGlassHeader.kt 里（internal），课表与成绩的
// 折叠顶栏也用同两个，不再各留一份私有副本。

/** 分离式头部右上角的独立玻璃圆钮。 */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    tint: Color = LocalWallpaperAppearanceColors.current.onSurface,
    backdrop: Backdrop? = LocalControlBackdrop.current
) {
    val accessibility = rememberGlassAccessibilityMode()
    val optics = rememberInteractiveOptics()
    Box(
        modifier = modifier
            .size(size)
            // 与顶栏图标按钮同一套芯片：有 backdrop 时走真折射，
            // 无 backdrop（纯色区域）时自动退回边缘光，调用方无需关心
            .adaptiveGlassChip(
                backdrop = backdrop,
                shape = CircleShape,
                optics = optics,
                enabled = enabled,
                interactive = enabled
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size * 0.48f)
                .graphicsLayer {
                    if (accessibility.reduceMotion) return@graphicsLayer
                    // 与玻璃层同一段行程、同向各向异性，否则拖动时图标会脱出
                    applyChipContentDeformation(
                        optics = optics,
                        travelPx = GlassRecipe.ChipDragTravelDp.dp.toPx(),
                        stretch = GlassRecipe.ChipDragStretch,
                        pressDepth = GlassRecipe.ChipIconPressDepth,
                        damping = GlassRecipe.ChipContentDeformDamping
                    )
                },
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}

@Composable
fun SystemCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(CardPadding),
    backdrop: Backdrop? = LocalAppBackdrop.current,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = MotionSpring.bounce(),
        label = "cardScale"
    )

    val cardShape = RoundedCornerShape(24.dp)

    val baseModifier = modifier
        .fillMaxWidth()
        .scale(pressScale)

    val clickableModifier = if (onClick != null) {
        baseModifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        baseModifier
    }

    // 调用方普遍显式传 colorScheme.surface；把"默认白面"语义映射为半透玻璃面
    // （纯 alpha 混合零采样开销，多彩壁纸自然透出），特殊色卡保持原色。
    val isLightTheme = !rememberGlassDarkTheme()
    val translucent = backgroundColor == MaterialTheme.colorScheme.surface
    val effectiveColor = when {
        !translucent -> backgroundColor
        isLightTheme -> Color.White.copy(alpha = 0.62f)
        else -> Color(0xFF1C1C1E).copy(alpha = 0.55f)
    }
    val effectiveBorder = if (translucent) {
        Color.White.copy(alpha = if (isLightTheme) 0.55f else 0.12f)
    } else {
        borderColor.copy(alpha = 0.15f)
    }

    Surface(
        modifier = clickableModifier,
        shape = cardShape,
        color = effectiveColor,
        border = BorderStroke(
            width = 0.5.dp,
            color = effectiveBorder
        ),
        // 半透玻璃面必须关 elevation 阴影：RenderNode 阴影会透过半透表面显形为白蒙层
        shadowElevation = when {
            translucent -> 0.dp
            isPressed -> 1.dp
            else -> 2.dp
        },
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
fun SystemSectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!actionLabel.isNullOrBlank() && onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun SystemListItem(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = onClick != null, onClick = onClick ?: {})
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                leadingIcon()
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(12.dp))
            trailingContent()
        }
    }
}

@Composable
fun SystemDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = NeuDivider
    )
}

@Composable
fun SystemStatStrip(
    modifier: Modifier = Modifier,
    items: List<Pair<String, String>>
) {
    // 玻璃数字胶囊横排：替代灰底凹陷统计条
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { (label, value) ->
            GlassStatChip(
                value = value,
                label = label,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun SystemStatusBadge(
    text: String,
    tone: SystemTone = SystemTone.Info,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val (containerColor, contentColor) = when (tone) {
        SystemTone.Info -> SemanticInfoContainer to SemanticInfo
        SystemTone.Success -> SemanticSuccessContainer to SemanticSuccess
        SystemTone.Warning -> SemanticWarningContainer to SemanticWarning
        SystemTone.Danger -> SemanticDangerContainer to SemanticDanger
        SystemTone.Neutral -> NeuInsetBackground to Neutral500
    }

    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SystemSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * 轨道高度。默认值就是原来的写死值，所以现有调用点全部不受影响。
     * 别传 36dp 及以下：`LiquidSegmentedControl` 在 `height <= 36.dp` 会翻到
     * compact 排版档，中途换档观感很突兀。
     */
    height: Dp = 52.dp
) {
    LiquidSegmentedControl(
        options = options,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop,
        height = height
    )
}

@Composable
fun SystemCompactSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop? = LocalControlBackdrop.current,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    LiquidSegmentedControl(
        options = options,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop,
        height = 36.dp
    )
}

@Composable
fun SystemPicker(
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "请选择",
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    backdrop: Backdrop? = LocalControlBackdrop.current
) {
    LiquidPicker(
        options = options.map(::LiquidPickerOption),
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        enabled = enabled,
        leadingIcon = leadingIcon,
        actionLabel = actionLabel,
        onAction = onAction,
        backdrop = backdrop
    )
}

@Composable
fun SystemPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    // iOS filled button：不透明实色胶囊。按压回缩与灰罩由全局 GlassPressIndication 统一提供。
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = LiquidButtonStyle.SolidTinted,
        tint = if (!rememberGlassDarkTheme()) IOSBlueLight else IOSBlueDark,
        shape = Capsule()
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SystemSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val appearance = LocalWallpaperAppearanceColors.current
    // iOS gray-fill：不透明浅灰胶囊，文字用 onSurface 实色，保证任何底色上都清晰。
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = LiquidButtonStyle.SolidSurface,
        contentColor = appearance.onSurface,
        shape = Capsule()
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SystemDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = LiquidButtonStyle.SolidTinted,
        tint = if (!rememberGlassDarkTheme()) IOSRedLight else IOSRedDark,
        shape = Capsule()
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SystemEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    val isLightTheme = !rememberGlassDarkTheme()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 玻璃圆底托一个占位圆点，让空态与液态层次一致
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isLightTheme) Color.White.copy(alpha = 0.50f)
                    else Color.White.copy(alpha = 0.10f)
                )
                .border(
                    0.5.dp,
                    Color.White.copy(alpha = if (isLightTheme) 0.55f else 0.14f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (action != null) {
            Spacer(modifier = Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun SystemLoadingState(
    text: String = "加载中…",
    modifier: Modifier = Modifier
) {
    GlassLoadingState(text = text, modifier = modifier)
}

@Composable
fun SystemIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    /** 顶栏/工具栏级别的图标按钮默认带玻璃芯片；行内小按钮传 false。 */
    chip: Boolean = true,
    backdrop: Backdrop? = LocalControlBackdrop.current
) {
    AnimatedIconButton(
        onClick = onClick,
        icon = icon,
        contentDescription = contentDescription,
        enabled = enabled,
        tint = tint,
        chip = chip,
        backdrop = backdrop
    )
}

@Composable
fun SystemActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalControlBackdrop.current
) {
    val contentColor = if (primary) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        style = if (primary) LiquidButtonStyle.SolidTinted else LiquidButtonStyle.SolidSurface,
        tint = if (!rememberGlassDarkTheme()) IOSBlueLight else IOSBlueDark,
        contentColor = contentColor,
        shape = Capsule(),
        minHeight = 36.dp,
        horizontalPadding = 14.dp
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SystemCapacityIndicator(
    selected: Int,
    capacity: Int,
    modifier: Modifier = Modifier
) {
    val safeCapacity = capacity.coerceAtLeast(0)
    val safeSelected = selected.coerceAtLeast(0)
    val ratio = if (safeCapacity > 0) safeSelected.toFloat() / safeCapacity.toFloat() else 0f
    val normalizedRatio = ratio.coerceIn(0f, 1f)
    val remaining = (safeCapacity - safeSelected).coerceAtLeast(0)
    val isFull = safeCapacity > 0 && safeSelected >= safeCapacity
    val isNearFull = !isFull && safeCapacity > 0 && ratio >= 0.85f
    val indicatorColor = when {
        isFull -> SemanticDanger
        isNearFull -> SemanticWarning
        else -> NeuPrimary
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LinearProgressIndicator(
            progress = { normalizedRatio },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = indicatorColor,
            trackColor = NeuInsetBackground
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (safeCapacity > 0) "$safeSelected/$safeCapacity" else "$safeSelected/--",
                style = MaterialTheme.typography.labelSmall,
                color = if (isFull) SemanticDanger else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = when {
                    safeCapacity <= 0 -> "容量未知"
                    isFull -> "已满"
                    else -> "余 $remaining"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isFull || isNearFull) indicatorColor else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        if (safeCapacity > 0) {
            Text(
                text = "占用 ${(normalizedRatio * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }
    }
}

/**
 * 柔和的玻璃态分割线（两端淡出）
 * 替代原生生硬的 HorizontalDivider
 */
@Composable
fun SystemDivider(
    modifier: Modifier = Modifier,
    alpha: Float = 0.5f
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        NeuDivider.copy(alpha = alpha),
                        NeuDivider.copy(alpha = alpha),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
fun DisablePlatformDialogDim() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.setDimAmount(0f)
        onDispose { }
    }
}

/**
 * 物理流体玻璃对话框 — 通过 DialogHost portal 在同窗口内渲染，
 * 使 backdrop 折射正常工作。当 portal 不可用时回退到 Dialog()。
 */
@Composable
fun SystemDialog(
    onDismissRequest: () -> Unit,
    backdrop: Backdrop? = LocalAppBackdrop.current ?: LocalModalBackdrop.current ?: LocalControlBackdrop.current,
    useVisualEffects: Boolean = true,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val dialogHost = LocalDialogHost.current

    val dialogBody: @Composable () -> Unit = {
        SystemDialogContent(
            backdrop = backdrop,
            useVisualEffects = useVisualEffects,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            content = content
        )
    }

    if (dialogHost != null) {
        // 【必须过 rememberUpdatedState】dialogHost.show 只在挂载时调用一次，
        // 直接把 dialogBody 交出去，Host 拿到的就永远是第一次组合时那个闭包——
        // 闭包里按值捕获的东西（传进来的参数、当次算出的 val）之后再也不会更新。
        // 大多数弹窗看不出来（内容开着的时候不变），但背景取色那种"拖一下就变"的
        // 内容会整块定格。这里让 Host 读一个 State，闭包换新它就重组。
        val currentBody by androidx.compose.runtime.rememberUpdatedState(dialogBody)
        androidx.compose.runtime.DisposableEffect(Unit) {
            dialogHost.show(onDismissRequest) { currentBody() }
            onDispose { dialogHost.dismiss() }
        }
    } else {
        Dialog(onDismissRequest = onDismissRequest) {
            DisablePlatformDialogDim()
            // 平台窗口路径没有 DialogHost，自己补同一套淡入缩放，避免弹窗硬切出现。
            var cardVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                cardVisible = true
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = cardVisible,
                enter = androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.scaleIn(initialScale = 0.92f),
                exit = androidx.compose.animation.fadeOut(
                        animationSpec = androidx.compose.animation.core.tween(150)
                    ) +
                        androidx.compose.animation.scaleOut(
                            targetScale = 0.92f,
                            animationSpec = androidx.compose.animation.core.tween(150)
                        )
            ) {
                dialogBody()
            }
        }
    }
}

@Composable
private fun SystemDialogContent(
    backdrop: Backdrop?,
    useVisualEffects: Boolean,
    confirmButton: @Composable (() -> Unit)?,
    dismissButton: @Composable (() -> Unit)?,
    icon: @Composable (() -> Unit)?,
    title: @Composable (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    // iOS squircle：连续曲率，避免圆弧与直边交界处的折角感
    val dialogShape = RoundedRectangle(
        cornerRadius = GlassRecipe.DialogCornerDp.dp,
        style = RoundedCornerStyle.Continuous
    )
    val accessibility = rememberGlassAccessibilityMode()
    val dialogMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Modal,
        accessibility = accessibility
    )
    val glassBackdrop = backdrop?.takeIf { useVisualEffects && isBackdropSupported() }
    val regionState = rememberWallpaperRegionState()
    val appearance = rememberWallpaperRegionAppearance(regionState)
    val isLightTheme = appearance.usesDarkForeground
    // 模态卡片是玻璃而不是实色板：只保留一层弱中性表面，让身后画面经 blur/lens 透出。
    val dialogSurfaceColor = appearance.surface.copy(
        alpha = maxOf(appearance.surface.alpha, 0.30f)
    )
    val dialogBorderColor = appearance.border
    val dialogShadowColor = Color.Black.copy(alpha = dialogMaterial.shadowAlpha)

    val dialogLayerBackdrop = rememberLayerBackdrop()
    val nestedControlBackdrop = if (glassBackdrop != null) {
        rememberCombinedBackdrop(glassBackdrop, dialogLayerBackdrop)
    } else {
        null
    }

    // ## API 31/32
    //
    // 卡片本体折射身后的页面，用**模糊版**的 App 全局区域：33+ 这里的管线是
    // vibrancy → blur → lens，lens 采的是已经模糊过的像素，而那层模糊只能烤进
    // 底图（见 LocalGlassLensModalAnchor）。
    //
    // 卡片**里面**没有再建区域：弹窗内的按钮一律是 SolidSurface / SolidTinted
    // （见 SystemPrimaryButton，实色是刻意的，"避免玻璃叠玻璃发糊"），
    // 它们的 glassBackdrop 为 null，压根不折射；`glassChip` 也只有 alpha + 描边。
    // 所以那份区域会是一张没人读的全屏快照（8MB + 每次开弹窗约 5ms）。
    //
    // 将来若真往弹窗里放会折射的控件：它采样的是 `combined(页面, 卡片自己)`，
    // 得在这里建一份区域、底图重建那个组合、取景框挂在卡片这个 Box 上
    // （卡片有 20~24dp 内边距，比控件大一圈，边缘位移采样不会跑出底图），
    // 再用 `LocalGlassLensAnchor provides` 覆盖掉全局那份 —— 全局那份只有壁纸，
    // 直接拿来会让控件折射出"卡片不存在"的画面。
    val dialogDensity = LocalDensity.current
    val panelLensAnchor = LocalGlassLensModalAnchor.current

    // 320dp 是设计宽度；窄屏上按屏宽收，两侧至少留 20dp，不顶满边缘。
    val screen = rememberScreenMetrics()
    val dialogWidth = minOf(320.dp, screen.widthDp - 40.dp)
    // 短屏把卡片内边距与两处间距一起收，合计给正文多让出约 30dp。
    val dialogHorizontalPadding = screen.tall(24.dp, 20.dp)
    val dialogVerticalPadding = screen.tall(24.dp, 18.dp)
    val dialogHeaderGap = screen.tall(16.dp, 12.dp)
    val dialogButtonGap = screen.tall(24.dp, 16.dp)

    Box(
        modifier = Modifier
            .width(dialogWidth)
            .wallpaperRegion(regionState)
    ) {
        if (glassBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layerBackdrop(dialogLayerBackdrop)
                    // 31/32：折射由自家着色器在这里完成，就着 App 全局区域的底图。
                    // 必须在 drawBackdrop **上游**，它下面那层会把背景再画一遍。
                    .glassLens(
                        panelLensAnchor,
                        optics = { w, h ->
                            glassLensOpticsFrom(
                                material = dialogMaterial,
                                density = dialogDensity,
                                cornerRadiusPx = lensCornerRadiusPx(
                                    dialogShape, w, h, dialogDensity
                                ),
                                minDimensionPx = minOf(w, h),
                                // 静态面板：不随按压变化，也不要静止色散
                                pressScalesRefraction = false,
                                chromaticAberrationAtRest = false
                            )
                        }
                    )
                    .drawBackdrop(
                        backdrop = glassBackdrop,
                        shape = { dialogShape },
                        // 与官方控件同源的模板管线：vibrancy → blur → lens，
                        // 参数取 Modal 材质档，不再走 resolvePhysicalLens 自定义组合。
                        effects = {
                            vibrancy()
                            if (isRuntimeLensEnabled()) {
                                blur(GlassRecipe.DialogBlurDp.dp.toPx())
                                lens(
                                    refractionHeight = GlassRecipe.DialogRefractionHeightDp.dp.toPx(),
                                    refractionAmount = GlassRecipe.DialogRefractionAmountDp.dp.toPx()
                                )
                            } else if (panelLensAnchor != null) {
                                // 31/32：什么都不加。模糊已经烤进底图（见
                                // LocalGlassLensModalAnchor），折射也已在上游画完，
                                // 这里再 blur 是对着空图层做的，白付一次离屏。
                                //
                                // 原先这条分支是 2× 模糊 —— 那是"这台机器没有折射"
                                // 时的补偿；现在有折射了，补偿要一起撤，否则是
                                // 糊 + 折射叠着，比 33+ 糊一倍。
                            } else {
                                blur((GlassRecipe.DialogBlurDp * 2f).dp.toPx())
                            }
                        },
                        onDrawBackdrop = { drawBackdrop ->
                            // 31/32 上背景已由 glassLens 以折射方式画过
                            if (panelLensAnchor == null) drawBackdrop()
                        },
                        // 仅保留模板需要的光学表面和阴影，不再叠加常驻边缘高光描边。
                        highlight = { null },
                        shadow = { Shadow(alpha = dialogMaterial.shadowAlpha) },
                        onDrawSurface = { drawRect(dialogSurfaceColor) }
                    )
                    // 库的 Highlight shader 只认 CornerBasedShape，对连续曲率 squircle
                    // 会退化成 minDimension/2 的圆角，高光位置整体错位。
                    // 这里直接按 outline 描边，圆角多大就贴多大。
                    .glassRim(dialogShape, intensity = 0.9f, isLightTheme = isLightTheme)
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .shadow(
                        elevation = GlassRecipe.DialogShadowElevationDp.dp,
                        shape = dialogShape,
                        clip = false,
                        ambientColor = dialogShadowColor,
                        spotColor = dialogShadowColor
                    )
                    .clip(dialogShape)
                    .background(appearance.solidSurface)
                    .border(0.75.dp, dialogBorderColor, dialogShape)
            )
        }

        CompositionLocalProvider(LocalControlBackdrop provides nestedControlBackdrop) {
        ProvideWallpaperAppearance(appearance) {
            Column(
                modifier = Modifier.padding(
                    horizontal = dialogHorizontalPadding,
                    vertical = dialogVerticalPadding
                ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.height(dialogHeaderGap))
            }
            if (title != null) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides appearance.onSurface
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        title()
                    }
                }
                Spacer(modifier = Modifier.height(dialogHeaderGap))
            }
            Box(
                modifier = Modifier
                    .weight(weight = 1f, fill = false)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = content
                )
            }
            if (confirmButton != null || dismissButton != null) {
                Spacer(modifier = Modifier.height(dialogButtonGap))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (dismissButton != null) {
                        Box(modifier = Modifier.weight(1f)) {
                            dismissButton()
                        }
                    }
                    if (confirmButton != null) {
                        Box(modifier = Modifier.weight(1f)) {
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}
}
}

/**
 * 物理流体玻璃确认弹窗 (SystemConfirmDialog)
 * 快速平替原生的 AlertDialog，内置大圆角取消与确认物理玻璃按钮。
 */
@Composable
fun SystemConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    showCancel: Boolean = true
) {
    SystemDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        confirmButton = {
            SystemPrimaryButton(
                text = confirmText,
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth()
            )
        },
        dismissButton = if (showCancel) {
            {
                SystemSecondaryButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else null
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
