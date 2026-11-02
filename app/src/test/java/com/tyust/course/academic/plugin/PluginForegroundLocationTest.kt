package com.tyust.course.academic.plugin

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PluginForegroundLocationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val handles = mutableListOf<String>()
    private fun add(handle: String, location: Boolean) {
        handles.add(handle)
        PluginForegroundWork.add(app, handle, PluginForegroundWork.Entry("synthetic", "Selected work", location) { PluginForegroundWork.remove(app, handle) })
    }
    @After fun clear() { handles.forEach { PluginForegroundWork.remove(app, it) } }
    @Test fun packagedServiceDeclaresLocationAndDataSyncTypesAndPermission() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, PluginForegroundService::class.java), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, info.foregroundServiceType)
        val permissions = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_LOCATION in permissions)
    }
    @Test fun dataSyncWorkCanStartWithoutLocationAndDoesNotRequestIt() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        add("synthetic-data", false)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, PluginForegroundWork.foregroundTypes())
    }
    @Test fun locationWorkRequiresAnExistingSystemGrantAndDowngradesAfterStopping() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertThrows(PluginException::class.java) { add("synthetic-denied", true) }
        assertFalse(PluginForegroundWork.items().any { it.needsLocation })
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        add("synthetic-data", false); add("synthetic-location", true)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, PluginForegroundWork.foregroundTypes())
        PluginForegroundWork.remove(app, "synthetic-location")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, PluginForegroundWork.foregroundTypes())
    }
}
