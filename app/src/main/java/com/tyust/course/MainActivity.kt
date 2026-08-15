package com.tyust.course

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tyust.course.activation.ActivationManager
import com.tyust.course.activation.ActivationScreen
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.WallpaperPreset
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.OnboardingScreen
import com.tyust.course.ui.screen.SchoolAdaptationCompletionReminder
import com.tyust.course.ui.system.CapsuleNavigationBar
import com.tyust.course.ui.system.DialogHost
import com.tyust.course.ui.system.GlassToastHost
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.drawWallpaperPattern
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.system.LocalDialogHost
import com.tyust.course.ui.system.LocalModalBackdrop
import com.tyust.course.ui.system.LocalFloatingNotice
import com.tyust.course.ui.system.LocalNoticeAnchor
import com.tyust.course.ui.system.NoticeAnchorState
import com.tyust.course.ui.system.LocalControlBackdrop
import com.tyust.course.ui.system.FloatingNotice
import com.tyust.course.ui.system.FloatingNoticeHost
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberDialogHostState
import com.tyust.course.ui.system.rememberGlassAccessibilityMode
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.ui.theme.MotionEasing
import com.tyust.course.update.UpdateDialog
import com.tyust.course.update.rememberUpdateState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.ui.system.SystemDialog
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton
import kotlin.math.roundToInt

class MainActivity : FragmentActivity() {

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UserManager.getInstance().init(this)

        val userManager = UserManager.getInstance()
        if (userManager.hasSavedCookie() && userManager.currentSchool != null) {
            com.tyust.course.network.CourseApiClient.getInstance().setCookie(
                userManager.currentSchool.baseUrl,
                userManager.savedCookie
            )
        }

        SmartSelector.getInstance().init(this)
        com.tyust.course.network.CourseApiClient.getInstance().init(this)

        if (!UserManager.getInstance().isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasSeenOnboarding = prefs.getBoolean(KEY_HAS_SEEN_ONBOARDING, false)

        setContent {
            CourseSelectorTheme {
                var showOnboarding by remember { mutableStateOf(!hasSeenOnboarding) }
                var activationState by remember { mutableIntStateOf(0) }

                LaunchedEffect(Unit) {
                    val activated = ActivationManager.checkActivation(this@MainActivity)
                    activationState = if (activated) 2 else 1
                }

                when {
                    activationState == 0 -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            SystemLoadingState(text = "正在检查设备授权…")
                        }
                    }

                    activationState == 1 -> {
                        ActivationScreen(onActivated = { activationState = 2 })
                    }

                    showOnboarding -> {
                        OnboardingScreen(
                            onFinish = {
                                prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, true).apply()
                                showOnboarding = false
                            }
                        )
                    }

                    else -> {
                        MainScreen(fragmentActivity = this@MainActivity)
                    }
                }
            }
        }
    }
}

sealed class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val label: String
) {
    object Courses : BottomNavItem("courses", Icons.AutoMirrored.Filled.List, "课程")
    object Schedule : BottomNavItem("schedule", Icons.Default.DateRange, "课表")
    object Grab : BottomNavItem("grab", Icons.Default.PlayArrow, "抢课")
    object Grades : BottomNavItem("grades", Icons.Default.Star, "成绩/考试")
    object Settings : BottomNavItem("settings", Icons.Default.Settings, "设置")
}

private data class MainPageTarget(
    val accountStorageKey: String,
    val tabIndex: Int
)

