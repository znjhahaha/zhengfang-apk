package com.tyust.course.academic.plugin

import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.ui.system.GlassPageScaffold
import com.tyust.course.ui.system.SystemDialogButton
import com.tyust.course.ui.theme.CourseSelectorTheme

/** Borrows the running view. Closing this panel does not close the script session. */
class PluginUserscriptActivity : ComponentActivity() {
    private var session: UserscriptSession? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = PluginUserscripts.get(intent.getStringExtra("handle").orEmpty())
        setContent { CourseSelectorTheme {
            val live = session
            GlassPageScaffold(title = (live?.policy?.declaration?.optString("title") ?: "脚本任务") + " · 原页面", onBack = { finish() }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (live == null) Text("任务已停止，请回到插件核对进度后继续")
                    else {
                        val view by live.visible.collectAsState()
                        SystemDialogButton(onClick = { live.stop("stopped"); finish() }) { Text("停止任务") }
                        view?.let { web -> key(web) {
                            AndroidView(factory = { (web.parent as? ViewGroup)?.removeView(web); web }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { (it.parent as? ViewGroup)?.removeView(it) })
                        } } ?: Text("任务已停止")
                    }
                }
            }
        } }
    }
    override fun onResume() { super.onResume(); session?.activity = this }
    override fun onPause() { session?.activity = null; super.onPause() }
}
