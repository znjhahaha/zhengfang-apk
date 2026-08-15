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
import androidx.compose.ui.draw.drawBehind
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
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.theme.GlassArcHighlight
import com.tyust.course.ui.theme.GlassBorderDark
import com.tyust.course.ui.theme.GlassBorderLight
import com.tyust.course.ui.theme.GlassHighlight
import com.tyust.course.ui.theme.GlassOverlay
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

/**
 * 兼容路径的静态高光只提示上缘，不模拟额外折射或内阴影。
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassHighlight(
    highlightAlpha: Float = 0.12f,
    spotAlpha: Float = 0f
): Modifier = this.drawBehind {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = highlightAlpha),
                Color.Transparent
            ),
            startY = 0f,
            endY = size.height * 0.28f
        )
    )
}

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
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()

    // iOS 26 分离式头部：展开态大标题直接浮在内容上（无背景），
    // 滚动折叠时标题缩小、浮出细玻璃条。折叠进度由滚动偏移连续驱动、
    // 全程跟手；高刚度临界阻尼弹簧只负责抹平 LazyList 快滚时的跳变。
    val collapse by animateFloatAsState(
        targetValue = collapseFraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 1f, stiffness = 900f),
        label = "headerCollapse"
    )
    val surfaceTint = if (isLightTheme) {
        Color.White.copy(alpha = 0.62f)
    } else {
        Color.Black.copy(alpha = 0.46f)
    }

    Column(modifier = Modifier.fillMaxWidth().reportNoticeAnchor()) {
        val showShell = collapse > 0.01f
        val shellModifier = when {
            useGlass && backdrop != null && showShell -> Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(0.dp) },
                effects = {
                    vibrancy()
                    val radius = 10.dp.toPx() * collapse
                    if (radius > 0.5f) blur(radius)
                },
                onDrawSurface = { drawRect(surfaceTint.copy(alpha = surfaceTint.alpha * collapse)) }
            )
            !useGlass && showShell -> Modifier.background(
                MaterialTheme.colorScheme.surface.copy(alpha = collapse)
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
                            Brush.verticalGradient(
                                colors = listOf(
                                    surfaceTint.copy(alpha = surfaceTint.alpha * 0.85f * (1f - collapse)),
                                    surfaceTint.copy(alpha = surfaceTint.alpha * 0.35f * (1f - collapse)),
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
                        color = MaterialTheme.colorScheme.onSurface,
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
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
        // scroll edge effect：折叠后底缘软渐隐，内容滚入头部时平滑没入
        if (useGlass && showShell) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                surfaceTint.copy(alpha = surfaceTint.alpha * 0.5f * collapse),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

private fun lerpDp(start: Dp, stop: Dp, fraction: Float): Dp =
    start + (stop - start) * fraction

private fun lerpSp(startSp: Float, stopSp: Float, fraction: Float) =
    (startSp + (stopSp - startSp) * fraction).sp

/** 分离式头部右上角的独立玻璃圆钮。 */
@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    backdrop: Backdrop? = LocalControlBackdrop.current
) {
    val useGlass = backdrop != null && isBackdropSupported()
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.9f else 1f,
        animationSpec = MotionSpring.liquidTap(),
        label = "glassCircleScale"
    )
    val surfaceColor = if (isLightTheme) {
        Color.White.copy(alpha = 0.55f)
    } else {
        Color.Black.copy(alpha = 0.38f)
    }
    val shellModifier = if (useGlass && backdrop != null) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                vibrancy()
                blur(8.dp.toPx())
            },
            onDrawSurface = { drawRect(surfaceColor) }
        )
    } else {
        Modifier
            .clip(CircleShape)
            .background(
                if (isLightTheme) Color.White.copy(alpha = 0.85f)
                else Color(0xFF2C2C2E).copy(alpha = 0.85f)
            )
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(shellModifier)
            .clickable(
                interactionSource = interactionSource,
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
            modifier = Modifier.size(size * 0.48f),
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
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
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
    enabled: Boolean = true
) {
    LiquidSegmentedControl(
        options = options,
        selectedIndex = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop
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
    // 主按钮使用高饱和 tint 覆盖中性折射层，保留玻璃边缘和按压形变。
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = LiquidButtonStyle.Tinted,
        tint = NeuPrimary,
        shape = RoundedCornerShape(16.dp),
        cornerRadius = 16.dp
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
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
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = LiquidButtonStyle.Surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        cornerRadius = 16.dp
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
        style = LiquidButtonStyle.Tinted,
        tint = SemanticDanger,
        shape = RoundedCornerShape(16.dp),
        cornerRadius = 16.dp
    ) {
        if (leadingIcon != null) leadingIcon()
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
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
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
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
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = contentDescription, tint = tint)
    }
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
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    LiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        style = if (primary) LiquidButtonStyle.Tinted else LiquidButtonStyle.Surface,
        tint = NeuPrimary,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
        cornerRadius = 12.dp,
        minHeight = 36.dp,
        horizontalPadding = 12.dp
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
        androidx.compose.runtime.DisposableEffect(Unit) {
            dialogHost.show(onDismissRequest, dialogBody)
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
    val dialogCorner = GlassRecipe.DialogCornerDp.dp
    val dialogShape = RoundedCornerShape(dialogCorner)
    val accessibility = rememberGlassAccessibilityMode()
    val dialogMaterial = GlassMaterials.resolve(
        role = GlassMaterialRole.Modal,
        accessibility = accessibility
    )
    val glassBackdrop = backdrop?.takeIf { useVisualEffects && isBackdropSupported() }
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    // 模态卡片是玻璃而不是实色板：只保留一层弱中性表面，让身后画面经 blur/lens 透出。
    val dialogSurfaceColor = if (isLightTheme) {
        Color(0xFFF4F5F7).copy(alpha = 0.30f)
    } else {
        Color(0xFF1E2024).copy(alpha = 0.34f)
    }
    val dialogBorderColor = Color.White.copy(
        alpha = if (isLightTheme) 0.64f else 0.16f
    )
    val dialogShadowColor = Color.Black.copy(alpha = dialogMaterial.shadowAlpha)

    val dialogLayerBackdrop = rememberLayerBackdrop()
    val nestedControlBackdrop = if (glassBackdrop != null) {
        rememberCombinedBackdrop(glassBackdrop, dialogLayerBackdrop)
    } else {
        null
    }

    Box(modifier = Modifier.width(320.dp)) {
        if (glassBackdrop != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .layerBackdrop(dialogLayerBackdrop)
                    .drawBackdrop(
                        backdrop = glassBackdrop,
                        shape = { dialogShape },
                        // 与官方控件同源的模板管线：vibrancy → blur → lens，
                        // 参数取 Modal 材质档，不再走 resolvePhysicalLens 自定义组合。
                        effects = {
                            vibrancy()
                            if (isRuntimeShaderTrulySupported()) {
                                blur(GlassRecipe.DialogBlurDp.dp.toPx())
                                lens(
                                    refractionHeight = GlassRecipe.DialogRefractionHeightDp.dp.toPx(),
                                    refractionAmount = GlassRecipe.DialogRefractionAmountDp.dp.toPx()
                                )
                            } else {
                                blur((GlassRecipe.DialogBlurDp * 2f).dp.toPx())
                            }
                        },
                        // 仅保留模板需要的光学表面和阴影，不再叠加常驻边缘高光描边。
                        highlight = { null },
                        shadow = { Shadow(alpha = dialogMaterial.shadowAlpha) },
                        onDrawSurface = { drawRect(dialogSurfaceColor) }
                    )
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
                    .background(if (isLightTheme) Color(0xFFE0E2E6) else Color(0xFF25272B))
                    .border(0.75.dp, dialogBorderColor, dialogShape)
            )
        }

        CompositionLocalProvider(LocalControlBackdrop provides nestedControlBackdrop) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (title != null) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        title()
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
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
                Spacer(modifier = Modifier.height(24.dp))
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
