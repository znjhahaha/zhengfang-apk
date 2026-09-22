package com.tyust.course.academic.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tyust.course.academic.AcademicException
import com.tyust.course.academic.AcademicStatus
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

internal fun campusFixtureBytes(id: String = "example.campus.service", anonymous: Boolean = false): ByteArray {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val manifest = JSONObject(assets.open("academic-plugin/campus-service-manifest.json").bufferedReader().use { it.readText() }).put("id", id)
    val source = if (anonymous) """globalThis.plugin={service:{page:args=>({ok:true,data:{pageId:args.pageId,title:'Public service',blocks:[]}})}};"""
        else assets.open("academic-plugin/campus-service.js").bufferedReader().use { it.readText() }
    if (anonymous) {
        manifest.put("capabilities", JSONArray(listOf("service.page")))
        manifest.getJSONObject("service").put("authentication", JSONObject().put("mode", "none")).put("actions", JSONArray())
    }
    manifest.put("files", JSONObject().put("index.js", PluginJson.sha256(source.toByteArray())))
    return java.io.ByteArrayOutputStream().also { out -> java.util.zip.ZipOutputStream(out).use { zip ->
        for ((name, value) in mapOf("manifest.json" to manifest.toString(), "index.js" to source)) {
            zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
        }
    } }.toByteArray()
}

@RunWith(AndroidJUnit4::class)
class CampusServiceDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    @Test fun separateLoginCaptchaQueriesActionsAndLogoutRunInAndroidSandbox() = runBlocking {
        val pkg = AcademicProviderRegistry.packages().install(campusFixtureBytes(), true)
        AcademicProviderRegistry.reload()
        val one = ServicePluginSession(app, pkg, "academic-account-one")
        val two = ServicePluginSession(app, pkg, "academic-account-two")
        try {
            assertEquals(AcademicStatus.SUCCESS, one.login("demo", "demo").status)
            assertEquals(7, one.page("overview").getJSONArray("blocks").length())
            try { two.page("overview"); fail("service account state leaked") }
            catch (error: AcademicException) { assertEquals(AcademicStatus.SESSION_EXPIRED, error.status) }
            try { one.action("register", JSONObject(), false); fail("mutation accepted without confirmation") }
            catch (error: PluginException) { assertEquals(PluginErrorCode.VALIDATION_FAILED, error.code) }
            assertTrue(one.action("register", JSONObject(), true).getBoolean("confirmed"))
            assertEquals("已报名", one.page("records").getJSONArray("blocks").getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("value"))
            val old = one.session; one.logout(); assertTrue(old.retired); assertFalse(one.authenticated)
            assertEquals(AcademicStatus.CAPTCHA_REQUIRED, one.login("captcha", "demo").status)
            assertTrue(one.refreshCaptcha()!!.image.isNotEmpty())
            assertEquals(AcademicStatus.CAPTCHA_REQUIRED, one.submitCaptcha("wrong").status)
            assertEquals(AcademicStatus.SUCCESS, one.submitCaptcha("1234").status)
            assertEquals("可报名", one.page("records").getJSONArray("blocks").getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("value"))
        } finally { one.close(); two.close(); AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    }
    @Test fun publicServiceCanRestartItsAnonymousSessionAndScopeRevocationStopsUse() = runBlocking {
        val pkg = AcademicProviderRegistry.packages().install(campusFixtureBytes("test.public.service", true), true)
        var active = true
        val runtime = ServicePluginSession(app, pkg, "anonymous") { active }
        try {
            assertTrue(runtime.authenticated); runtime.page("overview")
            val old = runtime.session; runtime.logout()
            assertTrue(old.retired); assertFalse(runtime.session.retired); runtime.page("overview")
            active = false
            try { runtime.page("overview"); fail("scope revocation ignored") }
            catch (error: AcademicException) { assertEquals(AcademicStatus.SESSION_EXPIRED, error.status) }
        } finally { runtime.close(); AcademicProviderRegistry.packages().deactivate(pkg.manifest.id); AcademicProviderRegistry.reload() }
    }
}
