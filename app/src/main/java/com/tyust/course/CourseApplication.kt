package com.tyust.course

import android.app.Application
import com.tyust.course.manager.AppearanceSettingsManager
import com.tyust.course.manager.AppThemeCoordinator
import com.tyust.course.ui.system.GlassRuntimeGuard

class CourseApplication : Application() {
    private var mainProcess = false
    override fun onCreate() {
        super.onCreate()
        GlassRuntimeGuard.initialize(this)
        AppearanceSettingsManager.initialize(this)
        AppThemeCoordinator.initialize(this)
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName() else {
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        if (processName == packageName) {
            mainProcess = true
            com.tyust.course.schedule.ScheduleReminderScheduler.get(this).start(this)
            com.tyust.course.schedule.ScheduleWidgetUpdater.start(this)
            com.tyust.course.usage.UsageStatsManager.initialize(this)
            com.tyust.course.survey.SurveyVisitTracker.initialize(this)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppThemeCoordinator.configurationChanged()
        if (mainProcess) com.tyust.course.schedule.ScheduleWidgetUpdater.update(this)
    }
}
