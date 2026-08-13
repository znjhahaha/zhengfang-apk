package com.tyust.course.ui.system

import android.graphics.BlurMaskFilter
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.tyust.course.ui.system.glass.DampedDragAnimation
import com.tyust.course.ui.system.glass.resolvePhysicalLens
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
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    backdrop: Backdrop? = LocalAppBackdrop.current
) {
    val useGlass = backdrop != null && isBackdropSupported()
    val isLightTheme = !androidx.compose.foundation.isSystemInDarkTheme()

    val barContent: @Composable () -> Unit = {
        TopAppBar(
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 副标题前缀圆点指示器
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f)
                            )
                        }
                    }
                }
            },
            navigationIcon = { navigationIcon?.invoke() },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }

    if (useGlass && backdrop != null) {
        val accessibility = rememberGlassAccessibilityMode()
        val barMaterial = GlassMaterials.resolve(
            role = GlassMaterialRole.Navigation,
            accessibility = accessibility
        )
        val surfaceTint = if (isLightTheme) {
            Color.White.copy(alpha = 0.60f)
        } else {
            Color.Black.copy(alpha = 0.44f)
        }
        Column {
            // 真玻璃顶栏：模糊采样壁纸/内容层，内容可从下方穿过
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(0.dp) },
                        effects = {
                            vibrancy()
                            if (barMaterial.blurDp > 0f) blur(barMaterial.blurDp.dp.toPx())
                        },
                        onDrawSurface = { drawRect(surfaceTint) }
                    )
            ) {
                barContent()
            }
            // scroll edge effect：底缘软渐隐，内容滚入顶栏时平滑没入
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                surfaceTint.copy(alpha = surfaceTint.alpha * 0.55f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    } else {
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            barContent()
            // 渐变分割线 + 底部柔和环境光
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                GlassBorderLight.copy(alpha = 0.40f),
                                GlassBorderLight.copy(alpha = 0.40f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
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

    val cardShape = RoundedCornerShape(20.dp)

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

    Surface(
        modifier = clickableModifier,
        shape = cardShape,
        color = backgroundColor,
        border = BorderStroke(
            width = 0.5.dp,
            color = borderColor.copy(alpha = 0.15f)
        ),
        shadowElevation = if (isPressed) 1.dp else 2.dp,
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
    SystemCard(
        modifier = modifier,
        backgroundColor = NeuInsetBackground,
        borderColor = NeuDivider,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { (label, value) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    backdrop: Backdrop? = LocalAppBackdrop.current,
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
    backdrop: Backdrop? = LocalAppBackdrop.current,
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
        height = 32.dp
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
    backdrop: Backdrop? = LocalAppBackdrop.current
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
    val insideGlass = LocalInsideGlassSurface.current
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = if (insideGlass) LiquidButtonStyle.SolidTinted else LiquidButtonStyle.Tinted,
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
    val insideGlass = LocalInsideGlassSurface.current
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = if (insideGlass) LiquidButtonStyle.SolidSurface else LiquidButtonStyle.Surface,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
    val insideGlass = LocalInsideGlassSurface.current
    LiquidButton(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        enabled = enabled,
        style = if (insideGlass) LiquidButtonStyle.SolidTinted else LiquidButtonStyle.Tinted,
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
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
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = NeuPrimary,
            trackColor = NeuInsetBackground
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
    backdrop: Backdrop? = LocalAppBackdrop.current
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

/**
 * 标记当前组合处于玻璃容器（弹窗等）之上。
 * System*Button 读取后自动切换为实色填充，避免玻璃叠玻璃发糊。
 */
val LocalInsideGlassSurface = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 物理流体玻璃对话框 — 通过 DialogHost portal 在同窗口内渲染，
 * 使 backdrop 折射正常工作。当 portal 不可用时回退到 Dialog()。
 */
@Composable
fun SystemDialog(
    onDismissRequest: () -> Unit,
    backdrop: Backdrop? = LocalAppBackdrop.current,
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
            dialogBody()
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
    val dialogSurfaceColor = MaterialTheme.colorScheme.surface.copy(
        alpha = dialogMaterial.surfaceAlpha
    )
    val dialogBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(
        alpha = dialogMaterial.borderAlpha
    )
    val dialogShadowColor = Color.Black.copy(alpha = dialogMaterial.shadowAlpha)

    val fallbackModifier = Modifier
        .width(320.dp)
        .shadow(
            elevation = GlassRecipe.DialogShadowElevationDp.dp,
            shape = dialogShape,
            clip = false,
            ambientColor = dialogShadowColor,
            spotColor = dialogShadowColor
        )
        .clip(dialogShape)
        .background(MaterialTheme.colorScheme.surface)
        .border(1.dp, dialogBorderColor, dialogShape)
        .padding(24.dp)
    val modifier = if (glassBackdrop != null) {
        Modifier
            .width(320.dp)
            .drawBackdrop(
                backdrop = glassBackdrop,
                shape = { dialogShape },
                effects = {
                    val params = resolvePhysicalLens(
                        density = this,
                        material = dialogMaterial,
                        shape = dialogShape,
                        minCornerRadiusPx = dialogCorner.toPx(),
                        minDimensionPx = size.minDimension,
                        interactionProgress = 0f,
                        enableBlur = true,
                        allowChromaticAberration = false
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
                shadow = { Shadow(alpha = dialogMaterial.shadowAlpha) },
                onDrawSurface = { drawRect(dialogSurfaceColor) }
            )
            .border(1.dp, dialogBorderColor, dialogShape)
            .padding(24.dp)
    } else {
        fallbackModifier
    }

    CompositionLocalProvider(LocalInsideGlassSurface provides true) {
        Column(
            modifier = modifier,
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
