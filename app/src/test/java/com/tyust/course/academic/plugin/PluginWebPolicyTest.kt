package com.tyust.course.academic.plugin

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginWebPolicyTest {
    private fun page(mode: String = "browser") = JSONObject("""{"path":"/app","web":{"mode":"$mode","navigationOrigins":["https://login.test"]}}""")
    @Test fun browserNeedsProfilesButNeverBridgeFeatureOrPermissions() {
        val p = PluginWebPolicy("https://site.test", page())
        assertTrue(p.supported(true, false)); assertFalse(p.supported(false, true))
        assertFalse(p.bridges("https://site.test/app")); assertFalse(p.bridges("https://login.test/"))
    }
    @Test fun allowlistNavigationDoesNotExpandBridge() {
        val p = PluginWebPolicy("https://site.test", page("bridge"))
        assertTrue(p.allows("https://login.test/auth?next=app")); assertFalse(p.bridges("https://login.test/auth"))
        assertTrue(p.bridges("https://site.test/app")); assertFalse(p.supported(true, false))
    }
    @Test fun legacyDefaultsRemainBridgeAndOnlySameOrigin() {
        val p = PluginWebPolicy("https://site.test", JSONObject())
        assertTrue(p.bridges("https://site.test/")); assertFalse(p.allows("https://login.test/"))
    }
    @Test fun malformedAndLookalikeNavigationCannotEnter() {
        val p = PluginWebPolicy("https://site.test", page())
        for (url in listOf("https://site.test.attacker.test", "https://user@site.test/", "javascript:alert(1)", "file:///a", "https://site.test:444/", "http://site.test/")) assertFalse(url, p.allows(url))
    }
    @Test fun externalLinksAreExplicitHttpOnly() {
        val p = PluginWebPolicy("https://site.test", page())
        assertTrue(p.external("https://other.test/")); assertFalse(p.external("intent://secret")); assertFalse(p.external("https://u:p@other.test/"))
    }
    @Test fun webScopeSurvivesAcademicEpochChangesButBridgeDoesNot() {
        assertEquals(PluginWebPolicy.academicScope(page(), 1), PluginWebPolicy.academicScope(page(), 2))
        assertNotEquals(PluginWebPolicy.academicScope(page("bridge"), 1), PluginWebPolicy.academicScope(page("bridge"), 2))
    }
    @Test fun failedOrSubmittedDocumentCannotSilentlyReplay() {
        val state = PluginWebState()
        state.navigation("https://site.test/submit", "POST"); state.ready()
        assertFalse(state.canReplay); assertFalse(state.loading); assertTrue(state.firstContent)
        state.fail("网络断开"); assertFalse(state.canReplay); assertFalse(state.loading)
        state.navigation("https://site.test/", "GET"); assertTrue(state.canReplay)
    }
    @Test fun independentRetainedPagesDoNotShareHistoryAndAreBounded() {
        val owner = PluginWebRetainer(); val a = owner.obtain("a:account1"); a.url = "https://a.test/page"
        assertSame(a, owner.obtain("a:account1")); assertNotSame(a, owner.obtain("a:account2"))
        repeat(9) { owner.obtain("other:$it") }
        assertNotSame(a, owner.obtain("a:account1"))
    }
}
