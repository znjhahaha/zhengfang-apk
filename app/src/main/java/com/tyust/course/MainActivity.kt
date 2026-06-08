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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tyust.course.activation.ActivationManager
import com.tyust.course.activation.ActivationScreen
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.OnboardingScreen
import com.tyust.course.ui.system.CapsuleNavigationBar
import com.tyust.course.ui.system.DialogHost
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalDialogHost
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.system.isBackdropSupported
import com.tyust.course.ui.system.rememberDialogHostState
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.ui.theme.MotionSpecs
import com.tyust.course.update.UpdateDialog
import com.tyust.course.update.rememberUpdateState
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tyust.course.ui.system.SystemPrimaryButton
import com.tyust.course.ui.system.SystemSecondaryButton

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

@Composable
fun MainScreen(fragmentActivity: FragmentActivity) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    
    val hasStarred = prefs.getBoolean("has_starred", false)
    val dismissCount = prefs.getInt("star_dismiss_count", 0)
    var showStarDialog by remember { mutableStateOf(!hasStarred && dismissCount < 3) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var currentAccountStorageKey by remember { mutableStateOf(UserManager.getInstance().currentAccountStorageKey) }
    val items = listOf(
        BottomNavItem.Courses,
        BottomNavItem.Schedule,
        BottomNavItem.Grab,
        BottomNavItem.Grades,
        BottomNavItem.Settings
    )
    val updateState = rememberUpdateState()
    var isTokenExpired by remember { mutableStateOf(false) }

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
                    isTokenExpired = true
                    // 全局即时 Toast 强提醒，确保用户在任何页面都能感知
                    android.widget.Toast.makeText(
                        fragmentActivity,
                        "登录状态已过期，请重新登录",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
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
        val bgColor = MaterialTheme.colorScheme.background
        val useGlass = isBackdropSupported()

        val rootBackdrop = if (useGlass) {
            rememberLayerBackdrop()
        } else {
            null
        }

        // 第二个 backdrop：捕获壁纸+页面内容，专供底栏折射
        // 底栏在此 layer 之外，不会形成循环
        val navBarBackdrop = if (useGlass) {
            rememberLayerBackdrop()
        } else {
            null
        }

        val dialogHostState = rememberDialogHostState()
        CompositionLocalProvider(
            LocalAppBackdrop provides rootBackdrop,
            LocalDialogHost provides dialogHostState
        ) {
            // navBarBackdrop 捕获壁纸+页面内容，底栏在此 Box 之外折射它
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (useGlass && navBarBackdrop != null) Modifier.layerBackdrop(navBarBackdrop)
                    else Modifier
                )
            ) {
            // 壁纸层：layerBackdrop 只捕获壁纸，给卡片/顶栏用（避免循环）
            if (useGlass && rootBackdrop != null) {
                Canvas(modifier = Modifier.fillMaxSize().layerBackdrop(rootBackdrop)) {
                    drawWallpaperPattern(this)
                }
            } else {
                Box(Modifier.fillMaxSize().background(bgColor))
            }

            Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = isTokenExpired,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        // 规避刘海屏和系统状态栏遮挡
                        modifier = Modifier.statusBarsPadding()
                    ) {
                        TokenExpiredBanner(
                            onClick = {
                                val intent = Intent(fragmentActivity, LoginActivity::class.java)
                                intent.putExtra("force_relogin", true)
                                fragmentActivity.startActivity(intent)
                            }
                        )
                    }

                    Box(
                        modifier = Modifier.weight(1f)
                    ) {
                        key(currentAccountStorageKey) {
                            AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                val offsetTween = MotionSpecs.tabTransition<IntOffset>()
                                val fadeTween = MotionSpecs.standard<Float>()
                                if (targetState > initialState) {
                                    (slideInHorizontally(animationSpec = offsetTween) { width -> width / 28 } + fadeIn(animationSpec = fadeTween)) togetherWith
                                        (slideOutHorizontally(animationSpec = offsetTween) { width -> -width / 32 } + fadeOut(animationSpec = fadeTween))
                                } else {
                                    (slideInHorizontally(animationSpec = offsetTween) { width -> -width / 28 } + fadeIn(animationSpec = fadeTween)) togetherWith
                                        (slideOutHorizontally(animationSpec = offsetTween) { width -> width / 32 } + fadeOut(animationSpec = fadeTween))
                                }
                            },
                            label = "MainTabTransition"
                        ) { targetIndex ->
                            when (targetIndex) {
                                0 -> com.tyust.course.ui.theme.CourseSelectorTheme(primaryOverride = androidx.compose.ui.graphics.Color(0xFF6366F1)) {
                                    com.tyust.course.ui.route.CourseListRoute()
                                }
                                1 -> com.tyust.course.ui.theme.CourseSelectorTheme(primaryOverride = androidx.compose.ui.graphics.Color(0xFF10B981)) {
                                    com.tyust.course.ui.route.ScheduleRoute()
                                }
                                2 -> com.tyust.course.ui.theme.CourseSelectorTheme(primaryOverride = androidx.compose.ui.graphics.Color(0xFF6366F1)) {
                                    com.tyust.course.ui.route.GrabProRoute()
                                }
                                3 -> com.tyust.course.ui.theme.CourseSelectorTheme(primaryOverride = androidx.compose.ui.graphics.Color(0xFFF59E0B)) {
                                    com.tyust.course.ui.route.GradesRoute()
                                }
                                4 -> com.tyust.course.ui.theme.CourseSelectorTheme(primaryOverride = androidx.compose.ui.graphics.Color(0xFF3B82F6)) {
                                    com.tyust.course.ui.route.SettingsRoute(
                                        onAccountChanged = {
                                            currentAccountStorageKey = UserManager.getInstance().currentAccountStorageKey
                                        }
                                    )
                                }
                                else -> com.tyust.course.ui.theme.CourseSelectorTheme(primaryOverride = androidx.compose.ui.graphics.Color(0xFF6366F1)) {
                                    com.tyust.course.ui.route.CourseListRoute()
                                }
                            }
                        }
                    }
                }
            }

            AppBuildWatermarks(fragmentActivity = fragmentActivity)
            } // 关闭 navBarBackdrop Box

            // 底栏在 navBarBackdrop 之外，折射壁纸+页面内容
            CapsuleNavigationBar(
                items = items,
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it },
                backdrop = navBarBackdrop,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            DialogHost(
                state = dialogHostState,
                modifier = Modifier.fillMaxSize()
            )

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

                Dialog(
                    onDismissRequest = {}, // 点击外部或按返回键不响应，防止误触
                    properties = DialogProperties(
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false
                    )
                ) {
                    Surface(
                        modifier = Modifier
                            .width(320.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(28.dp),
                        tonalElevation = 4.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = dialogTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = dialogText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 20.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    SystemSecondaryButton(
                                        text = "暂不",
                                        onClick = {
                                            showStarDialog = false
                                            prefs.edit().putInt("star_dismiss_count", dismissCount + 1).apply()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
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
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenExpiredBanner(
    onClick: () -> Unit
) {
    Surface(
        color = com.tyust.course.ui.theme.NeuInsetBackground,
        contentColor = com.tyust.course.ui.theme.SemanticWarning,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = com.tyust.course.ui.theme.SemanticWarning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "登录已过期，点击此处重新登录",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun BoxScope.AppBuildWatermarks(
    fragmentActivity: FragmentActivity
) {
    Text(
        text = "开源版 · 请勿商用",
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 12.dp, bottom = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium
    )

    val isDebug = (fragmentActivity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (isDebug) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 88.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 54f
                    setColor(android.graphics.Color.argb(64, 199, 58, 47))
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val centerX = size.width / 2f
                val centerY = size.height / 2f
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
        }
    }
}

private fun drawWallpaperPattern(scope: DrawScope) {
    // 渐变壁纸：模拟文档推荐的"蓝、紫色模糊球"
    // 需要足够饱和度让 blur+lens 产生可感知的玻璃折射
    val w = scope.size.width
    val h = scope.size.height

    // 底色：柔白
    scope.drawRect(Color(0xFFF5F5FA))

    // 蓝色模糊球（左下）
    scope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x5599BBFF), Color.Transparent),
            center = Offset(w * 0.2f, h * 0.8f),
            radius = w * 0.6f
        ),
        radius = w * 0.6f,
        center = Offset(w * 0.2f, h * 0.8f)
    )

    // 紫色模糊球（右上）
    scope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x44BB99FF), Color.Transparent),
            center = Offset(w * 0.85f, h * 0.15f),
            radius = w * 0.5f
        ),
        radius = w * 0.5f,
        center = Offset(w * 0.85f, h * 0.15f)
    )

    // 青色模糊球（中下）— 确保底栏位置有色彩
    scope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0x4488DDCC), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.9f),
            radius = w * 0.45f
        ),
        radius = w * 0.45f,
        center = Offset(w * 0.5f, h * 0.9f)
    )
}
