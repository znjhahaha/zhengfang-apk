package com.tyust.course.ui.system

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuPrimary

/**
 * 液态玻璃基础组件套件。
 * 全部采用纯半透 alpha 混合（零 backdrop 采样开销），
 * 多彩壁纸自然透出即呈现"薄玻璃"层次；真折射透镜只保留给交互滑块与导航层。
 */

private val GlassCardShape = RoundedCornerShape(24.dp)
private val GlassRowShape = RoundedCornerShape(16.dp)

@Composable
private fun glassSurfaceColor(): Color {
    return if (!isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.62f)
    } else {
        Color(0xFF1C1C1E).copy(alpha = 0.55f)
    }
}

@Composable
private fun glassBorderColor(): Color {
    return if (!isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.55f)
    } else {
        Color.White.copy(alpha = 0.12f)
    }
}

/** 玻璃数字胶囊：大数值 + 小标签，替代灰底统计块。 */
@Composable
fun GlassStatChip(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(glassSurfaceColor())
            .border(0.5.dp, glassBorderColor(), RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** iOS 设置风格的彩色圆角图标块。 */
@Composable
fun GlassIconChip(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.27f))
            .background(tint),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(size * 0.62f)
        )
    }
}

/** iOS inset grouped 分组容器：圆角玻璃组，行间自带两端淡出分割线。 */
@Composable
fun InsetGroupedSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!header.isNullOrBlank()) {
            Text(
                text = header,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 18.dp, bottom = 7.dp)
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GlassCardShape)
                .background(glassSurfaceColor())
                .border(0.5.dp, glassBorderColor(), GlassCardShape)
                .animateContentSize(animationSpec = MotionSpring.liquidSettle()),
            content = content
        )
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 18.dp, top = 7.dp)
            )
        }
    }
}

/** inset grouped 行：图标 chip + 标题/副标题 + 尾随内容，分割线避开图标区两端淡出。 */
@Composable
fun InsetGroupedRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = NeuPrimary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailing: @Composable (() -> Unit)? = null,
    showDivider: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (icon != null) {
                GlassIconChip(icon = icon, tint = iconTint)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) titleColor else titleColor.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    trailing()
                }
            }
        }
        if (showDivider) {
            val dividerColor = MaterialTheme.colorScheme.outlineVariant
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (icon != null) 58.dp else 16.dp)
                    .height(0.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                dividerColor.copy(alpha = 0.45f),
                                dividerColor.copy(alpha = 0.45f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}

/** 玻璃进度条：半透槽 + 高饱和圆角填充。 */
@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    tint: Color = NeuPrimary,
    height: Dp = 8.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = MotionSpring.liquidSettle(),
        label = "glassProgress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (!isSystemInDarkTheme()) Color.White.copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.14f)
            )
    ) {
        if (animated > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(tint.copy(alpha = 0.85f), tint)
                        )
                    )
            )
        }
    }
}

/** 分段玻璃条：按权重分宽的彩色圆角段（成绩分布等），段间留白。 */
@Composable
fun GlassSegmentedBar(
    segments: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val total = segments.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for ((weight, color) in segments) {
            if (weight <= 0f) continue
            Box(
                modifier = Modifier
                    .weight(weight / total)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(color.copy(alpha = 0.9f))
            )
        }
    }
}

/** 无描边填充式玻璃输入框：半透面 + 聚焦主色光边，替代 Material TextField。 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    minHeight: Dp = 46.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isLightTheme = !isSystemInDarkTheme()
    val borderAlpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = MotionSpring.liquidTap(),
        label = "glassFieldFocus"
    )
    val shape = GlassRowShape
    val textColor = MaterialTheme.colorScheme.onSurface
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(shape)
            .background(
                if (isLightTheme) Color.White.copy(alpha = 0.55f)
                else Color.White.copy(alpha = 0.10f)
            )
            .border(
                width = if (isFocused) 1.5.dp else 0.5.dp,
                color = androidx.compose.ui.graphics.lerp(
                    glassBorderColor(),
                    NeuPrimary.copy(alpha = 0.85f),
                    borderAlpha
                ),
                shape = shape
            ),
        enabled = enabled,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        interactionSource = interactionSource,
        textStyle = LocalTextStyle.current.copy(
            color = textColor,
            fontSize = 15.sp
        ),
        cursorBrush = SolidColor(NeuPrimary),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = LocalTextStyle.current.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            maxLines = 1
                        )
                    }
                    innerTextField()
                }
                if (trailing != null) trailing()
            }
        }
    )
}

/**
 * 玻璃加载指示器：半透玻璃圆片托着一段旋转圆弧。
 * 取代 Material 默认的裸转圈，让加载态与液态玻璃层次一致。
 */
@Composable
fun GlassLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    arcColor: Color = NeuPrimary
) {
    val transition = rememberInfiniteTransition(label = "glassLoading")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing)
        ),
        label = "glassLoadingRotation"
    )
    val isLightTheme = !isSystemInDarkTheme()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (isLightTheme) Color.White.copy(alpha = 0.55f)
                else Color(0xFF1C1C1E).copy(alpha = 0.50f)
            )
            .border(0.5.dp, glassBorderColor(), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(size * 0.5f)
                .graphicsLayer { rotationZ = rotation }
        ) {
            val stroke = 2.5.dp.toPx()
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        arcColor.copy(alpha = 0f),
                        arcColor.copy(alpha = 0.9f)
                    )
                ),
                startAngle = 30f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/** 页面级加载态：玻璃指示器 + 文案，垂直居中排布。 */
@Composable
fun GlassLoadingState(
    text: String = "加载中…",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        GlassLoadingIndicator()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 玻璃下拉刷新容器：替代 Material 默认的下拉转圈，
 * 玻璃圆片随下拉浮现放大，刷新中持续旋转。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GlassPullRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val state = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = state,
        modifier = modifier,
        indicator = {
            val fraction = state.distanceFraction.coerceIn(0f, 1f)
            if (isRefreshing || fraction > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 10.dp)
                        .graphicsLayer {
                            val f = if (isRefreshing) 1f else fraction
                            alpha = f
                            scaleX = 0.6f + 0.4f * f
                            scaleY = 0.6f + 0.4f * f
                        }
                ) {
                    GlassLoadingIndicator(size = 40.dp)
                }
            }
        },
        content = content
    )
}
