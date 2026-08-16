package com.tyust.course.ui.screen

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAddCheck
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.drawWallpaperPattern
import com.tyust.course.ui.system.glass.glassChip
import com.tyust.course.ui.system.glass.glassSheet
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.theme.MotionSpring
import com.tyust.course.ui.theme.NeuPrimary
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String
)

/**
 * 首次启动引导。
 *
 * 它跑在【登录之后】（见 `MainActivity` 的渲染分支），所以文案讲的是"这个 App 能做什么"，
 * 不是"接下来去登录"。视觉上与登录页同源：同一张流体壁纸做采样底，卡片用
 * [glassSheet]（Modal 角色的整块玻璃），图标砖用不采样的 [glassChip]。
 *
 * 玻璃层是内容的【兄弟】节点，不是父节点——`layerBackdrop` 会捕获所在节点的整棵子树，
 * 挂在包含玻璃卡的父节点上就会自采样，RenderThread 直接死循环。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val pages = remember {
        listOf(
            OnboardingPage(
                icon = Icons.Outlined.CalendarMonth,
                title = "课表与成绩",
                description = "课表、成绩与考试安排集中在一处查看；数据会缓存到本地，网络不稳时也能继续阅读。"
            ),
            OnboardingPage(
                icon = Icons.AutoMirrored.Outlined.PlaylistAddCheck,
                title = "选课与抢课",
                description = "支持单门、队列与定时任务，按你设定的规则持续尝试，成功后自动推进到下一个目标。"
            ),
            OnboardingPage(
                icon = Icons.Outlined.Shield,
                title = "账号与会话",
                description = "密码经系统密钥库加密后仅保存在本机，用于登录状态失效时自动续期；可在「设置 → 账号管理」中随时删除。"
            ),
            OnboardingPage(
                icon = Icons.Outlined.RocketLaunch,
                title = "开始使用",
                description = "适用于采用正方教务系统的学校，也可以自行添加所在学校的教务域名。"
            )
        )
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val accessibility = rememberGlassAccessibilityMode()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind { drawRect(AppearanceSettingsManager.style.baseColor) }
    ) {
        // 每处都在【绘制 lambda 内部】读 state：rememberLayerBackdrop 没有 key，
        // 捕获外面的快照会让图片壁纸异步解码完成后这一层不重绘。
        val backdrop = if (isBackdropSupported()) {
            rememberLayerBackdrop {
                drawWallpaperPattern(AppearanceSettingsManager.style)
                drawContent()
            }
        } else {
            null
        }

        // 采样源：一个只画壁纸的空节点，与下面的内容列同级
        if (backdrop != null) {
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop))
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawWallpaperPattern(AppearanceSettingsManager.style)
            }
        }

        CompositionLocalProvider(
            LocalAppBackdrop provides backdrop,
            LocalControlBackdrop provides backdrop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = PagePadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 高度恒定：最后一页藏掉「跳过」时不能让下面整块跳一下
                Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    if (!isLastPage) {
                        TextButton(
                            onClick = onFinish,
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Text(
                                text = "跳过",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { pageIndex ->
                    // 页偏移：0 = 正中，±1 = 相邻页。视差按它取值，reduceMotion 时恒为 0
                    val pageOffset = if (accessibility.reduceMotion) {
                        0f
                    } else {
                        ((pagerState.currentPage - pageIndex) +
                            pagerState.currentPageOffsetFraction).coerceIn(-1f, 1f)
                    }
                    OnboardingPageContent(
                        page = pages[pageIndex],
                        backdrop = backdrop,
                        pageOffset = pageOffset
                    )
                }

                PagerCapsuleIndicator(
                    pageCount = pages.size,
                    currentPage = pagerState.currentPage,
                    reduceMotion = accessibility.reduceMotion
                )

                SystemPrimaryButton(
                    text = if (isLastPage) "开始使用" else "下一步",
                    onClick = {
                        if (isLastPage) {
                            onFinish()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 当前页是一枚 26dp 胶囊，其余是 8dp 圆点；宽度用弹簧过渡，读起来像液体被拉长。 */
@Composable
private fun PagerCapsuleIndicator(
    pageCount: Int,
    currentPage: Int,
    reduceMotion: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            val targetWidth = if (selected) 26.dp else 8.dp
            val width by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = if (reduceMotion) {
                    androidx.compose.animation.core.snap()
                } else {
                    MotionSpring.liquidSettle()
                },
                label = "onboardingIndicatorWidth"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(width)
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) {
                            NeuPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                        }
                    )
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    backdrop: Backdrop?,
    pageOffset: Float
) {
    val fade = 1f - (pageOffset.absoluteValue * 0.5f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val sheetShape = RoundedCornerShape(28.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 卡片本身跟手，内部元素走不同系数 → 视差
                .graphicsLayer { alpha = fade }
                .then(
                    if (backdrop != null) {
                        Modifier.glassSheet(backdrop = backdrop, cornerRadius = 28.dp)
                    } else {
                        Modifier
                            .clip(sheetShape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    }
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                Box(
                    modifier = Modifier
                        .graphicsLayer { translationX = pageOffset * 56f }
                        .size(84.dp)
                        .glassChip(shape = RoundedCornerShape(26.dp), elevation = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = NeuPrimary,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Column(
                    modifier = Modifier.graphicsLayer { translationX = pageOffset * 24f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = page.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}
