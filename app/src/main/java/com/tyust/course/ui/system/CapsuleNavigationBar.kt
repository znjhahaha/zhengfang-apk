package com.tyust.course.ui.system

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyust.course.BottomNavItem
import com.tyust.course.ui.theme.GlassBorderDark
import com.tyust.course.ui.theme.GlassBorderLight
import com.tyust.course.ui.theme.GlassHighlight
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuInsetBackground
import com.tyust.course.ui.theme.NeuPrimary
import com.tyust.course.ui.theme.NeuSurface

/**
 * 胶囊导航栏 — 新拟态 + 液态玻璃版本
 *
 * 结构：一个被 clip(RoundedCornerShape) 裁剪的真胶囊容器，
 * 上层绘制液态玻璃高光条纹，内部放置选中指示器和导航项 Row。
 * 所有内容都被胶囊边界裁剪，不会溢出。
 */
@Composable
fun CapsuleNavigationBar(
    items: List<BottomNavItem>,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val capsuleShape = RoundedCornerShape(28.dp)
    val trackHeight = 60.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                // 新拟态凸起阴影
                .neumorphicShadow(cornerRadius = 28.dp, elevation = 10.dp)
                // 整个容器为真正的胶囊形状 clip
                .clip(capsuleShape)
                .background(NeuSurface)
                // 液态玻璃高光层：顶部半透明白色渐变（增强效果）
                .drawBehind {
                    // 顶部高光条纹（提升alpha使效果更显著）
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                GlassHighlight.copy(alpha = 0.55f),
                                GlassHighlight.copy(alpha = 0.20f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = size.height * 0.50f
                        )
                    )
                    // 左上角微妙的液态折射光斑
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.30f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.15f, size.height * 0.20f),
                            radius = size.height * 0.8f
                        )
                    )
                    // 双色边框效果：顶部亮 + 底部暗
                    val borderWidth = 1.dp.toPx()
                    // 顶部亮线
                    drawLine(
                        color = GlassBorderLight.copy(alpha = 0.45f),
                        start = Offset(28.dp.toPx(), 0f),
                        end = Offset(size.width - 28.dp.toPx(), 0f),
                        strokeWidth = borderWidth
                    )
                    // 底部暗线
                    drawLine(
                        color = GlassBorderDark.copy(alpha = 0.30f),
                        start = Offset(28.dp.toPx(), size.height),
                        end = Offset(size.width - 28.dp.toPx(), size.height),
                        strokeWidth = borderWidth
                    )
                }
        ) {
            val slotCount = items.size.coerceAtLeast(1)
            val slotWidth = maxWidth / slotCount
            // 胶囊指示器宽度略小于插槽
            val indicatorHPad = 5.dp
            val indicatorWidth = slotWidth - (indicatorHPad * 2)
            val indicatorHeight = trackHeight - 12.dp

            // 带弹性动画的偏移
            val animatedOffset by animateDpAsState(
                targetValue = slotWidth * selectedTab + indicatorHPad,
                animationSpec = MotionSpring.gentle(),
                label = "navIndicatorOffset"
            )

            // 选中指示器：内凹背景 + 半透明渐变
            Box(
                modifier = Modifier
                    .offset(x = animatedOffset)
                    .align(Alignment.CenterStart)
                    .width(indicatorWidth)
                    .height(indicatorHeight)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                NeuInsetBackground,
                                NeuInsetBackground.copy(alpha = 0.80f)
                            )
                        )
                    )
                    .drawBehind {
                        // 顶部反光带（增强玻璃厚度感）
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                startY = 0f,
                                endY = size.height * 0.45f
                            )
                        )
                        // 底部微弱内阴影（模拟凹陷玻璃片效果）
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.08f)
                                ),
                                startY = size.height * 0.5f,
                                endY = size.height
                            )
                        )
                    }
            )

            // 导航项 Row
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, item ->
                    CapsuleNavItem(
                        item = item,
                        selected = selectedTab == index,
                        onClick = { onTabSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CapsuleNavItem(
    item: BottomNavItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 选中时图标略微放大
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = MotionSpring.bounce(),
        label = "navIconScale"
    )
    // 选中时整体微微上浮
    val contentOffsetY by animateDpAsState(
        targetValue = if (selected) (-3).dp else 0.dp,
        animationSpec = MotionSpring.gentle(),
        label = "navContentOffset"
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected)
            NeuPrimary
        else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        animationSpec = com.tyust.course.ui.theme.MotionSpecs.standard(),
        label = "navIconTint"
    )
    val labelTint by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.onSurface
        else
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        animationSpec = com.tyust.course.ui.theme.MotionSpecs.navLabel(),
        label = "navLabelTint"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.offset(y = contentOffsetY),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconTint,
                modifier = Modifier
                    .size(21.dp)
                    .scale(iconScale)
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = item.label,
                color = labelTint,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
