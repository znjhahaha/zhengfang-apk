package com.tyust.course.academic.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.tyust.course.ui.system.GlassPageScaffold
import com.tyust.course.ui.system.GlassWindowHost
import com.tyust.course.ui.theme.CourseSelectorTheme
import org.json.JSONObject
import org.json.JSONArray

class NativePluginActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); PluginPages.refresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginPages.refresh()
        val pluginId = intent.getStringExtra("pluginId").orEmpty()
        val initial = intent.getStringExtra("pageId")?.takeIf { it.isNotBlank() }
            ?: PluginPages.registry.pages().firstOrNull { it.pluginId == pluginId }?.id.orEmpty()
        val first = if (initial.contains('/') || initial.isBlank()) initial else "$pluginId/$initial"
        val command = intent.getStringExtra("commandId")
        setContent { CourseSelectorTheme { GlassWindowHost {
            var history by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(JSONArray().put(JSONObject().put("route", first).put("params", runCatching { JSONObject(intent.getStringExtra("pageParams") ?: "{}") }.getOrDefault(JSONObject())).put("command", command)).toString()) }
            val revision by PluginPages.revision.collectAsState()
            val frames = PluginJson.objects(JSONArray(history))
            val frame = frames.last()
            val route = frame.getString("route")
            val activeCommand = frame.optString("command").takeIf { it.isNotBlank() }
            val page = remember(route, revision) { PluginPages.registry.page(route) }
            fun back() { if (frames.size > 1) history = JSONArray(frames.dropLast(1)).toString() else finish() }
            BackHandler { back() }
            LaunchedEffect(revision) { if (activeCommand == null && page == null) { val remaining = frames.filter { PluginPages.registry.page(it.optString("route")) != null }; if (remaining.isEmpty()) finish() else history = JSONArray(remaining).toString() } }
            GlassPageScaffold(title = page?.title ?: "插件工具", onBack = { back() }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    PluginPageContent(route, onNavigate = { requested, params ->
                        val next = if (requested.contains('/') || requested.startsWith("app.")) requested else "$pluginId/$requested"
                        if (next.startsWith("$pluginId/")) history = JSONArray((frames + JSONObject().put("route", next).put("params", params)).takeLast(32)).toString()
                        else PluginPages.open(this@NativePluginActivity, next, params)
                    }, onBack = { back() }, commandId = activeCommand, pluginId = pluginId, params = frame.optJSONObject("params") ?: JSONObject())
                }
            }
        } } }
    }
    companion object {
        fun open(context: Context, pkg: PluginPackage, pageId: String? = null, params: JSONObject = JSONObject()) { context.startActivity(Intent(context, NativePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id).putExtra("pageId", pageId).putExtra("pageParams", params.toString())) }
        fun command(context: Context, pkg: PluginPackage, commandId: String) { context.startActivity(Intent(context, NativePluginActivity::class.java).putExtra("pluginId", pkg.manifest.id).putExtra("commandId", commandId)) }
    }
}
