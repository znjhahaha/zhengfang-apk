package com.tyust.course.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.tyust.course.ui.theme.SystemBlue
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val iconColor: Color
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val pages = listOf(
        OnboardingPage(
            icon = Icons.Default.Search,
            title = "智能搜索课程",
            description = "快速搜索全校开放课程\n支持按课程名、教师名、时间段精确筛选",
            iconColor = com.tyust.course.ui.theme.SystemBlue
        ),
        OnboardingPage(
            icon = Icons.Default.PlayArrow,
            title = "一键抢课引擎",
            description = "设置目标课程后高频自动循环获取\n支持多课程队列，成功后平滑切换",
            iconColor = com.tyust.course.ui.theme.SystemBlue
        ),
        OnboardingPage(
            icon = Icons.Default.DateRange,
            title = "极简可视课表",
            description = "系统级极简体验展示每周课程安排\n脱离校园网也能查看并支持导出至日历",
            iconColor = com.tyust.course.ui.theme.SystemBlue
        ),
        OnboardingPage(
            icon = Icons.Default.Star,
            title = "探索完整功能",
            description = "授权安全会话凭证录入\n即刻进入纯粹工具主义体验",
            iconColor = com.tyust.course.ui.theme.SystemBlue
        )
    )
    
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.tyust.course.ui.theme.Neutral50)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip Button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (pagerState.currentPage < pages.size - 1) {
                    TextButton(onClick = onFinish) {
                        Text(
                            "跳过", 
                            color = com.tyust.course.ui.theme.Neutral500,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
            
            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                OnboardingPageContent(page = pages[pageIndex])
            }
            
            // Page Indicators (Spring fluid stretch)
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                pages.forEachIndexed { index, _ ->
                    val isSelected = pagerState.currentPage == index
                    val width by animateDpAsState(
                        targetValue = if (isSelected) 28.dp else 8.dp,
                        animationSpec = androidx.compose.animation.core.spring(
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow, 
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy
                        ),
                        label = "indicator_width"
                    )
                    val color by animateColorAsState(
                        targetValue = if (isSelected) com.tyust.course.ui.theme.SystemBlue else com.tyust.course.ui.theme.Neutral200,
                        animationSpec = tween(300),
                        label = "indicator_color"
                    )
                    
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(color)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Bottom Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                if (pagerState.currentPage == pages.size - 1) {
                    // Last page: Start button
                    Button(
                        onClick = onFinish,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.tyust.course.ui.theme.SystemBlue,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            "开始使用",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    // Not last page: Next button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(
                                    page = pagerState.currentPage + 1,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy
                                    )
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = com.tyust.course.ui.theme.Neutral100,
                            contentColor = com.tyust.course.ui.theme.Neutral900
                        ),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(
                            "下一步",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon (Clean Geometry aesthetic)
        Surface(
            modifier = Modifier.size(140.dp),
            shape = RoundedCornerShape(36.dp),
            color = Color.White,
            shadowElevation = 16.dp,
            tonalElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Background subtle ring
                Box(
                    modifier = Modifier.size(80.dp).background(page.iconColor.copy(alpha = 0.1f), CircleShape)
                )
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = page.iconColor
                )
            }
        }
        
        Spacer(modifier = Modifier.height(56.dp))
        
        // Title
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = com.tyust.course.ui.theme.Neutral900,
            letterSpacing = (-0.5).sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Description
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = com.tyust.course.ui.theme.Neutral500,
            textAlign = TextAlign.Center,
            lineHeight = 28.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
