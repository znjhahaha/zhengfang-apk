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
class PluginWebContractTest {
    private fun manifest() = PluginManifest(JSONObject("""{
      "id":"demo.browser","name":"Browser","version":"1.0.0","kind":"native","apiVersion":3,"minAppVersionCode":107,
      "capabilities":[],"permissions":[],"network":[{"origin":"https://site.test","pathPrefix":"/","methods":["GET"],"purposes":["query"]}],
      "servers":[{"id":"site","title":"Site","origin":"https://site.test","authentication":"web"}],
      "contributes":{"pages":[{"id":"home","title":"Home","renderer":"web","serverId":"site","path":"/","web":{"mode":"browser","navigationOrigins":["https://login.test"]}}],"entries":[]}}
    """))
    @Test fun genericWebNeedsNoAcademicMethodsOrSchool() { val m=manifest(); PluginPlatformContract.validate(m); assertFalse(m.isAcademic) }
    @Test fun oldHostIsRejectedAndLegacyPageStillValid() {
        val m=manifest(); assertThrows(PluginException::class.java) { PluginPlatformContract.requireCompatible(m,106,emptyMap()) }
        m.json.put("minAppVersionCode",106); assertThrows(PluginException::class.java) { PluginPlatformContract.validate(m) }
        m.contributes.getJSONArray("pages").getJSONObject(0).remove("web"); PluginPlatformContract.validate(m)
    }
    @Test fun pureBrowserKeepsEncodedQueryAndSpaRouteWithoutWeakeningLegacyBridge() {
        val m=manifest(); val page=m.contributes.getJSONArray("pages").getJSONObject(0)
        page.put("path","/a%20b?q=%E4%BD%A0#/home"); PluginPlatformContract.validate(m)
        page.getJSONObject("web").put("mode","bridge")
        assertThrows(PluginException::class.java) { PluginPlatformContract.validate(m) }
    }
    @Test fun genericPagesMatchAllSchoolsUntilAnExplicitScopeIsDeclared() {
        val m=manifest(); val pkg=PluginPackage(m,"", "synthetic",false)
        val a=com.tyust.course.model.SchoolConfig("a","A","a.test","https")
        val b=com.tyust.course.model.SchoolConfig("b","B","b.test","https")
        assertTrue(AcademicProviderRegistry.matches(pkg,a)); assertTrue(AcademicProviderRegistry.matches(pkg,b))
        m.json.put("matches",org.json.JSONArray("""[{"host":"a.test","pathPrefix":"/"}]"""))
        assertTrue(AcademicProviderRegistry.matches(pkg,a)); assertFalse(AcademicProviderRegistry.matches(pkg,b))
    }
    @Test fun navigationOriginsAreExactHttps() {
        for (origin in listOf("http://login.test", "https://login.test/path", "https://u:p@login.test", "https://login.test:443", "https://*.test")) {
            val m=manifest(); m.contributes.getJSONArray("pages").getJSONObject(0).getJSONObject("web").getJSONArray("navigationOrigins").put(0,origin)
            assertThrows(origin,PluginException::class.java) { PluginPlatformContract.validate(m) }
        }
    }
}
