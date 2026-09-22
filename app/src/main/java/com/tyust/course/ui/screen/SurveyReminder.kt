package com.tyust.course.ui.screen

import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.tyust.course.survey.Survey
import com.tyust.course.survey.SurveyRepository
import com.tyust.course.survey.SurveyVisitTracker
import com.tyust.course.ui.system.SystemDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SurveyReminder(repository: SurveyRepository, canPresent: Boolean, foreground: Boolean, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by repository.state.collectAsState()
    val visit by SurveyVisitTracker.visit.collectAsState()
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var focus by remember { mutableStateOf(view.hasWindowFocus()) }
    var visible by remember(repository) { mutableStateOf<List<Survey>>(emptyList()) }
    DisposableEffect(view) {
        val observer = ViewTreeObserver.OnWindowFocusChangeListener { focus = it }
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(observer) }
    }
    LaunchedEffect(visit, foreground) { if (!foreground) visible = emptyList() }
    LaunchedEffect(state) {
        visible = visible.filter { survey -> state.saved.surveys.any { it.id == survey.id && it.isActive(state.serverNow(System.currentTimeMillis())) } && state.local(survey.id).completedAt == null }
    }
    LaunchedEffect(repository, state, canPresent, foreground, focus, visit) {
        if (!canPresent || !focus || !foreground || visible.isNotEmpty() || repository.reminderCandidates().isEmpty()) return@LaunchedEffect
        delay(1200)
        val candidates = repository.reminderCandidates()
        if (candidates.isNotEmpty() && SurveyVisitTracker.budget.claim()) {
            visible = candidates
            scope.launch {
                try { repository.markReminded(candidates) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The foreground budget still prevents repeated popups. */ }
            }
        }
    }
    if (visible.isNotEmpty() && canPresent && foreground && state.saved.remindersEnabled) {
        SystemDialog(ownerKey = "survey-reminder", onDismissRequest = { visible = emptyList() },
            title = { Text(if (visible.size == 1) "有一份问卷待填写" else "有 ${visible.size} 份问卷待填写") },
            confirmButton = { TextButton(onClick = { val id = visible.first().id; visible = emptyList(); onOpen(id) }) { Text("查看问卷") } },
            dismissButton = { TextButton(onClick = { visible = emptyList() }) { Text("稍后") } }) {
            Column(modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                visible.forEach { survey -> Text(survey.title, style = MaterialTheme.typography.titleMedium) }
            }
            Text("完成后请在问卷页标记“已填写”，即可停止提醒。未完成的问卷每天最多提醒一次。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { scope.launch { repository.setRemindersEnabled(false); visible = emptyList() } }) { Text("关闭问卷提醒") }
        }
    }
}
