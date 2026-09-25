package com.tyust.course.academic.plugin

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class PluginWebCookiesTest {
    @Test fun profileCookiesAreReadOnlyForTheExactServiceOriginAndRequestPath() {
        val read = mutableListOf<String>()
        val jar = ScopedWebCookieJar("https://forum.test", { true }, { read.add(it); "SESSION=synthetic; theme=dark" }, { _, _ -> })
        val url = "https://forum.test/forum/api/topics".toHttpUrl()
        val cookies = jar.loadForRequest(url)
        assertEquals(listOf(url.toString()), read)
        assertEquals(listOf("SESSION", "theme"), cookies.map { it.name })
        assertTrue(cookies.all { it.hostOnly && it.secure && it.matches(url) })
        for (other in listOf("http://forum.test/forum/api", "https://forum.test:8443/forum/api", "https://other.test/forum/api"))
            assertTrue(jar.loadForRequest(other.toHttpUrl()).isEmpty())
        assertEquals(1, read.size)
    }
    @Test fun revokedOrForeignResponsesCannotRestoreCookiesAndNewDocumentsInvalidateOldCalls() {
        var active = true; val writes = mutableListOf<String>()
        val jar = ScopedWebCookieJar("https://forum.test", { active }, { "SESSION=old" }, { _, cookie -> writes.add(cookie) })
        val url = "https://forum.test/api".toHttpUrl()
        val cookie = Cookie.parse(url, "SESSION=synthetic; Secure; HttpOnly")!!
        jar.saveFromResponse(url, listOf(cookie)); assertEquals(1, writes.size)
        jar.saveFromResponse("https://other.test/api".toHttpUrl(), listOf(cookie)); assertEquals(1, writes.size)
        active = false; jar.saveFromResponse(url, listOf(cookie)); assertEquals(1, writes.size)
        assertThrows(PluginException::class.java) { jar.loadForRequest(url) }
        val gate = PluginWebGate("https://forum.test")
        val first = gate.bind("https://forum.test/topic/one")
        assertEquals("https://forum.test/topic/one", gate.documentUrl())
        val second = gate.bind("https://forum.test/topic/two#reply")
        assertFalse(gate.current(first)); assertTrue(gate.current(second))
        assertThrows(PluginException::class.java) { gate.accept("https://forum.test", true, first, "same_id") }
        gate.revoke(); assertNull(gate.documentUrl())
    }
}
