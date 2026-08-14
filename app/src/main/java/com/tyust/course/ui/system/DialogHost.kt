package com.tyust.course.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

private const val EXIT_ANIM_DURATION_MS = 150L

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
    val isLightTheme = !isSystemInDarkTheme()
    val scrimColor = if (isLightTheme) {
        Color(0xFF5F636B).copy(alpha = 0.34f)
    } else {
        Color.Black.copy(alpha = 0.52f)
    }
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
        fadeIn() + scaleIn(initialScale = 0.92f)
    }
    val exitTransition = if (accessibility.reduceMotion) {
        androidx.compose.animation.ExitTransition.None
    } else {
        fadeOut(animationSpec = tween(150)) +
            scaleOut(targetScale = 0.92f, animationSpec = tween(150))
    }

    AnimatedVisibility(
        visible = state.isVisible && dialogContent != null,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier
    ) {
        if (dialogContent != null) {
            BackHandler { state.dismiss() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.dismiss() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // consume click to prevent dismiss
                    )
                ) {
                    dialogContent()
                }
            }
        }
    }
}