package com.tyust.course.academic.plugin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginWebCleanupTest {
    private val pkg = PluginPackage(PluginManifest(JSONObject("""{
      "id":"test.web.cleanup","kind":"native","version":"1.0.0","apiVersion":3,
      "servers":[{"id":"one","origin":"https://one.test"},{"id":"two","origin":"https://two.test"}]
    }""")), "", "synthetic", false)

    @Test fun clearingCurrentAccountLeavesOtherServiceAndAccountProfilesIntact() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val accounts = PluginServiceAccounts(app)
        val other = accounts.select(pkg, "one", "Other")
        val otherProfile = accounts.profile(pkg, "one", other)
        val current = accounts.select(pkg, "one", "Current")
        val oldProfile = accounts.profile(pkg, "one", current)
        val second = accounts.select(pkg, "two", "Second website")
        val secondProfile = accounts.profile(pkg, "two", second)
        accounts.clearCurrent(pkg, "one", current)
        assertEquals(listOf(other), accounts.accounts(pkg.manifest.id, "one").map { it.getString("id") })
        assertEquals(otherProfile, accounts.profile(pkg, "one", other))
        assertEquals(second, accounts.selected(pkg.manifest.id, "two"))
        assertEquals(secondProfile, accounts.profile(pkg, "two", second))
        assertNotEquals(oldProfile, accounts.profile(pkg, "one"))
        assertEquals(setOf(oldProfile), app.getSharedPreferences("plugin-service-accounts", 0).getStringSet("retiredProfiles", emptySet()))
        // A new anonymous identity after cleanup must itself be removable.
        val anonymous = accounts.selected(pkg.manifest.id, "one")
        accounts.clearCurrent(pkg, "one", anonymous)
        assertNotEquals(anonymous, accounts.selected(pkg.manifest.id, "one"))
    }

    @Test fun staleConfirmationCannotClearNewlySelectedAccount() {
        val accounts = PluginServiceAccounts(ApplicationProvider.getApplicationContext())
        val old = accounts.select(pkg, "one", "Old")
        val current = accounts.select(pkg, "one", "New")
        val profile = accounts.profile(pkg, "one")
        assertThrows(PluginException::class.java) { accounts.clearCurrent(pkg, "one", old) }
        assertEquals(current, accounts.selected(pkg.manifest.id, "one"))
        assertEquals(profile, accounts.profile(pkg, "one"))
        assertEquals(2, accounts.accounts(pkg.manifest.id, "one").size)
    }
}
