package com.tyust.course.manager

import android.content.Context
import android.content.SharedPreferences

enum class StartupPage(val route: String, val label: String) {
    Courses("courses", "课程"),
    Schedule("schedule", "课表"),
    Grab("grab", "抢课"),
    Grades("grades", "成绩"),
    Settings("settings", "设置");

    companion object {
        fun decode(value: String?): StartupPage = entries.firstOrNull { it.route == value } ?: Schedule
    }
}

/** App preference shared across accounts; activity restoration still retains its current page. */
class StartupPagePreferences(private val preferences: SharedPreferences) {
    fun read(): StartupPage = StartupPage.decode(runCatching { preferences.getString(KEY, null) }.getOrNull())

    fun write(page: StartupPage) {
        preferences.edit().putString(KEY, page.route).apply()
    }

    companion object {
        private const val KEY = "startup_page"
        fun from(context: Context) = StartupPagePreferences(context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE))
    }
}
