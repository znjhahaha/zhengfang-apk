package com.tyust.course.announcement

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 公告弹窗 - 优化版支持丝滑动画
 */
@Composable
fun AnnouncementDialog(
    announcement: AnnouncementManager.Announcement,
    onDismiss: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    
    // 启动时触发动画
    LaunchedEffect(Unit) {
        isVisible = true
    }

    // 根据类型选择颜色和图标
    val (primaryColor, secondaryColor, icon) = when (announcement.type) {
        "warning" -> Triple(Color(0xFFFF9800), Color(0xFFFFF3E0), Icons.Default.Warning)
        "important" -> Triple(Color(0xFFF44336), Color(0xFFFFEBEE), Icons.Default.Campaign)
        else -> Triple(Color(0xFF2196F3), Color(0xFFE3F2FD), Icons.Default.Info)
    }

    Dialog(
        onDismissRequest = { 
            isVisible = false
            onDismiss() 
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { 
                    isVisible = false
                    onDismiss() 
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn() + scaleIn(
                    initialScale = 0.8f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ),
                exit = fadeOut() + scaleOut(targetScale = 0.9f)
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth(0.95f)
                        .padding(16.dp)
                        .clickable(enabled = false) { }, // 防止点击穿透
                    shape = RoundedCornerShape(32.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(16.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        // 顶部背景装饰小图
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(secondaryColor, Color.White)
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 悬浮感图标
                            Surface(
                                shape = CircleShape,
                                color = Color.White,
                                shadowElevation = 8.dp,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(secondaryColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(36.dp),
                                        tint = primaryColor
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            // 标题使用更有冲击力的样式
                            Text(
                                text = announcement.title,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = Color(0xFF1A1A1A),
                                textAlign = TextAlign.Center,
                                letterSpacing = 0.5.sp
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // 可滚动的正文内容，限制最大高度
                            val scrollState = rememberScrollState()
                            Box(
                                modifier = Modifier
                                    .weight(weight = 1f, fill = false)
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(scrollState)
                            ) {
                                Text(
                                    text = announcement.content,
                                    fontSize = 16.sp,
                                    color = Color(0xFF555555),
                                    textAlign = TextAlign.Start, // 改为左对齐更专业
                                    lineHeight = 26.sp,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            // 按钮样式强化
                            Button(
                                onClick = { 
                                    isVisible = false
                                    onDismiss() 
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryColor
                                ),
                                shape = RoundedCornerShape(18.dp),
                                elevation = ButtonDefaults.buttonElevation(
                                    defaultElevation = 4.dp,
                                    pressedElevation = 0.dp
                                )
                            ) {
                                Text(
                                    text = "确认",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                        }

                        // 顶层关闭按钮
                        IconButton(
                            onClick = { 
                                isVisible = false
                                onDismiss() 
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.8f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                modifier = Modifier.size(20.dp),
                                tint = Color(0xFF666666)
                            )
                        }
                    }
                }
            }
        }
    }
}
