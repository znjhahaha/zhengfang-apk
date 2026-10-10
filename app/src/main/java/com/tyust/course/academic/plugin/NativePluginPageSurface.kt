package com.tyust.course.academic.plugin

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.tyust.course.ui.system.*
import kotlinx.coroutines.delay
import org.json.JSONObject

data class NativePageNavigation(val key: String, val title: String, val depth: Int, val backEvent: String?) {
    companion object {
        fun from(result: JSONObject): NativePageNavigation? = result.optJSONObject("navigation")?.let {
            val depth = it.getInt("depth")
            val back = it.optString("backEvent").takeIf(String::isNotBlank)
            if ((depth > 0) != (back != null)) throw PluginException(PluginErrorCode.VALIDATION_FAILED, "子页面需要声明返回事件，根页面不能拦截返回")
            NativePageNavigation(it.getString("key"), it.getString("title"), depth, back)
        }
    }
}

@Composable
internal fun NativePluginPageSurface(
    snapshot: NativeUiSnapshot, files: NativePluginFiles, title: String, standalone: Boolean,
    viewportModifier: Modifier, onExit: () -> Unit, onRetry: () -> Unit, emit: (JSONObject, Boolean) -> Unit
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dialogs = LocalDialogHost.current
    val reduced = rememberGlassAccessibilityMode().reduceMotion
    val listStates = rememberSaveableStateHolder()
    var backPending by remember(snapshot.instance) { mutableStateOf(false) }
    LaunchedEffect(snapshot.busy, snapshot.view) { if (!snapshot.busy) backPending = false }
    val back: () -> Unit = {
        focus.clearFocus(); keyboard?.hide()
        val event = snapshot.navigation?.backEvent
        if (event == null) onExit() else if (!backPending) {
            backPending = true
            emit(JSONObject().put("type", "click").put("name", event), true)
        }
    }
    BackHandler(enabled = snapshot.navigation?.backEvent != null && dialogs?.hasBlockingSurface != true) { back() }
    var showBusy by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.busy) {
        if (snapshot.busy) { delay(180); showBusy = true } else showBusy = false
    }
    val content: @Composable (PaddingValues) -> Unit = { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(viewportModifier.widthIn(max = 840.dp).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!standalone && snapshot.navigation?.backEvent != null) Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SystemIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", back)
                    Text(snapshot.navigation.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                if (snapshot.error.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(snapshot.error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    SystemActionButton("重试", onRetry)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(targetState = snapshot, contentKey = { it.navigation?.key ?: it.instance }, label = "plugin-page",
                        transitionSpec = {
                            val direction = if ((targetState.navigation?.depth ?: 0) >= (initialState.navigation?.depth ?: 0)) 1 else -1
                            val duration = if (reduced) 0 else 220
                            ((fadeIn(tween(duration)) + slideInHorizontally(tween(duration)) { if (reduced) 0 else it / 16 * direction }) togetherWith
                                (fadeOut(tween(duration / 2)) + slideOutHorizontally(tween(duration)) { if (reduced) 0 else -it / 32 * direction }))
                                .using(SizeTransform(clip = false))
                        }) { frame ->
                        frame.view?.let { view ->
                            NativePluginNode(view, files, Modifier.fillMaxSize(),
                                enabled = frame.navigation?.key == snapshot.navigation?.key && frame.instance == snapshot.instance,
                                inputValues = frame.inputValues, listStates = listStates,
                                listNamespace = frame.instance + "/" + frame.navigation?.key.orEmpty(), emit = emit)
                        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GlassLoadingIndicator() }
                    }
                    if (showBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
                }
            }
        }
    }
    if (standalone) GlassPageScaffold(title = snapshot.navigation?.title ?: title, onBack = back, content = content)
    else content(PaddingValues())
}
