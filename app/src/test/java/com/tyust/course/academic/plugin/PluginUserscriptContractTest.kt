package com.tyust.course.academic.plugin

import android.app.Application
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class PluginUserscriptContractTest {
    private fun sharedCookieManifest(origin: String, declaredOrigin: String = origin, authentication: String = "web") =
        PluginManifest(JSONObject().put("kind", "native").put("minAppVersionCode", 111)
            .put("network", JSONArray().put(JSONObject().put("origin", declaredOrigin)))
            .put("servers", JSONArray().put(JSONObject().put("authentication", authentication)
                .put("cookieOrigins", JSONArray().put(origin)))))

    @Test fun sharedCookieDeclarationsAcceptCanonicalHttpsOrigins() {
        for (origin in listOf("https://course.test", "https://course.test:8443")) {
            PluginUserscriptPolicy.validate(sharedCookieManifest(origin))
        }
    }

    @Test fun sharedCookieDeclarationsRejectNonOriginsAndUndeclaredDestinations() {
        for (origin in listOf("http://course.test", "https://course.test/", "https://course.test:443",
            "https://course.test/path", "https://course.test?query=1", "https://course.test#fragment",
            "https://user:secret@course.test", "https://*.course.test")) {
            assertThrows(origin, PluginException::class.java) { PluginUserscriptPolicy.validate(sharedCookieManifest(origin)) }
        }
        for (origin in listOf("https://other.test", "https://sub.course.test", "https://course.test:8443")) {
            assertThrows(origin, PluginException::class.java) {
                PluginUserscriptPolicy.validate(sharedCookieManifest(origin, "https://course.test"))
            }
        }
        assertThrows(PluginException::class.java) {
            PluginUserscriptPolicy.validate(sharedCookieManifest("https://course.test", authentication = "none"))
        }
    }

    @Test fun entryAndClassAdaptersRequireTheEmbeddedAccountAtInstallTime() {
        val json = JSONObject("""{
            "kind":"native","minAppVersionCode":112,"permissions":["network","userscripts"],
            "network":[{"origin":"https://course.test"}],
            "requires":[{"name":"userscript.start","version":1},{"name":"browser.session.open","version":1}],
            "servers":[{"id":"course","authentication":"web","browser":{"engine":"embedded","version":1}}],
            "userscripts":[{"id":"original","serverId":"course","sourcePrefix":"https://updates.test/scripts/1/",
                "updateUrl":"https://updates.test/scripts/1/code.meta.js","downloadUrl":"https://updates.test/scripts/1/code.user.js",
                "matches":[{"host":"course.test","pathPrefix":"/course"}],"connect":["course.test"],
                "adapter":{"entrySelector":"#entry","settings":[{"key":"video","selector":"#video","type":"boolean","enabledClass":"enabled"}]}}]
        }""")
        PluginUserscriptPolicy.validate(PluginManifest(json))
        json.getJSONArray("servers").getJSONObject(0).remove("browser")
        assertThrows(PluginException::class.java) { PluginUserscriptPolicy.validate(PluginManifest(json)) }
    }

    private fun policy(): PluginUserscriptPolicy {
        val manifest = PluginManifest(JSONObject("""{"id":"test.script","version":"1.0.0","apiVersion":3,"kind":"native","network":[{"origin":"https://course.test","pathPrefix":"/","methods":["GET","POST"],"purposes":["query","mutation"]}]}"""))
        val declaration = JSONObject("""{"id":"original","serverId":"course","sourcePrefix":"https://updates.test/scripts/1/","matches":[{"host":"course.test","pathPrefix":"/course"}],"exclude":[{"host":"course.test","pathPrefix":"/course/exam"}],"connect":["course.test"]}""")
        return PluginUserscriptPolicy(declaration, manifest)
    }
    @Test fun headNeedsBothScriptPermissionAndAReadOnlyNetworkRule() {
        val manifest = PluginManifest(JSONObject("""{"id":"test.script","version":"1.0.0","apiVersion":3,"kind":"native",
            "network":[{"origin":"https://course.test","pathPrefix":"/course","methods":["GET","HEAD"],"purposes":["query","mutation"]}]}"""))
        val declaration = JSONObject("""{"id":"original","serverId":"course","sourcePrefix":"https://updates.test/scripts/1/",
            "matches":[{"host":"course.test","pathPrefix":"/course"}],"exclude":[],"connect":["course.test"]}""")
        val policy = PluginUserscriptPolicy(declaration, manifest)
        val url = "https://course.test/course/redirect".toHttpUrl()
        assertThrows(PluginException::class.java) { policy.request(url, "HEAD", "query", null) }
        declaration.put("requestMethods", JSONArray().put("HEAD"))
        policy.request(url, "HEAD", "query", null)
        assertThrows(PluginException::class.java) { policy.request(url, "HEAD", "mutation", null) }
        assertThrows(PluginException::class.java) { policy.request("https://course.test/outside".toHttpUrl(), "HEAD", "query", null) }
    }
    private fun original(grants: String = "GM_getValue", download: String = "https://updates.test/scripts/1/original.user.js") = """
        // ==UserScript==
        // @name Original synthetic fixture
        // @version 1.0.0
        // @grant $grants
        // @run-at document-end
        // @updateURL https://updates.test/scripts/1/original.meta.js
        // @downloadURL $download
        // ==/UserScript==
        void 0;
    """.trimIndent()
    @Test fun originalMatchCannotExpandTheExecutionOrNetworkDeclaration() {
        val policy = policy()
        assertTrue(policy.executes("https://course.test/course/chapter?id=1"))
        for (url in listOf("https://course.test/course/exam/start", "https://course.test/courseware", "http://course.test/course", "https://course.test.attacker.test/course")) assertFalse(url, policy.executes(url))
        assertFalse(policy.resource("https://course.test:8443/api"))
        assertFalse(policy.resource("https://other.test/api"))
        assertThrows(PluginException::class.java) { policy.source("https://updates.test/scripts/2/code.user.js") }
        assertThrows(PluginException::class.java) { policy.source("https://updates.test/scripts/1/../2/code.user.js") }
    }
    @Test fun unsupportedUpdatesAndEscapedDownloadsAreRejectedBeforeActivation() {
        assertEquals("1.0.0", UserscriptMetadata.validate(original(), policy()).version)
        assertThrows(PluginException::class.java) { UserscriptMetadata.validate(original("GM_download"), policy()) }
        assertThrows(PluginException::class.java) { UserscriptMetadata.validate(original(download = "https://other.test/code.user.js"), policy()) }
        assertThrows(PluginException::class.java) { UserscriptMetadata.validate(original().replace("// @grant", "// @require https://other.test/a.js\n// @grant"), policy()) }
    }
    @Test fun taskGrantBindsBothSelectedCourseFieldsAndRevokesWithTheAccount() {
        var active = true
        val grant = NativeForegroundGrant(JSONObject("""{"mutations":[{"host":"course.test","pathPrefix":"/sign","scope":{"inputList":"courses","parameters":{"courseId":"courseId","classId":"classId"}}}]}"""),
            JSONObject("""{"courses":[{"courseId":"11","classId":"22"},{"courseId":"33","classId":"44"}]}""")) { active }
        grant.requireRequest("https://course.test/sign?courseId=11&classId=22".toHttpUrl(), "GET", "mutation", null)
        for (url in listOf("https://course.test/sign?courseId=11&classId=44", "https://course.test/sign?courseId=11&courseId=33&classId=22", "https://course.test/other?courseId=11&classId=22", "http://course.test/sign?courseId=11&classId=22", "https://course.test:8443/sign?courseId=11&classId=22"))
            assertThrows(url, PluginException::class.java) { grant.requireRequest(url.toHttpUrl(), "GET", "mutation", null) }
        assertThrows(PluginException::class.java) { grant.requireRequest("https://course.test/sign?courseId=11&classId=22".toHttpUrl(), "POST", "mutation", JSONObject().put("courseId", "11")) }
        active = false
        assertThrows(PluginException::class.java) { grant.requireRequest("https://course.test/sign?courseId=11&classId=22".toHttpUrl(), "GET", "query", null) }
    }
    @Test fun declaredSubdomainsShareTheAccountWithoutSharingUnlistedOrigins() {
        val reads = mutableListOf<String>(); val writes = mutableListOf<String>()
        val jar = ScopedWebCookieJar("https://login.test", { true }, { reads.add(it); "SESSION=synthetic" }, { url, _ -> writes.add(url) }, setOf("https://course.test"))
        val course = "https://course.test/classes".toHttpUrl()
        assertEquals(1, jar.loadForRequest(course).size)
        jar.saveFromResponse(course, listOf(Cookie.parse(course, "SESSION=new; Secure; HttpOnly")!!))
        assertEquals(listOf(course.toString()), writes)
        assertTrue(jar.loadForRequest("https://other.test/classes".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://sub.course.test/classes".toHttpUrl()).isEmpty())
        assertEquals(1, reads.size)
    }
    @Test fun browserParametersStayInsideDeclaredNavigationOriginsWithoutAddingABridge() {
        val page = JSONObject("""{"path":"/login","web":{"mode":"browser","urlParam":"url","navigationOrigins":["https://course.test"]}}""")
        val policy = PluginWebPolicy("https://login.test", page, JSONObject().put("url", "https://course.test/work?id=1"))
        assertEquals("https://course.test/work?id=1", policy.initialUrl); assertFalse(policy.bridges(policy.initialUrl))
        assertThrows(PluginException::class.java) { PluginWebPolicy("https://login.test", page, JSONObject().put("url", "https://other.test/work")) }
        assertThrows(PluginException::class.java) { PluginWebPolicy("https://login.test", page, JSONObject().put("url", "https://user:secret@course.test/work")) }
    }
}
