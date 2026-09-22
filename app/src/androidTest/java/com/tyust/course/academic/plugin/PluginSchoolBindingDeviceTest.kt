package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tyust.course.BuildConfig
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginSchoolBindingDeviceTest : PluginSchoolBindingChecks() {
    override fun testContext(): Context {
        assumeTrue(BuildConfig.UI_PREVIEW)
        return ApplicationProvider.getApplicationContext()
    }
}
