package com.tyust.course.login

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingMemoryCookieJarTest {
    @Test
    fun doesNotSendSecureCookieOverHttp() {
        val jar = MatchingMemoryCookieJar()
        val https = "https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()
        jar.saveFromResponse(
            https,
            listOf(
                Cookie.Builder()
                    .name("JSESSIONID")
                    .value("test-session")
                    .hostOnlyDomain("newjwc.tyust.edu.cn")
                    .path("/jwglxt")
                    .secure()
                    .build()
            )
        )

        assertTrue(
            jar.loadForRequest("http://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()).isEmpty()
        )
    }

    @Test
    fun serializesOnlyCookiesMatchingTheTeachingHost() {
        val jar = MatchingMemoryCookieJar()
        jar.saveFromResponse(
            "https://sso1.tyust.edu.cn/login".toHttpUrl(),
            listOf(
                Cookie.Builder().name("SESSION").value("sso")
                    .hostOnlyDomain("sso1.tyust.edu.cn").path("/").secure().build()
            )
        )
        jar.saveFromResponse(
            "https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl(),
            listOf(
                Cookie.Builder().name("JSESSIONID").value("jw")
                    .hostOnlyDomain("newjwc.tyust.edu.cn").path("/jwglxt").secure().build()
            )
        )

        assertEquals(
            "JSESSIONID=jw",
            jar.cookieHeaderFor("https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl())
        )
    }

    @Test
    fun replacesSameNameDomainAndPathAndRemovesExpiredCookie() {
        val jar = MatchingMemoryCookieJar()
        val url = "https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()

        jar.saveFromResponse(url, listOf(sessionCookie("old")))
        jar.saveFromResponse(url, listOf(sessionCookie("new")))
        assertEquals("JSESSIONID=new", jar.cookieHeaderFor(url))

        jar.saveFromResponse(
            url,
            listOf(sessionCookie("deleted", expiresAt = System.currentTimeMillis() - 1L))
        )
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun canSerializeOnlyCookiesSetByTeachingHost() {
        val jar = MatchingMemoryCookieJar()
        val teachingUrl = "https://newjwc.tyust.edu.cn/jwglxt/".toHttpUrl()
        jar.saveFromResponse(
            "https://sso1.tyust.edu.cn/login".toHttpUrl(),
            listOf(
                Cookie.Builder().name("SESSION").value("parent-domain-sso")
                    .domain("tyust.edu.cn").path("/").secure().build()
            )
        )
        jar.saveFromResponse(teachingUrl, listOf(sessionCookie("teaching-session")))

        assertTrue(jar.cookieHeaderFor(teachingUrl).contains("SESSION=parent-domain-sso"))
        assertEquals(
            "JSESSIONID=teaching-session",
            jar.cookieHeaderFor(teachingUrl, setByHost = "newjwc.tyust.edu.cn")
        )
    }

    private fun sessionCookie(value: String, expiresAt: Long? = null): Cookie {
        val builder = Cookie.Builder()
            .name("JSESSIONID")
            .value(value)
            .hostOnlyDomain("newjwc.tyust.edu.cn")
            .path("/jwglxt")
            .secure()
        expiresAt?.let(builder::expiresAt)
        return builder.build()
    }
}
