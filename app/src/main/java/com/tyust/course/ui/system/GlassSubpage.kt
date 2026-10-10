package com.tyust.course.ui.system

import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp

@Composable
fun GlassSubpage(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    val host = LocalDialogHost.current
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val saveableKey = androidx.compose.runtime.saveable.rememberSaveable { java.util.UUID.randomUUID().toString() }
    if (host != null) {
        var handle by remember { mutableStateOf<DialogHandle?>(null) }
        DisposableEffect(host) {
            val owner = host.show({ currentDismiss() }, DialogPresentation.Page, saveableKey = saveableKey) {
                CompositionLocalProvider(LocalAppOverlayBottomInset provides 0.dp, LocalFloatingNotice provides null) {
                    // A page covers its parent and samples its own wallpaper, even in one window.
                    GlassWindowHost(isolatePage = true) { currentContent { host.dismiss(handle) } }
                }
            }
            handle = owner
            onDispose { host.dismiss(owner, notify = false) }
        }
    } else {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            GlassWindowHost { currentContent { currentDismiss() } }
        }
    }
}
