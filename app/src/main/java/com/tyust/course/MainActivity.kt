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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import com.tyust.course.ui.system.LocalAppBackdrop
import com.tyust.course.ui.system.LocalAppOverlayBottomInset
import com.tyust.course.ui.system.LocalDialogHost
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
        val bgColor = MaterialTheme.colorScheme.background
        val useGlass = isBackdropSupported()

        // 页内玻璃只采样独立壁纸层，不能采样包含自身的页面内容层。
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
        CompositionLocalProvider(
            LocalAppBackdrop provides wallpaperBackdrop,
            LocalAppOverlayBottomInset provides 96.dp,
            LocalDialogHost provides dialogHostState
        ) {
            Box(
                modifier = Modifier.fillMaxSize().then(
                    if (useGlass && navBarBackdrop != null) Modifier.layerBackdrop(navBarBackdrop)
                    else Modifier
                )
            ) {
            val wallpaperPreset = AppearanceSettingsManager.wallpaper
            if (useGlass && wallpaperBackdrop != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(wallpaperBackdrop)
                ) {
                    drawWallpaperPattern(this, wallpaperPreset)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(wallpaperPreset.baseColor)
                ) {}
            }

            Column(modifier = Modifier.fillMaxSize()) {
                    AnimatedVisibility(
                        visible = isTokenExpired,
                        enter = fadeIn() + slideInVertically { -it / 2 },
                        exit = fadeOut() + slideOutVertically { -it / 2 },
                        // 规避刘海屏和系统状态栏遮挡；居中悬浮玻璃气泡
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            TokenExpiredBanner(
                                backdrop = wallpaperBackdrop,
                                onClick = {
                                    val intent = Intent(fragmentActivity, LoginActivity::class.java)
                                    intent.putExtra("force_relogin", true)
                                    fragmentActivity.startActivity(intent)
                                }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier.weight(1f)
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
                                    (
                                        slideInHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 290,
                                                easing = MotionEasing.FastOutSlowIn
                                            )
                                        ) { fullWidth ->
                                            direction * (fullWidth * 0.16f).roundToInt()
                                        } + fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 240,
                                                easing = MotionEasing.FastOutSlowIn
                                            )
                                        )
                                    ).togetherWith(
                                        slideOutHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 220,
                                                easing = MotionEasing.Accelerate
                                            )
                                        ) { fullWidth ->
                                            -direction * (fullWidth * 0.10f).roundToInt()
                                        } + fadeOut(
                                            animationSpec = tween(
                                                durationMillis = 180,
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

            AppBuildWatermarks(fragmentActivity = fragmentActivity)
            } // 关闭 navBarBackdrop 捕获层

            // 底栏位于捕获层外，避免采样源包含底栏自身。
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
    backdrop: com.kyant.backdrop.Backdrop?,
    onClick: () -> Unit
) {
    val useGlass = backdrop != null && isBackdropSupported()
    val capsuleShape = RoundedCornerShape(percent = 50)
    val isLight = !androidx.compose.foundation.isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary

    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "登录已过期，点击重新登录",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (useGlass && backdrop != null) {
        Box(
            modifier = Modifier
                .clip(capsuleShape)
                .clickable(onClick = onClick)
                .com_tyust_tokenBannerGlass(backdrop, capsuleShape, isLight)
        ) { content() }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = capsuleShape,
            shadowElevation = 6.dp,
            modifier = Modifier.clickable(onClick = onClick)
        ) { content() }
    }
}

private fun Modifier.com_tyust_tokenBannerGlass(
    backdrop: com.kyant.backdrop.Backdrop,
    shape: androidx.compose.foundation.shape.RoundedCornerShape,
    isLight: Boolean
): Modifier = this.drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(8.dp.toPx())
    },
    shadow = { Shadow(alpha = 0.18f) },
    onDrawSurface = {
        drawRect(
            if (isLight) Color.White.copy(alpha = 0.55f)
            else Color.Black.copy(alpha = 0.40f)
        )
    }
)

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

private fun drawWallpaperPattern(scope: DrawScope, preset: WallpaperPreset) {
    val w = scope.size.width
    val h = scope.size.height

    // 底色 + 左上高光 + 右下暗角，提升明暗对比，让 Backdrop 折射有可采样层次。
    scope.drawRect(preset.baseColor)
    scope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                preset.glowColor.copy(alpha = if (preset.isDark) 0.42f else 0.72f),
                Color.Transparent
            ),
            center = Offset(w * 0.18f, h * 0.12f),
            radius = w * 0.72f
        ),
        radius = w * 0.72f,
        center = Offset(w * 0.18f, h * 0.12f)
    )
    scope.drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                preset.shadeColor.copy(alpha = if (preset.isDark) 0.30f else 0.12f),
                Color.Transparent
            ),
            center = Offset(w * 0.78f, h * 0.88f),
            radius = w * 0.64f
        ),
        radius = w * 0.64f,
        center = Offset(w * 0.78f, h * 0.88f)
    )
}
