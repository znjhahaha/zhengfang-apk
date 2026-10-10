package com.tyust.course.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tyust.course.schedule.*
import com.tyust.course.ui.system.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable fun ScheduleAdjustmentsScreen(courses: List<ScheduleCourseUi>, base: ScheduleTimeBase, periodCount: Int,
    initialDate: String, initialCourseId: String? = null, onSave: (ScheduleAdjustments) -> Unit, onClose: () -> Unit) {
    var mode by remember { mutableIntStateOf(if (initialCourseId == null) 0 else 1) }
    var date by remember { mutableStateOf(initialDate) }
    var target by remember { mutableStateOf(initialDate) }
    var cancel by remember { mutableStateOf(true) }
    var courseIndex by remember { mutableIntStateOf(courses.indexOfFirst { it.id == initialCourseId }.coerceAtLeast(0)) }
    val course = courses.getOrNull(courseIndex)
    var start by remember(course?.id) { mutableStateOf(course?.startPeriod?.toString().orEmpty()) }
    var end by remember(course?.id) { mutableStateOf(course?.endPeriod?.toString().orEmpty()) }
    var location by remember(course?.id) { mutableStateOf(course?.location.orEmpty()) }
    var error by remember { mutableStateOf("") }
    var holidays by remember { mutableStateOf(false) }
    val unresolved = remember(courses, base) { base.adjustments.unresolved(courses.map { it.record() }, base).toSet() }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("节假日与调课", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onClose) { Text("完成") }
        }
        Text("调整只影响当前账号的这个学期，刷新课表后仍会保留。", style = MaterialTheme.typography.bodySmall)
        SystemDialogButton(onClick = { holidays = true }) { Text("查看法定节假日参考") }
        SystemPicker(listOf("整日安排", "单次课程"), mode, { mode = it }, Modifier.fillMaxWidth())
        OutlinedTextField(date, { date = it }, label = { Text("原日期（yyyy-MM-dd）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (mode == 1 && courses.isNotEmpty()) SystemPicker(courses.map { it.name + " · 周" + it.day + " " + it.startPeriod + "–" + it.endPeriod + "节" },
            courseIndex.coerceAtMost(courses.lastIndex), { courseIndex = it }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mode == 0) "当天停课" else "取消这一次课程")
            Switch(cancel, { cancel = it })
        }
        if (!cancel) {
            OutlinedTextField(target, { target = it }, label = { Text(if (mode == 0) "按哪一天的课表上课" else "调整到日期（yyyy-MM-dd）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (mode == 0) Text("读取所选日期原始课表；不会连带取消那一天的课程。", style = MaterialTheme.typography.bodySmall)
            else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(start, { start = it.filter(Char::isDigit).take(2) }, label = { Text("开始节次") },
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(end, { end = it.filter(Char::isDigit).take(2) }, label = { Text("结束节次") },
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(location, { location = it }, label = { Text("上课地点") }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        SystemDialogButton(primary = true, onClick = {
            error = when {
                ScheduleOccurrenceResolver.date(date) == null || (!cancel && ScheduleOccurrenceResolver.date(target) == null) -> "请填写有效日期，例如 2026-10-08"
                mode == 1 && course == null -> "请先添加或同步课程"
                mode == 1 && !cancel && (start.toIntOrNull() !in 1..periodCount || end.toIntOrNull() !in (start.toIntOrNull() ?: 1)..periodCount) -> "请检查开始和结束节次"
                else -> ""
            }
            if (error.isBlank()) {
                val rules = if (mode == 0) base.adjustments.withDay(ScheduleDayOverride(date, if (cancel) "" else target))
                    else base.adjustments.withLesson(ScheduleLessonOverride(course!!.id, date, cancel, if (cancel) date else target,
                        if (cancel) null else start.toInt(), if (cancel) null else end.toInt(), if (cancel) null else location))
                onSave(rules); GlassToaster.show("已保存调课安排")
            }
        }) { Text("确认应用") }
        Text("已保存的安排", style = MaterialTheme.typography.titleMedium)
        if (base.adjustments.isEmpty) Text("还没有调整", style = MaterialTheme.typography.bodySmall)
        base.adjustments.days.sortedBy { it.date }.forEach { rule ->
            InsetGroupedSection {
                InsetGroupedRow(title = rule.date, subtitle = if (rule.teachingDate.isBlank()) "停课 " + rule.note else "按 " + rule.teachingDate + " 的课表上课",
                    showDivider = false, trailing = { TextButton(onClick = { onSave(base.adjustments.copy(days = base.adjustments.days - rule)) }) { Text("撤销") } })
            }
        }
        base.adjustments.lessons.sortedBy { it.date }.forEach { rule ->
            val original = courses.firstOrNull { it.id == rule.courseId }
            InsetGroupedSection {
                InsetGroupedRow(title = rule.date + " · " + (original?.name ?: "课程已变化") + if (rule in unresolved) " · 待核对" else "",
                    subtitle = if (rule.cancelled) "取消本次" else rule.targetDate + " · " + (rule.startPeriod ?: original?.startPeriod) + "–" + (rule.endPeriod ?: original?.endPeriod) + "节 · " + rule.location.orEmpty(),
                    showDivider = false, trailing = { TextButton(onClick = { onSave(base.adjustments.copy(lessons = base.adjustments.lessons - rule)) }) { Text("撤销") } })
            }
        }
        Spacer(Modifier.height(40.dp))
    }
    if (holidays) ScheduleHolidayPicker(base, onDismiss = { holidays = false }, onApply = onSave)
}

@Composable private fun ScheduleHolidayPicker(base: ScheduleTimeBase, onDismiss: () -> Unit, onApply: (ScheduleAdjustments) -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    var year by remember { mutableIntStateOf(base.firstWeekDate.take(4).toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)) }
    var calendar by remember { mutableStateOf<ScheduleHolidayCalendar?>(null) }
    var error by remember { mutableStateOf("") }
    var selected by remember(year) { mutableStateOf(setOf<String>()) }
    LaunchedEffect(year) {
        calendar = null; error = ""
        try { calendar = withContext(Dispatchers.IO) { ScheduleHolidays.load(context, year) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "节假日数据加载失败" }
    }
    SystemDialog(onDismissRequest = onDismiss, title = { Text("法定节假日参考") }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { year-- }) { Text("上一年") }; Text(year.toString()); TextButton(onClick = { year++ }) { Text("下一年") }
            }
            Text("勾选学校实际停课的日期后应用。国家调休上班日仍需按学校通知设置课表。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            else if (calendar == null) PageLoadingIcon("app.schedule")
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                calendar?.days?.forEach { day ->
                    Row(Modifier.fillMaxWidth()) {
                        Checkbox(day.date in selected, onCheckedChange = { checked -> selected = if (checked) selected + day.date else selected - day.date }, enabled = day.off)
                        Text(day.date + " " + day.name + if (day.off) " · 放假参考" else " · 调休上班", Modifier.padding(top = 12.dp))
                    }
                }
            }
            calendar?.let { data -> TextButton(onClick = { uri.openUri(data.source) }) { Text("查看来源" + if (data.cached) " · 已缓存" else "") } }
            SystemDialogButton(primary = true, enabled = selected.isNotEmpty(), onClick = {
                var rules = base.adjustments
                calendar?.days?.filter { it.off && it.date in selected }?.forEach { rules = rules.withDay(ScheduleDayOverride(it.date, note = it.name)) }
                onApply(rules); onDismiss()
            }) { Text("确认将选中 " + selected.size + " 天设为停课") }
        }
    }
}
