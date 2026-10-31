package com.tyust.course.academic.plugin.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.academic.plugin.AcademicProviderRegistry
import com.tyust.course.academic.plugin.PluginPackage
import com.tyust.course.academic.plugin.PluginPages
import com.tyust.course.academic.plugin.PluginWidgetContract
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Per-instance configuration: choose a declared plugin widget, its view and options.
 *
 * Launched by the launcher when the user adds a widget (and by "add to home screen" in the plugin
 * center, which pins the widget through the system pin flow first).
 */
class PluginWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val family = familyOf(manager.getAppWidgetInfo(id)?.provider)
        if (id <= 0 || family == null) {
            finish()
            return
        }
        if (PluginWidgetPinRequests.isCallback(intent) && savedInstanceState == null &&
            !PluginWidgetPinRequests.consume(this, intent, family)) {
            finish()
            return
        }
        setResult(Activity.RESULT_CANCELED)
        setContent {
            CourseSelectorTheme {
                GlassWindowHost {
                    PluginWidgetConfigScreen(this@PluginWidgetConfigActivity, id, family)
                }
            }
        }
    }

    private fun familyOf(provider: ComponentName?): PluginWidgetContract.Family? = when (provider?.className) {
        PluginWidgetSmallProvider::class.java.name -> PluginWidgetContract.Family.Small
        PluginWidgetMediumProvider::class.java.name -> PluginWidgetContract.Family.Medium
        PluginWidgetLargeProvider::class.java.name -> PluginWidgetContract.Family.Large
        else -> null
    }

    companion object {
        fun open(context: Context, appWidgetId: Int) = context.startActivity(
            Intent(context, PluginWidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private data class Entry(
    val pkg: PluginPackage,
    val declaration: PluginWidgetContract.Declaration,
)

@Composable
private fun PluginWidgetConfigScreen(activity: Activity, appWidgetId: Int, family: PluginWidgetContract.Family) {
    val context = activity
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(0) }
    var viewKey by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf("auto") }
    var options by remember { mutableStateOf<JSONObject>(JSONObject()) }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<android.widget.RemoteViews?>(null) }
    val existing = remember { PluginWidgetBindings.read(context, appWidgetId) }
    val dark = isSystemInDarkTheme()

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { AcademicProviderRegistry.packages().list() }.getOrNull().orEmpty()
                .filter { PluginPages.available(it) && PluginWidgetContract.declaresWidgets(it.manifest) }
                .flatMap { pkg ->
                    PluginWidgetContract.declarations(pkg.manifest)
                        .filter { declaration -> declaration.sizes.any { it in family.sizes } }
                        .map { declaration -> Entry(pkg, declaration) }
                }
        }
        entries = loaded
        loading = false
        if (loaded.isNotEmpty()) {
            val index = existing?.let { saved ->
                loaded.indexOfFirst { it.pkg.manifest.id == saved.pluginId && it.declaration.id == saved.widgetId }
            }?.takeIf { it >= 0 } ?: 0
            selected = index
            val declaration = loaded[index].declaration
            viewKey = existing?.viewKey?.takeIf { key -> declaration.views.any { it.key == key } }
                ?: declaration.viewKey(null)
            theme = existing?.theme ?: "auto"
            options = existing?.options ?: declaration.options.associate { option ->
                option.id to option.default
            }.let { map -> JSONObject().also { json -> map.forEach { (key, value) -> json.put(key, value) } } }
        }
    }

    val entry = entries.getOrNull(selected)
    LaunchedEffect(entry, viewKey, theme, options, dark) {
        val current = entry ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            runCatching {
                val declaration = current.declaration
                val binding = PluginWidgetBindings.Binding(
                    appWidgetId = appWidgetId,
                    pluginId = current.pkg.manifest.id,
                    widgetId = declaration.id,
                    viewKey = viewKey,
                    template = declaration.template,
                    theme = theme,
                    account = "",
                    options = options,
                    size = family.sizes.firstOrNull { it in declaration.sizes } ?: family.sizes.first(),
                    boundAt = System.currentTimeMillis(),
                )
                val snapshot = WidgetSnapshotStore.read(context, binding, declaration) ?: WidgetSnapshotStore.Snapshot(
                    pluginId = binding.pluginId,
                    widgetId = binding.widgetId,
                    viewKey = binding.viewKey,
                    updatedAt = System.currentTimeMillis(),
                    root = JSONObject().put(
                        "snapshot",
                        JSONObject()
                            .put("schema", 1)
                            .put("updatedAt", System.currentTimeMillis())
                            .put("header", JSONObject().put("title", declaration.title).put("subtitle", "示例数据"))
                            .put(
                                "rows",
                                org.json.JSONArray()
                                    .put(JSONObject().put("id", "r1").put("title", "第一条内容").put("subtitle", "副标题"))
                                    .put(JSONObject().put("id", "r2").put("title", "第二条内容")),
                            ),
                    ),
                )
                PluginWidgetRenderer.views(
                    context, current.pkg, binding, snapshot, declaration.staleAfterSeconds,
                    if (family == PluginWidgetContract.Family.Small) 200 else 320,
                    if (family == PluginWidgetContract.Family.Large) 160 else 120,
                )
            }.getOrNull()
        }
    }

    GlassPageScaffold(title = "小组件设置", onBack = { activity.finish() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(family.label + " 小组件", style = MaterialTheme.typography.titleMedium)
            preview?.let { remote ->
                Box(Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { FrameLayout(it) },
                        modifier = Modifier.fillMaxWidth().height(170.dp),
                        update = { host ->
                            runCatching {
                                host.removeAllViews()
                                host.addView(remote.apply(host.context, host))
                                disableInteraction(host)
                            }
                        },
                    )
                }
            }
            when {
                loading -> PageLoadingIcon("app.plugin.widget")
                entries.isEmpty() -> Text("没有可用的小组件插件。请先在插件中心安装并启用带小组件的插件。")
                else -> {
                    Text("插件与小组件", Modifier.fillMaxWidth())
                    entries.forEachIndexed { index, item ->
                        OutlinedButton(
                            onClick = {
                                selected = index
                                viewKey = item.declaration.viewKey(null)
                                options = item.declaration.options.associate { option -> option.id to option.default }
                                    .let { map ->
                                        JSONObject().also { json ->
                                            map.forEach { (key, value) -> json.put(key, value) }
                                        }
                                    }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (index == selected) MaterialTheme.colorScheme.primaryContainer else
                                    MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(item.pkg.manifest.name + " · " + item.declaration.title)
                                Text(
                                    item.declaration.description.ifBlank { item.pkg.manifest.id },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    val declaration = entry?.declaration
                    if (declaration != null) {
                        val views = declaration.views
                        if (views.isNotEmpty()) {
                            Text("显示视图", Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                views.forEach { view ->
                                    FilterChip(
                                        selected = view.key == viewKey,
                                        onClick = { viewKey = view.key },
                                        label = { Text(view.title.ifBlank { view.key }) },
                                    )
                                }
                            }
                        }
                        Text("主题", Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, label) ->
                                FilterChip(
                                    selected = theme == key,
                                    onClick = { theme = key },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (declaration.options.isNotEmpty()) {
                            Text("选项", Modifier.fillMaxWidth())
                            declaration.options.forEach { option ->
                                when (option.type) {
                                    "boolean" -> Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(option.title.ifBlank { option.id }, Modifier.weight(1f))
                                        Switch(
                                            checked = options.optBoolean(option.id, option.default as? Boolean ?: false),
                                            onCheckedChange = { checked -> options = JSONObject(options.toString()).put(option.id, checked) },
                                        )
                                    }
                                    "number" -> OptionField(option.title.ifBlank { option.id }, options.optString(option.id)) { raw ->
                                        val value = raw.toDoubleOrNull()
                                        options = JSONObject(options.toString()).put(
                                            option.id,
                                            value ?: JSONObject.NULL,
                                        )
                                    }
                                    else -> OptionField(option.title.ifBlank { option.id }, options.optString(option.id)) { raw ->
                                        options = JSONObject(options.toString()).put(option.id, raw)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            SystemDialogButton(primary = true, enabled = entry != null && !loading, onClick = {
                val item = entry
                if (item == null) {
                    error = "请选择一个小部件"
                    return@SystemDialogButton
                }
                val declaration = item.declaration
                runCatching {
                    check(PluginPages.available(item.pkg)) { "插件已停用，请重新选择" }
                    PluginWidgetBindings.save(
                        context,
                        PluginWidgetBindings.Binding(
                            appWidgetId = appWidgetId,
                            pluginId = item.pkg.manifest.id,
                            widgetId = declaration.id,
                            viewKey = declaration.viewKey(viewKey),
                            template = declaration.template,
                            theme = theme,
                            account = "",
                            options = options,
                            size = family.sizes.firstOrNull { it in declaration.sizes } ?: family.sizes.first(),
                            boundAt = System.currentTimeMillis(),
                        ),
                    )
                    PluginWidgets.refreshId(context, appWidgetId)
                    PluginWidgets.rescheduleCountdown(context)
                    if (!declaration.push) PluginWidgetPullService.requestImmediate(context, item.pkg.manifest.id)
                    activity.setResult(
                        Activity.RESULT_OK,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                    )
                    activity.finish()
                }.onFailure { error = it.message ?: "保存失败" }
            }) { Text("保存到这个组件") }
        }
    }
}

@Composable
private fun OptionField(title: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(title) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun disableInteraction(view: android.view.View) {
    view.isClickable = false
    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) disableInteraction(view.getChildAt(index))
}
