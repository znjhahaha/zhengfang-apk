package com.tyust.course.ui.route

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.tyust.course.manager.GradeBrowsePreferences
import com.tyust.course.manager.GradeBrowseScope
import com.tyust.course.manager.GradeBrowseSelection

internal fun applyGradeDefault(current: GradeBrowseSelection, chosen: Boolean, catalogChanged: Boolean,
    defaultId: String, defaultLabel: String): GradeBrowseSelection =
    if (defaultId.isNotBlank() && (current.termId.isBlank() || (!chosen && catalogChanged)))
        current.copy(termId = defaultId, termLabel = defaultLabel) else current

/** Saved instance state wins over disk; only explicit user choices are written to disk. */
@Composable
internal fun rememberGradeBrowseSelection(scope: GradeBrowseScope): MutableState<GradeBrowseSelection> {
    val context = LocalContext.current
    val preferences = remember(context) { GradeBrowsePreferences.from(context) }
    return rememberSaveable(scope.key, stateSaver = listSaver(
        save = { listOf(scope.key, it.termId, it.termLabel, it.tab) },
        restore = { if (it[0] == scope.key) GradeBrowseSelection(it[1] as String, it[2] as String, it[3] as Int) else null }
    )) { mutableStateOf(preferences.read(scope)) }
}