@Composable
fun MainScreen(fragmentActivity: FragmentActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    
    val hasStarred = prefs.getBoolean("has_starred", false)
    val dismissCount = prefs.getInt("star_dismiss_count", 0)
    var showStarDialog by remember { mutableStateOf(!hasStarred && dismissCount < 3) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var currentAccountStorageKey by remember { mutableStateOf(UserManager.getInstance().currentAccountStorageKey) }
    val accessibility = rememberGlassAccessibilityMode()
    val items = listOf(
        BottomNavItem.Courses,
        BottomNavItem.Schedule,
        BottomNavItem.Grab,
        BottomNavItem.Grades,
        BottomNavItem.Settings
    )
    val updateState = rememberUpdateState()
    var isTokenExpired by remember { mutableStateOf(false) }

    // 底栏滚动最小化：捕获页面内任意滚动的方向（nested scroll 冒泡，页面零改动）
    var navBarMinimized by remember { mutableStateOf(false) }
    val navBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    if (available.y < -8f) {
                        navBarMinimized = true
                    } else if (available.y > 8f) {
                        navBarMinimized = false
                    }
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(selectedTab) { navBarMinimized = false }

    LaunchedEffect(Unit) {
        updateState.checkForUpdate()
    }

    DisposableEffect(fragmentActivity) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED) {
                    val eventAccountKey = intent.getStringExtra(com.tyust.course.network.CourseApiClient.EXTRA_ACCOUNT_STORAGE_KEY).orEmpty()
                    val currentAccountKey = UserManager.getInstance().currentAccountStorageKey
                    if (eventAccountKey.isNotEmpty() && eventAccountKey != currentAccountKey) return
                    // 幂等闸门：已在提醒态就不重复弹，避免多次 401 连环触发。
                    // Toast 由过期处理链自带（GradesRoute.handleExpiredCookie），此处只驱动 Banner。
                    isTokenExpired = true
                }
            }
        }
        val filter = android.content.IntentFilter(com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED)
        ContextCompat.registerReceiver(
            fragmentActivity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 启动全局 Cookie 定期检查
        val school = com.tyust.course.manager.UserManager.getInstance().currentSchool
        if (school != null && com.tyust.course.manager.UserManager.getInstance().isLoggedIn) {
            com.tyust.course.utils.CookieWatchdog.start(fragmentActivity)
        }
        onDispose {
            com.tyust.course.utils.CookieWatchdog.stop()
            fragmentActivity.unregisterReceiver(receiver)
        }
    }

    val updateInfo = updateState.updateInfo()
    SchoolAdaptationCompletionReminder(
        enabled = !showStarDialog && !updateState.showDialog(),
        accountScopeKey = currentAccountStorageKey
    )
    if (updateState.showDialog() && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo,
            currentVersion = updateState.getCurrentVersion(),
            onDismiss = { updateState.dismiss() },
            onUpdate = { updateState.startDownload() },
            downloadProgress = updateState.downloadProgress(),
            isDownloading = updateState.isDownloading()
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val useGlass = isBackdropSupported()
        val tokenExpiredNotice = if (isTokenExpired) {
            FloatingNotice(
                message = "登录已过期",
                actionLabel = "重新登录",
                onClick = {
                    val intent = Intent(fragmentActivity, LoginActivity::class.java)
                    intent.putExtra("force_relogin", true)
                    fragmentActivity.startActivity(intent)
                }
            )
        } else {
            null
        }

        val wallpaperBackdrop = if (useGlass) {
            rememberLayerBackdrop()
        } else {
            null
        }

        // 底栏位于该层外，只能单向采样壁纸与页面内容。
        val navBarBackdrop = if (useGlass) {
            rememberLayerBackdrop()
        } else {
            null
        }

        val dialogHostState = rememberDialogHostState()
        // 顶栏把底边写进这里，通知覆盖层据此落位，避免压住顶栏按钮
        val noticeAnchorState = remember { NoticeAnchorState() }
        CompositionLocalProvider(
            LocalAppBackdrop provides wallpaperBackdrop,
            LocalControlBackdrop provides wallpaperBackdrop,
            LocalModalBackdrop provides navBarBackdrop,
            LocalAppOverlayBottomInset provides 96.dp,
            LocalDialogHost provides dialogHostState,
            LocalFloatingNotice provides tokenExpiredNotice,
            LocalNoticeAnchor provides noticeAnchorState
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (useGlass && navBarBackdrop != null) Modifier.layerBackdrop(navBarBackdrop)
                    else Modifier
                )
            ) {
            val wallpaperPreset = AppearanceSettingsManager.wallpaper
            // debug 水印画进壁纸捕获层，而不是盖在内容最上层。只有进入
            // LocalControlBackdrop 的采样范围，顶栏玻璃芯片才可能折射它——
            // 高对比斜线是验收折射管道最好的标靶：笔画在芯片边缘有没有弯，
            // 一眼就能判定。release 构建只保留右下角角标。
            val showPiracyTiles = (
                LocalContext.current.applicationInfo.flags and
                    ApplicationInfo.FLAG_DEBUGGABLE
                ) != 0
            if (useGlass && wallpaperBackdrop != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(wallpaperBackdrop)
                ) {
                    drawWallpaperPattern(wallpaperPreset)
                    if (showPiracyTiles) drawPiracyWatermarkTiles()
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(wallpaperPreset.baseColor)
                    if (showPiracyTiles) drawPiracyWatermarkTiles()
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(navBarScrollConnection)
                    ) {
                        val pageTarget = MainPageTarget(
                            accountStorageKey = currentAccountStorageKey,
                            tabIndex = selectedTab
                        )
                        AnimatedContent(
                            targetState = pageTarget,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val accountChanged =
                                    targetState.accountStorageKey != initialState.accountStorageKey
                                if (accessibility.reduceMotion || accountChanged) {
                                    fadeIn(
                                        animationSpec = tween(
                                            durationMillis = if (accessibility.reduceMotion) 90 else 160,
                                            easing = MotionEasing.FastOutSlowIn
                                        )
                                    ).togetherWith(
                                        fadeOut(
                                            animationSpec = tween(
                                                durationMillis = if (accessibility.reduceMotion) 70 else 120,
                                                easing = MotionEasing.Accelerate
                                            )
                                        )
                                    )
                                } else {
                                    val direction = if (
                                        targetState.tabIndex >= initialState.tabIndex
                                    ) {
                                        1
                                    } else {
                                        -1
                                    }
                                    // 轻位移 + 微缩放 + 交叉淡化：弱化横移的生硬感
                                    (
                                        slideInHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 260,
                                                easing = MotionEasing.FastOutSlowIn
                                            )
                                        ) { fullWidth ->
                                            direction * (fullWidth * 0.08f).roundToInt()
                                        } + scaleIn(
                                            initialScale = 0.985f,
                                            animationSpec = tween(
                                                durationMillis = 260,
                                                easing = MotionEasing.FastOutSlowIn
                                            )
                                        ) + fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = MotionEasing.FastOutSlowIn
                                            )
                                        )
                                    ).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 200,
                                                easing = MotionEasing.Accelerate
                                            )
                                        ) { fullWidth ->
                                            -direction * (fullWidth * 0.05f).roundToInt()
                                        } + fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 150,
                                                easing = MotionEasing.Accelerate
                                            )
                                        )
                                    )
                                }
                            },
                            contentKey = { target ->
                                "${target.accountStorageKey}:${target.tabIndex}"
                            },
                            label = "mainPageTransition"
                        ) { target ->
                            key(target.accountStorageKey, target.tabIndex) {
                                when (target.tabIndex) {
                                    0 -> com.tyust.course.ui.route.CourseListRoute()
                                    1 -> com.tyust.course.ui.route.ScheduleRoute()
                                    2 -> com.tyust.course.ui.route.GrabProRoute()
                                    3 -> com.tyust.course.ui.route.GradesRoute()
                                    4 -> com.tyust.course.ui.route.SettingsRoute(
                                        onAccountChanged = {
                                            currentAccountStorageKey =
                                                UserManager.getInstance().currentAccountStorageKey
                                        }
                                    )
                                    else -> com.tyust.course.ui.route.CourseListRoute()
                                }
                            }
                        }
                    }
                }

            AppBuildWatermarks()
            } // 关闭 navBarBackdrop 捕获层

            // 底栏位于捕获层外，避免采样源包含底栏自身。
            CapsuleNavigationBar(
                items = items,
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it },
                minimized = navBarMinimized,
                onExpandRequest = { navBarMinimized = false },
                backdrop = navBarBackdrop,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            // 全局玻璃 Toast：悬浮在底栏上方
            GlassToastHost(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 108.dp)
            )

            DialogHost(
                state = dialogHostState,
                modifier = Modifier.fillMaxSize()
            )

            // 悬浮玻璃通知：叠加在正文之上，落点由顶栏上报的底边决定，不压顶栏操作
            FloatingNoticeHost(modifier = Modifier.fillMaxSize())

            if (showStarDialog) {
                val dialogTitle = when (dismissCount) {
                    0 -> "点个 Star 支持一下 ⭐"
                    1 -> "小小的 Star，大大的支持 ⭐"
                    else -> "最后一次求 Star 支持 ⭐"
                }

                val dialogText = when (dismissCount) {
                    0 -> "哈喽！感谢你使用抢课助手。\n\n如果你觉得这个应用对你有帮助，欢迎给我们的 GitHub 仓库点一颗 Star ⭐！你的支持是我们持续优化的最大动力～"
                    1 -> "嗨，又见面啦！我们一直在努力优化体验。\n\n如果抢课助手帮到了你，不妨花几秒钟去 GitHub 点个 Star ⭐ 支持一下作者吧，非常感谢！"
                    else -> "这是最后一次打扰啦～\n\n写这个小工具很不容易，如果你喜欢它，真心希望能得到你的一颗 Star ⭐ 鼓励。非常感谢一路以来的陪伴！"
                }

                // 走 DialogHost portal（同窗口渲染），玻璃采样与按钮显示才正确
                SystemDialog(
                    onDismissRequest = {}, // 点击外部或按返回键不响应，防止误触
                    title = {
                        Text(
                            text = dialogTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    dismissButton = {
                        SystemSecondaryButton(
                            text = "暂不",
                            onClick = {
                                showStarDialog = false
                                prefs.edit().putInt("star_dismiss_count", dismissCount + 1).apply()
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        SystemPrimaryButton(
                            text = "去点 Star",
                            onClick = {
                                showStarDialog = false
                                prefs.edit().putBoolean("has_starred", true).apply()
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/znjhahaha/zhengfang-apk.git")).apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, "无法打开浏览器", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                ) {
                    Text(
                        text = dialogText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.AppBuildWatermarks() {
    Text(
        text = "开源版 · 请勿商用",
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )
    // debug 的平铺水印不在这里画。它必须落在壁纸捕获层内才能被玻璃芯片折射，
    // 所以由 drawPiracyWatermarkTiles 在壁纸 Canvas 里绘制。画在这一层会盖在
    // 所有内容之上，穿过按钮时笔画完全不弯——那正是之前看不出折射的原因之一。
}

/** 平铺水印避开底栏区域的底部内缩。原先由外层 Box 的 bottom padding 承担。 */
private val PiracyWatermarkBottomInset = 88.dp

/**
 * debug 防倒卖水印的平铺绘制。
 *
 * 调用点在壁纸 Canvas 内（见 [layerBackdrop] 那一层），因此它进入
 * LocalControlBackdrop 的采样范围，顶栏芯片会折射这些斜线。
 * release 构建不调用。
 */
private fun DrawScope.drawPiracyWatermarkTiles() {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 54f
        setColor(android.graphics.Color.argb(64, 199, 58, 47))
        textAlign = android.graphics.Paint.Align.CENTER
    }
    val centerX = size.width / 2f
    val centerY = (size.height - PiracyWatermarkBottomInset.toPx()) / 2f
    val nativeCanvas = drawContext.canvas.nativeCanvas
    nativeCanvas.save()
    nativeCanvas.rotate(-28f, centerX, centerY)
    for (i in -2..2) {
        for (j in -3..3) {
            nativeCanvas.drawText(
                "开源版 / 严禁倒卖",
                centerX + (i * 560f),
                centerY + (j * 620f),
                paint
            )
        }
    }
    nativeCanvas.restore()
}

