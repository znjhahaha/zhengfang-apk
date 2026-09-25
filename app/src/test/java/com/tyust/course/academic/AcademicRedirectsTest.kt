package com.tyust.course.academic

import com.tyust.course.model.SchoolConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AcademicRedirectsTest {
    private val login = "https://xk.huel.edu.cn/jwglxt/xtgl/login_slogin.html".toHttpUrl()

    @Test fun huelCallbacksRetainPathAndQueryWhileKeepingTls() {
        val target = "http://xk.huel.edu.cn/jwglxt/xtgl/index_initMenu.html?gnmkdm=index&ticket=a%2Bb".toHttpUrl()
        val secure = AcademicRedirects.preferVerifiedHttps(login, target)
        assertEquals("https", secure.scheme)
        assertEquals(target.encodedPath, secure.encodedPath)
        assertEquals(target.encodedQuery, secure.encodedQuery)
        assertEquals(443, secure.port)
    }

    @Test fun otherOriginsPortsAndApplicationsKeepTheExistingPolicy() {
        for (value in listOf(
            "http://other.example/jwglxt/xtgl/login_slogin.html",
            "http://xk.huel.edu.cn.evil.test/jwglxt/xtgl/login_slogin.html",
            "http://xk.huel.edu.cn:8080/jwglxt/xtgl/login_slogin.html",
            "http://xk.huel.edu.cn/jwglxt-other/login",
            "http://xk.huel.edu.cn/outside",
            "http://user:password@xk.huel.edu.cn/jwglxt/xtgl/login_slogin.html"
        )) {
            val target = value.toHttpUrl()
            assertEquals(target, AcademicRedirects.preferVerifiedHttps(login, target))
        }
        val target = login.newBuilder().scheme("http").port(80).build()
        for (source in listOf("https://another.example/jwglxt/", "https://xk.huel.edu.cn:8443/jwglxt/", "https://xk.huel.edu.cn/other/", "http://xk.huel.edu.cn/jwglxt/"))
            assertEquals(target, AcademicRedirects.preferVerifiedHttps(source.toHttpUrl(), target))
    }

    @Test fun legacyTransportFollowsHuelCallbackWithoutSendingAnyHttpRequest() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(if (requests.size == 1) 302 else 200)
                .apply { if (requests.size == 1) header("Location", "http://xk.huel.edu.cn/jwglxt/xtgl/index_initMenu.html") }
                .body("verified".toResponseBody()).build()
        }.build()
        val school = SchoolConfig("huel", "HUEL", "xk.huel.edu.cn", "https").apply { basePath = "/jwglxt" }
        val session = AcademicSession(AcademicSessionKey("huel", "fixture"), school.fullBasePath)
        try {
            val result = AcademicHttpTransport(school, session, client).postForm(login.toString(), listOf("mm" to "synthetic-ciphertext"))
            assertEquals(200, result.code)
            assertEquals(listOf("POST", "GET"), requests.map { it.method })
            assertTrue(requests.all { it.url.isHttps })
            assertNull(requests.last().body)
        } finally { session.retire() }
    }
}
