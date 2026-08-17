package com.tyust.course

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Paint
import android.graphics.Picture
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tyust.course.activation.ActivationManager
import com.tyust.course.activation.ActivationScreen
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.OnboardingScreen
import com.tyust.course.ui.screen.SchoolAdaptationCompletionReminder
import com.tyust.course.ui.system.CapsuleNavigationBar
import com.tyust.course.ui.system.DialogHost
import com.tyust.course.ui.system.GlassToastHost
import com.tyust.course.ui.system.GlassToaster
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
import kotlinx.coroutines.delay
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
                // 开源版授权检查不会拒绝设备，先呈现真实内容，再完成兼容检查。
                var activationState by remember { mutableIntStateOf(2) }

                LaunchedEffect(Unit) {
                    val activated = ActivationManager.checkActivation(this@MainActivity)
                    if (!activated) activationState = 1
                }

                when {
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
    val shouldShowStarDialog = !hasStarred && dismissCount < 3
    var showStarDialog by rememberSaveable { mutableStateOf(false) }
    var startupOverlaysReady by remember { mutableStateOf(false) }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var currentAccountStorageKey by remember { mutableStateOf(UserManager.getInstance().currentAccountStorageKey) }
    val accessibility = rememberGlassAccessibilityMode()
    var hasDisplayedInitialTab by remember { mutableStateOf(false) }
    var tabEnterDirection by remember { mutableIntStateOf(1) }
    val animateCurrentTab = remember(selectedTab) {
        hasDisplayedInitialTab && !accessibility.reduceMotion
    }
    val tabEnterProgress = remember(selectedTab) {
        Animatable(if (animateCurrentTab) 0f else 1f)
    }
    var tabEnterLayerActive by remember(selectedTab) {
        mutableStateOf(animateCurrentTab)
    }
    val items = listOf(
        BottomNavItem.Courses,
        BottomNavItem.Schedule,
        BottomNavItem.Grab,
        BottomNavItem.Grades,
        BottomNavItem.Settings
    )
    val updateState = rememberUpdateState()
    var isTokenExpired by remember { mutableStateOf(false) }
    var isRenewingSession by remember { mutableStateOf(false) }

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
    LaunchedEffect(selectedTab) {
        navBarMinimized = false
        if (!hasDisplayedInitialTab) {
            hasDisplayedInitialTab = true
            return@LaunchedEffect
        }
        if (!animateCurrentTab) {
            tabEnterLayerActive = false
            return@LaunchedEffect
        }
        tabEnterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 160,
                easing = MotionEasing.FastOutSlowIn
            )
        )
        // 动画结束后移除整页 RenderNode，稳态不保留额外全屏图层。
        tabEnterLayerActive = false
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(1_000)
        updateState.checkForUpdate()
    }

    LaunchedEffect(shouldShowStarDialog) {
        withFrameNanos { }
        delay(1_600)
        startupOverlaysReady = true
        if (shouldShowStarDialog) showStarDialog = true
    }

    DisposableEffect(fragmentActivity) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED) {
                    val eventAccountKey = intent.getStringExtra(com.tyust.course.network.CourseApiClient.EXTRA_ACCOUNT_STORAGE_KEY).orEmpty()
                    val currentAccountKey = UserManager.getInstance().currentAccountStorageKey
                    if (eventAccountKey.isNotEmpty() && eventAccountKey != currentAccountKey) return
                    // 先试静默续期，横幅是最后手段：密码已加密存在本机，多数情况下
                    // 用户完全不需要知道会话失效过。SessionRenewer 内部单飞，
                    // 连环 401 不会打出多次登录请求。
                    if (com.tyust.course.utils.SessionRenewer.canRenew()) {
                        if (!isRenewingSession) {
                            isRenewingSession = true
                            GlassToaster.show("登录状态已失效，正在自动续期…")
                        }
                        com.tyust.course.utils.SessionRenewer.renew(fragmentActivity) { renewed ->
                            isRenewingSession = false
                            if (renewed) {
                                isTokenExpired = false
                                GlassToaster.show("登录状态已恢复")
                                // 看门狗在失败分支里把自己停了，续上之后重新挂起来
                                com.tyust.course.utils.CookieWatchdog.start(fragmentActivity)
                            } else {
                                isTokenExpired = true
                            }
                        }
                    } else {
                        isTokenExpired = true
                    }
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
        enabled = startupOverlaysReady && !showStarDialog && !updateState.showDialog(),
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
                message = "登录状态已失效",
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
                        .debugPiracyWatermark(showPiracyTiles)
                ) {
                    // 在绘制 lambda 内部再读一次 state：图片壁纸的位图是异步解码的，
                    // 只读外面那份快照的话，位图到位时这一层不会重绘。
                    drawWallpaperPattern(AppearanceSettingsManager.style)
                }
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .debugPiracyWatermark(showPiracyTiles)
                ) {
                    drawRect(AppearanceSettingsManager.style.baseColor)
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .nestedScroll(navBarScrollConnection)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (tabEnterLayerActive) {
                                        Modifier.graphicsLayer {
                                            val remaining = 1f - tabEnterProgress.value
                                            translationX =
                                                tabEnterDirection * 14.dp.toPx() * remaining
                                            val scale = 1f - (0.004f * remaining)
                                            scaleX = scale
                                            scaleY = scale
                                            alpha = 1f - (0.035f * remaining)
                                            transformOrigin = TransformOrigin.Center
                                            // 避免淡入触发整页离屏缓冲，只调制绘制指令透明度。
                                            compositingStrategy = CompositingStrategy.ModulateAlpha
                                        }
                                    } else {
                                        Modifier
                                    }
                                )
                        ) {
                            // 旧页立即释放，只让新页做轻量入场；底栏液态动画完全独立。
                            key(currentAccountStorageKey, selectedTab) {
                                when (selectedTab) {
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
                onTabSelect = { targetTab ->
                    if (targetTab != selectedTab) {
                        tabEnterDirection = if (targetTab > selectedTab) 1 else -1
                        selectedTab = targetTab
                    }
                },
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

            if (showStarDialog && !updateState.showDialog()) {
                val dialogTitle = when (dismissCount) {
                    0 -> "在 GitHub 上支持这个项目"
                    1 -> "一个 Star，就是最好的反馈"
                    else -> "最后一次邀请"
                }

                val dialogText = when (dismissCount) {
                    0 -> "感谢使用正方教务助手。\n\n如果它帮到了你，欢迎去 GitHub 仓库点一个 Star —— 这是项目持续维护最直接的动力。"
                    1 -> "我们仍在持续优化体验。\n\n如果这个应用对你有用，花几秒钟给仓库点个 Star，就是对作者最好的支持。"
                    else -> "这是最后一次提示。\n\n如果你愿意，欢迎去 GitHub 留下一个 Star；无论如何，都感谢你的使用。"
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
    // 所以由 debugPiracyWatermark 在壁纸 Canvas 的绘制链里输出。画在这一层会盖在
    // 所有内容之上，穿过按钮时笔画完全不弯——那正是之前看不出折射的原因之一。
}

/** 平铺水印避开底栏区域的底部内缩。原先由外层 Box 的 bottom padding 承担。 */
private val PiracyWatermarkBottomInset = 88.dp

/**
 * debug 防倒卖水印缓存为 Picture；每次壁纸重绘只回放一次绘制记录。
 *
 * 调用点在壁纸 Canvas 内（见 [layerBackdrop] 那一层），因此它进入
 * LocalControlBackdrop 的采样范围，顶栏芯片会折射这些斜线。
 * release 构建不调用。
 */
private fun Modifier.debugPiracyWatermark(enabled: Boolean): Modifier {
    if (!enabled) return this
    return drawWithCache {
        val picture = Picture()
        val recordingCanvas = picture.beginRecording(
            size.width.roundToInt().coerceAtLeast(1),
            size.height.roundToInt().coerceAtLeast(1)
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 54f
            color = android.graphics.Color.argb(64, 199, 58, 47)
            textAlign = Paint.Align.CENTER
        }
        val centerX = size.width / 2f
        val centerY = (size.height - PiracyWatermarkBottomInset.toPx()) / 2f
        recordingCanvas.save()
        recordingCanvas.rotate(-28f, centerX, centerY)
        for (i in -2..2) {
            for (j in -3..3) {
                recordingCanvas.drawText(
                    "开源版 / 严禁倒卖",
                    centerX + (i * 560f),
                    centerY + (j * 620f),
                    paint
                )
            }
        }
        recordingCanvas.restore()
        picture.endRecording()

        onDrawWithContent {
            drawContent()
            drawContext.canvas.nativeCanvas.drawPicture(picture)
        }
    }
}

