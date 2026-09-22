package com.tyust.course.academic.plugin

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// API 30+ AtomicFile uses rename-over-existing, which Windows cannot emulate.
// The same checks run on Android API 24 / 32 / 35 in PluginSchoolBindingDeviceTest.
@Config(sdk = [24], application = android.app.Application::class)
class PluginSchoolBindingTest : PluginSchoolBindingChecks() {
    override fun testContext(): Context = RuntimeEnvironment.getApplication()
}
