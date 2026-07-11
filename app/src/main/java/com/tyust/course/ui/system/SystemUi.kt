package com.tyust.course.ui.system

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
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
import kotlin.math.roundToInt

val PagePadding = 20.dp
val SectionSpacing = 20.dp
val CardPadding = 16.dp

// ═══════════════════════════════════════════════════════════
// 新拟态阴影 Modifier
// 参考模板：Neumorphism 新拟态 — 左上角亮影+右下角暗影
// ═══════════════════════════════════════════════════════════

/**
 * 新拟态凸起阴影效果
 * 通过在 Canvas 中绘制两层带 BlurMaskFilter 的圆角矩形，
 * 模拟光源从左上方照射时产生的凸起立体感。
 *
 * @param cornerRadius 圆角半径
 * @param elevation 阴影偏移量（越大越明显）
 * @param lightColor 左上角光源亮影色
 * @param darkColor 右下角环境暗影色
 */
fun Modifier.neumorphicShadow(
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 6.dp,
    lightColor: Color = NeuLightShadow,
    darkColor: Color = NeuDarkShadow
): Modifier = this
    .graphicsLayer { clip = false }
    .drawBehind {
        val elevationPx = elevation.toPx()
        // BlurMaskFilter 不接受 radius <= 0，直接跳过绘制
        if (elevationPx <= 0f) return@drawBehind
        val blurPx = (elevationPx * 2f).coerceAtLeast(0.1f)
        val radiusPx = cornerRadius.toPx()

        // 组件本体对应的圆角矩形路径（大小不变）
        val basePath = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, size.width, size.height),
                    radiusX = radiusPx,
                    radiusY = radiusPx
                )
            )
        }

        drawIntoCanvas { canvas ->
            // 暗影：向右下偏移，模拟遮挡面
            val darkPaint = Paint().also { paint ->
                paint.color = darkColor.copy(alpha = 0.55f)
                paint.asFrameworkPaint().apply {
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
            canvas.save()
            canvas.translate(elevationPx, elevationPx)
            canvas.drawPath(basePath, darkPaint)
            canvas.restore()

            // 亮影：向左上偏移，模拟光照面
            val lightPaint = Paint().also { paint ->
                paint.color = lightColor.copy(alpha = 0.80f)
                paint.asFrameworkPaint().apply {
                    isAntiAlias = true
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
            }
            canvas.save()
            canvas.translate(-elevationPx, -elevationPx)
            canvas.drawPath(basePath, lightPaint)
            canvas.restore()
        }
    }

/**
 * 液态玻璃高光效果 Modifier
 * 在组件上方绘制：
 * 1. 顶部水平渐变高光条（模拟玻璃表面反射）
 * 2. 左上角径向光斑（模拟水滴折射光点）
 * 3. 底部微弱内阴影（增加玻璃深度感）
 */
fun Modifier.glassHighlight(
    highlightAlpha: Float = 0.22f,
    spotAlpha: Float = 0.12f
): Modifier = this.drawBehind {
    // 1. 顶部高光条纹：模拟光源从上方照射玻璃的反射
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                GlassHighlight.copy(alpha = highlightAlpha),
                GlassHighlight.copy(alpha = highlightAlpha * 0.35f),
                Color.Transparent
            ),
            startY = 0f,
            endY = size.height * 0.45f
        )
    )
    // 2. 左上角径向光斑：模拟水滴折射的聚焦光点
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = spotAlpha),
                Color.Transparent
            ),
            center = Offset(size.width * 0.12f, size.height * 0.15f),
            radius = size.height * 0.7f
        )
    )
    // 3. 底部微弱阴影：增加玻璃厚度感
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                GlassBorderDark.copy(alpha = 0.06f)
            ),
            startY = size.height * 0.75f,
            endY = size.height
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
    val fallbackModifier = Modifier.background(MaterialTheme.colorScheme.surface)
    val backgroundModifier = if (useGlass && backdrop != null) {
        runCatching {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    vibrancy()
                    blur(GlassRecipe.TopBarBlurDp.dp.toPx())
                    if (isLensSupported()) {
                        lens(
                            refractionHeight = GlassRecipe.TopBarLensRefractionHeightDp.dp.toPx(),
                            refractionAmount = GlassRecipe.TopBarLensRefractionAmountDp.dp.toPx()
                        )
                    }
                },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = GlassRecipe.TopBarSurfaceAlpha))
                }
            )
        }.getOrElse { fallbackModifier }
    } else {
        fallbackModifier
    }
    Column(
        modifier = backgroundModifier
    ) {
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
    modifier: Modifier = Modifier
) {
    val trackShape = RoundedCornerShape(14.dp)
    val indicatorShape = RoundedCornerShape(10.dp)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NeuInsetBackground,
        shape = trackShape,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp)
        ) {
            val optionCount = options.size.coerceAtLeast(1)
            val gapWidth = 5.dp * (optionCount - 1)
            val slotWidth = (maxWidth - gapWidth) / optionCount

            val indicatorOffset by animateDpAsState(
                targetValue = slotWidth * selectedIndex + 5.dp * selectedIndex,
                animationSpec = androidx.compose.animation.core.spring(
                    dampingRatio = 0.82f,
                    stiffness = 280f
                ),
                label = "segmentIndicator"
            )

            // 指示器：带阴影和微描边的浮层
            Surface(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(slotWidth)
                    .height(38.dp),
                shape = indicatorShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 3.dp,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f))
            ) {}

            Row(
                modifier = Modifier.fillMaxWidth().height(38.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val textColor by androidx.compose.animation.animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        animationSpec = com.tyust.course.ui.theme.MotionSpecs.standard(),
                        label = "segmentTextColor"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(indicatorShape)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { onSelect(index) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = textColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val activeColor = NeuPrimary
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = MotionSpring.bounce(),
        label = "btnPressScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp).scale(pressScale),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = activeColor),
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp
        )
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(8.dp))
        }
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = MotionSpring.bounce(),
        label = "secBtnPressScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp).scale(pressScale),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = NeuSurface,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        ),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(8.dp))
        }
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = MotionSpring.bounce(),
        label = "destBtnPressScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp).scale(pressScale),
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = SemanticDanger),
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 1.dp,
            pressedElevation = 0.dp
        )
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(8.dp))
        }
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
    val buttonShape = RoundedCornerShape(12.dp)
    val containerColor = if (primary) NeuPrimary else NeuSurface

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
        shape = buttonShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = if (primary) 1.dp else 0.dp,
        border = if (!primary) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)) else null
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
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
    val dialogShape = RoundedCornerShape(32.dp)
    val useGlass = useVisualEffects && backdrop != null && isBackdropSupported()

    val fallbackModifier = Modifier
        .width(320.dp)
        .then(
            if (useVisualEffects) {
                Modifier.neumorphicShadow(cornerRadius = 32.dp, elevation = 12.dp)
            } else {
                Modifier.shadow(elevation = 6.dp, shape = dialogShape, clip = false)
            }
        )
        .clip(dialogShape)
        .background(MaterialTheme.colorScheme.surface)
        .padding(24.dp)
    val modifier = if (useGlass && backdrop != null) {
        runCatching {
            Modifier
                .width(320.dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { dialogShape },
                    effects = {
                        vibrancy()
                        if (isLensSupported()) {
                            lens(
                                refractionHeight = 16f.dp.toPx(),
                                refractionAmount = 32f.dp.toPx()
                            )
                        }
                    }
                )
                .padding(24.dp)
        }.getOrElse { fallbackModifier }
    } else {
        fallbackModifier
    }

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
