package com.tyust.course.academic.plugin

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PluginRedirectsTest {
    private val current = "https://cas.school.test/cas/login?service=http%3A%2F%2Facademic.school.test%2Fsso%2Fcallback".toHttpUrl()
    @Test fun advertisedHttpCallbackUsesTlsWithoutRewritingTicketOrNestedTarget() {
        val next = "http://academic.school.test/sso/callback?targetUrl=%7Bbase64%7DZmFrZQ%3D%3D&ticket=synthetic%2Bticket".toHttpUrl()
        val upgraded = PluginRedirects.upgradeToHttps(current, next, true)
        assertEquals("https", upgraded.scheme)
        assertEquals(443, upgraded.port)
        assertEquals(next.host, upgraded.host)
        assertEquals(next.encodedPath, upgraded.encodedPath)
        assertEquals(next.encodedQuery, upgraded.encodedQuery)
        assertEquals(next, PluginRedirects.upgradeToHttps(current, next, false))
        val unusualPort = next.newBuilder().port(8080).build()
        assertEquals(unusualPort, PluginRedirects.upgradeToHttps(current, unusualPort, true))
    }

    @Test fun upgradingDoesNotGrantAuthorityOverOtherHostsOrPaths() {
        val rule = JSONObject("""{"origin":"https://academic.school.test","pathPrefix":"/sso/callback","methods":["GET"],"purposes":["auth"]}""")
        val policy = PluginNetworkPolicy(listOf(rule))
        for (url in listOf("http://another.test/sso/callback", "http://academic.school.test/outside")) {
            val candidate = PluginRedirects.upgradeToHttps(current, url.toHttpUrl(), true)
            try { policy.requireAllowed(candidate, "GET", "auth", null); fail("Upgraded URL must still be declared") }
            catch (e: PluginException) { assertEquals(PluginErrorCode.UNTRUSTED_URL, e.code) }
        }
    }
}
