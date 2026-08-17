package com.tyust.course.ui.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.theme.MotionEasing
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局玻璃 Toast：悬浮深色玻璃胶囊，取代系统 Toast 的样式割裂。
 * 任意位置调用 [GlassToaster.show]，由挂在 Activity 根部的 [GlassToastHost] 渲染。
 */
object GlassToaster {
    internal data class ToastData(val id: Long, val message: String)

    private val counter = AtomicLong(0L)
    internal val current = mutableStateOf<ToastData?>(null)

    fun show(message: String) {
        if (message.isBlank()) return
        current.value = ToastData(counter.incrementAndGet(), message)
    }
}

@Composable
fun GlassToastHost(modifier: Modifier = Modifier) {
    val data = GlassToaster.current.value
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(data?.id) {
        if (data != null) {
            visible = true
            delay(2400)
            visible = false
        }
    }

    // visible 变 false 后胶囊淡出；保留最后一条内容避免退出动画期间文字消失
    var lastMessage by remember { mutableStateOf("") }
    if (data != null) lastMessage = data.message

    val isLightTheme = !rememberGlassDarkTheme()
    val capsuleColor = if (isLightTheme) {
        Color(0xFF26292F).copy(alpha = 0.86f)
    } else {
        Color(0xFF303338).copy(alpha = 0.92f)
    }
    val shape = RoundedCornerShape(22.dp)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(260, easing = MotionEasing.FastOutSlowIn)
            ) { it / 2 } + scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(260, easing = MotionEasing.FastOutSlowIn)
            ) + fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(
                animationSpec = tween(220, easing = MotionEasing.Accelerate)
            ) { it / 3 } + fadeOut(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .background(capsuleColor)
                    .border(0.5.dp, Color.White.copy(alpha = 0.14f), shape)
                    .padding(horizontal = 18.dp, vertical = 11.dp)
            ) {
                Text(
                    text = lastMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.96f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
