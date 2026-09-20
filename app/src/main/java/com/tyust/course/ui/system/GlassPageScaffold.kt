package com.tyust.course.ui.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Shared page shell for forms, history and standalone browser activities. */
@Composable
fun GlassPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    topBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    val page: @Composable () -> Unit = {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                bottomBar = bottomBar,
                topBar = {
                    if (topBar != null) topBar() else SystemTopBar(
                        title = title,
                        subtitle = subtitle,
                        navigationIcon = {
                            if (onBack != null) SystemIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
                        },
                        actions = actions
                    )
                }
            ) { padding ->
                content(PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = maxOf(padding.calculateBottomPadding(), LocalAppOverlayBottomInset.current)
                ))
            }
    }
    if (LocalDialogHost.current == null) {
        GlassWindowHost(modifier) { page() }
    } else {
        Box(modifier.fillMaxSize()) { page() }
    }
}
