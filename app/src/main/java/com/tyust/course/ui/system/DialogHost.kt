package com.tyust.course.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class DialogHostState {
    var currentDialog: (@Composable () -> Unit)? by mutableStateOf(null)
        private set
    private var dismissCallback: (() -> Unit)? = null

    fun show(onDismiss: () -> Unit, content: @Composable () -> Unit) {
        dismissCallback = onDismiss
        currentDialog = content
    }

    fun dismiss() {
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
    AnimatedVisibility(
        visible = dialogContent != null,
        enter = fadeIn() + scaleIn(initialScale = 0.92f),
        exit = fadeOut() + scaleOut(targetScale = 0.92f),
        modifier = modifier
    ) {
        if (dialogContent != null) {
            BackHandler { state.dismiss() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
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