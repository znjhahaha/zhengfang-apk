package com.tyust.course.schedule

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tyust.course.ui.system.*
import com.tyust.course.ui.theme.CourseSelectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class ScheduleWidgetAppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val provider = manager.getAppWidgetInfo(id)?.provider
        val style = ScheduleWidgetStyle.entries.firstOrNull { ComponentName(this, it.provider) == provider }
        if (id <= 0 || style == null) { finish(); return }
        setResult(Activity.RESULT_CANCELED)
        setContent { CourseSelectorTheme { GlassWindowHost {
            val saved = remember { ScheduleWidgetAppearances.read(this@ScheduleWidgetAppearanceActivity, id) }
            var image by rememberSaveable { mutableStateOf(saved.image) }
            var opacity by rememberSaveable { mutableIntStateOf(saved.opacity) }
            var blur by rememberSaveable { mutableIntStateOf(saved.blur) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val appearance = ScheduleWidgetAppearance(image, opacity, blur)
            val latestImage by rememberUpdatedState(image)
            DisposableEffect(Unit) { onDispose {
                if (isFinishing && latestImage != saved.image) ScheduleWidgetAppearances.discardImage(this@ScheduleWidgetAppearanceActivity, latestImage)
            } }
            val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) scope.launch {
                    busy = true; error = ""
                    try {
                        val next = withContext(Dispatchers.IO) { ScheduleWidgetAppearances.importImage(this@ScheduleWidgetAppearanceActivity, uri) }
                        if (image != saved.image) ScheduleWidgetAppearances.discardImage(this@ScheduleWidgetAppearanceActivity, image)
                        image = next
                    } catch (e: Exception) { error = e.message ?: "图片读取失败" } finally { busy = false }
                }
            }
            val state = remember { widgetAppearancePreview() }
            val width = if (style == ScheduleWidgetStyle.Single) 160 else 300
            val height = if (style == ScheduleWidgetStyle.Double) 150 else if (style == ScheduleWidgetStyle.Single) 160 else 280
            var views by remember { mutableStateOf<android.widget.RemoteViews?>(null) }
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            LaunchedEffect(appearance, dark) {
                kotlinx.coroutines.delay(60)
                views = withContext(Dispatchers.Default) { ScheduleWidgetRenderer.views(this@ScheduleWidgetAppearanceActivity, state, width, height, style, appearance) }
            }
            GlassPageScaffold(title = "组件外观", onBack = { finish() }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(style.title + " · 独立设置", style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.fillMaxWidth().height(height.dp), contentAlignment = Alignment.Center) {
                        AndroidView(factory = { FrameLayout(it) }, modifier = Modifier.width(width.dp).height(height.dp), update = { host ->
                            views?.let { remote -> host.removeAllViews(); host.addView(remote.apply(host.context, host))
                                fun disable(view: android.view.View) { view.isClickable = false; if (view is android.view.ViewGroup) for (i in 0 until view.childCount) disable(view.getChildAt(i)) }
                                disable(host)
                            }
                        })
                    }
                    Text("示例预览；桌面组件显示当前账号的课表。", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SystemActionButton("选择背景图片", { pick.launch("image/*") }, enabled = !busy)
                        SystemActionButton("使用纯色", {
                            if (image != saved.image) ScheduleWidgetAppearances.discardImage(this@ScheduleWidgetAppearanceActivity, image)
                            image = ""
                        }, enabled = image.isNotBlank())
                    }
                    Text("背景不透明度 " + opacity + "%", Modifier.fillMaxWidth())
                    LiquidSlider({ opacity.toFloat() }, { opacity = it.toInt() }, valueRange = 0f..100f)
                    Text("图片模糊 " + blur, Modifier.fillMaxWidth())
                    LiquidSlider({ blur.toFloat() }, { blur = it.toInt() }, valueRange = 0f..32f, enabled = image.isNotBlank(), steps = 31)
                    if (busy) PageLoadingIcon("app.schedule")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    SystemDialogButton(primary = true, enabled = !busy, onClick = {
                        ScheduleWidgetAppearances.save(this@ScheduleWidgetAppearanceActivity, id, appearance)
                        setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                    }) { Text("保存到这个组件") }
                }
            }
        } } }
    }
    companion object {
        fun open(context: Context, id: Int) = context.startActivity(Intent(context, ScheduleWidgetAppearanceActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
    }
}

private fun widgetAppearancePreview(): ScheduleWidgetState {
    val monday = ScheduleDates.mondayOfWeek(System.currentTimeMillis())
    val now = (monday.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 10) }.timeInMillis
    val base = ScheduleTimeBase(ScheduleTimeBase.dateFromMillis(monday.timeInMillis), mapOf(1 to "08:00", 3 to "10:00", 5 to "14:00"), mapOf(2 to "09:40", 4 to "11:40", 6 to "15:40"))
    val courses = listOf(ScheduleCourseRecord("a", "高等数学", "张老师", "博学楼 A205", 1, 1, 2, "1-16"),
        ScheduleCourseRecord("b", "计算机网络", "李老师", "明理楼 B302", 1, 3, 4, "1-16"),
        ScheduleCourseRecord("c", "大学英语", "陈老师", "博学楼 A102", 1, 5, 6, "1-16"))
    return ScheduleWidgetState.from(ScheduleSnapshot("", "", "", courses, base, now, true), now)
}
