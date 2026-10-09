package com.tyust.course.academic.plugin

import com.tyust.course.academic.AcademicSessionStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class PluginNativeRefererTest {
    private fun origin(s: MockWebServer) = "http://127.0.0.1:${s.port}"
    private fun rule(s: MockWebServer) = JSONObject().put("origin", origin(s)).put("pathPrefix", "/sign")
        .put("methods", JSONArray(listOf("GET"))).put("purposes", JSONArray(listOf("query")))
    private fun host(s: MockWebServer, targets: List<MockWebServer> = listOf(s), version: Int = 4): PluginHost {
        val manifest = PluginManifest(JSONObject().put("id", "test.native-referer").put("name", "Test").put("version", "1.0.0")
            .put("kind", "native").put("apiVersion", 3).put("network", JSONArray(targets.map(::rule)))
            .put("requires", JSONArray().put(JSONObject().put("name", "network.request").put("version", version))))
        val session = AcademicSessionStore().session("synthetic", "synthetic", origin(s))
        return PluginHost(PluginOperation(session, manifest, "host.effect"), File("build/native-referer-test"))
    }
    private fun request(s: MockWebServer, ref: String) = JSONObject().put("url", origin(s) + "/sign/verify")
        .put("purpose", "query").put("headers", JSONObject().put("Referer", ref))

    @Test fun declaredReferenceIsSentButDroppedAcrossOrigins() {
        MockWebServer().use { a -> MockWebServer().use { b ->
            a.start(InetAddress.getByName("127.0.0.1"), 0); b.start(InetAddress.getByName("127.0.0.1"), 0)
            a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/sign/next"))
            a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", origin(b) + "/sign/end"))
            b.enqueue(MockResponse().setBody("ok"))
            val ref = origin(a) + "/sign/page?id=synthetic"
            assertEquals(200, host(a, listOf(a,b)).call("http", request(a, ref)).getJSONObject("data").getInt("status"))
            repeat(2) { assertEquals(ref, a.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Referer")) }
            assertNull(b.takeRequest(1, TimeUnit.SECONDS)?.getHeader("Referer"))
        } }
    }

    @Test fun olderCapabilityAndUndeclaredOrMalformedSourcesSendNothing() {
        MockWebServer().use { s ->
            s.start(InetAddress.getByName("127.0.0.1"), 0)
            val ref = origin(s) + "/sign/page"
            val invalid = listOf(host(s,version=3) to request(s,ref), host(s) to request(s, origin(s)+"/private"),
                host(s) to request(s,"https://unlisted.test/sign"), host(s) to request(s,ref+"#secret"),
                host(s) to request(s,ref+"\r\nOther: value"), host(s) to request(s,ref).put("headers",JSONObject().put("Referer",ref).put("referer",ref)))
            for ((h,r) in invalid) { try { h.call("http",r); fail("Must reject invalid source") } catch (_: PluginException) {} }
            assertEquals(0,s.requestCount)
        }
    }
}
