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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.tyust.course.activation.ActivationManager
import com.tyust.course.activation.ActivationScreen
import com.tyust.course.manager.SmartSelector
import com.tyust.course.manager.UserManager
import com.tyust.course.ui.screen.OnboardingScreen
import com.tyust.course.ui.system.CapsuleNavigationBar
import com.tyust.course.ui.system.PagePadding
import com.tyust.course.ui.system.SystemLoadingState
import com.tyust.course.ui.theme.CourseSelectorTheme
import com.tyust.course.ui.theme.MotionSpecs
import com.tyust.course.update.UpdateDialog
import com.tyust.course.update.rememberUpdateState

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
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
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
        onDispose {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            CapsuleNavigationBar(
                items = items,
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = isTokenExpired,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    TokenExpiredBanner(
                        onClick = {
                            val intent = Intent(fragmentActivity, LoginActivity::class.java)
                            intent.putExtra("force_relogin", true)
                            fragmentActivity.startActivity(intent)
                        }
                    )
                }

                Box(modifier = Modifier.weight(1f)) {
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

            AppBuildWatermarks(fragmentActivity = fragmentActivity)
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
