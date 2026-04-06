package com.tyust.course

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.setContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.tyust.course.activation.ActivationManager
import com.tyust.course.activation.ActivationScreen
import com.tyust.course.fragment.*
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.OnboardingScreen
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.ui.theme.PrimaryPurple
import com.tyust.course.update.UpdateDialog
import com.tyust.course.update.rememberUpdateState
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    
    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_HAS_SEEN_ONBOARDING = "has_seen_onboarding"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 UserManager（确保自定义学校和登录状态被加载）
        UserManager.getInstance().init(this)
        
        // 恢复 Cookie 到 CourseApiClient（关键！）
        val userManager = UserManager.getInstance()
        if (userManager.hasSavedCookie() && userManager.currentSchool != null) {
            com.tyust.course.network.CourseApiClient.getInstance().setCookie(
                userManager.currentSchool.baseUrl,
                userManager.savedCookie
            )
        }
        
        // 初始化 SmartSelector 持久化
        SmartSelector.getInstance().init(this)
        
        // 🔧 初始化 CourseApiClient 拦截器上下文
        com.tyust.course.network.CourseApiClient.getInstance().init(this)

        // Check Login
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
                
                // 激活状态：0=检查中, 1=未激活, 2=已激活
                var activationState by remember { mutableIntStateOf(0) }
                val scope = rememberCoroutineScope()
                
                // 启动时检查激活状态
                LaunchedEffect(Unit) {
                    val activated = ActivationManager.checkActivation(this@MainActivity)
                    activationState = if (activated) 2 else 1
                }
                
                when {
                    // 检查中 - 显示加载
                    activationState == 0 -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryPurple)
                        }
                    }
                    // 未激活 - 显示激活界面
                    activationState == 1 -> {
                        ActivationScreen(
                            onActivated = { activationState = 2 }
                        )
                    }
                    // 已激活 - 正常流程
                    showOnboarding -> {
                        OnboardingScreen(
                            onFinish = {
                                prefs.edit().putBoolean(KEY_HAS_SEEN_ONBOARDING, true).apply()
                                showOnboarding = false
                            }
                        )
                    }
                    else -> {
                        MainScreen(fragmentActivity = this)
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
    object Courses : BottomNavItem("courses", Icons.Default.List, "课程")
    object Schedule : BottomNavItem("schedule", Icons.Default.DateRange, "课表")
    object Grab : BottomNavItem("grab", Icons.Default.PlayArrow, "抢课")
    object Grades : BottomNavItem("grades", Icons.Default.Star, "成绩/考试")
    object Settings : BottomNavItem("settings", Icons.Default.Settings, "设置")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(fragmentActivity: FragmentActivity) {
    var selectedTab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        BottomNavItem.Courses,
        BottomNavItem.Schedule,
        BottomNavItem.Grab,
        BottomNavItem.Grades,
        BottomNavItem.Settings
    )
    
    // 更新检查状态
    val updateState = rememberUpdateState()
    
    // 🔧 全局 Token 过期状态
    var isTokenExpired by remember { mutableStateOf(false) }

    // 启动时检查更新
    LaunchedEffect(Unit) {
        updateState.checkForUpdate()
    }

    // 🔧 注册全局 Cookie 过期监听
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED) {
                    isTokenExpired = true
                }
            }
        }
        val filter = android.content.IntentFilter(com.tyust.course.network.CourseApiClient.ACTION_COOKIE_EXPIRED)
        androidx.core.content.ContextCompat.registerReceiver(
            fragmentActivity,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            fragmentActivity.unregisterReceiver(receiver)
        }
    }
    
    // 更新对话框
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

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = PrimaryPurple
            ) {
                items.forEachIndexed { index, item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryPurple,
                            selectedTextColor = PrimaryPurple,
                            indicatorColor = PrimaryPurple.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 🔧 1. 恢复通栏提示条样式 (放在顶层 Column 中保证不挡标题)
                androidx.compose.animation.AnimatedVisibility(
                    visible = isTokenExpired,
                    enter = androidx.compose.animation.expandVertically() + fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        onClick = {
                            // 跳转重登逻辑
                            val intent = Intent(fragmentActivity, LoginActivity::class.java)
                            intent.putExtra("force_relogin", true)
                            fragmentActivity.startActivity(intent)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "登录已过期，部分功能受限。点击此处重新登录",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // 🔧 2. 内容区域 (占满剩余空间)
                Box(modifier = Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            val smoothEasing = androidx.compose.animation.core.CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
                            val tweenSpec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(150, easing = smoothEasing)
                            val fadeSpec = androidx.compose.animation.core.tween<Float>(150, easing = smoothEasing)
                            
                            if (targetState > initialState) {
                                (slideInHorizontally(animationSpec = tweenSpec) { width -> width + 100 } + fadeIn(animationSpec = fadeSpec)) togetherWith
                                        (slideOutHorizontally(animationSpec = tweenSpec) { width -> -width / 4 } + fadeOut(animationSpec = fadeSpec))
                            } else {
                                (slideInHorizontally(animationSpec = tweenSpec) { width -> -width - 100 } + fadeIn(animationSpec = fadeSpec)) togetherWith
                                        (slideOutHorizontally(animationSpec = tweenSpec) { width -> width / 4 } + fadeOut(animationSpec = fadeSpec))
                            }
                        },
                        label = "MainTabTransition"
                    ) { targetIndex ->
                        when (targetIndex) {
                            0 -> com.tyust.course.ui.route.CourseListRoute()
                            1 -> com.tyust.course.ui.route.ScheduleRoute()
                            2 -> com.tyust.course.ui.route.GrabProRoute()
                            3 -> com.tyust.course.ui.route.GradesRoute()
                            4 -> com.tyust.course.ui.route.SettingsRoute()
                            else -> com.tyust.course.ui.route.CourseListRoute()
                        }
                    }
                }
            }
            
            // 🔧 3. 水印 (放在 Box 作用域内以支持 align)
            Text(
                text = "作者:znj | 免费软件，请勿用于盈利",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 4.dp),
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.35f),
                fontSize = 9.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light
            )
            
            Text(
                text = "znj © 免费开源",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 4.dp),
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.3f),
                fontSize = 8.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light
            )
            
            Text(
                text = "请勿商用",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 12.dp, top = 8.dp),
                color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.25f),
                fontSize = 8.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light
            )

            // 🔧 4. 防倒卖强制大水印 (仅针对未签发 Release 的 Debug 开源构建版本展示)
            val isDebug = (fragmentActivity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebug) {
                // 不可交互点击的全屏遮罩
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val paint = android.graphics.Paint().apply {
                            textSize = 55f
                            // 稍微加深一点透明度，解决看不清的问题
                            color = android.graphics.Color.argb(70, 255, 80, 80)
                            textAlign = android.graphics.Paint.Align.CENTER
                            isAntiAlias = true
                        }
                        
                        // 计算屏幕中心并在各个角落倾斜绘制交叉水印
                        val centerX = size.width / 2
                        val centerY = size.height / 2
                        
                        val nativeCanvas = drawContext.canvas.nativeCanvas
                        nativeCanvas.save()
                        nativeCanvas.rotate(-30f, centerX, centerY)
                        
                        // 疏远间距，防止文字重叠
                        for (i in -2..2) {
                            for (j in -3..3) {
                                nativeCanvas.drawText(
                                    "开源版 / 严禁倒卖",
                                    centerX + (i * 600f),
                                    centerY + (j * 650f),
                                    paint
                                )
                            }
                        }
                        nativeCanvas.restore()
                    }
                }
            }
        }
    }
}
