package com.tyust.course.academic.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import com.tyust.course.ui.system.GlassDatePickerDialog
import com.tyust.course.ui.system.GlassDateTimePickerDialog
import com.tyust.course.ui.system.GlassTimePickerDialog
import com.tyust.course.ui.system.GlassTimeRangePickerDialog
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 插件调用宿主滚轮选择器时的待处理请求。
 *
 * 插件只给初始值和标题，用户确认后结果回传；界面完全复用 App 自己的
 * GlassDatePickerDialog / GlassTimePickerDialog / GlassDateTimePickerDialog /
 * GlassTimeRangePickerDialog（课表「第一周开始日期」「节次时间」用的是同一套）。
 */
internal class PluginPickerPrompt(val kind: String, val input: JSONObject) {
    val id = UUID.randomUUID().toString()
    val result = CompletableDeferred<JSONObject?>()
}

private const val DATE_PATTERN = "yyyy-MM-dd"
private const val DATETIME_PATTERN = "yyyy-MM-dd HH:mm"
private const val TIME_PATTERN = "HH:mm"

private fun formatMillis(millis: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.US).format(Date(millis))

private fun parseMillis(value: String, pattern: String): Long? =
    runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)?.time }.getOrNull()

private fun PluginPickerPrompt.title(fallback: String): String =
    input.optString("title").trim().ifBlank { fallback }

@Composable
internal fun NativePluginPickerPrompts(state: PageInteraction) {
    val prompt = state.pickerPrompt ?: return
    key(prompt.id) {
        when (prompt.kind) {
            "ui.date.pick" -> DatePick(prompt)
            "ui.time.pick" -> TimePick(prompt)
            "ui.datetime.pick" -> DateTimePick(prompt)
            "ui.time.range.pick" -> TimeRangePick(prompt)
        }
    }
}

@Composable
private fun DatePick(prompt: PluginPickerPrompt) {
    GlassDatePickerDialog(
        initialMillis = parseMillis(prompt.input.optString("initial"), DATE_PATTERN) ?: System.currentTimeMillis(),
        title = prompt.title("选择日期"),
        preserveInitial = true,
        onConfirm = { millis ->
            prompt.result.complete(JSONObject().put("date", formatMillis(millis, DATE_PATTERN)).put("millis", millis))
        },
        onDismiss = { prompt.result.complete(null) }
    )
}

@Composable
private fun TimePick(prompt: PluginPickerPrompt) {
    val initial = prompt.input.optString("initial").trim().ifBlank { formatMillis(System.currentTimeMillis(), TIME_PATTERN) }
    GlassTimePickerDialog(
        initialTime = initial,
        title = prompt.title("选择时间"),
        minuteStep = prompt.input.optInt("minuteStep", 5),
        preserveInitial = true,
        onConfirm = { time -> prompt.result.complete(JSONObject().put("time", time)) },
        onDismiss = { prompt.result.complete(null) }
    )
}

@Composable
private fun DateTimePick(prompt: PluginPickerPrompt) {
    GlassDateTimePickerDialog(
        initialMillis = parseMillis(prompt.input.optString("initial"), DATETIME_PATTERN) ?: System.currentTimeMillis(),
        title = prompt.title("选择时间"),
        preserveInitial = true,
        onConfirm = { millis ->
            prompt.result.complete(JSONObject().put("datetime", formatMillis(millis, DATETIME_PATTERN)).put("millis", millis))
        },
        onDismiss = { prompt.result.complete(null) }
    )
}

@Composable
private fun TimeRangePick(prompt: PluginPickerPrompt) {
    GlassTimeRangePickerDialog(
        initialStart = prompt.input.optString("initialStart").trim().ifBlank { "08:00" },
        initialEnd = prompt.input.optString("initialEnd").trim().ifBlank { "08:45" },
        title = prompt.title("编辑节次时间"),
        minuteStep = prompt.input.optInt("minuteStep", 5),
        preserveInitial = true,
        onConfirm = { start, end -> prompt.result.complete(JSONObject().put("start", start).put("end", end)) },
        onDismiss = { prompt.result.complete(null) }
    )
}
