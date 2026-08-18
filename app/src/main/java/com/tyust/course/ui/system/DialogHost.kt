package com.tyust.course.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val EXIT_ANIM_DURATION_MS = 190L

class DialogHostState {
    var currentDialog: (@Composable () -> Unit)? by mutableStateOf(null)
        private set
    var isVisible: Boolean by mutableStateOf(false)
        private set
    private var dismissCallback: (() -> Unit)? = null

    fun show(onDismiss: () -> Unit, content: @Composable () -> Unit) {
        dismissCallback = onDismiss
        currentDialog = content
        isVisible = true
    }

    fun dismiss() {
        isVisible = false
    }

    internal fun clearAfterAnimation() {
        val cb = dismissCallback
        dismissCallback = null
        currentDialog = null
        cb?.invoke()
    }
}

@Composable
fun rememberDialogHostState(): DialogHostState = remember { DialogHostState() }

val LocalDialogHost = compositionLocalOf<DialogHostState?> { null }

@Composable
fun DialogHost(
    state: DialogHostState,
    modifier: Modifier = Modifier
) {
    val dialogContent = state.currentDialog
    val accessibility = rememberGlassAccessibilityMode()
    val exitDurationMillis = if (accessibility.reduceMotion) 0L else EXIT_ANIM_DURATION_MS

    LaunchedEffect(state.isVisible, exitDurationMillis) {
        if (!state.isVisible && state.currentDialog != null) {
            delay(exitDurationMillis)
            state.clearAfterAnimation()
        }
    }

    val enterTransition = if (accessibility.reduceMotion) {
        androidx.compose.animation.EnterTransition.None
    } else {
        // 进入用 spring：低阻尼让卡片冲过 1.0 再回落，是"被弹出来"而不是"被拉大"。
        // 起始 0.86 比原来的 0.92 更小，过冲才有行程可走。
        fadeIn(animationSpec = tween(120)) +
            scaleIn(
                initialScale = 0.86f,
                animationSpec = spring(dampingRatio = 0.66f, stiffness = 400f)
            )
    }
    val exitTransition = if (accessibility.reduceMotion) {
        androidx.compose.animation.ExitTransition.None
    } else {
        // 退出反过来：先慢后快地抽离，缩得比进入起点更狠，
        // 加上加速 easing，观感是被"吸走"而不是匀速淡掉。
        fadeOut(animationSpec = tween(EXIT_ANIM_DURATION_MS.toInt(), easing = FastOutLinearInEasing)) +
            scaleOut(
                targetScale = 0.84f,
                animationSpec = tween(EXIT_ANIM_DURATION_MS.toInt(), easing = FastOutLinearInEasing)
            )
    }

    if (dialogContent != null) {
        // 内容先挂载、下一帧再置为可见，否则 AnimatedVisibility 的首帧就是终态，
        // 弹窗会直接"跳"出来而没有淡入缩放过渡。
        var cardVisible by remember(dialogContent) { mutableStateOf(false) }
        LaunchedEffect(dialogContent, state.isVisible) {
            if (state.isVisible) {
                withFrameNanos { }
                cardVisible = true
            } else {
                cardVisible = false
            }
        }

        BackHandler { state.dismiss() }
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { state.dismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            // 背景压暗：弹窗要"浮在页面之上"就必须有一层把页面推远的介质，
            // 否则卡片再怎么加阴影都还是贴在同一平面上。遮罩淡入比卡片慢一拍，
            // 卡片弹出时才有"先出现、后压暗"的纵深顺序。
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(EXIT_ANIM_DURATION_MS.toInt()))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.22f))
                )
            }
            AnimatedVisibility(
                visible = cardVisible,
                enter = enterTransition,
                exit = exitTransition
            ) {
                Box(
                    modifier = Modifier
                        // 安全区必须在这一层：卡片没有任何限高，内容一长（几个弹窗的
                        // 正文是 heightIn(max = 420.dp)）整张卡就有 580dp 以上，
                        // 640dp 的屏幕上"完成"按钮会落到系统导航栏底下。
                        // 从外面把可用高钳住，正文自带的 verticalScroll 会接管溢出。
                        //
                        // 用 systemBars 而不是 safeDrawing：Manifest 是 adjustResize，
                        // 窗口本身已经被键盘缩过一次，再叠一次 IME inset 会压两遍。
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(vertical = 12.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    dialogContent()
                }
            }
        }
    }
}