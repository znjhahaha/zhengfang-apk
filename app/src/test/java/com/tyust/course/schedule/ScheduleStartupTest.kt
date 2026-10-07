package com.tyust.course.schedule

import com.tyust.course.manager.StartupPage
import com.tyust.course.manager.StartupPagePreferences
import com.tyust.course.academic.plugin.PluginPageRegistry
import com.tyust.course.academic.plugin.PluginManifest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ScheduleStartupTest {
    @Test fun absentAndInvalidLegacySettingsDoNotOverrideAnExplicitPluginChoice() {
        val raw = MemoryPreferences()
        val preferences = StartupPagePreferences(raw)
        assertNull(preferences.readExplicit())
        raw.edit().putString("startup_page", "removed-page").commit()
        assertNull(preferences.readExplicit())
        assertEquals(StartupPage.Schedule, StartupPage.decode("removed-page"))
        val registry = PluginPageRegistry(JSONObject().put("startup", PluginPageRegistry.SERVICES))
        registry.migrateStartup(preferences.readExplicit()?.let { "app.${it.route}" })
        assertEquals(PluginPageRegistry.SERVICES, registry.startup())
    }

    @Test fun explicitScheduleWinsLegacyConflictOnlyOnceAndSurvivesRestart() {
        val raw = MemoryPreferences()
        raw.edit().putString("startup_page", "schedule").commit()
        var saved = JSONObject()
        val registry = PluginPageRegistry(JSONObject().put("startup", PluginPageRegistry.SERVICES)) { saved = it }
        val legacy = StartupPagePreferences(raw).readExplicit()!!.let { "app.${it.route}" }
        registry.migrateStartup(legacy)
        assertEquals(PluginPageRegistry.SCHEDULE, registry.startup())
        registry.setStartup("app.grades")
        val restarted = PluginPageRegistry(saved)
        restarted.migrateStartup(legacy)
        assertEquals("app.grades", restarted.startup())
    }

    @Test fun freshInstallAndUnavailableTargetsFallBackToScheduleIndependentlyOfPinnedOrder() {
        val registry = PluginPageRegistry()
        registry.customize(listOf(PluginPageRegistry.SETTINGS, PluginPageRegistry.SERVICES))
        registry.migrateStartup(null)
        assertEquals(PluginPageRegistry.SCHEDULE, registry.startup())
        val unknown = PluginPageRegistry(JSONObject().put("startup", "removed.plugin/home"))
        unknown.migrateStartup(null)
        assertEquals(PluginPageRegistry.SCHEDULE, unknown.startup())
        assertEquals("removed.plugin/home", unknown.preferredStartup())
    }

    @Test fun temporarilyDisabledPluginKeepsItsPreferenceUntilAvailableAgain() {
        val manifest = PluginManifest(JSONObject("""{"id":"demo.plugin","name":"Demo","version":"1.0.0","kind":"native","apiVersion":3,"capabilities":["ui.init","ui.reduce"],"permissions":[],"network":[],"contributes":{"pages":[{"id":"home","title":"Home"}],"entries":[]}}"""))
        var saved = JSONObject()
        val registry = PluginPageRegistry(persist = { saved = it })
        registry.synchronize(listOf(manifest), setOf("demo.plugin"), emptyMap())
        registry.setStartup("demo.plugin/home")
        registry.synchronize(listOf(manifest), emptySet(), emptyMap())
        assertEquals(PluginPageRegistry.SCHEDULE, registry.startup())
        assertEquals("demo.plugin/home", registry.preferredStartup())
        val restarted = PluginPageRegistry(saved)
        restarted.migrateStartup("app.grades")
        restarted.synchronize(listOf(manifest), setOf("demo.plugin"), emptyMap())
        assertEquals("demo.plugin/home", restarted.startup())
    }

    @Test fun eitherSettingsEntryWritesTheSameStartupRoute() {
        val registry = PluginPageRegistry()
        registry.migrateStartup(null)
        registry.setStartup(PluginPageRegistry.SERVICES)
        assertEquals(PluginPageRegistry.SERVICES, registry.preferredStartup())
        registry.setStartup(PluginPageRegistry.SCHEDULE)
        assertEquals(PluginPageRegistry.SCHEDULE, registry.startup())
    }
}
